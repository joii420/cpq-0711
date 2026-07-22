package com.cpq.quotation.service.backfill;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.quotation.service.QuotationService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * task-0721 报价数据版本升级 · {@code material_bom}/{@code material_bom_item} 主从表场景补测。
 *
 * <p>覆盖 AC-6（回填-新增）/AC-14（主子同步，从零建组场景）/AC-19 Q1（手工新增行双标记 ——
 * 平铺页签 {@code _origin:'manual'} 与树叶子 {@code __manual} 都要回填）/AC-19 Q6（新料号补
 * {@code material_master} stub）。
 *
 * <p>⚠️ <b>已知阻断（不在本文件覆盖范围）</b>：改值(CHANGE)/删除(DELETE)场景需要
 * {@code driverRow.__v6_id} 被正确解析为 {@code UUID}，但
 * {@code QuoteBackfillCollector.asUuid(driverRow.get("__v6_id"))} 直接对 Jackson
 * {@code JsonNode} 调用 {@code String.valueOf(...)}（等价于 {@code TextNode.toString()}，
 * 返回**带引号**的 JSON 文本形式，而非 {@code .asText()} 的裸字符串），导致
 * {@code UUID.fromString} 收到 38 字符（36+2 引号）输入抛
 * {@code IllegalArgumentException: UUID string too large}。该缺陷已在
 * {@code QuoteBackfillFlatAcceptanceTest}（changeRoute/deleteRoute/deleteToZero/frozen/
 * previewToken 5 个测试）现场复现并定位到 4 个完全同构的调用点
 * （{@code QuoteBackfillCollector.java:253/263/314/341}），故本文件不重复在
 * {@code material_bom_item} 上再证一遍——本文件只使用 ADD 路径（{@code v6Id==null}，
 * 不触发 {@code asUuid}）来验证 AC-6/AC-14/AC-19-Q1/AC-19-Q6。
 */
@QuarkusTest
class QuoteBackfillMasterChildAcceptanceTest {

    private static final String TAG = "T0721MC";

    @Inject QuoteBackfillService backfillService;
    @Inject QuotationService quotationService;
    @Inject EntityManager em;

