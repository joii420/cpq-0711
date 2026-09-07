package com.cpq.task260904;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.entity.Component;
import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.service.QuotationService;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.entity.TemplateComponentSnapshot;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC-17 —— <b>AC-17（反向 · 受限页签准入不得失效）</b>。
 *
 * <p>AC 原文（{@code 需求文档.md §3.3}）：
 * 「某料号 X 在 BOM 树上已有下级；报价单含一个「材质元素」语义的页签。
 *  把 X 加到该材质元素页签（走行新增或 saveDraft 任一路径）⇒ 仍返回 <b>400</b>
 *  『该料号在 BOM 树上已有下级，不能添加到「材质元素」页签』。
 *  ⚠️ 该校验的判据是<b>树结构</b>（是否已有下级），<b>不是料号类型</b> ——
 *  本次改造不得把它误改成按类型判」。
 *
 * <h3>🚨 「不得误改成按类型判」怎么验</h3>
 * 光验「有下级的料号被拒」不够 —— 一个改成「按类型判」的实现在很多料号上也会拒。
 * ⇒ 本用例配一个<b>阴性对照</b>：同一棵树上的<b>叶子</b>料号（没有下级）必须<b>放行</b>。
 * 两条一起才能锁住「判据是树结构」这件事。
 *
 * <p>夹具形状沿用既有的 {@code SaveDraftRestrictedTabValidationTest}（2026-08-29 修正版）：
 * 模板必须是 {@code PUBLISHED} 且写了 {@code template_component_snapshot} ——
 * task-0806 B19 起校验读的是冻结快照，DRAFT 模板会让校验方法拿不到 meta 直接放行，
 * <b>用例会以「通过」的形态空跑</b>。
 *
 * <h3>零残留</h3>
 * 全程 {@code @TestTransaction}（回滚）。报价单也是本用例自建的 ——
 * 🚫 不去改共享库里已有的 DRAFT 单的 {@code customer_template_id}（那是别人的在途数据）。
 */
@QuarkusTest
@DisplayName("task-260904 · AC-17 —— 受限页签准入判据仍是树结构，不是料号类型")
class RestrictedTabAdmissionAcTest extends Task260904Base {

    private static final String TAG = "T260904RT";
    /** api.md / 既有契约的逐字文案。 */
    private static final String EXPECTED_MSG = "该料号在 BOM 树上已有下级，不能添加到「材质元素」页签";

    @Inject
    QuotationService quotationService;

    private static final class Fx {
        UUID templateId;
        UUID treeComponentId;
        UUID materialComponentId;
        UUID quotationId;
        UUID lineItemId;
        String parentPartNo;
        String leafPartNo;
    }

