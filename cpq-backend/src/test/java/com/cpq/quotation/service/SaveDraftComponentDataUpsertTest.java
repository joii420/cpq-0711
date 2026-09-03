package com.cpq.quotation.service;

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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829 B-6/B-7 — {@code processBatchStage1} 的 componentData UPSERT 路径集成测试
 * （test.md T-15/T-16/T-17；T-18 的迁移/约束部分已用真实 SQL 在 dev/test 两库直测，见
 * test-report.md，本类只覆盖「连续保存不撞约束」这一条运行时行为 AC-23）。
 *
 * <p>与 {@link SaveDraftRestrictedTabValidationTest} 同样的取舍：复用共享库里已有一条 DRAFT
 * 报价单满足外键，自建最小 Template/Component/LineItem 挂在其下，{@code @TestTransaction} 回滚清理。
 * 组件 tabType 刻意取"主件"（非「材质元素」「外购件」），绕开 B8 反向校验，聚焦 B-6 本身。
 */
@QuarkusTest
@DisplayName("SaveDraftComponentDataUpsertTest — B-6 componentData UPSERT 路径")
class SaveDraftComponentDataUpsertTest {

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

    private static final class Fixture {
        UUID templateId;
        UUID compAId;
        UUID compBId;
        UUID lineItemId;
    }

