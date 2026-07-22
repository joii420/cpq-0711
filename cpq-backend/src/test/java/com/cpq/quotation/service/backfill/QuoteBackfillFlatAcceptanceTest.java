package com.cpq.quotation.service.backfill;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.RuntimeContext;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.datasource.sqlview.SqlViewExecutor;
import com.cpq.datasource.sqlview.SqlViewRuntimeContext;
import com.cpq.quotation.dto.QuotationDTO;
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
 * task-0721 报价数据版本升级 · 单表（{@code unit_price}）场景端到端验收补测。
 *
 * <p>覆盖 AC-5（回填-改值）/AC-7（回填-删除）/AC-19 Q5（删空整组下线）/AC-3（他单隔离）/
 * AC-9（有历史）/AC-10（已建单冻结）/AC-11（previewToken 幂等 + TOCTOU 409）/AC-8（一致性，
 * 单表侧初步验证，DAG/主子场景见 {@code QuoteBackfillMasterChildAcceptanceTest}）。
 *
 * <p>全部用 {@code @TestTransaction}（方法级自动回滚，测试结束不留痕迹）——已验证
 * {@code QuoteBackfillService.execute}/{@code ExistingProductService.list}/
 * {@code MaterialMasterRepository} 等被测路径不含 {@code REQUIRES_NEW} 子事务，
 * 与既有 {@code QuoteBackfillFlipRouteTest}/{@code ExistingProductGateTest} 同款可安全回滚。
 *
 * <p>组件 {@code $view} 模板复刻 {@code QuotePendingRewriterTest.PF_VIEW}
 * （已验证真实可改写/可注入锚点的单表 unit_price 模板形状），只是把 price_type 换成本测试专用
 * 前缀以免与共享 DB 现网数据混淆。
 */
@QuarkusTest
class QuoteBackfillFlatAcceptanceTest {

    private static final String TAG = "T0721FLAT";

    @Inject QuoteBackfillService backfillService;
    @Inject QuoteBackfillPreviewService previewService;
    @Inject QuotationService quotationService;
    @Inject SqlViewExecutor sqlViewExecutor;
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

    /** 建报价单（status 可指定），返回 id。 */
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