    private Fx buildFixture() {
        Fx f = new Fx();
        f.parentPartNo = TAG + "P" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        f.leafPartNo = TAG + "C" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        Component treeComp = new Component();
        treeComp.name = TAG + "-树页签";
        treeComp.code = TAG + "-TREE-" + UUID.randomUUID().toString().substring(0, 8);
        treeComp.fields = "[]";
        treeComp.formulas = "[]";
        treeComp.tabType = "BOM";
        treeComp.bomRecursiveExpand = true;
        treeComp.persist();
        f.treeComponentId = treeComp.id;

        Component materialComp = new Component();
        materialComp.name = TAG + "-材质元素";
        materialComp.code = TAG + "-MAT-" + UUID.randomUUID().toString().substring(0, 8);
        materialComp.fields = "[{\"name\":\"料号\",\"field_type\":\"INPUT_TEXT\"}]";
        materialComp.formulas = "[]";
        materialComp.tabType = "材质元素";
        materialComp.partNoField = "料号";
        materialComp.persist();
        f.materialComponentId = materialComp.id;

        Template tpl = new Template();
        tpl.templateSeriesId = UUID.randomUUID();
        tpl.name = TAG + "-模板";
        tpl.templateKind = "QUOTATION";
        // 🚨 必须 PUBLISHED + 写快照：校验读 PublishedTemplateReader.allTabsOf，
        //    DRAFT 恒返回空列表 ⇒ 校验被跳过 ⇒ 本用例空跑（既有测试 2026-08-29 踩过）
        tpl.status = "PUBLISHED";
        tpl.componentsSnapshot = "[{},{}]";
        tpl.createdAt = OffsetDateTime.now();
        tpl.updatedAt = OffsetDateTime.now();
        tpl.persist();
        f.templateId = tpl.id;

        TemplateComponent tcTree = new TemplateComponent();
        tcTree.templateId = tpl.id;
        tcTree.componentId = treeComp.id;
        tcTree.tabName = "BOM树";
        tcTree.sortOrder = 0;
        tcTree.createdAt = OffsetDateTime.now();
        tcTree.persist();

        TemplateComponent tcMat = new TemplateComponent();
        tcMat.templateId = tpl.id;
        tcMat.componentId = materialComp.id;
        tcMat.tabName = "材质元素";
        tcMat.sortOrder = 1;
        tcMat.createdAt = OffsetDateTime.now();
        tcMat.persist();

        TemplateComponentSnapshot scTree = new TemplateComponentSnapshot();
        scTree.templateId = tpl.id;
        scTree.templateComponentId = tcTree.id;
        scTree.componentId = treeComp.id;
        scTree.sortOrder = 0;
        scTree.tabName = "BOM树";
        scTree.componentName = treeComp.name;
        scTree.componentCode = treeComp.code;
        scTree.fields = "[]";
        scTree.formulas = "[]";
        scTree.tabType = "BOM";
        scTree.bomRecursiveExpand = true;
        scTree.persist();

        TemplateComponentSnapshot scMat = new TemplateComponentSnapshot();
        scMat.templateId = tpl.id;
        scMat.templateComponentId = tcMat.id;
        scMat.componentId = materialComp.id;
        scMat.sortOrder = 1;
        scMat.tabName = "材质元素";
        scMat.componentName = materialComp.name;
        scMat.componentCode = materialComp.code;
        scMat.fields = materialComp.fields;
        scMat.formulas = "[]";
        scMat.tabType = "材质元素";
        scMat.partNoField = "料号";
        scMat.persist();

        // 自建报价单（🚫 不劫持共享库里已有的 DRAFT 单）
        Object customer = col("SELECT id FROM customer WHERE status='ACTIVE' ORDER BY created_at LIMIT 1")
                .stream().findFirst().orElse(null);
        assertNotNull(customer, "前置未满足：库里没有 ACTIVE 客户");
        Object user = col("SELECT id FROM \"user\" WHERE username='admin' LIMIT 1").stream().findFirst().orElse(null);
        assertNotNull(user, "前置未满足：admin 用户应存在");

        f.quotationId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO quotation (id,quotation_number,customer_id,name,sales_rep_id,status,"
                        + "customer_template_id,tax_rate,tax_amount,created_at,updated_at) "
                        + "VALUES (:id,:qn,:cid,:n,:uid,'DRAFT',:tid,0,0,now(),now())")
                .setParameter("id", f.quotationId).setParameter("qn", TAG + "-" + f.quotationId.toString().substring(0, 8))
                .setParameter("cid", toUUID(customer)).setParameter("n", TAG + "-报价单")
                .setParameter("uid", toUUID(user)).setParameter("tid", f.templateId).executeUpdate();

        f.lineItemId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO quotation_line_item (id,quotation_id,template_id,sort_order,created_at) "
                        + "VALUES (:id,:qid,:tid,999,now())")
                .setParameter("id", f.lineItemId).setParameter("qid", f.quotationId)
                .setParameter("tid", f.templateId).executeUpdate();