    @SuppressWarnings("unchecked")
    private UUID resolveCustomerId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    @SuppressWarnings("unchecked")
    private UUID resolveUserId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    @SuppressWarnings("unchecked")
    private UUID financeUserId() {
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM \"user\" WHERE role = 'PRICING_MANAGER' LIMIT 1").getResultList();
        if (!rows.isEmpty()) return UUID.fromString(rows.get(0).toString());
        UUID fid = UUID.randomUUID();
        String suffix = fid.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, role, password_hash, created_at, updated_at) " +
                "VALUES(:id, :un, 'Test Finance', :email, 'PRICING_MANAGER', 'hash', now(), now())")
            .setParameter("id", fid).setParameter("un", "test_finance_" + suffix)
            .setParameter("email", "test_finance_" + suffix + "@test.invalid")
            .executeUpdate();
        return fid;
    }

    private String customerCodeOf(UUID customerId) {
        return (String) em.createNativeQuery("SELECT code FROM customer WHERE id = :cid")
            .setParameter("cid", customerId).getSingleResult();
    }

    private UUID newQuotation(UUID customerId, UUID salesRepId, String status) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                " tax_rate, tax_amount, created_at, updated_at) " +
                "VALUES (:id, :qn, :cid, :name, :srid, :status, 0, 0, now(), now())")
            .setParameter("id", id).setParameter("qn", TAG + "-" + id.toString().substring(0, 8))
            .setParameter("cid", customerId).setParameter("name", TAG + "-quotation")
            .setParameter("srid", salesRepId).setParameter("status", status)
            .executeUpdate();
        return id;
    }

    private UUID newLineItem(UUID quotationId, String productPartNo) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot, sort_order, created_at) " +
                "VALUES (:id, :qid, :pn, 0, now())")
            .setParameter("id", id).setParameter("qid", quotationId).setParameter("pn", productPartNo)
            .executeUpdate();
        return id;
    }

    /** 建平铺（非树）material_bom_item 组件 + $view，返回 componentId。 */
    private UUID newFlatBomItemComponent(String suffix) {
        Component c = new Component();
        c.name = TAG + "-平铺BOM-" + suffix;
        c.code = TAG + "-FLAT-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6);
        c.fields = "[{\"name\":\"子料号\",\"field_type\":\"INPUT_TEXT\"}]";
        c.formulas = "[]";
        c.tabType = "零件";
        c.partNoField = "子料号";
        c.rowKeyFields = "[\"子料号\"]";
        c.dataDriverPath = "$" + (TAG + "_flat_" + suffix).toLowerCase();
        c.persist();
        newBomItemView(c.id, (TAG + "_flat_" + suffix).toLowerCase());
        return c.id;
    }

    /** 建树（tabType=BOM）material_bom_item 组件 + $view，返回 componentId（本测试只测 collector 分类，非真实递归渲染）。 */
    private UUID newTreeBomItemComponent(String suffix) {
        Component c = new Component();
        c.name = TAG + "-树BOM-" + suffix;
        c.code = TAG + "-TREE-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6);
        c.fields = "[]";
        c.formulas = "[]";
        c.tabType = "BOM";
        c.bomRecursiveExpand = true;
        c.dataDriverPath = "$" + (TAG + "_tree_" + suffix).toLowerCase();
        c.persist();
        newBomItemView(c.id, (TAG + "_tree_" + suffix).toLowerCase());
        return c.id;
    }

    private void newBomItemView(UUID componentId, String viewName) {
        ComponentSqlView view = new ComponentSqlView();
        view.componentId = componentId;
        view.sqlViewName = viewName;
        view.sqlTemplate =
            "SELECT\n" +
            "  mbt.material_no AS hf_part_no,\n" +
            "  mbt.seq_no AS 序号,\n" +
            "  mbt.component_no AS 子料号,\n" +
            "  mbt.composition_qty AS 数量,\n" +
            "  mbt.issue_unit AS 单位\n" +
            "FROM material_bom_item mbt\n" +
            "WHERE mbt.system_type = 'QUOTE' AND mbt.customer_no = :customerCode AND mbt.is_current = true\n" +
            "ORDER BY mbt.seq_no";
        view.declaredColumns = "[]";
        view.persist();
        em.flush();
    }

    private void writeComponentData(UUID lineItemId, UUID componentId, String tabName,
                                     String snapshotRowsJson, String rowDataJson, String deletedRowKeysJson) {
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data " +
                "(id, line_item_id, component_id, tab_name, snapshot_rows, row_data, deleted_row_keys, created_at) " +
                "VALUES (:id, :lid, :cid, :tab, CAST(:sr AS jsonb), CAST(:rd AS jsonb), CAST(:drk AS jsonb), now())")
            .setParameter("id", UUID.randomUUID()).setParameter("lid", lineItemId).setParameter("cid", componentId)
            .setParameter("tab", tabName).setParameter("sr", snapshotRowsJson).setParameter("rd", rowDataJson)
            .setParameter("drk", deletedRowKeysJson)
            .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> currentBomItems(String customerNo, String rootMaterialNo) {
        return em.createNativeQuery(
                "SELECT component_no, composition_qty, issue_unit, bom_version FROM material_bom_item " +
                "WHERE system_type = 'QUOTE' AND customer_no = :cn AND material_no = :mn AND is_current = true " +
                "ORDER BY component_no")
            .setParameter("cn", customerNo).setParameter("mn", rootMaterialNo)
            .getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> currentBomMaster(String customerNo, String rootMaterialNo) {
        return em.createNativeQuery(
                "SELECT bom_version, bom_type FROM material_bom " +
                "WHERE system_type = 'QUOTE' AND customer_no = :cn AND material_no = :mn AND is_current = true")
            .setParameter("cn", customerNo).setParameter("mn", rootMaterialNo)
            .getResultList();
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-6 + AC-14 + AC-19 Q1(flat 标记) + AC-19 Q6：平铺页签手工新增(_origin:'manual')
    // 从零建组，验证主子同步 + 新料号补 stub
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void flatManualAdd_masterChildSyncFromScratch_newMaterialStubCreated() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String rootMaterialNo = "MCROOT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String childA = "MCCHILDA" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String childB = "MCCHILDB" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        // 前置：两个子料号在 material_master 均无记录（全新料号）
        long preExisting = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_master WHERE material_no IN (:a, :b)")
            .setParameter("a", childA).setParameter("b", childB).getSingleResult()).longValue();
        assertEquals(0L, preExisting, "测试前置：两个子料号不应预先存在于 material_master");

        UUID componentId = newFlatBomItemComponent("ADD");
        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineItemId = newLineItem(quotationId, rootMaterialNo);

        // 无既有 driver 行(snapshot_rows=[])，两条手工新增行走 row_data(_origin:'manual')
        String rowData =
            "[{\"_origin\":\"manual\",\"序号\":1,\"子料号\":\"" + childA + "\",\"数量\":2.5,\"单位\":\"EA\"}," +
            "{\"_origin\":\"manual\",\"序号\":2,\"子料号\":\"" + childB + "\",\"数量\":1.0,\"单位\":\"EA\"}]";
        writeComponentData(lineItemId, componentId, "零件", "[]", rowData, "[]");

        QuoteBackfillService.Summary summary = backfillService.execute(quotationId, finance);
        assertEquals(2, summary.addedRows, "应识别到 2 行手工新增（AC-19 Q1 flat 标记 _origin:'manual'）");

        List<Object[]> items = currentBomItems(customerNo, rootMaterialNo);
        assertEquals(2, items.size(), "回填后应有 2 条 material_bom_item 子行");
        java.util.Set<String> componentNos = new java.util.HashSet<>();
        String bomVersion = null;
        for (Object[] row : items) {
            componentNos.add((String) row[0]);
            bomVersion = (String) row[3];
        }
        assertTrue(componentNos.contains(childA) && componentNos.contains(childB),
            "两个手工新增子料号应都进入新版本，实际=" + componentNos);

        List<Object[]> master = currentBomMaster(customerNo, rootMaterialNo);
        assertEquals(1, master.size(), "应恰好产生 1 条 material_bom 主表行（AC-14 主子同步）");
        assertEquals(bomVersion, master.get(0)[0],
            "主表 bom_version 应与子表完全一致（不失步，复刻 V333/V339 教训）");

        // AC-19 Q6：全新料号应补建 material_master stub
        long stubCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_master WHERE material_no IN (:a, :b)")
            .setParameter("a", childA).setParameter("b", childB).getSingleResult()).longValue();
        assertEquals(2L, stubCount, "两个全新料号回填后都应补建 material_master stub（AC-19 Q6）");

        // pending 清理
        long pendingRemaining = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_bom_item WHERE pending_quotation_id = :qid")
            .setParameter("qid", quotationId).getSingleResult()).longValue();
        assertEquals(0L, pendingRemaining);
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-19 Q1（树叶子标记 __manual）：树页签手工新增叶子同样应被回填识别
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void treeManualLeaf_recognizedAndBackfilled() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String rootMaterialNo = "MCTROOT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String leafChild = "MCTLEAF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        UUID componentId = newTreeBomItemComponent("LEAF");
        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineItemId = newLineItem(quotationId, rootMaterialNo);

        // 树叶子：__manual=true，无 __v6_id；__parentNo=根料号；__nodeType=零件（BomNodeTypeResolver 已算好）
        String snapshotRows =
            "[{\"driverRow\":{\"material_no\":\"" + leafChild + "\"},\"__manual\":true," +
            "\"__parentNo\":\"" + rootMaterialNo + "\",\"__nodeType\":\"零件\",\"__nodeId\":\"" +
            rootMaterialNo + "/__manual_1\"}]";
        writeComponentData(lineItemId, componentId, "BOM树", snapshotRows, "[]", "[]");

        QuoteBackfillService.Summary summary = backfillService.execute(quotationId, finance);
        assertEquals(1, summary.addedRows, "树叶子 __manual 标记应被识别为新增行（AC-19 Q1 树标记）");

        List<Object[]> items = currentBomItems(customerNo, rootMaterialNo);
        assertEquals(1, items.size());
        assertEquals(leafChild, items.get(0)[0]);
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-14 撞键防护：两行仅 component_no 不同、其余轴列全同 —— 不应撞 uq_material_bom_item
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void nearCollisionRows_noUniqueConstraintViolation() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String rootMaterialNo = "MCCOLL" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String childA = "MCCOLLA" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String childB = "MCCOLLB" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        UUID componentId = newFlatBomItemComponent("COLL");
        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineItemId = newLineItem(quotationId, rootMaterialNo);

        // 两行 seq_no 相同、数量/单位相同，仅 component_no 不同 —— uq_material_bom_item 用
        // COALESCE(component_no,'') 区分，理应不撞键
        String rowData =
            "[{\"_origin\":\"manual\",\"序号\":1,\"子料号\":\"" + childA + "\",\"数量\":1.0,\"单位\":\"EA\"}," +
            "{\"_origin\":\"manual\",\"序号\":1,\"子料号\":\"" + childB + "\",\"数量\":1.0,\"单位\":\"EA\"}]";
        writeComponentData(lineItemId, componentId, "零件", "[]", rowData, "[]");

        assertDoesNotThrow(() -> backfillService.execute(quotationId, finance),
            "近撞键(仅 component_no 不同)不应触发 uq_material_bom_item 唯一约束冲突");

        List<Object[]> items = currentBomItems(customerNo, rootMaterialNo);
        assertEquals(2, items.size(), "两条近撞键行应都正确落库，互不覆盖");
    }
}
