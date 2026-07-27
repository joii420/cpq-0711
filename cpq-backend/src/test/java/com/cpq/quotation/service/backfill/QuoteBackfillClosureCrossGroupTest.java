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
 * repair-0727 · cpq-tester 独立验证 · AC-R6 闭包跨组精确（需求说明 §4 AC-R6，test-cases.md §7）。
 *
 * <p><b>为什么这是当前最大缺口</b>：技术总监在交接消息中明确点出——后端交付里没有任何测试验证
 * "一个页签的 __v6_id 横跨多个 V6 组"这一场景。真实生产模板 {@code wg_view}（外购件成本）的
 * {@code WHERE} 子句只有 {@code characteristic='OUTSOURCED' AND customer_no=:customerCode}，
 * <b>完全没有 material_no 过滤</b>——它天生就是"一个页签跨所有产品"的闭包形状（见需求说明 §1.3
 * "D1 的放大器"：task-0726 BOM 闭包渲染让平铺页签通过 {@code bom_closure} 展示子件所属其他组的行，
 * 一个页签的 __v6_id 可能横跨多个 V6 组）。本文件复刻这个真实形状（不加 material_no 过滤），
 * 而不是发明一套自定义的"闭包驱动"机制——这样测的就是生产会真实发生的场景。
 */
@QuarkusTest
class QuoteBackfillClosureCrossGroupTest {

    private static final String TAG = "REPAIR0727CLOSURE";

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

    /** 建"闭包"平铺 material_bom_item 组件：复刻真实 wg_view 形状——只按 characteristic + customer_no
     *  过滤，不按 material_no 过滤，天然会跨多个产品/V6 组。 */
    private UUID newClosureBomItemComponent(String suffix, String characteristicFilter) {
        Component c = new Component();
        c.name = TAG + "-闭包外购件-" + suffix;
        c.code = TAG + "-CLOS-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6);
        c.fields = "[{\"name\":\"子料号\",\"field_type\":\"INPUT_TEXT\"}]";
        c.formulas = "[]";
        c.tabType = "零件";
        c.partNoField = "子料号";
        c.rowKeyFields = "[\"子料号\"]";
        c.dataDriverPath = "$" + (TAG + "_clos_" + suffix).toLowerCase();
        c.persist();

        String sql =
            "SELECT\n" +
            "  mbt.material_no AS hf_part_no,\n" +
            "  mbt.material_no AS 产品料号,\n" +
            "  mbt.seq_no AS 序号,\n" +
            "  mbt.component_no AS 子料号,\n" +
            "  mbt.composition_qty AS 数量,\n" +
            "  mbt.issue_unit AS 单位\n" +
            "FROM material_bom_item mbt\n" +
            "WHERE mbt.system_type = 'QUOTE' AND mbt.customer_no = :customerCode AND mbt.is_current = true\n" +
            "  AND mbt.characteristic = '" + characteristicFilter + "'\n" +
            // 刻意不加 material_no 过滤——这正是真实 wg_view 的形状（跨所有产品/V6 组）。
            "ORDER BY mbt.material_no, mbt.seq_no";

        ComponentSqlView view = new ComponentSqlView();
        view.componentId = c.id;
        view.sqlViewName = (TAG + "_clos_" + suffix).toLowerCase();
        view.sqlTemplate = sql;
        view.declaredColumns = "[]";
        view.persist();
        em.flush();
        return c.id;
    }

