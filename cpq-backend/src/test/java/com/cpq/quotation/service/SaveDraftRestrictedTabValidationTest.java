package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.entity.Component;
import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.entity.Quotation;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
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
 * task-0721 B8（2026-07-21 补录）— saveDraft 接线的反向校验集成测试。
 *
 * <p><b>不是纯单测方法调用</b>：本测试真正通过 {@link QuotationService#saveDraft} 触发保存流程
 * （与生产 REST 端点走同一段代码，含 batchStage1 集合化路径的真实 SQL 落库 + flush），验证
 * "已有子节点的料号不能加入材质元素/外购件页签"这条规则确实在 saveDraft 落库前生效——而不仅仅是
 * {@code QuotationTreeService.assertCanAddToRestrictedTab} 方法本身逻辑正确。
 *
 * <p>策略（沿用 {@code SaveDraftCardValuesInvalidationTest} 的取舍：复用共享库里已有一条 DRAFT
 * 报价单以满足外键，自建最小 Template/Component/LineItem 挂在其下，@TestTransaction 保证回滚清理）。
 */
@QuarkusTest
@DisplayName("SaveDraftRestrictedTabValidationTest — B8 反向校验真落库集成测试")
class SaveDraftRestrictedTabValidationTest {

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
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }

    /** 造最小 fixture：树组件(tab_type=BOM) + 材质元素组件(part_no_field=料号) + 模板 + 挂载。 */
    private static final class Fixture {
        UUID templateId;
        UUID treeComponentId;
        UUID materialComponentId;
        UUID lineItemId;
    }

    private Fixture buildFixture(UUID quotationId) {
        Fixture f = new Fixture();

        Component treeComp = new Component();
        treeComp.name = "B8测试-树页签";
        treeComp.code = "B8-TREE-" + UUID.randomUUID().toString().substring(0, 8);
        treeComp.fields = "[]";
        treeComp.formulas = "[]";
        treeComp.tabType = "BOM";
        treeComp.bomRecursiveExpand = true;
        treeComp.persist();
        f.treeComponentId = treeComp.id;

        Component materialComp = new Component();
        materialComp.name = "B8测试-材质元素";
        materialComp.code = "B8-MAT-" + UUID.randomUUID().toString().substring(0, 8);
        materialComp.fields = "[{\"name\":\"料号\",\"field_type\":\"INPUT_TEXT\"}]";
        materialComp.formulas = "[]";
        materialComp.tabType = "材质元素";
        materialComp.partNoField = "料号";
        materialComp.persist();
        f.materialComponentId = materialComp.id;

        Template tpl = new Template();
        tpl.templateSeriesId = UUID.randomUUID();
        tpl.name = "B8测试-模板";
        tpl.templateKind = "QUOTATION";
        tpl.status = "DRAFT";
        tpl.createdAt = OffsetDateTime.now();
        tpl.updatedAt = OffsetDateTime.now();
        tpl.persist();
        f.templateId = tpl.id;

        TemplateComponent tcTree = new TemplateComponent();
        tcTree.templateId = tpl.id;
        tcTree.componentId = treeComp.id;
        tcTree.tabName = "BOM树";
        tcTree.createdAt = OffsetDateTime.now();
        tcTree.persist();

        TemplateComponent tcMat = new TemplateComponent();
        tcMat.templateId = tpl.id;
        tcMat.componentId = materialComp.id;
        tcMat.tabName = "材质元素";
        tcMat.createdAt = OffsetDateTime.now();
        tcMat.persist();

        // 把目标 quotation 的报价模板指向本测试模板(B8 校验读 quotation.customer_template_id)
        Quotation q = Quotation.findById(quotationId);
        assertNotNull(q, "前置 quotation 必须存在");
        q.customerTemplateId = tpl.id;

        // 新建一个 line item 挂在该 quotation 下
        UUID lineItemId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, template_id, sort_order, created_at) " +
                "VALUES (:id, :qid, :tid, 999, :now)")
                .setParameter("id", lineItemId)
                .setParameter("qid", quotationId)
                .setParameter("tid", tpl.id)
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

    private SaveDraftRequest.LineItemDraft draftFor(Fixture f, String partNoToSave) {
        SaveDraftRequest.LineItemDraft liDraft = new SaveDraftRequest.LineItemDraft();
        liDraft.id = f.lineItemId;
        liDraft.templateId = f.templateId;
        liDraft.sortOrder = 999;

        // 树页签也必须出现在本次 componentData 里(哪怕 rowData 是空数组占位)——saveDraft 是"全量重建"
        // 语义,某 componentId 不在本次请求里就不会被保留/重建，其 snapshot_rows 只在"该 componentId
        // 仍出现在请求中"时才由 preservedSnapshots 回填。这与生产真实前端行为一致：前端每次保存都会把
        // 该行【当前渲染出的全部 Tab】(含树 Tab)悉数带上，不会漏传某个 Tab。
        SaveDraftRequest.ComponentDataDraft treeCd = new SaveDraftRequest.ComponentDataDraft();
        treeCd.componentId = f.treeComponentId;
        treeCd.tabName = "BOM树";
        treeCd.rowData = "[]"; // 占位,真实值由 preservedSnapshots(旧 snapshot_rows)在 saveDraft 内部回填

        SaveDraftRequest.ComponentDataDraft cdDraft = new SaveDraftRequest.ComponentDataDraft();
        cdDraft.componentId = f.materialComponentId;
        cdDraft.tabName = "材质元素";
        cdDraft.rowData = "[{\"料号\":\"" + partNoToSave + "\"}]";

        liDraft.componentData = List.of(treeCd, cdDraft);
        return liDraft;
    }

    @Test
    @TestTransaction
    @DisplayName("已有子节点的料号(P1)加入材质元素页签 → saveDraft 拒绝 400")
    void saveDraft_rejectsPartWithExistingChildren() {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId, "DB 无 DRAFT 报价单 — 请先创建至少一条 DRAFT 状态报价单后再运行本测试");

        Fixture f = buildFixture(quotationId);

        SaveDraftRequest req = new SaveDraftRequest();
        req.lineItems = List.of(draftFor(f, "P1")); // P1 在树上已有子节点 CHILD

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.saveDraft(quotationId, req));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("已有下级"), "错误文案应说明已有下级原因: " + ex.getMessage());
    }

    @Test
    @TestTransaction
    @DisplayName("无子节点的料号(CHILD 本身)加入材质元素页签 → saveDraft 正常通过")
    void saveDraft_allowsLeafPartWithoutChildren() {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId, "DB 无 DRAFT 报价单 — 请先创建至少一条 DRAFT 状态报价单后再运行本测试");

        Fixture f = buildFixture(quotationId);

        SaveDraftRequest req = new SaveDraftRequest();
        req.lineItems = List.of(draftFor(f, "CHILD")); // CHILD 是叶子,无子节点

        assertDoesNotThrow(() -> quotationService.saveDraft(quotationId, req));

        // 确认真的落库了(不是校验被误跳过导致的"意外不抛异常")
        em.flush();
        em.clear();
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                "SELECT row_data::text FROM quotation_line_component_data " +
                "WHERE line_item_id = :lid AND component_id = :cid")
                .setParameter("lid", f.lineItemId)
                .setParameter("cid", f.materialComponentId)
                .getResultList();
        assertFalse(rows.isEmpty(), "材质元素页签的行数据应已落库");
        assertTrue(((String) rows.get(0)).contains("CHILD"), "落库内容应含 CHILD 料号");
    }

    /**
     * 逃生回落路径（{@code -Dcpq.savedraft-batch-stage1=false}，非默认，但生产仍保留为 kill switch）
     * 同款覆盖：B8 校验在两条路径都接线，不只是集合化默认路径。
     */
    @Test
    @TestTransaction
    @DisplayName("legacy 逐行路径(kill switch off)同样拒绝已有子节点的料号")
    void saveDraft_legacyPath_alsoRejectsPartWithExistingChildren() {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId, "DB 无 DRAFT 报价单 — 请先创建至少一条 DRAFT 状态报价单后再运行本测试");

        Fixture f = buildFixture(quotationId);

        SaveDraftRequest req = new SaveDraftRequest();
        req.lineItems = List.of(draftFor(f, "P1"));

        System.setProperty("cpq.savedraft-batch-stage1", "false");
        try {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> quotationService.saveDraft(quotationId, req));
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("已有下级"), "错误文案应说明已有下级原因: " + ex.getMessage());
        } finally {
            System.clearProperty("cpq.savedraft-batch-stage1");
        }
    }
}
