package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.entity.Component;
import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.entity.Quotation;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.entity.TemplateComponentSnapshot;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829 · T-04（AC-6）—— B-1「模板口径变化」专项。
 *
 * <p><b>验的是什么</b>：{@code 问题说明.md} §⑤ B-1 记录了一处<b>有意的口径变化</b>——修复前
 * {@code loadSingleComponentTabMeta} 逐行经 {@code resolveCustomerTemplateId(lineItemId)} 查库拿
 * {@code quotation.customer_template_id}（旧模板，flush 前的库值）；修复后主循环前一次性取
 * {@code q.customerTemplateId}（内存值）—— 但若本次保存请求同时携带了 {@code customerTemplateId}
 * （即本次保存正在切换模板），修复后校验必须按<b>本次请求要绑定的新模板</b>执行，而不是旧模板。
 *
 * <p><b>构造手法</b>：报价单原绑模板 A（只有 BOM 树页签，没有材质元素页签）；本次保存请求携带
 * {@code customerTemplateId = B}（B 才有材质元素页签，{@code tab_type=材质元素}、
 * {@code part_no_field=料号}）。保存的行数据把树上"已有下级"的料号 P1 塞进材质元素页签
 * （componentId 指向 B 的材质元素组件）。
 * <ul>
 *   <li>若校验仍按旧口径（模板 A）解析该 componentId 的 tabType —— A 的
 *       {@code template_component_snapshot} 里根本没有这个 componentId，校验找不到限制类型，
 *       会被<b>误放行</b>（200，错误）。</li>
 *   <li>若按 B-1 修复后的口径（本次要绑定的新模板 B）解析 —— 能查到
 *       {@code tab_type=材质元素}，正确拦截（400，符合 api.md 契约文案）。</li>
 * </ul>
 * 因此本测试在 B-1 修复前预期为<b>红</b>（未拦住/或以另一种异常失败），修复后为<b>绿</b>——
 * 是一条天然的回归夹紧测试，不需要额外的"还原实验"步骤。
 *
 * <p><b>fixture 写法沿用 {@link SaveDraftRestrictedTabValidationTest}（2026-08-29 已修正的版本）</b>：
 * 必须用 {@code status=PUBLISHED} + 同步写 {@code template_component_snapshot}（{@link TemplateComponentSnapshot}），
 * 因为 {@code PublishedTemplateReader.allTabsOf} 经该表读取、对 DRAFT 模板恒返回空列表
 * （见该文件 buildFixture 内的 2026-08-29 修复说明）。
 */
@QuarkusTest
@DisplayName("SaveDraftCustomerTemplateIdOverrideValidationTest — repair-260829 T-04(AC-6) 模板口径切换")
class SaveDraftCustomerTemplateIdOverrideValidationTest {

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    @SuppressWarnings("unchecked")
    private UUID findDraftQuotationId() {
        List<Object> rows = em.createNativeQuery(
                        "SELECT id FROM quotation WHERE status = 'DRAFT' ORDER BY created_at LIMIT 1")
                .getResultList();
        return rows.isEmpty() ? null : toUUID(rows.get(0));
    }