    /** 建报价行（line item），返回 id。 */
    private UUID newLineItem(UUID quotationId, String productPartNo) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot, sort_order, created_at) " +
                "VALUES (:id, :qid, :pn, 0, now())")
            .setParameter("id", id).setParameter("qid", quotationId).setParameter("pn", productPartNo)
            .executeUpdate();
        return id;
    }

    /** 建组件 + $view（单表 unit_price，price_type=本测试专用），返回 componentId。 */
    private UUID newFlatUnitPriceComponent(String suffix) {
        Component c = new Component();
        c.name = TAG + "-组件-" + suffix;
        c.code = TAG + "-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6);
        c.fields = "[{\"name\":\"_销售料号\",\"field_type\":\"INPUT_TEXT\"}]";
        c.formulas = "[]";
        c.tabType = "零件";
        c.partNoField = "_销售料号";
        c.rowKeyFields = "[\"_销售料号\"]";
        c.dataDriverPath = "$" + (TAG + "_" + suffix).toLowerCase();
        c.persist();

        ComponentSqlView view = new ComponentSqlView();
        view.componentId = c.id;
        view.sqlViewName = (TAG + "_" + suffix).toLowerCase();
        view.sqlTemplate =
            "SELECT\n" +
            "  up.finished_material_no AS hf_part_no,\n" +
            "  up.finished_material_no AS _销售料号,\n" +
            "  up.seq_no AS _项次,\n" +
            "  up.pricing_price AS _单价,\n" +
            "  up.unit AS _单位\n" +
            "FROM unit_price up\n" +
            "WHERE up.system_type = 'QUOTE' AND up.price_type = 'PROCESS' AND up.is_current = true\n" +
            "  AND up.customer_no = :customerCode\n" +
            "ORDER BY up.seq_no";
        view.declaredColumns = "[]";
        view.persist();
        em.flush();
        return c.id;
    }

    /** 插一条 unit_price 行，返回其 id。 */
    private UUID insertUnitPrice(String priceType, String customerNo, String materialNo, int seqNo,
                                  BigDecimal price, String versionNo, boolean isCurrent, UUID pendingQid) {
        return insertUnitPrice(priceType, customerNo, materialNo, seqNo, price, versionNo, isCurrent, pendingQid, null);
    }

    /** 插一条 unit_price 行（可指定 pending_supersedes，模拟 B2 导入产生的遮蔽指针），返回其 id。 */
    private UUID insertUnitPrice(String priceType, String customerNo, String materialNo, int seqNo,
                                  BigDecimal price, String versionNo, boolean isCurrent, UUID pendingQid,
                                  UUID supersedes) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO unit_price (id, system_type, price_type, version_no, code, finished_material_no, " +
                "  seq_no, pricing_price, unit, customer_no, is_current, pending_quotation_id, pending_supersedes, " +
                "  created_at, updated_at) " +
                "VALUES (:id, 'QUOTE', :pt, :vn, :code, :fmn, :seq, :price, '元', :cn, :cur, :pq, " +
                "  CASE WHEN CAST(:sup AS uuid) IS NULL THEN NULL ELSE ARRAY[CAST(:sup AS uuid)] END, now(), now())")
            .setParameter("id", id).setParameter("pt", priceType).setParameter("vn", versionNo)
            .setParameter("code", materialNo).setParameter("fmn", materialNo).setParameter("seq", seqNo)
            .setParameter("price", price).setParameter("cn", customerNo).setParameter("cur", isCurrent)
            .setParameter("pq", pendingQid).setParameter("sup", supersedes)
            .executeUpdate();
        return id;
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
    private BigDecimal currentPrice(String priceType, String customerNo, String materialNo) {
        List<Object> rows = em.createNativeQuery(
                "SELECT pricing_price FROM unit_price WHERE price_type = :pt AND customer_no = :cn " +
                "AND finished_material_no = :mn AND is_current = true")
            .setParameter("pt", priceType).setParameter("cn", customerNo).setParameter("mn", materialNo)
            .getResultList();
        return rows.isEmpty() ? null : (BigDecimal) rows.get(0);
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-5 + AC-9: 改值回填 —— 用户最终值写入新版本，旧版本降 is_current=false 留存
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void changeRoute_userEditedValueWins_oldVersionRetained() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String priceType = "PROCESS";
        String materialNo = "CHG" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        UUID componentId = newFlatUnitPriceComponent("CHG");
        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineItemId = newLineItem(quotationId, materialNo);

        // 旧官方版本：1.20（早于本单，模拟"另一张已生效报价单产生的历史数据"）
        UUID officialRowId = insertUnitPrice(priceType, customerNo, materialNo, 1,
            new BigDecimal("1.20"), "2000", true, null);
        // 本单 pending：导入原样复刻旧值 1.20（模拟 UT-B2-EDIT-1：改值不经过 V6 pending 行）
        UUID pendingRowId = insertUnitPrice(priceType, customerNo, materialNo, 1,
            new BigDecimal("1.20"), "2001", false, quotationId, officialRowId);

        // snapshot_rows：driverRow 含 __v6_id 指向 pending 行(导入原值 1.20)
        String snapshotRows = "[{\"driverRow\":{\"hf_part_no\":\"" + materialNo + "\",\"_销售料号\":\"" + materialNo +
            "\",\"_项次\":1,\"_单价\":1.20,\"_单位\":\"元\",\"__v6_id\":\"" + pendingRowId + "\"}}]";
        // row_data：用户在报价单编辑页把价格改成 1.35（覆盖 driverRow）
        String rowData = "[{\"_销售料号\":\"" + materialNo + "\",\"_项次\":1,\"_单价\":1.35,\"_单位\":\"元\"}]";
        writeComponentData(lineItemId, componentId, "工序费用", snapshotRows, rowData, "[]");

        QuoteBackfillService.Summary summary = backfillService.execute(quotationId, finance);
        assertEquals(1, summary.changedRows, "应识别到 1 行改值");

        BigDecimal newPrice = currentPrice(priceType, customerNo, materialNo);
        assertNotNull(newPrice, "回填后应有新的 is_current 行");
        assertEquals(0, new BigDecimal("1.35").compareTo(newPrice),
            "新版本应是用户最终编辑值 1.35，而非导入原值 1.20");

        Object[] oldRow = (Object[]) em.createNativeQuery(
                "SELECT is_current, version_no FROM unit_price WHERE id = :id")
            .setParameter("id", officialRowId).getSingleResult();
        assertEquals(Boolean.FALSE, oldRow[0], "旧版本应降 is_current=false（AC-9 有历史，非物理删除）");
        assertEquals("2000", oldRow[1]);

        long pendingRemaining = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE pending_quotation_id = :qid")
            .setParameter("qid", quotationId).getSingleResult()).longValue();
        assertEquals(0L, pendingRemaining, "回填后本单 pending 残留应清理干净");
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-7: 删除回填 —— 墓碑命中行从新版本消失，旧版本物理留存可查
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void deleteRoute_tombstonedRowExcluded_oldRowPhysicallyRetained() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String priceType = "PROCESS";
        String materialNo = "DEL" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        UUID componentId = newFlatUnitPriceComponent("DEL");
        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineItemId = newLineItem(quotationId, materialNo);

        // 该组两行：seq=1 保留，seq=2 将被用户删除
        UUID keepOfficial = insertUnitPrice(priceType, customerNo, materialNo, 1, new BigDecimal("1.00"), "2000", true, null);
        UUID keepPending = insertUnitPrice(priceType, customerNo, materialNo, 1, new BigDecimal("1.00"), "2001", false, quotationId, keepOfficial);
        UUID delOfficial = insertUnitPrice(priceType, customerNo, materialNo, 2, new BigDecimal("2.00"), "2000", true, null);
        UUID delPending = insertUnitPrice(priceType, customerNo, materialNo, 2, new BigDecimal("2.00"), "2001", false, quotationId, delOfficial);

        String snapshotRows =
            "[{\"driverRow\":{\"hf_part_no\":\"" + materialNo + "\",\"_销售料号\":\"" + materialNo +
            "\",\"_项次\":1,\"_单价\":1.00,\"_单位\":\"元\",\"__v6_id\":\"" + keepPending + "\"}}," +
            "{\"driverRow\":{\"hf_part_no\":\"" + materialNo + "\",\"_销售料号\":\"" + materialNo +
            "\",\"_项次\":2,\"_单价\":2.00,\"_单位\":\"元\",\"__v6_id\":\"" + delPending + "\"}}]";
        // rowKeyFields=["_销售料号"] → fp 由 _销售料号 + driverRow 全键升序拼接；两行的 _项次 不同使 fp 不同
        String rowData =
            "[{\"_销售料号\":\"" + materialNo + "\",\"_项次\":1,\"_单价\":1.00,\"_单位\":\"元\"}," +
            "{\"_销售料号\":\"" + materialNo + "\",\"_项次\":2,\"_单价\":2.00,\"_单位\":\"元\"}]";
        // 计算第 2 行(seq=2)的 fp：与 DeletedRowKeys.rowFingerprint 同口径
        // parts = [_销售料号取值] ++ driverRow 全键 String 自然序(Unicode)升序取值
        // driverRow keys 真实升序: __v6_id,_单价,_单位,_销售料号,_项次,hf_part_no; 数字 canon 去零(2.00→"2")
        String fpRow2 = materialNo // rowKeyFieldNames=["_销售料号"]
            + delPending + "2" + "元" + materialNo + "2" + materialNo; // driverRow 升序键值
        String deletedRowKeys = "[{\"effKey\":\"row-2\",\"fp\":\"" + fpRow2 + "\"}]";
        writeComponentData(lineItemId, componentId, "工序费用", snapshotRows, rowData, deletedRowKeys);

        QuoteBackfillService.Summary summary = backfillService.execute(quotationId, finance);
        assertEquals(1, summary.deletedRows, "应识别到 1 行删除，实际=" + summary.deletedRows);

        // seq=1 组：应仍是 is_current 行（未受影响）
        BigDecimal seq1Price = currentPrice(priceType, customerNo, materialNo); // NOTE: 两行同 axis(materialNo+priceType)，属同一组
        // seq=1/seq=2 同属一个 group(轴=finished_material_no+price_type 一致)，删除 seq=2 后该组新版本只剩 seq=1 一行
        long currentCountForGroup = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE price_type = :pt AND customer_no = :cn " +
                "AND finished_material_no = :mn AND is_current = true")
            .setParameter("pt", priceType).setParameter("cn", customerNo).setParameter("mn", materialNo)
            .getSingleResult()).longValue();
        assertEquals(1L, currentCountForGroup, "删除后新版本应只剩 1 行(seq=1)，实际=" + currentCountForGroup);

        Boolean delOfficialRow = (Boolean) em.createNativeQuery(
                "SELECT is_current FROM unit_price WHERE id = :id")
            .setParameter("id", delOfficial).getSingleResult();
        assertEquals(Boolean.FALSE, delOfficialRow, "被删行的旧官方版本应仍物理存在，is_current=false");

        long stillExists = ((Number) em.createNativeQuery("SELECT count(*) FROM unit_price WHERE id = :id")
            .setParameter("id", delOfficial).getSingleResult()).longValue();
        assertEquals(1L, stillExists, "旧行不应被物理删除（AC-7 可审计）");
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-19 Q5: 平铺页签删空整组 → 整组下线（不写空版本，不误判 flip）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void deleteToZero_wholeGroupGoesOffline_noEmptyVersionWritten() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String priceType = "PROCESS";
        String materialNo = "Q5X" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        UUID componentId = newFlatUnitPriceComponent("Q5");
        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineItemId = newLineItem(quotationId, materialNo);

        UUID officialRow = insertUnitPrice(priceType, customerNo, materialNo, 1, new BigDecimal("9.00"), "2000", true, null);
        UUID pendingRow = insertUnitPrice(priceType, customerNo, materialNo, 1, new BigDecimal("9.00"), "2001", false, quotationId, officialRow);
        String oldVersionNo = "2000";

        String snapshotRows = "[{\"driverRow\":{\"hf_part_no\":\"" + materialNo + "\",\"_销售料号\":\"" + materialNo +
            "\",\"_项次\":1,\"_单价\":9.00,\"_单位\":\"元\",\"__v6_id\":\"" + pendingRow + "\"}}]";
        String rowData = "[{\"_销售料号\":\"" + materialNo + "\",\"_项次\":1,\"_单价\":9.00,\"_单位\":\"元\"}]";
        // driverRow 真实升序: __v6_id,_单价,_单位,_销售料号,_项次,hf_part_no (9.00→canon"9")
        String fp = materialNo + pendingRow + "9" + "元" + materialNo + "1" + materialNo;
        String deletedRowKeys = "[{\"effKey\":\"row-1\",\"fp\":\"" + fp + "\"}]";
        writeComponentData(lineItemId, componentId, "工序费用", snapshotRows, rowData, deletedRowKeys);

        BackfillPreviewDTO preview = previewService.preview(quotationId);
        assertEquals(1, preview.summary.deletedRows, "预览应识别 1 行删除，实际=" + preview.summary.deletedRows);

        QuoteBackfillService.Summary summary = backfillService.execute(quotationId, finance);
        assertEquals(1, summary.deletedRows);

        long currentCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE price_type = :pt AND customer_no = :cn " +
                "AND finished_material_no = :mn AND is_current = true")
            .setParameter("pt", priceType).setParameter("cn", customerNo).setParameter("mn", materialNo)
            .getSingleResult()).longValue();
        assertEquals(0L, currentCount, "整组删空后不应有任何 is_current 行（下线，非空版本）");

        // 不应出现"空版本号"（version_no 不应新增一个比旧版本更高但内容为空的行——本身没有新行）
        long anyVersionBeyondOld = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE price_type = :pt AND customer_no = :cn " +
                "AND finished_material_no = :mn AND version_no <> :oldv")
            .setParameter("pt", priceType).setParameter("cn", customerNo).setParameter("mn", materialNo)
            .setParameter("oldv", oldVersionNo)
            .getSingleResult()).longValue();
        assertEquals(0L, anyVersionBeyondOld, "整组下线不应写出任何新版本号的行");

        Boolean oldRow = (Boolean) em.createNativeQuery("SELECT is_current FROM unit_price WHERE id = :id")
            .setParameter("id", officialRow).getSingleResult();
        assertEquals(Boolean.FALSE, oldRow, "旧官方行应降 is_current=false（下线）");
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-3: 他单隔离 —— 另一报价单看不到本单 pending，且行数不因 pending 翻倍
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void otherQuotationIsolation_pendingNotLeaked_rowCountNotDoubled() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String priceType = "PROCESS";
        String materialNo = "ISO" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        UUID componentId = newFlatUnitPriceComponent("ISO");
        UUID q1 = newQuotation(customerId, salesRepId, "DRAFT");
        UUID q2 = newQuotation(customerId, salesRepId, "DRAFT");

        UUID officialRow = insertUnitPrice(priceType, customerNo, materialNo, 1, new BigDecimal("5.00"), "2000", true, null);
        insertUnitPrice(priceType, customerNo, materialNo, 1, new BigDecimal("5.50"), "2001", false, q1, officialRow);

        SqlViewRuntimeContext.set(componentId, null, q1, "DRAFT");
        try {
            List<Map<String, Object>> rowsQ1 = sqlViewExecutor.executeAllRows(
                "$" + (TAG + "_ISO").toLowerCase(), buildCtx(customerNo), null);
            assertEquals(1, rowsQ1.size(), "Q1 应能看到自己的 pending 行(遮蔽旧官方行,不翻倍)，实际=" + rowsQ1.size());
            assertEquals(0, new BigDecimal("5.50").compareTo((BigDecimal) rowsQ1.get(0).get("_单价")));
        } finally {
            SqlViewRuntimeContext.clear();
        }

        SqlViewRuntimeContext.set(componentId, null, q2, "DRAFT");
        try {
            List<Map<String, Object>> rowsQ2 = sqlViewExecutor.executeAllRows(
                "$" + (TAG + "_ISO").toLowerCase(), buildCtx(customerNo), null);
            assertEquals(1, rowsQ2.size(), "Q2 应只看到官方旧值，不应看到 Q1 的 pending，实际=" + rowsQ2.size());
            assertEquals(0, new BigDecimal("5.00").compareTo((BigDecimal) rowsQ2.get(0).get("_单价")),
                "Q2 应看到官方旧值 5.00，而非 Q1 的 pending 5.50");
        } finally {
            SqlViewRuntimeContext.clear();
        }
    }

    private RuntimeContext buildCtx(String customerCode) {
        RuntimeContext ctx = new RuntimeContext();
        ctx.quotation = new RuntimeContext.QuotationContext();
        ctx.quotation.customerCode = customerCode;
        return ctx;
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-10: 已建单冻结 —— 已审核报价单的 snapshot_rows 不受他单回填影响，逐字节一致
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void frozenQuotation_snapshotUnaffectedByOtherQuotationBackfill() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String priceType = "PROCESS";
        String materialNo = "FRZ" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        UUID componentId = newFlatUnitPriceComponent("FRZ");

        // Q0：已审核（冻结），snapshot 中写死旧值 3.00
        UUID q0 = newQuotation(customerId, salesRepId, "APPROVED");
        UUID q0Line = newLineItem(q0, materialNo);
        String q0Snapshot = "[{\"driverRow\":{\"hf_part_no\":\"" + materialNo + "\",\"_销售料号\":\"" + materialNo +
            "\",\"_项次\":1,\"_单价\":3.00,\"_单位\":\"元\"}}]";
        writeComponentData(q0Line, componentId, "工序费用", q0Snapshot, "[]", "[]");
        String q0SnapshotBefore = readSnapshotRows(q0Line, componentId);

        // Q1：改值回填，把该料号价格改成 4.20
        UUID officialRow = insertUnitPrice(priceType, customerNo, materialNo, 1, new BigDecimal("3.00"), "2000", true, null);
        UUID pendingRow = insertUnitPrice(priceType, customerNo, materialNo, 1, new BigDecimal("3.00"), "2001", false, null, officialRow);
        UUID q1 = newQuotation(customerId, salesRepId, "SUBMITTED");
        // 补上 pending 归属（insertUnitPrice 早于 q1 id 生成顺序无关，这里直接 UPDATE 绑定）
        em.createNativeQuery("UPDATE unit_price SET pending_quotation_id = :qid WHERE id = :id")
            .setParameter("qid", q1).setParameter("id", pendingRow).executeUpdate();
        UUID q1Line = newLineItem(q1, materialNo);
        String q1Snapshot = "[{\"driverRow\":{\"hf_part_no\":\"" + materialNo + "\",\"_销售料号\":\"" + materialNo +
            "\",\"_项次\":1,\"_单价\":3.00,\"_单位\":\"元\",\"__v6_id\":\"" + pendingRow + "\"}}]";
        String q1RowData = "[{\"_销售料号\":\"" + materialNo + "\",\"_项次\":1,\"_单价\":4.20,\"_单位\":\"元\"}]";
        writeComponentData(q1Line, componentId, "工序费用", q1Snapshot, q1RowData, "[]");

        backfillService.execute(q1, finance);
        assertEquals(0, new BigDecimal("4.20").compareTo(currentPrice(priceType, customerNo, materialNo)));

        String q0SnapshotAfter = readSnapshotRows(q0Line, componentId);
        assertEquals(q0SnapshotBefore, q0SnapshotAfter,
            "已审核报价单 Q0 的 snapshot_rows 不应被 Q1 的回填改动（读快照冻结,AC-10）");
    }

    private String readSnapshotRows(UUID lineId, UUID componentId) {
        List<Object> r = em.createNativeQuery(
                "SELECT snapshot_rows::text FROM quotation_line_component_data " +
                "WHERE line_item_id = :lid AND component_id = :cid")
            .setParameter("lid", lineId).setParameter("cid", componentId).getResultList();
        return r.isEmpty() ? null : (String) r.get(0);
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-11: previewToken 幂等 + TOCTOU 409
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void previewToken_idempotentWhenUnchanged_and409OnDrift() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String priceType = "PROCESS";
        String materialNo = "TOK" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        UUID componentId = newFlatUnitPriceComponent("TOK");
        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineItemId = newLineItem(quotationId, materialNo);

        UUID officialRow = insertUnitPrice(priceType, customerNo, materialNo, 1, new BigDecimal("1.00"), "2000", true, null);
        UUID pendingRow = insertUnitPrice(priceType, customerNo, materialNo, 1, new BigDecimal("1.00"), "2001", false, quotationId, officialRow);
        String snapshotRows = "[{\"driverRow\":{\"hf_part_no\":\"" + materialNo + "\",\"_销售料号\":\"" + materialNo +
            "\",\"_项次\":1,\"_单价\":1.00,\"_单位\":\"元\",\"__v6_id\":\"" + pendingRow + "\"}}]";
        String rowData = "[{\"_销售料号\":\"" + materialNo + "\",\"_项次\":1,\"_单价\":1.10,\"_单位\":\"元\"}]";
        writeComponentData(lineItemId, componentId, "工序费用", snapshotRows, rowData, "[]");

        // Q4：同状态两次 preview → 同一 token（幂等）
        String token1 = previewService.preview(quotationId).previewToken;
        String token2 = previewService.preview(quotationId).previewToken;
        assertEquals(token1, token2, "同一未变状态的两次 preview 应得到相同 previewToken（AC-11/Q4）");

        // 预览之后数据漂移：追加一条改值（把价格再改成 1.25）
        em.createNativeQuery(
                "UPDATE quotation_line_component_data SET row_data = CAST(:rd AS jsonb) " +
                "WHERE line_item_id = :lid AND component_id = :cid")
            .setParameter("rd", "[{\"_销售料号\":\"" + materialNo + "\",\"_项次\":1,\"_单价\":1.25,\"_单位\":\"元\"}]")
            .setParameter("lid", lineItemId).setParameter("cid", componentId)
            .executeUpdate();

        BusinessException ex = assertThrows(BusinessException.class,
            () -> quotationService.costingApprove(quotationId, "ok", finance, token1),
            "预览后数据漂移，用旧 token 提交应 409");
        assertEquals(409, ex.getCode());

        // 重新预览拿新 token 后提交应成功
        String token3 = previewService.preview(quotationId).previewToken;
        assertNotEquals(token1, token3, "数据已变化，新 token 应与旧 token 不同");
        QuotationDTO dto = quotationService.costingApprove(quotationId, "ok", finance, token3);
        assertEquals("APPROVED", dto.status);
        assertEquals(0, new BigDecimal("1.25").compareTo(currentPrice(priceType, customerNo, materialNo)),
            "重新预览后提交应回填最新值 1.25");
    }

    @Test
    @TestTransaction
    void costingApprove_missingToken_returns400() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");

        BusinessException ex = assertThrows(BusinessException.class,
            () -> quotationService.costingApprove(quotationId, "ok", finance, null),
            "previewToken 缺失应 400（AC-12 结构性校验：提交面必须携带 token）");
        assertEquals(400, ex.getCode());
    }
}