    private void writeComponentData(UUID lineItemId, UUID componentId, String tabName,
                                     String snapshotRowsJson, String rowDataJson, String deletedRowKeysJson, int sortOrder) {
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data " +
                "(id, line_item_id, component_id, tab_name, snapshot_rows, row_data, deleted_row_keys, sort_order, created_at) " +
                "VALUES (:id, :lid, :cid, :tab, CAST(:sr AS jsonb), CAST(:rd AS jsonb), CAST(:drk AS jsonb), :so, now())")
            .setParameter("id", UUID.randomUUID()).setParameter("lid", lineItemId).setParameter("cid", componentId)
            .setParameter("tab", tabName).setParameter("sr", snapshotRowsJson).setParameter("rd", rowDataJson)
            .setParameter("drk", deletedRowKeysJson).setParameter("so", sortOrder)
            .executeUpdate();
    }

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

    /** 返回完整 snapshot_rows 元素 {@code {"driverRow": {...}}}（不带「序号」——同
     *  {@code insertPendingBomItem} 从不设置 seq_no，避免 NULL(基底) vs 有值(patch) 的伪差异）。 */
    private String driverRow(String rootNo, String componentNo, UUID v6Id, BigDecimal qty, String unit) {
        return "{\"driverRow\":{\"hf_part_no\":\"" + rootNo + "\",\"产品料号\":\"" + rootNo + "\",\"子料号\":\"" +
            componentNo + "\",\"数量\":" + qty.toPlainString() + ",\"单位\":\"" + unit + "\",\"__v6_id\":\"" + v6Id + "\"}}";
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 1（核心）：单个闭包页签在同一 snapshot_rows 里同时表征组 A 的 1 行(改值) + 组 B 的 1 行
    // (未改)——两组各自独立 patch，互不污染，且各自其余未表征的兄弟行原样保留。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void closureTabTouchesTwoGroups_eachGroupPatchedIndependently_noCrossContamination() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String rootA = "CLA" + UUID.randomUUID().toString().substring(0, 7).toUpperCase();
        String rootB = "CLB" + UUID.randomUUID().toString().substring(0, 7).toUpperCase();
        String aOut = "CLAOUT" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        String aRecipe = "CLARCP" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        String bOut = "CLBOUT" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        String bRecipe = "CLBRCP" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();

        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        // 两个产品各自一个 line item（模拟一张报价单里挂了两个产品，闭包页签横跨两者）。
        UUID lineA = newLineItem(quotationId, rootA);
        UUID lineB = newLineItem(quotationId, rootB);

        // 组 A：2 行（OUTSOURCED 将被改值，RECIPE 完全未表征）。
        insertPendingBomItem(quotationId, customerNo, rootA, aRecipe, "RECIPE",
            new BigDecimal("1.0"), new BigDecimal("0.5"), "g/PCS");
        UUID aOutId = insertPendingBomItem(quotationId, customerNo, rootA, aOut, "OUTSOURCED",
            new BigDecimal("2.0"), new BigDecimal("9.9"), "EA");

        // 组 B：2 行（OUTSOURCED 被表征但未改值，RECIPE 完全未表征）。
        insertPendingBomItem(quotationId, customerNo, rootB, bRecipe, "RECIPE",
            new BigDecimal("3.0"), new BigDecimal("0.7"), "g/PCS");
        UUID bOutId = insertPendingBomItem(quotationId, customerNo, rootB, bOut, "OUTSOURCED",
            new BigDecimal("4.0"), new BigDecimal("8.8"), "EA");

        // 一个闭包页签，snapshot_rows 同时含 A 组的 aOut 行 + B 组的 bOut 行（跨组投影）。
        UUID compClosure = newClosureBomItemComponent("X", "OUTSOURCED");
        String snapshot = "[" + driverRow(rootA, aOut, aOutId, new BigDecimal("2.0"), "EA") + "," +
            driverRow(rootB, bOut, bOutId, new BigDecimal("4.0"), "EA") + "]";
        // row_data：A 组那行改成 5.0；B 组那行不变。
        String rowData = "[{\"子料号\":\"" + aOut + "\",\"数量\":5.0,\"单位\":\"EA\"}," +
            "{\"子料号\":\"" + bOut + "\",\"数量\":4.0,\"单位\":\"EA\"}]";
        // 闭包页签实际渲染在哪个 line item 下不影响 collector 分组逻辑（分组按 DB 回查到的 material_no
        // 轴，不按 line item），这里挂在 lineA 下（模拟"产品A详情页里的闭包子表"）。
        writeComponentData(lineA, compClosure, "外购件闭包", snapshot, rowData, "[]", 0);

        BackfillPreviewDTO preview = previewService.preview(quotationId);
        var groups = preview.groups.stream().filter(g -> "material_bom_item".equals(g.table)).toList();
        assertEquals(2, groups.size(), "应识别到 2 个独立的 material_bom_item 组（按 material_no 轴区分，不因同一页签表征而合并）");

        var groupA = groups.stream().filter(g -> rootA.equals(g.groupKey.get("material_no"))).findFirst().orElse(null);
        var groupB = groups.stream().filter(g -> rootB.equals(g.groupKey.get("material_no"))).findFirst().orElse(null);
        assertNotNull(groupA, "组 A 应出现");
        assertNotNull(groupB, "组 B 应出现（即便零内容变化，仍应展示 FLIP 物化）");

        assertEquals(2, groupA.baseRowCount, "组 A 基底应为 2 行（aRecipe + aOut）");
        assertEquals(2, groupA.resultRowCount, "组 A 有效行集应仍为 2 行（不因闭包页签只表征其中 1 行而丢 aRecipe）");
        assertEquals(1, groupA.rows.size(), "组 A 应恰好 1 条改值记录（aOut）");

        assertEquals(2, groupB.baseRowCount, "组 B 基底应为 2 行（bRecipe + bOut）");
        assertEquals(2, groupB.resultRowCount, "组 B 有效行集应仍为 2 行（不因闭包页签只表征其中 1 行而丢 bRecipe）");
        assertTrue(groupB.rows.isEmpty(), "组 B 那行未改值，不应产生 rowChange（预览不应把 A 组的改动错记到 B 组）");

        backfillService.execute(quotationId, finance);

        List<Object[]> itemsA = currentBomItemsFull(customerNo, rootA);
        assertEquals(2, itemsA.size(), "组 A 执行后应仍为 2 行");
        Map<String, Object[]> byCompA = new java.util.HashMap<>();
        for (Object[] r : itemsA) byCompA.put((String) r[0], r);
        assertNotNull(byCompA.get(aRecipe), "组 A 的 aRecipe（完全未被闭包页签表征）必须存活");
        assertEquals(0, new BigDecimal("0.5").compareTo((BigDecimal) byCompA.get(aRecipe)[3]));
        assertEquals(0, new BigDecimal("5.0").compareTo((BigDecimal) byCompA.get(aOut)[2]), "组 A 的 aOut 应取改后新值 5.0");

        List<Object[]> itemsB = currentBomItemsFull(customerNo, rootB);
        assertEquals(2, itemsB.size(), "组 B 执行后应仍为 2 行");
        Map<String, Object[]> byCompB = new java.util.HashMap<>();
        for (Object[] r : itemsB) byCompB.put((String) r[0], r);
        assertNotNull(byCompB.get(bRecipe), "组 B 的 bRecipe（完全未被闭包页签表征）必须存活");
        assertEquals(0, new BigDecimal("0.7").compareTo((BigDecimal) byCompB.get(bRecipe)[3]));
        // ★交叉污染核心断言：组 B 的 bOut 值必须仍是原始 4.0，绝不能被 A 组的改动(5.0)串过来。
        assertEquals(0, new BigDecimal("4.0").compareTo((BigDecimal) byCompB.get(bOut)[2]),
            "★核心：组 B 的 bOut 不应被组 A 的改动(5.0)污染，应保持原值 4.0");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 2：两个不同闭包页签（sortOrder 不同）各自表征 A/B 两组各 1 行，验证不重复/不遗漏、
    // 各组其余未表征的行原样保留。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void multipleClosureTabsTouchDifferentGroupsRows_bothGroupsPreserveUntouchedRows() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String rootA = "CL2A" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String rootB = "CL2B" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String aOut = "CL2AOUT" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        String aAsm = "CL2AASM" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        String bOut = "CL2BOUT" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        String bAsm = "CL2BASM" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();

        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineA = newLineItem(quotationId, rootA);

        insertPendingBomItem(quotationId, customerNo, rootA, aAsm, "ASSEMBLY",
            new BigDecimal("1.0"), new BigDecimal("0.5"), "EA");
        UUID aOutId = insertPendingBomItem(quotationId, customerNo, rootA, aOut, "OUTSOURCED",
            new BigDecimal("2.0"), new BigDecimal("9.9"), "EA");
        insertPendingBomItem(quotationId, customerNo, rootB, bAsm, "ASSEMBLY",
            new BigDecimal("3.0"), new BigDecimal("0.7"), "EA");
        UUID bOutId = insertPendingBomItem(quotationId, customerNo, rootB, bOut, "OUTSOURCED",
            new BigDecimal("4.0"), new BigDecimal("8.8"), "EA");

        // 闭包页签 1（sortOrder=0）：只表征 A 组的 aOut 行，改值 2.0→2.5。
        UUID compClosure1 = newClosureBomItemComponent("Y1", "OUTSOURCED");
        String snapshot1 = "[" + driverRow(rootA, aOut, aOutId, new BigDecimal("2.0"), "EA") + "]";
        String rowData1 = "[{\"子料号\":\"" + aOut + "\",\"数量\":2.5,\"单位\":\"EA\"}]";
        writeComponentData(lineA, compClosure1, "闭包1", snapshot1, rowData1, "[]", 0);

        // 闭包页签 2（sortOrder=1）：只表征 B 组的 bOut 行，改值 4.0→4.5。
        UUID compClosure2 = newClosureBomItemComponent("Y2", "OUTSOURCED");
        String snapshot2 = "[" + driverRow(rootB, bOut, bOutId, new BigDecimal("4.0"), "EA") + "]";
        String rowData2 = "[{\"子料号\":\"" + bOut + "\",\"数量\":4.5,\"单位\":\"EA\"}]";
        writeComponentData(lineA, compClosure2, "闭包2", snapshot2, rowData2, "[]", 1);

        backfillService.execute(quotationId, finance);

        List<Object[]> itemsA = currentBomItemsFull(customerNo, rootA);
        assertEquals(2, itemsA.size(), "组 A 应仍为 2 行（aAsm 未被表征需保留 + aOut 改值）");
        List<Object[]> itemsB = currentBomItemsFull(customerNo, rootB);
        assertEquals(2, itemsB.size(), "组 B 应仍为 2 行（bAsm 未被表征需保留 + bOut 改值）");

        Map<String, Object[]> byCompA = new java.util.HashMap<>();
        for (Object[] r : itemsA) byCompA.put((String) r[0], r);
        assertNotNull(byCompA.get(aAsm), "组 A 的 aAsm 必须存活（两个闭包页签都没碰它）");
        assertEquals(0, new BigDecimal("2.5").compareTo((BigDecimal) byCompA.get(aOut)[2]));

        Map<String, Object[]> byCompB = new java.util.HashMap<>();
        for (Object[] r : itemsB) byCompB.put((String) r[0], r);
        assertNotNull(byCompB.get(bAsm), "组 B 的 bAsm 必须存活（两个闭包页签都没碰它）");
        assertEquals(0, new BigDecimal("4.5").compareTo((BigDecimal) byCompB.get(bOut)[2]));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 3：闭包页签删除 B 组里的 1 行 → 只有 B 组收缩，A 组完全不受影响。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void closureTabDeletesRowInGroupB_onlyGroupBShrinks_groupAUnaffected() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String rootA = "CL3A" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String rootB = "CL3B" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String aOut = "CL3AOUT" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        String aAsm = "CL3AASM" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        String bOut = "CL3BOUT" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        String bAsm = "CL3BASM" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();

        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineA = newLineItem(quotationId, rootA);

        insertPendingBomItem(quotationId, customerNo, rootA, aAsm, "ASSEMBLY",
            new BigDecimal("1.0"), new BigDecimal("0.5"), "EA");
        UUID aOutId = insertPendingBomItem(quotationId, customerNo, rootA, aOut, "OUTSOURCED",
            new BigDecimal("2.0"), new BigDecimal("9.9"), "EA");
        insertPendingBomItem(quotationId, customerNo, rootB, bAsm, "ASSEMBLY",
            new BigDecimal("3.0"), new BigDecimal("0.7"), "EA");
        UUID bOutId = insertPendingBomItem(quotationId, customerNo, rootB, bOut, "OUTSOURCED",
            new BigDecimal("4.0"), new BigDecimal("8.8"), "EA");

        UUID compClosure = newClosureBomItemComponent("Z", "OUTSOURCED");
        String snapshot = "[" + driverRow(rootA, aOut, aOutId, new BigDecimal("2.0"), "EA") + "," +
            driverRow(rootB, bOut, bOutId, new BigDecimal("4.0"), "EA") + "]";
        String rowData = "[{\"子料号\":\"" + aOut + "\",\"数量\":2.0,\"单位\":\"EA\"}," +
            "{\"子料号\":\"" + bOut + "\",\"数量\":4.0,\"单位\":\"EA\"}]";
        // 只删 B 组的 bOut 行。fp 计算同 BackfillPreviewFidelityTest 口径：
        // rowKeyField(子料号) + driverRow 升序键(__v6_id, hf_part_no, 产品料号, 单位, 子料号, 数量)。
        String fpB = bOut + bOutId + rootB + rootB + "EA" + bOut + "4";
        String deletedRowKeys = "[{\"effKey\":\"row-1\",\"fp\":\"" + fpB + "\"}]";
        writeComponentData(lineA, compClosure, "外购件闭包", snapshot, rowData, deletedRowKeys, 0);

        BackfillPreviewDTO preview = previewService.preview(quotationId);
        var groups = preview.groups.stream().filter(g -> "material_bom_item".equals(g.table)).toList();
        var groupA = groups.stream().filter(g -> rootA.equals(g.groupKey.get("material_no"))).findFirst().orElse(null);
        var groupB = groups.stream().filter(g -> rootB.equals(g.groupKey.get("material_no"))).findFirst().orElse(null);
        assertNotNull(groupA);
        assertNotNull(groupB);
        assertEquals(2, groupA.resultRowCount, "组 A 不应受 B 组删除影响，有效行集仍为 2");
        assertEquals(1, groupB.resultRowCount, "组 B 应减为 1（bAsm 保留，bOut 被删）");

        backfillService.execute(quotationId, finance);

        List<Object[]> itemsA = currentBomItemsFull(customerNo, rootA);
        assertEquals(2, itemsA.size(), "★核心：组 A 不应受组 B 的删除操作牵连，应仍为 2 行");

        List<Object[]> itemsB = currentBomItemsFull(customerNo, rootB);
        assertEquals(1, itemsB.size(), "组 B 应剩 1 行（bAsm）");
        assertEquals(bAsm, itemsB.get(0)[0]);
    }
}