    private static UUID toUUID(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try {
            return UUID.fromString(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Fixture {
        UUID templateAId;
        UUID templateBId;
        UUID treeComponentId;
        UUID materialComponentBId;
        UUID lineItemId;
    }

    /** 造 BOM 树组件（tab_type=BOM）。两个模板共享同一个物理组件，因为树数据挂在 lineItem 自己的
     *  componentData 上（keyed by componentId），与"用哪个模板解析限制类型"无关。 */
    private Component buildTreeComponent() {
        Component treeComp = new Component();
        treeComp.name = "T04测试-树页签";
        treeComp.code = "T04-TREE-" + UUID.randomUUID().toString().substring(0, 8);
        treeComp.fields = "[]";
        treeComp.formulas = "[]";
        treeComp.tabType = "BOM";
        treeComp.bomRecursiveExpand = true;
        treeComp.persist();
        return treeComp;
    }

    /** 造材质元素组件（tab_type=材质元素, part_no_field=料号），只挂在模板 B 上。 */
    private Component buildMaterialComponent() {
        Component materialComp = new Component();
        materialComp.name = "T04测试-材质元素(仅B)";
        materialComp.code = "T04-MAT-" + UUID.randomUUID().toString().substring(0, 8);
        materialComp.fields = "[{\"name\":\"料号\",\"field_type\":\"INPUT_TEXT\"}]";
        materialComp.formulas = "[]";
        materialComp.tabType = "材质元素";
        materialComp.partNoField = "料号";
        materialComp.persist();
        return materialComp;
    }

    /** PUBLISHED 模板 + 对应 template_component + template_component_snapshot（照 TemplateService.publish() 产出形状）。 */
    private UUID buildPublishedTemplate(String name, List<Component> boundComponents, List<String> tabNames,
                                         boolean isTreeAt0) {
        Template tpl = new Template();
        tpl.templateSeriesId = UUID.randomUUID();
        tpl.name = name;
        tpl.templateKind = "QUOTATION";
        tpl.status = "PUBLISHED";
        // PublishedTemplateReader.verifyConsistentWithJsonb 一致性校验：长度须与 snapshot 行数一致
        StringBuilder snap = new StringBuilder("[");
        for (int i = 0; i < boundComponents.size(); i++) {
            if (i > 0) snap.append(",");
            snap.append("{}");
        }
        snap.append("]");
        tpl.componentsSnapshot = snap.toString();
        tpl.createdAt = OffsetDateTime.now();
        tpl.updatedAt = OffsetDateTime.now();
        tpl.persist();

        for (int i = 0; i < boundComponents.size(); i++) {
            Component c = boundComponents.get(i);
            String tabName = tabNames.get(i);

            TemplateComponent tc = new TemplateComponent();
            tc.templateId = tpl.id;
            tc.componentId = c.id;
            tc.tabName = tabName;
            tc.sortOrder = i;
            tc.createdAt = OffsetDateTime.now();
            tc.persist();

            TemplateComponentSnapshot sc = new TemplateComponentSnapshot();
            sc.templateId = tpl.id;
            sc.templateComponentId = tc.id;
            sc.componentId = c.id;
            sc.sortOrder = i;
            sc.tabName = tabName;
            sc.componentName = c.name;
            sc.componentCode = c.code;
            sc.fields = c.fields;
            sc.formulas = "[]";
            sc.tabType = c.tabType;
            if (isTreeAt0 && i == 0) {
                sc.bomRecursiveExpand = true;
            } else {
                sc.partNoField = c.partNoField;
            }
            sc.persist();
        }
        return tpl.id;
    }

    private Fixture buildFixture(UUID quotationId) {
        Fixture f = new Fixture();

        Component treeComp = buildTreeComponent();
        f.treeComponentId = treeComp.id;
        Component materialCompB = buildMaterialComponent();
        f.materialComponentBId = materialCompB.id;

        // 模板 A：只有 BOM 树页签，没有材质元素页签(模拟"报价单原绑模板 A")
        f.templateAId = buildPublishedTemplate("T04测试-模板A(无材质元素页签)",
                List.of(treeComp), List.of("BOM树"), true);

        // 模板 B：BOM 树页签 + 材质元素页签(本次保存要切换到的新模板)
        f.templateBId = buildPublishedTemplate("T04测试-模板B(有材质元素页签)",
                List.of(treeComp, materialCompB), List.of("BOM树", "材质元素"), true);

        // 报价单原绑模板 A
        Quotation q = Quotation.findById(quotationId);
        assertNotNull(q, "前置 quotation 必须存在");
        q.customerTemplateId = f.templateAId;

        UUID lineItemId = UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO quotation_line_item (id, quotation_id, template_id, sort_order, created_at) " +
                                "VALUES (:id, :qid, :tid, 999, :now)")
                .setParameter("id", lineItemId)
                .setParameter("qid", quotationId)
                .setParameter("tid", f.templateAId)
                .setParameter("now", OffsetDateTime.now())
                .executeUpdate();
        f.lineItemId = lineItemId;

        // 树页签 snapshot_rows：P1(根,无父) -> P1/CHILD(子,parent=P1)。P1 因此"已有子节点"。
        String treeRows = "["
                + "{\"driverRow\":{},\"basicDataValues\":{},\"__nodeId\":\"P1\",\"__parentId\":null,"
                + "  \"__lvl\":0,\"__hfPartNo\":\"P1\",\"__parentNo\":null,\"__bomVersion\":null},"
                + "{\"driverRow\":{},\"basicDataValues\":{},\"__nodeId\":\"P1/CHILD\",\"__parentId\":\"P1\","
                + "  \"__lvl\":1,\"__hfPartNo\":\"CHILD\",\"__parentNo\":\"P1\",\"__bomVersion\":null}"
                + "]";
        em.createNativeQuery(
                        "INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, snapshot_rows) " +
                                "VALUES (:id, :lid, :cid, :tab, CAST(:rows AS jsonb))")
                .setParameter("id", UUID.randomUUID())
                .setParameter("lid", lineItemId)
                .setParameter("cid", treeComp.id)
                .setParameter("tab", "BOM树")
                .setParameter("rows", treeRows)
                .executeUpdate();

        em.flush();
        return f;
    }

    @Test
    @TestTransaction
    @DisplayName("AC-6: 保存请求携带 customerTemplateId=B(与原绑模板A不同) → 校验按B执行,拦住B独有的材质元素限制")
    void saveDraft_validatesAgainstRequestedTemplate_notStaleQuotationTemplate() {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId, "DB 无 DRAFT 报价单 — 请先创建至少一条 DRAFT 状态报价单后再运行本测试");

        Fixture f = buildFixture(quotationId);

        SaveDraftRequest req = new SaveDraftRequest();
        // 本次保存同时切换模板：从 A 切到 B
        req.customerTemplateId = f.templateBId;

        SaveDraftRequest.LineItemDraft liDraft = new SaveDraftRequest.LineItemDraft();
        liDraft.id = f.lineItemId;
        liDraft.templateId = f.templateBId;
        liDraft.sortOrder = 999;

        SaveDraftRequest.ComponentDataDraft treeCd = new SaveDraftRequest.ComponentDataDraft();
        treeCd.componentId = f.treeComponentId;
        treeCd.tabName = "BOM树";
        treeCd.rowData = "[]"; // 占位,真实值由 preservedSnapshots 回填

        // B 独有的材质元素组件：把树上"已有下级"的 P1 塞进去 —— 只有按 B 校验才会被拦
        SaveDraftRequest.ComponentDataDraft materialCd = new SaveDraftRequest.ComponentDataDraft();
        materialCd.componentId = f.materialComponentBId;
        materialCd.tabName = "材质元素";
        materialCd.rowData = "[{\"料号\":\"P1\"}]";

        liDraft.componentData = List.of(treeCd, materialCd);
        req.lineItems = List.of(liDraft);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.saveDraft(quotationId, req),
                "本次保存请求携带 customerTemplateId=B,B 的材质元素页签应拦住已有下级的料号 P1 —— "
                        + "若未抛异常,说明校验仍按旧的 quotation.customerTemplateId(A)解析,A 没有材质元素页签导致误放行(B-1 口径回归)");
        assertEquals(400, ex.getCode());
        assertEquals("该料号在 BOM 树上已有下级，不能添加到「材质元素」页签", ex.getMessage(),
                "错误文案应与 api.md 契约逐字一致: " + ex.getMessage());
    }
}