    /**
     * 造一条已有 2 个 componentData 记录（compA/compB）的复用行——模拟"续存"场景。
     * compA 带非空 snapshotRows + deletedRowKeys（供 T-15/T-17 断言"UPSERT 路径不碰这两列"）。
     */
    private Fixture buildFixture(UUID quotationId) {
        Fixture f = new Fixture();

        Component compA = new Component();
        compA.name = "B6测试-组件A";
        compA.code = "B6-A-" + UUID.randomUUID().toString().substring(0, 8);
        compA.fields = "[]";
        compA.formulas = "[]";
        compA.tabType = "主件"; // 非材质元素/外购件，绕开 B8
        compA.persist();
        f.compAId = compA.id;

        Component compB = new Component();
        compB.name = "B6测试-组件B";
        compB.code = "B6-B-" + UUID.randomUUID().toString().substring(0, 8);
        compB.fields = "[]";
        compB.formulas = "[]";
        compB.tabType = "主件";
        compB.persist();
        f.compBId = compB.id;

        Template tpl = new Template();
        tpl.templateSeriesId = UUID.randomUUID();
        tpl.name = "B6测试-模板";
        tpl.templateKind = "QUOTATION";
        tpl.status = "PUBLISHED";
        tpl.componentsSnapshot = "[{},{}]";
        tpl.createdAt = OffsetDateTime.now();
        tpl.updatedAt = OffsetDateTime.now();
        tpl.persist();
        f.templateId = tpl.id;

        TemplateComponent tcA = new TemplateComponent();
        tcA.templateId = tpl.id;
        tcA.componentId = compA.id;
        tcA.tabName = "组件A";
        tcA.sortOrder = 0;
        tcA.createdAt = OffsetDateTime.now();
        tcA.persist();

        TemplateComponent tcB = new TemplateComponent();
        tcB.templateId = tpl.id;
        tcB.componentId = compB.id;
        tcB.tabName = "组件B";
        tcB.sortOrder = 1;
        tcB.createdAt = OffsetDateTime.now();
        tcB.persist();

        TemplateComponentSnapshot scA = new TemplateComponentSnapshot();
        scA.templateId = tpl.id;
        scA.templateComponentId = tcA.id;
        scA.componentId = compA.id;
        scA.sortOrder = 0;
        scA.tabName = "组件A";
        scA.componentName = compA.name;
        scA.componentCode = compA.code;
        scA.fields = "[]";
        scA.formulas = "[]";
        scA.tabType = "主件";
        scA.persist();

        TemplateComponentSnapshot scB = new TemplateComponentSnapshot();
        scB.templateId = tpl.id;
        scB.templateComponentId = tcB.id;
        scB.componentId = compB.id;
        scB.sortOrder = 1;
        scB.tabName = "组件B";
        scB.componentName = compB.name;
        scB.componentCode = compB.code;
        scB.fields = "[]";
        scB.formulas = "[]";
        scB.tabType = "主件";
        scB.persist();

        Quotation q = Quotation.findById(quotationId);
        assertNotNull(q, "前置 quotation 必须存在");
        q.customerTemplateId = tpl.id;

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

        // 既有 componentData：compA 带非空 snapshot_rows + deleted_row_keys（UPSERT 不应碰这两列），
        // compB 也带一条基线数据。两条 id 固定生成，供 T-15 断言"UPSERT 后 id 不变"。
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data " +
                "(id, line_item_id, component_id, tab_name, row_data, subtotal, sort_order, snapshot_rows, deleted_row_keys, created_at) " +
                "VALUES (:id, :lid, :cid, :tab, CAST(:rd AS jsonb), :st, :so, CAST(:sr AS jsonb), CAST(:drk AS jsonb), :now)")
                .setParameter("id", COMP_A_CD_ID)
                .setParameter("lid", lineItemId)
                .setParameter("cid", compA.id)
                .setParameter("tab", "组件A")
                .setParameter("rd", "[{\"foo\":\"old-A\"}]")
                .setParameter("st", new BigDecimal("10.00"))
                .setParameter("so", 0)
                .setParameter("sr", "[{\"__nodeId\":\"N1\",\"driverRow\":{},\"basicDataValues\":{}}]")
                .setParameter("drk", "[{\"effKey\":\"K1\",\"fp\":\"FP1\"}]")
                .setParameter("now", OffsetDateTime.now())
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data " +
                "(id, line_item_id, component_id, tab_name, row_data, subtotal, sort_order, created_at) " +
                "VALUES (:id, :lid, :cid, :tab, CAST(:rd AS jsonb), :st, :so, :now)")
                .setParameter("id", COMP_B_CD_ID)
                .setParameter("lid", lineItemId)
                .setParameter("cid", compB.id)
                .setParameter("tab", "组件B")
                .setParameter("rd", "[{\"foo\":\"old-B\"}]")
                .setParameter("st", new BigDecimal("20.00"))
                .setParameter("so", 1)
                .setParameter("now", OffsetDateTime.now())
                .executeUpdate();

        em.flush();
        return f;
    }

    private static final UUID COMP_A_CD_ID = UUID.randomUUID();
    private static final UUID COMP_B_CD_ID = UUID.randomUUID();

    private SaveDraftRequest.LineItemDraft draftSameStructure(Fixture f, String newValueA, String newValueB) {
        SaveDraftRequest.LineItemDraft liDraft = new SaveDraftRequest.LineItemDraft();
        liDraft.id = f.lineItemId;
        liDraft.templateId = f.templateId;
        liDraft.sortOrder = 999;

        SaveDraftRequest.ComponentDataDraft cdA = new SaveDraftRequest.ComponentDataDraft();
        cdA.componentId = f.compAId;
        cdA.tabName = "组件A";
        cdA.rowData = "[{\"foo\":\"" + newValueA + "\"}]";
        cdA.subtotal = new BigDecimal("11.00");
        cdA.sortOrder = 0;

        SaveDraftRequest.ComponentDataDraft cdB = new SaveDraftRequest.ComponentDataDraft();
        cdB.componentId = f.compBId;
        cdB.tabName = "组件B";
        cdB.rowData = "[{\"foo\":\"" + newValueB + "\"}]";
        cdB.subtotal = new BigDecimal("21.00");
        cdB.sortOrder = 1;

        liDraft.componentData = List.of(cdA, cdB);
        return liDraft;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> queryComponentData(UUID lineItemId) {
        return em.createNativeQuery(
                "SELECT id, component_id, row_data::text, subtotal, snapshot_rows::text, deleted_row_keys::text " +
                "FROM quotation_line_component_data WHERE line_item_id = :lid ORDER BY sort_order ASC")
                .setParameter("lid", lineItemId).getResultList();
    }

    @Test
    @TestTransaction
    @DisplayName("T-15/AC-18/AC-19：结构未变 → UPSERT，id 不变、snapshot_rows/deleted_row_keys 逐字不变")
    void upsertPath_reusesRows_preservesSnapshotAndTombstones() {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId, "DB 无 DRAFT 报价单 — 请先创建至少一条 DRAFT 状态报价单后再运行本测试");
        Fixture f = buildFixture(quotationId);

        // 保存前先取一次 DB 里 snapshot_rows/deleted_row_keys 的权威文本（PG jsonb 会按自己的规则
        // 重排 key 顺序，不等于我们手写字面量的顺序——必须以"入库后已规范化过一次"的文本为基准，
        // 而不是拿字面量直接比，否则会把 jsonb 的正常规范化误判成 UPSERT 碰了这两列）。
        em.flush();
        em.clear();
        String snapshotRowsBefore = null, deletedRowKeysBefore = null;
        for (Object[] r : queryComponentData(f.lineItemId)) {
            if (f.compAId.equals(toUUID(r[1]))) {
                snapshotRowsBefore = (String) r[4];
                deletedRowKeysBefore = (String) r[5];
            }
        }
        assertNotNull(snapshotRowsBefore, "前置 fixture 应已写入组件A 的 snapshot_rows");
        assertNotNull(deletedRowKeysBefore, "前置 fixture 应已写入组件A 的 deleted_row_keys");

        SaveDraftRequest req = new SaveDraftRequest();
        req.lineItems = List.of(draftSameStructure(f, "new-A", "new-B"));

        assertDoesNotThrow(() -> quotationService.saveDraft(quotationId, req));

        em.flush();
        em.clear();
        List<Object[]> rows = queryComponentData(f.lineItemId);
        assertEquals(2, rows.size(), "UPSERT 后 componentData 行数应仍为 2（不多不少）");

        boolean foundA = false, foundB = false;
        for (Object[] r : rows) {
            UUID id = toUUID(r[0]);
            UUID cid = toUUID(r[1]);
            String rowData = (String) r[2];
            String subtotal = r[3].toString();
            String snapshotRows = (String) r[4];
            String deletedRowKeys = (String) r[5];
            if (f.compAId.equals(cid)) {
                foundA = true;
                assertEquals(COMP_A_CD_ID, id, "组件A 的 componentData id 应保持不变（证明是 UPDATE 而非 delete+insert）");
                assertTrue(rowData.contains("new-A"), "row_data 应已更新为新值: " + rowData);
                assertEquals(new BigDecimal("11.00").doubleValue(), Double.parseDouble(subtotal), 1e-9);
                assertEquals(snapshotRowsBefore, snapshotRows,
                        "🔒 snapshot_rows 必须逐字不变（B-6 UPSERT 不碰该列）");
                assertEquals(deletedRowKeysBefore, deletedRowKeys,
                        "🔒 deleted_row_keys 必须逐字不变（B-6 UPSERT 不碰该列）");
            } else if (f.compBId.equals(cid)) {
                foundB = true;
                assertEquals(COMP_B_CD_ID, id, "组件B 的 componentData id 应保持不变（证明是 UPDATE 而非 delete+insert）");
                assertTrue(rowData.contains("new-B"), "row_data 应已更新为新值: " + rowData);
            }
        }
        assertTrue(foundA && foundB, "两个组件的 componentData 都应存在");
    }

    @Test
    @TestTransaction
    @DisplayName("T-16/AC-20：页签结构变化(新增第三个组件) → 自动回落全删全建")
    void structureChanged_fallsBackToDeleteRebuild() {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId, "DB 无 DRAFT 报价单 — 请先创建至少一条 DRAFT 状态报价单后再运行本测试");
        Fixture f = buildFixture(quotationId);

        // 新增一个此前不存在于该行 componentData 的 componentId → payload 集合 ≠ db 集合 → 回落全删全建
        UUID compCId = UUID.randomUUID();
        SaveDraftRequest.LineItemDraft liDraft = draftSameStructure(f, "new-A", "new-B");
        SaveDraftRequest.ComponentDataDraft cdC = new SaveDraftRequest.ComponentDataDraft();
        cdC.componentId = compCId;
        cdC.tabName = "组件C(新增)";
        cdC.rowData = "[{\"foo\":\"new-C\"}]";
        cdC.subtotal = new BigDecimal("30.00");
        cdC.sortOrder = 2;
        liDraft.componentData = List.of(liDraft.componentData.get(0), liDraft.componentData.get(1), cdC);

        SaveDraftRequest req = new SaveDraftRequest();
        req.lineItems = List.of(liDraft);

        assertDoesNotThrow(() -> quotationService.saveDraft(quotationId, req));

        em.flush();
        em.clear();
        List<Object[]> rows = queryComponentData(f.lineItemId);
        assertEquals(3, rows.size(), "结构变化后应按新结构重建，行数=3（compA+compB+新增compC）");

        boolean foundC = false;
        for (Object[] r : rows) {
            UUID id = toUUID(r[0]);
            UUID cid = toUUID(r[1]);
            if (f.compAId.equals(cid)) {
                assertNotEquals(COMP_A_CD_ID, id, "回落全删全建路径下组件A的 id 应换新（证明真的重建了，不是误判成 UPSERT）");
            }
            if (compCId.equals(cid)) foundC = true;
        }
        assertTrue(foundC, "新增的组件C应已落库");
    }

    @Test
    @TestTransaction
    @DisplayName("AC-23：同一行连续保存 3 次（UPSERT 路径）均成功，不撞 uq_qlcd_line_component 约束")
    void consecutiveUpsertSaves_neverViolateUniqueConstraint() {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId, "DB 无 DRAFT 报价单 — 请先创建至少一条 DRAFT 状态报价单后再运行本测试");
        Fixture f = buildFixture(quotationId);

        for (int i = 1; i <= 3; i++) {
            SaveDraftRequest req = new SaveDraftRequest();
            req.lineItems = List.of(draftSameStructure(f, "round" + i + "-A", "round" + i + "-B"));
            final int round = i;
            assertDoesNotThrow(() -> quotationService.saveDraft(quotationId, req),
                    "第 " + round + " 次保存不应抛异常（含唯一约束冲突）");
        }

        em.flush();
        em.clear();
        List<Object[]> rows = queryComponentData(f.lineItemId);
        assertEquals(2, rows.size(), "连续 3 次保存后仍恰好 2 条 componentData（无重复写入）");
        for (Object[] r : rows) {
            String rowData = (String) r[2];
            assertTrue(rowData.contains("round3-"), "最终落库内容应是最后一次保存的值: " + rowData);
        }
    }
}