        // 树上：parent → leaf ⇒ parent「已有下级」，leaf 没有下级
        String treeRows = "["
                + "{\"driverRow\":{},\"basicDataValues\":{},\"__nodeId\":\"" + f.parentPartNo + "\",\"__parentId\":null,"
                + "\"__lvl\":0,\"__hfPartNo\":\"" + f.parentPartNo + "\",\"__parentNo\":null,\"__bomVersion\":null},"
                + "{\"driverRow\":{},\"basicDataValues\":{},\"__nodeId\":\"" + f.parentPartNo + "/" + f.leafPartNo + "\","
                + "\"__parentId\":\"" + f.parentPartNo + "\",\"__lvl\":1,\"__hfPartNo\":\"" + f.leafPartNo + "\","
                + "\"__parentNo\":\"" + f.parentPartNo + "\",\"__bomVersion\":null}]";
        em.createNativeQuery("INSERT INTO quotation_line_component_data (id,line_item_id,component_id,tab_name,snapshot_rows) "
                        + "VALUES (:id,:lid,:cid,'BOM树',CAST(:rows AS jsonb))")
                .setParameter("id", UUID.randomUUID()).setParameter("lid", f.lineItemId)
                .setParameter("cid", f.treeComponentId).setParameter("rows", treeRows).executeUpdate();
        em.flush();
        return f;
    }

    private SaveDraftRequest draftWith(Fx f, String partNoToSave) {
        SaveDraftRequest.LineItemDraft li = new SaveDraftRequest.LineItemDraft();
        li.id = f.lineItemId;
        li.templateId = f.templateId;
        li.sortOrder = 999;

        // saveDraft 是「全量重建」语义：树页签也必须出现在本次请求里（占位空数组，
        // 真实值由 preservedSnapshots 回填），否则它的 snapshot_rows 会被丢掉、
        // 「已有下级」这个前提当场消失 ⇒ 用例空跑。
        SaveDraftRequest.ComponentDataDraft treeCd = new SaveDraftRequest.ComponentDataDraft();
        treeCd.componentId = f.treeComponentId;
        treeCd.tabName = "BOM树";
        treeCd.rowData = "[]";

        SaveDraftRequest.ComponentDataDraft matCd = new SaveDraftRequest.ComponentDataDraft();
        matCd.componentId = f.materialComponentId;
        matCd.tabName = "材质元素";
        matCd.rowData = "[{\"料号\":\"" + partNoToSave + "\"}]";

        li.componentData = List.of(treeCd, matCd);
        SaveDraftRequest req = new SaveDraftRequest();
        req.lineItems = List.of(li);
        return req;
    }

    @Test
    @TestTransaction
    @DisplayName("AC-17①：树上已有下级的料号加入材质元素页签 → 仍 400，文案逐字不变")
    void ac17_partWithChildrenStillRejected() {
        Fx f = buildFixture();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.saveDraft(f.quotationId, draftWith(f, f.parentPartNo)),
                "AC-17①：树上已有下级的料号 " + f.parentPartNo + " 加入材质元素页签应被拒 —— "
                        + "没抛异常说明校验被跳过（模板不是 PUBLISHED？快照没写？）");
        assertEquals(400, ex.getCode(), "AC-17①：应为 400，实际=" + ex.getCode() + " msg=" + ex.getMessage());
        assertEquals(EXPECTED_MSG, ex.getMessage(),
                "AC-17①：错误文案应与契约逐字一致（不是宽松的关键词匹配），实际=" + ex.getMessage());
    }

    /**
     * <b>AC-17② 阴性对照</b>：同一棵树上的<b>叶子</b>料号（没有下级）必须放行。
     * <p>🚨 没有这一条，一个被误改成「按料号类型判」的实现照样能让 ① 变绿 ——
     * AC-17 原文点名的正是这个风险。
     */
    @Test
    @TestTransaction
    @DisplayName("AC-17②：树上的叶子料号（无下级）加入材质元素页签 → 放行（证明判据是树结构不是类型）")
    void ac17_leafPartStillAllowed() {
        Fx f = buildFixture();

        assertDoesNotThrow(() -> quotationService.saveDraft(f.quotationId, draftWith(f, f.leafPartNo)),
                "AC-17②：叶子料号 " + f.leafPartNo + " 无下级，应放行。被拒说明判据被误改成了按类型判。");

        em.flush();
        em.clear();
        List<Object> saved = col("SELECT row_data::text FROM quotation_line_component_data "
                + "WHERE line_item_id = '" + f.lineItemId + "' AND component_id = '" + f.materialComponentId + "'");
        assertTrue(!saved.isEmpty(), "AC-17②：材质元素页签的行数据应已落库（没落库的话『不抛异常』可能只是校验没跑）");
        assertTrue(String.valueOf(saved.get(0)).contains(f.leafPartNo),
                "AC-17②：落库内容应含叶子料号 " + f.leafPartNo + "，实际=" + saved.get(0));
    }
}
