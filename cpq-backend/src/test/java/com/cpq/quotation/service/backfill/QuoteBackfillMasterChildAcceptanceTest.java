package com.cpq.quotation.service.backfill;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.quotation.dto.backfill.BackfillPreviewDTO;
import com.cpq.quotation.service.QuotationService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
 * <p>历史遗留：本文件曾因 {@code QuoteBackfillCollector.asUuid} 的一个 JsonNode 解析缺陷
 * （{@code UUID.fromString} 收到带引号的 38 字符输入报错）而只能用 ADD 路径覆盖——该缺陷已在
 * task-0721 修复（{@code QuoteBackfillFlatAcceptanceTest} 的 changeRoute/deleteRoute 系列已验证），
 * 下方 repair-0727 新增的 {@link #existingBaseGroup_partialPageOverlap_untouchedSiblingsSurvive_D1D2Dedup}
 * 直接在本表用 CHANGE 路径复现 D1 事故场景，证明该缺陷不再是阻断。
 *
 * <p>repair-0727 新增：回填改 patch 语义（基底行集 ⊕ 列级 patch ⊖ 墓碑 ⊕ 手工新增）在主从表
 * （{@code material_bom_item}/{@code material_bom}）上的验收——D1（页签投影当整组权威 → 反向删行）
 * 事故本体就发生在这张表（QT-20260726-0016 / S-3120014539，需求说明 §1.2），旧 ADD-only 测试对此
 * 完全没有覆盖（从零建组时"基底"天然为空，patch 语义与旧"页签行=全部"语义结果一致，侥幸通过、
 * 掩盖了 D1）。见 {@link #existingBaseGroup_partialPageOverlap_untouchedSiblingsSurvive_D1D2Dedup}。
 */
@QuarkusTest
class QuoteBackfillMasterChildAcceptanceTest {

    private static final String TAG = "T0721MC";

    @Inject QuoteBackfillService backfillService;
    @Inject QuoteBackfillPreviewService previewService;
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
        writeComponentData(lineItemId, componentId, tabName, snapshotRowsJson, rowDataJson, deletedRowKeysJson, 0);
    }

    /** repair-0727 B3.2：带 sortOrder 的重载，供多页签 patch 冲突仲裁测试用。 */
    private void writeComponentData(UUID lineItemId, UUID componentId, String tabName,
                                     String snapshotRowsJson, String rowDataJson, String deletedRowKeysJson,
                                     int sortOrder) {
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data " +
                "(id, line_item_id, component_id, tab_name, snapshot_rows, row_data, deleted_row_keys, sort_order, created_at) " +
                "VALUES (:id, :lid, :cid, :tab, CAST(:sr AS jsonb), CAST(:rd AS jsonb), CAST(:drk AS jsonb), :so, now())")
            .setParameter("id", UUID.randomUUID()).setParameter("lid", lineItemId).setParameter("cid", componentId)
            .setParameter("tab", tabName).setParameter("sr", snapshotRowsJson).setParameter("rd", rowDataJson)
            .setParameter("drk", deletedRowKeysJson).setParameter("so", sortOrder)
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

    /** repair-0727 D1/D2 验证用：多带 characteristic + base_qty（未被任何页签暴露的列）。 */
    @SuppressWarnings("unchecked")
    private List<Object[]> currentBomItemsFull(String customerNo, String rootMaterialNo) {
        return em.createNativeQuery(
                "SELECT component_no, characteristic, composition_qty, base_qty, issue_unit FROM material_bom_item " +
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

    /** 插一条 pending material_bom_item 行（模拟"本单 pending 基底"），返回其 id。 */
    private UUID insertPendingBomItem(UUID quotationId, String customerNo, String rootNo, String componentNo,
                                       String characteristic, BigDecimal compositionQty, BigDecimal baseQty,
                                       String issueUnit) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO material_bom_item (id, system_type, customer_no, material_no, component_no, " +
                "  characteristic, composition_qty, base_qty, issue_unit, bom_version, is_current, " +
                "  pending_quotation_id, created_at, updated_at) " +
                "VALUES (:id, 'QUOTE', :cn, :mn, :comp, :ch, :cq, :bq, :iu, '2001', false, :pq, now(), now())")
            .setParameter("id", id).setParameter("cn", customerNo).setParameter("mn", rootNo)
            .setParameter("comp", componentNo).setParameter("ch", characteristic)
            .setParameter("cq", compositionQty).setParameter("bq", baseQty).setParameter("iu", issueUnit)
            .setParameter("pq", quotationId)
            .executeUpdate();
        return id;
    }

    /** 建平铺 material_bom_item 组件 + $view，带 {@code characteristic} 过滤（复刻真实 wg_view「外购件」页签形状，
     *  D1 事故正是这类"只表征组内一个 characteristic 子集"的页签触发）。两列都映射的默认版本。 */
    private UUID newFilteredFlatBomItemComponent(String suffix, String characteristicFilter) {
        return newFilteredFlatBomItemComponent(suffix, characteristicFilter, true, true);
    }

    /**
     * repair-0727 decision②自检专用：可控制该视图只映射「数量」/「单位」中的哪一列——模拟真实场景里
     * 不同页签（如 wg_view vs 另一个 bom_closure 共享页签）本就映射不同物理列子集，用于验证"两页签
     * 表征同一行但各自只 patch 不重叠的列"应正确合并到 1 行，而不是像本测试最初版本那样因为两个 view
     * 都映射了全部列、把"值未变但列存在"也当成 patch candidate，制造出本不存在的列冲突。
     */
    private UUID newFilteredFlatBomItemComponent(String suffix, String characteristicFilter,
                                                  boolean mapQty, boolean mapUnit) {
        Component c = new Component();
        c.name = TAG + "-外购件-" + suffix;
        c.code = TAG + "-FILT-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6);
        c.fields = "[{\"name\":\"子料号\",\"field_type\":\"INPUT_TEXT\"}]";
        c.formulas = "[]";
        c.tabType = "零件";
        c.partNoField = "子料号";
        c.rowKeyFields = "[\"子料号\"]";
        c.dataDriverPath = "$" + (TAG + "_filt_" + suffix).toLowerCase();
        c.persist();

        StringBuilder sql = new StringBuilder(
            "SELECT\n" +
            "  mbt.material_no AS hf_part_no,\n" +
            "  mbt.seq_no AS 序号,\n" +
            "  mbt.component_no AS 子料号");
        if (mapQty) sql.append(",\n  mbt.composition_qty AS 数量");
        if (mapUnit) sql.append(",\n  mbt.issue_unit AS 单位");
        sql.append("\nFROM material_bom_item mbt\n")
           .append("WHERE mbt.system_type = 'QUOTE' AND mbt.customer_no = :customerCode AND mbt.is_current = true\n")
           .append("  AND mbt.characteristic = '").append(characteristicFilter).append("'\n")
           .append("ORDER BY mbt.seq_no");

        ComponentSqlView view = new ComponentSqlView();
        view.componentId = c.id;
        view.sqlViewName = (TAG + "_filt_" + suffix).toLowerCase();
        view.sqlTemplate = sql.toString();
        view.declaredColumns = "[]";
        view.persist();
        em.flush();
        return c.id;
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

    // ══════════════════════════════════════════════════════════════════════
    // repair-0727 核心验收：D1(行丢失)+D2(列丢失)+decision②(多页签重叠去重)+AC-R4(预览≡执行)
    //
    // 复刻 QT-20260726-0016 事故（需求说明 §1.2）：组 4 行（RECIPE×2/ASSEMBLY/OUTSOURCED），本单
    // pending 基底已存在；「外购件」页签只表征 OUTSOURCED 那 1 行（旧丙·全量对齐语义会把整组重建成
    // 只剩这 1 行，正是 D1）。本测试额外让第二个页签也表征同一行的不同列（验证 decision② "同一行被
    // 多页签表征不产生重复行，只合并 patch"），并追加 1 条手工新增。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void existingBaseGroup_partialPageOverlap_untouchedSiblingsSurvive_D1D2Dedup() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String rootMaterialNo = "D1ROOT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String recipe1 = "D1RCP1" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String recipe2 = "D1RCP2" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String assembly = "D1ASM" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String outsourced = "D1OUT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String newChild = "D1NEW" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineItemId = newLineItem(quotationId, rootMaterialNo);

        // 组 4 行本单 pending 基底（D2：base_qty 是任何页签都不暴露的列，见下方 view 只映射「数量」=composition_qty）。
        insertPendingBomItem(quotationId, customerNo, rootMaterialNo, recipe1, "RECIPE",
            new BigDecimal("1.100000"), new BigDecimal("0.624610"), "g/PCS");
        insertPendingBomItem(quotationId, customerNo, rootMaterialNo, recipe2, "RECIPE",
            new BigDecimal("2.200000"), new BigDecimal("0.800000"), "g/PCS");
        insertPendingBomItem(quotationId, customerNo, rootMaterialNo, assembly, "ASSEMBLY",
            new BigDecimal("3.000000"), new BigDecimal("1.500000"), "EA");
        UUID outsourcedId = insertPendingBomItem(quotationId, customerNo, rootMaterialNo, outsourced, "OUTSOURCED",
            new BigDecimal("2.000000"), new BigDecimal("2.000000"), "EA");

        // 页签 A（外购件，sortOrder=0，只映射「数量」列）：CHANGE 表征 OUTSOURCED 行，composition_qty 2.0→5.0。
        // decision②自检：两页签映射不重叠的物理列（模拟真实 bom_closure 场景里不同页签本就各映射一部分
        // 列），验证合并到 1 行时互不覆盖——而不是像"两 view 都映射全部列"那样把"值未变但列存在"也
        // 当 patch candidate 制造出假冲突。
        UUID compA = newFilteredFlatBomItemComponent("A", "OUTSOURCED", true, false);
        String snapshotA = "[{\"driverRow\":{\"hf_part_no\":\"" + rootMaterialNo + "\",\"序号\":1,\"子料号\":\"" +
            outsourced + "\",\"数量\":2.000000,\"__v6_id\":\"" + outsourcedId + "\"}}]";
        // 手工新增（_origin:manual）也放在页签 A（该页签映射「数量」列，新增行的 composition_qty 才能落库）。
        String rowDataA = "[{\"序号\":1,\"子料号\":\"" + outsourced + "\",\"数量\":5.000000}," +
            "{\"_origin\":\"manual\",\"序号\":2,\"子料号\":\"" + newChild + "\",\"数量\":9.000000}]";
        writeComponentData(lineItemId, compA, "外购件A", snapshotA, rowDataA, "[]", 0);

        // 页签 B（外购件-同 characteristic，sortOrder=1，只映射「单位」列）：同一行(__v6_id 相同)、改
        // issue_unit EA→PC。decision②要求：同一行两页签表征不应产生 2 条 effectiveNewRows
        // （不撞 uq_material_bom_item），不重叠列的 patch 应都合并到 1 行上。
        UUID compB = newFilteredFlatBomItemComponent("B", "OUTSOURCED", false, true);
        String snapshotB = "[{\"driverRow\":{\"hf_part_no\":\"" + rootMaterialNo + "\",\"序号\":1,\"子料号\":\"" +
            outsourced + "\",\"单位\":\"EA\",\"__v6_id\":\"" + outsourcedId + "\"}}]";
        String rowDataB = "[{\"序号\":1,\"子料号\":\"" + outsourced + "\",\"单位\":\"PC\"}]";
        writeComponentData(lineItemId, compB, "外购件B", snapshotB, rowDataB, "[]", 1);

        // ── AC-R4 前半：先预览，断言 dry-run 声称的变更数 ──
        BackfillPreviewDTO preview = previewService.preview(quotationId);
        assertEquals(1, preview.summary.changedRows, "预览应只识别 1 行改值(合并后的 OUTSOURCED 行)，实际="
            + preview.summary.changedRows);
        assertEquals(1, preview.summary.addedRows, "预览应识别 1 行手工新增");
        assertEquals(0, preview.summary.deletedRows);
        var bomGroup = preview.groups.stream().filter(g -> "material_bom_item".equals(g.table)).findFirst().orElse(null);
        assertNotNull(bomGroup, "预览应包含 material_bom_item 组");
        assertEquals(4, bomGroup.baseRowCount, "基底行数应为 4（本单 pending 全部行）");
        assertEquals(5, bomGroup.resultRowCount, "有效行集应为 4 基底(1 改值+3 原样) + 1 新增 = 5");
        assertEquals("PENDING", bomGroup.baseSource);
        assertEquals("REBUILD", bomGroup.route);

        // ── 执行回填 ──
        QuoteBackfillService.Summary summary = assertDoesNotThrow(() -> backfillService.execute(quotationId, finance),
            "同一行被两页签表征不应触发 uq_material_bom_item 唯一约束冲突（decision②）");

        // ── AC-R4 后半：执行结果应与预览完全一致 ──
        assertEquals(1, summary.changedRows, "执行摘要应与预览一致：1 行改值");
        assertEquals(1, summary.addedRows, "执行摘要应与预览一致：1 行新增");
        assertEquals(0, summary.deletedRows);

        List<Object[]> items = currentBomItemsFull(customerNo, rootMaterialNo);
        assertEquals(5, items.size(), "D1 核心断言：组应保留 4 条基底(3 未表征+1 改值) + 1 新增 = 5 行，" +
            "而不是像旧丙·全量对齐语义那样被「外购件」页签的 1 行投影重建成只剩 1 行");

        Map<String, Object[]> byComponentNo = new java.util.HashMap<>();
        for (Object[] row : items) byComponentNo.put((String) row[0], row);

        // D1：未被任何页签表征的 3 行必须原样存活（component_no/characteristic/composition_qty/base_qty/issue_unit 逐字段不变）。
        Object[] r1 = byComponentNo.get(recipe1);
        assertNotNull(r1, "recipe1 未被任何页签表征，回填后必须仍存在（D1）");
        assertEquals("RECIPE", r1[1]);
        assertEquals(0, new BigDecimal("1.100000").compareTo((BigDecimal) r1[2]), "recipe1 composition_qty 不应被改动");
        assertEquals(0, new BigDecimal("0.624610").compareTo((BigDecimal) r1[3]), "recipe1 base_qty(未暴露列) 不应被改动(D2)");

        Object[] r2 = byComponentNo.get(recipe2);
        assertNotNull(r2, "recipe2 未被任何页签表征，回填后必须仍存在（D1）");
        assertEquals(0, new BigDecimal("2.200000").compareTo((BigDecimal) r2[2]));
        assertEquals(0, new BigDecimal("0.800000").compareTo((BigDecimal) r2[3]), "recipe2 base_qty(未暴露列) 不应被改动(D2)");

        Object[] ra = byComponentNo.get(assembly);
        assertNotNull(ra, "assembly 未被任何页签表征，回填后必须仍存在（D1）");
        assertEquals("ASSEMBLY", ra[1]);
        assertEquals(0, new BigDecimal("3.000000").compareTo((BigDecimal) ra[2]));
        assertEquals(0, new BigDecimal("1.500000").compareTo((BigDecimal) ra[3]), "assembly base_qty(未暴露列) 不应被改动(D2)");

        // decision②：OUTSOURCED 行应恰好 1 条（不因两页签表征而重复），且两页签的 patch 应合并
        // （数量=5.0 来自页签A，单位=PC 来自页签B），未被任一页签碰过的 base_qty 保持基底值(D2)。
        Object[] ro = byComponentNo.get(outsourced);
        assertNotNull(ro, "OUTSOURCED 行应存在且恰好 1 条");
        assertEquals(0, new BigDecimal("5.000000").compareTo((BigDecimal) ro[2]), "composition_qty 应取页签A的新值(合并①)");
        assertEquals("PC", ro[4], "issue_unit 应取页签B的新值(合并②，两页签 patch 不同列应都生效)");
        assertEquals(0, new BigDecimal("2.000000").compareTo((BigDecimal) ro[3]),
            "base_qty(两页签都未暴露的列) 回填后应仍是基底原值 2.0，不应被清空或改动(D2)");

        Object[] rn = byComponentNo.get(newChild);
        assertNotNull(rn, "手工新增行应存在");
        assertEquals(0, new BigDecimal("9.000000").compareTo((BigDecimal) rn[2]));

        // 主子同步（AC-14）：material_bom 主表应恰好 1 条，bom_version 与子表一致。
        List<Object[]> master = currentBomMaster(customerNo, rootMaterialNo);
        assertEquals(1, master.size(), "应恰好产生 1 条 material_bom 主表行");

        // pending 清理
        long pendingRemaining = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_bom_item WHERE pending_quotation_id = :qid")
            .setParameter("qid", quotationId).getSingleResult()).longValue();
        assertEquals(0L, pendingRemaining, "回填后本单 pending 残留应清理干净");
    }
}
