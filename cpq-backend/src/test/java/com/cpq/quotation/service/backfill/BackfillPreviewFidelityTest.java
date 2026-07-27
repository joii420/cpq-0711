package com.cpq.quotation.service.backfill;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.quotation.dto.backfill.BackfillPreviewDTO;
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
 * repair-0727 · cpq-tester 独立验证 · AC-R4 黄金用例（预览 ≡ 执行，需求说明 §4 AC-R4，
 * test-cases.md §5）。
 *
 * <p>本文件不是重复造轮子——已读过 {@code QuoteBackfillMasterChildAcceptanceTest
 * #existingBaseGroup_partialPageOverlap_untouchedSiblingsSurvive_D1D2Dedup}（覆盖"改值+新增"
 * 组合的 D1/D2/decision② + 部分 AC-R4），但该测试全程都有真实内容变化（route 恒为 REBUILD），
 * 没有覆盖 repair-0727 裁决①新增 NOOP 路径之后、patch 语义下真正的两个边界：
 * <ol>
 *   <li>{@link #flipPath_partialTabCoverageNoEdit_allFourBaseRowsSurvive}：基底来自 PENDING 且
 *       页签只表征组内一行、一字未改 → 路径应为 FLIP（非 REBUILD 非 NOOP），预览应如实显示
 *       "0 变更但组仍会被物化"，执行后 4 行基底逐字段原样转正——这是 D1 场景在"零编辑"边界下的
 *       退化情形，也是全文档"预览≡执行"最容易被裁决①的 NOOP 新增而混淆的一条（NOOP 只吃
     *       baseSource=CURRENT 的零变更，PENDING 零变更走 FLIP 且必须保留全部行，两者不能混为一谈）。</li>
 *   <li>{@link #deleteWithPartialCoverage_untouchedSiblingsSurvive_notCollapsedToZero}：组内 4 行
 *       仅 1 行被页签表征，且该行被显式墓碑删除——必须验证结果是"剩 3 行"而不是（容易被误判的）
 *       "整组归零"。这是 test-cases.md BE-R1-03 标注的"master 上会坍缩到 0 而非 3"陷阱用例，
 *       现有测试从未覆盖"部分表征 + 删除"的组合。</li>
 *   <li>{@link #changeAndDeletePreviewExecuteAlignment_exactRowCounts}：同一组内一行改值、另一行
 *       删除，验证预览宣称的 changedRows/deletedRows 与执行后 DB 实际差异逐数字相等，且未被
     *       任何页签触达的第三行原样保留（D1D2Dedup 测试用的是"改值+新增"组合，从未验证过
 *       "改值+删除"组合下预览数字与执行结果的精确对齐）。</li>
 * </ol>
 */
@QuarkusTest
class BackfillPreviewFidelityTest {

    private static final String TAG = "REPAIR0727PF";

    @Inject QuoteBackfillService backfillService;
    @Inject QuoteBackfillPreviewService previewService;
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

    /** 建平铺 material_bom_item 组件 + $view，带 characteristic 过滤（复刻真实 wg_view「外购件」页签形状）。 */
    private UUID newFilteredFlatBomItemComponent(String suffix, String characteristicFilter) {
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

        String sql =
            "SELECT\n" +
            "  mbt.material_no AS hf_part_no,\n" +
            "  mbt.seq_no AS 序号,\n" +
            "  mbt.component_no AS 子料号,\n" +
            "  mbt.composition_qty AS 数量,\n" +
            "  mbt.issue_unit AS 单位\n" +
            "FROM material_bom_item mbt\n" +
            "WHERE mbt.system_type = 'QUOTE' AND mbt.customer_no = :customerCode AND mbt.is_current = true\n" +
            "  AND mbt.characteristic = '" + characteristicFilter + "'\n" +
            "ORDER BY mbt.seq_no";

        ComponentSqlView view = new ComponentSqlView();
        view.componentId = c.id;
        view.sqlViewName = (TAG + "_filt_" + suffix).toLowerCase();
        view.sqlTemplate = sql;
        view.declaredColumns = "[]";
        view.persist();
        em.flush();
        return c.id;
    }

    private void writeComponentData(UUID lineItemId, UUID componentId, String tabName,
                                     String snapshotRowsJson, String rowDataJson, String deletedRowKeysJson) {
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data " +
                "(id, line_item_id, component_id, tab_name, snapshot_rows, row_data, deleted_row_keys, sort_order, created_at) " +
                "VALUES (:id, :lid, :cid, :tab, CAST(:sr AS jsonb), CAST(:rd AS jsonb), CAST(:drk AS jsonb), 0, now())")
            .setParameter("id", UUID.randomUUID()).setParameter("lid", lineItemId).setParameter("cid", componentId)
            .setParameter("tab", tabName).setParameter("sr", snapshotRowsJson).setParameter("rd", rowDataJson)
            .setParameter("drk", deletedRowKeysJson)
            .executeUpdate();
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

    @SuppressWarnings("unchecked")
    private List<Object[]> currentBomItemsFull(String customerNo, String rootMaterialNo) {
        return em.createNativeQuery(
                "SELECT component_no, characteristic, composition_qty, base_qty, issue_unit FROM material_bom_item " +
                "WHERE system_type = 'QUOTE' AND customer_no = :cn AND material_no = :mn AND is_current = true " +
                "ORDER BY component_no")
            .setParameter("cn", customerNo).setParameter("mn", rootMaterialNo)
            .getResultList();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 1（test-cases.md BE-R4-01 更新版）：基底=PENDING、页签只表征 1 行且未改值 → FLIP，
    // 4 行基底应全部原样转正（不是"未表征的 3 行丢失"）。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void flipPath_partialTabCoverageNoEdit_allFourBaseRowsSurvive() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String rootMaterialNo = "PF1ROOT" + UUID.randomUUID().toString().substring(0, 7).toUpperCase();
        String recipe1 = "PF1R1" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String recipe2 = "PF1R2" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String assembly = "PF1A" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String outsourced = "PF1O" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineItemId = newLineItem(quotationId, rootMaterialNo);

        insertPendingBomItem(quotationId, customerNo, rootMaterialNo, recipe1, "RECIPE",
            new BigDecimal("1.100000"), new BigDecimal("0.624610"), "g/PCS");
        insertPendingBomItem(quotationId, customerNo, rootMaterialNo, recipe2, "RECIPE",
            new BigDecimal("2.200000"), new BigDecimal("0.800000"), "g/PCS");
        insertPendingBomItem(quotationId, customerNo, rootMaterialNo, assembly, "ASSEMBLY",
            new BigDecimal("3.000000"), new BigDecimal("1.500000"), "EA");
        UUID outsourcedId = insertPendingBomItem(quotationId, customerNo, rootMaterialNo, outsourced, "OUTSOURCED",
            new BigDecimal("2.000000"), new BigDecimal("9.999999"), "EA");

        // 注意：不带「序号」——insertPendingBomItem 从不设置 seq_no（DB 侧为 NULL），若在这里带上
        // 「序号」会造成 NULL(基底) vs 1(patch) 的伪差异，把本该 0-diff 的场景误判成有变化（这是
        // 测试 fixture 本身要避免的陷阱，不是被测代码的问题——colToBase 只要 driverRow/row_data 都不带
        // 该 alias 键就不会尝试 patch 它，seq_no 保持基底 NULL 不受影响）。
        UUID compA = newFilteredFlatBomItemComponent("A", "OUTSOURCED");
        String snapshotA = "[{\"driverRow\":{\"hf_part_no\":\"" + rootMaterialNo + "\",\"子料号\":\"" +
            outsourced + "\",\"数量\":2.000000,\"单位\":\"EA\",\"__v6_id\":\"" + outsourcedId + "\"}}]";
        // row_data 与 driverRow 完全一致——用户只是看了一眼，一字未改。
        String rowDataA = "[{\"子料号\":\"" + outsourced + "\",\"数量\":2.000000,\"单位\":\"EA\"}]";
        writeComponentData(lineItemId, compA, "外购件", snapshotA, rowDataA, "[]");

        BackfillPreviewDTO preview = previewService.preview(quotationId);
        var bomGroup = preview.groups.stream().filter(g -> "material_bom_item".equals(g.table)).findFirst().orElse(null);
        assertNotNull(bomGroup, "组应出现在预览里（即便零内容变化，仍会真实发生 pending→current 转正写入）");
        assertEquals("FLIP", bomGroup.route, "基底=PENDING 且无实际差异 → 应判定 FLIP（不是 REBUILD，也不是裁决①新增的 NOOP——" +
            "NOOP 只吃 baseSource=CURRENT 的零变更）");
        assertEquals("PENDING", bomGroup.baseSource);
        assertEquals(4, bomGroup.baseRowCount, "基底行数应为 4（本单 pending 全部行，不是页签表征的 1 行）");
        assertEquals(4, bomGroup.resultRowCount);
        assertTrue(bomGroup.rows.isEmpty(), "无实际差异，rows 应为空（预览如实：不存在的变更不展示）");
        assertEquals(0, preview.summary.changedRows);
        assertEquals(0, preview.summary.addedRows);
        assertEquals(0, preview.summary.deletedRows);

        QuoteBackfillService.Summary summary = backfillService.execute(quotationId, finance);
        assertEquals(1, summary.versionedGroups, "FLIP 仍应计入 versionedGroups（真实发生了 pending→current 写入）");
        assertEquals(0, summary.changedRows);
        assertEquals(0, summary.addedRows);
        assertEquals(0, summary.deletedRows);

        List<Object[]> items = currentBomItemsFull(customerNo, rootMaterialNo);
        assertEquals(4, items.size(), "★黄金断言：4 行基底应全部转正存活，不因页签只表征 OUTSOURCED 1 行而坍缩");

        Map<String, Object[]> byComponentNo = new java.util.HashMap<>();
        for (Object[] row : items) byComponentNo.put((String) row[0], row);

        Object[] r1 = byComponentNo.get(recipe1);
        assertNotNull(r1, "recipe1 从未被任何页签表征，flip 后必须仍存在");
        assertEquals(0, new BigDecimal("1.100000").compareTo((BigDecimal) r1[2]));
        assertEquals(0, new BigDecimal("0.624610").compareTo((BigDecimal) r1[3]), "recipe1 base_qty(未暴露列) 应原样保留");

        Object[] r2 = byComponentNo.get(recipe2);
        assertNotNull(r2, "recipe2 从未被任何页签表征，flip 后必须仍存在");
        assertEquals(0, new BigDecimal("0.800000").compareTo((BigDecimal) r2[3]));

        Object[] ra = byComponentNo.get(assembly);
        assertNotNull(ra, "assembly 从未被任何页签表征，flip 后必须仍存在");
        assertEquals(0, new BigDecimal("1.500000").compareTo((BigDecimal) ra[3]));

        Object[] ro = byComponentNo.get(outsourced);
        assertNotNull(ro, "outsourced 被表征但未改值，flip 后仍应存在且值不变");
        assertEquals(0, new BigDecimal("2.000000").compareTo((BigDecimal) ro[2]), "composition_qty 应保持基底原值 2.0");
        assertEquals(0, new BigDecimal("9.999999").compareTo((BigDecimal) ro[3]), "base_qty(未暴露列) 应保持基底原值");

        long pendingRemaining = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_bom_item WHERE pending_quotation_id = :qid")
            .setParameter("qid", quotationId).getSingleResult()).longValue();
        assertEquals(0L, pendingRemaining, "flip 后本单 pending 残留应清理干净");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 2（test-cases.md BE-R1-03 陷阱用例）：组内 4 行仅 1 行被页签表征，且该行被显式墓碑删除
    // → 应剩 3 行（未表征的兄弟行原样保留），而不是被误判为"整组下线剩 0 行"。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void deleteWithPartialCoverage_untouchedSiblingsSurvive_notCollapsedToZero() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String rootMaterialNo = "PF2ROOT" + UUID.randomUUID().toString().substring(0, 7).toUpperCase();
        String recipe1 = "PF2R1" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String recipe2 = "PF2R2" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String assembly = "PF2A" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String outsourced = "PF2O" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineItemId = newLineItem(quotationId, rootMaterialNo);

        insertPendingBomItem(quotationId, customerNo, rootMaterialNo, recipe1, "RECIPE",
            new BigDecimal("1.100000"), new BigDecimal("0.6"), "g/PCS");
        insertPendingBomItem(quotationId, customerNo, rootMaterialNo, recipe2, "RECIPE",
            new BigDecimal("2.200000"), new BigDecimal("0.8"), "g/PCS");
        insertPendingBomItem(quotationId, customerNo, rootMaterialNo, assembly, "ASSEMBLY",
            new BigDecimal("3.000000"), new BigDecimal("1.5"), "EA");
        UUID outsourcedId = insertPendingBomItem(quotationId, customerNo, rootMaterialNo, outsourced, "OUTSOURCED",
            new BigDecimal("2.000000"), new BigDecimal("2.0"), "EA");

        UUID compA = newFilteredFlatBomItemComponent("DEL", "OUTSOURCED");
        String snapshotA = "[{\"driverRow\":{\"hf_part_no\":\"" + rootMaterialNo + "\",\"序号\":1,\"子料号\":\"" +
            outsourced + "\",\"数量\":2.000000,\"单位\":\"EA\",\"__v6_id\":\"" + outsourcedId + "\"}}]";
        String rowDataA = "[{\"序号\":1,\"子料号\":\"" + outsourced + "\",\"数量\":2.000000,\"单位\":\"EA\"}]";
        // rowKeyFields=["子料号"]，fp 由「子料号」+ driverRow 全键升序值拼接（DeletedRowKeys.rowFingerprint 同口径）。
        // driverRow 真实升序键(Unicode 码点)：__v6_id, hf_part_no, 单位, 子料号, 序号, 数量
        String fp = outsourced + outsourcedId + rootMaterialNo + "EA" + outsourced + "1" + "2";
        String deletedRowKeys = "[{\"effKey\":\"row-0\",\"fp\":\"" + fp + "\"}]";
        writeComponentData(lineItemId, compA, "外购件", snapshotA, rowDataA, deletedRowKeys);

        BackfillPreviewDTO preview = previewService.preview(quotationId);
        var bomGroup = preview.groups.stream().filter(g -> "material_bom_item".equals(g.table)).findFirst().orElse(null);
        assertNotNull(bomGroup);
        assertEquals(1, preview.summary.deletedRows, "预览应识别到 1 行删除，实际=" + preview.summary.deletedRows);
        assertEquals(0, preview.summary.changedRows);
        assertEquals(4, bomGroup.baseRowCount, "基底行数应为 4");
        assertEquals(3, bomGroup.resultRowCount, "★陷阱断言：有效行集应为 3（4 基底 - 1 删除），" +
            "不是「该页签只表征 1 行且被删 → effectiveNewRows 空 → 整组归零」");
        assertEquals("REBUILD", bomGroup.route);

        QuoteBackfillService.Summary summary = backfillService.execute(quotationId, finance);
        assertEquals(1, summary.deletedRows);
        assertEquals(0, summary.changedRows);

        List<Object[]> items = currentBomItemsFull(customerNo, rootMaterialNo);
        assertEquals(3, items.size(), "★黄金断言：删除的是 OUTSOURCED 这 1 行，未被任何页签表征的 recipe1/recipe2/assembly " +
            "3 行必须原样存活——不是「整组坍缩到 0 行」这种被 DELETE 语义误导的结果");

        Map<String, Object[]> byComponentNo = new java.util.HashMap<>();
        for (Object[] row : items) byComponentNo.put((String) row[0], row);
        assertNotNull(byComponentNo.get(recipe1), "recipe1 应存活");
        assertNotNull(byComponentNo.get(recipe2), "recipe2 应存活");
        assertNotNull(byComponentNo.get(assembly), "assembly 应存活");
        assertNull(byComponentNo.get(outsourced), "outsourced 应已被删除，不在新版本中");

        // 本 fixture 4 行全部以「本单 pending」身份直接插入（没有独立的"官方 current 前身"行——
        // 这本就是"该组首次创建"场景，不同于 QuoteBackfillFlatAcceptanceTest 的
        // deleteRoute_tombstonedRowExcluded_oldRowPhysicallyRetained（那条测试里 official 行与
        // pending 行是两条独立记录，official 行才是"应留存审计"的对象）。outsourcedId 本身只是一条
        // pending 暂存行，其使命在 QuoteBackfillService.cleanupPending 统一清理阶段结束——它会被
        // 物理 DELETE（`DELETE FROM material_bom_item WHERE pending_quotation_id=:qid`），这是正确
        // 行为，不是 bug：这条 pending 行从未成为过任何"正式版本"，没有历史可留。
        long stillExists = ((Number) em.createNativeQuery("SELECT count(*) FROM material_bom_item WHERE id = :id")
            .setParameter("id", outsourcedId).getSingleResult()).longValue();
        assertEquals(0L, stillExists, "纯 pending 基底(无官方前身)的墓碑行应随 cleanupPending 一并清理，" +
            "不是「物理保留」——AC-7 的物理保留语义只适用于有官方前身(is_current=true,pending_quotation_id=NULL)被取代的场景");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 3（test-cases.md BE-R4-02）：一行改值 + 另一行删除 + 第三行完全未表征 → 预览数字与
    // 执行后 DB 实际差异必须逐一相等。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void changeAndDeletePreviewExecuteAlignment_exactRowCounts() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String rootMaterialNo = "PF3ROOT" + UUID.randomUUID().toString().substring(0, 7).toUpperCase();
        String recipe1 = "PF3R1" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String assembly = "PF3A" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String outsourced = "PF3O" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineItemId = newLineItem(quotationId, rootMaterialNo);

        // recipe1：完全未被任何页签表征，必须原样保留。
        insertPendingBomItem(quotationId, customerNo, rootMaterialNo, recipe1, "RECIPE",
            new BigDecimal("1.100000"), new BigDecimal("0.6"), "g/PCS");
        // assembly：将被改值。
        UUID assemblyId = insertPendingBomItem(quotationId, customerNo, rootMaterialNo, assembly, "ASSEMBLY",
            new BigDecimal("3.000000"), new BigDecimal("1.5"), "EA");
        // outsourced：将被删除。
        UUID outsourcedId = insertPendingBomItem(quotationId, customerNo, rootMaterialNo, outsourced, "OUTSOURCED",
            new BigDecimal("2.000000"), new BigDecimal("2.0"), "EA");

        // 页签 A（ASSEMBLY 过滤）：改值 3.0 → 4.5。
        UUID compA = newFilteredFlatBomItemComponent("CHG", "ASSEMBLY");
        String snapshotA = "[{\"driverRow\":{\"hf_part_no\":\"" + rootMaterialNo + "\",\"序号\":1,\"子料号\":\"" +
            assembly + "\",\"数量\":3.000000,\"单位\":\"EA\",\"__v6_id\":\"" + assemblyId + "\"}}]";
        String rowDataA = "[{\"序号\":1,\"子料号\":\"" + assembly + "\",\"数量\":4.500000,\"单位\":\"EA\"}]";
        writeComponentData(lineItemId, compA, "组装", snapshotA, rowDataA, "[]");

        // 页签 B（OUTSOURCED 过滤）：删除。
        UUID compB = newFilteredFlatBomItemComponent("DEL", "OUTSOURCED");
        String snapshotB = "[{\"driverRow\":{\"hf_part_no\":\"" + rootMaterialNo + "\",\"序号\":1,\"子料号\":\"" +
            outsourced + "\",\"数量\":2.000000,\"单位\":\"EA\",\"__v6_id\":\"" + outsourcedId + "\"}}]";
        String rowDataB = "[{\"序号\":1,\"子料号\":\"" + outsourced + "\",\"数量\":2.000000,\"单位\":\"EA\"}]";
        String fpB = outsourced + outsourcedId + rootMaterialNo + "EA" + outsourced + "1" + "2";
        String deletedRowKeysB = "[{\"effKey\":\"row-0\",\"fp\":\"" + fpB + "\"}]";
        writeComponentData(lineItemId, compB, "外购件", snapshotB, rowDataB, deletedRowKeysB);

        BackfillPreviewDTO preview = previewService.preview(quotationId);
        var bomGroup = preview.groups.stream().filter(g -> "material_bom_item".equals(g.table)).findFirst().orElse(null);
        assertNotNull(bomGroup);
        assertEquals(1, preview.summary.changedRows, "预览应识别 1 行改值");
        assertEquals(1, preview.summary.deletedRows, "预览应识别 1 行删除");
        assertEquals(0, preview.summary.addedRows);
        assertEquals(3, bomGroup.baseRowCount, "基底 3 行");
        assertEquals(2, bomGroup.resultRowCount, "有效行集 = 3 基底 - 1 删除 = 2（recipe1 保留 + assembly 改值保留）");

        QuoteBackfillService.Summary summary = backfillService.execute(quotationId, finance);
        // ── 预览 ≡ 执行：逐数字核对 ──
        assertEquals(preview.summary.changedRows, summary.changedRows, "changedRows 预览与执行应相等");
        assertEquals(preview.summary.deletedRows, summary.deletedRows, "deletedRows 预览与执行应相等");
        assertEquals(preview.summary.addedRows, summary.addedRows, "addedRows 预览与执行应相等");

        List<Object[]> items = currentBomItemsFull(customerNo, rootMaterialNo);
        assertEquals(2, items.size(), "执行后应恰好 2 行：recipe1(未动)+assembly(改值)，outsourced 已删除");

        Map<String, Object[]> byComponentNo = new java.util.HashMap<>();
        for (Object[] row : items) byComponentNo.put((String) row[0], row);

        Object[] r1 = byComponentNo.get(recipe1);
        assertNotNull(r1, "recipe1 完全未被表征，必须原样保留");
        assertEquals(0, new BigDecimal("1.100000").compareTo((BigDecimal) r1[2]));
        assertEquals(0, new BigDecimal("0.6").compareTo((BigDecimal) r1[3]), "recipe1 base_qty(未暴露列) 应原样保留");

        Object[] ra = byComponentNo.get(assembly);
        assertNotNull(ra, "assembly 应存在（改值，非删除）");
        assertEquals(0, new BigDecimal("4.500000").compareTo((BigDecimal) ra[2]), "assembly composition_qty 应为改后新值 4.5");
        assertEquals(0, new BigDecimal("1.5").compareTo((BigDecimal) ra[3]), "assembly base_qty(未暴露列) 应原样保留，未被清空");

        assertNull(byComponentNo.get(outsourced), "outsourced 应已删除");
    }
}
