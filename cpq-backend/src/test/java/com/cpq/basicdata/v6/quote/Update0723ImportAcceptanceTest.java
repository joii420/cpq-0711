package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.dto.CreateQuotationFromImportRequest;
import com.cpq.basicdata.v6.service.V6QuotationCommitService;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * update-0723 验收测试（第二阶段·测试工程师进场）：直调 {@link QuoteImportService#processImport}
 * （+ {@link V6QuotationCommitService#createQuotation}）在 {@code @QuarkusTest} 内跑通两条主线：
 *
 * <ol>
 *   <li><b>黄金样例全链路</b>（{@link #goldenTemplate_fullImport_endToEndAcceptance}）：用真实
 *       {@code 报价系统模板0723.xlsx} 导入客户 {@code CUST-1269}（罗克韦尔，S-3120014539/S-80011
 *       已合法在其名下，避免误触跨客户串号），断言三态推断 / 落库矩阵 / R1 staging 口径 / B4 工序反填
 *       / B3 composition_qty+issue_unit / B5 Q13 item_seq 修复 / U6-U7 正常全过 / U8 性能 /
 *       U13 pending 语义 + create-quotation 过户。</li>
 *   <li><b>Phase2 中途失败整单回滚</b>（{@link #phase2CrossCustomerFailure_rollsBackEntireTransaction}）：
 *       构造一份 Phase1 全通过、但 Phase2 第 12 个 handler（组成件其他费用/Q13）才触发真实跨客户串号
 *       异常的文件——之前 bomMerge/单重/客户料号关系 3 个 handler 已在<b>同一</b>
 *       {@code writeAll} 事务内写入真实数据，验证整个事务（含更早的 handler 写入）随 Q13 的失败
 *       一起回滚，零残留、非 PARTIAL。</li>
 * </ol>
 *
 * <p><b>清理策略</b>：Test 2（全新客户 {@code UPD0723-} 前缀）按标准 QMNI 式前缀清理，零残留。
 * Test 1 复用共享 dev DB 上 CUST-1269 的既有黄金料号（S-3120014539/S-80011/991/992/W-1001）——
 * 版本化写入器会为其写一个新的 {@code is_current} 版本（这是「导入成功」的正确副作用，不是脏数据）；
 * 本测试只清理明确可安全移除的新增产物（import_record + create-quotation 建的 quotation/明细行），
 * 不回滚版本化表的 is_current 指针（回滚需要精确重建"改动前最新版本"状态，误操作风险高于保留新版本，
 * 且该客户在 backtask/RECORD.md 已被记录为本 feature 的既定自测客户）。
 */
@QuarkusTest
class Update0723ImportAcceptanceTest {

    @Inject QuoteImportService svc;
    @Inject V6QuotationCommitService commitService;
    @Inject ManagedExecutor managedExecutor;
    @Inject EntityManager em;

    static final String GOLDEN_CUSTOMER_NO = "CUST-1269";
    static final Path GOLDEN_XLSX = Path.of(
        "../dev-docs/task-0709-导入报价数据和导入核价数据的版本升级与版本维护/update-0723/报价系统模板0723.xlsx");

    // ===== 清理用状态（本测试实例本次创建的产物，@AfterEach 精确清理） =====
    private UUID goldenRecordId;
    private UUID goldenQuotationId;
    private UUID rollbackRecordId;

    @AfterEach
    void cleanup() {
        cleanupGolden();
        cleanupRollback();
    }

    @Transactional
    void cleanupGolden() {
        // import_record.quotation_id 是 FK 指向 quotation，必须先清 import_record（或置空该列）
        // 再删 quotation，否则违反 import_record_quotation_id_fkey。
        if (goldenRecordId != null) {
            em.createNativeQuery("DELETE FROM import_record WHERE id=:r")
              .setParameter("r", goldenRecordId).executeUpdate();
        }
        if (goldenQuotationId != null) {
            em.createNativeQuery("DELETE FROM quotation_line_process WHERE line_item_id IN " +
                "(SELECT id FROM quotation_line_item WHERE quotation_id=:q)")
              .setParameter("q", goldenQuotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN " +
                "(SELECT id FROM quotation_line_item WHERE quotation_id=:q)")
              .setParameter("q", goldenQuotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id=:q")
              .setParameter("q", goldenQuotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation WHERE id=:q")
              .setParameter("q", goldenQuotationId).executeUpdate();
            // create-quotation 已把 8 张表 pending_quotation_id 从 goldenRecordId 过户为 goldenQuotationId，
            // 这些行是版本化表的"新当前版本"，按类头注释策略保留，不删。
        }
    }

    @Transactional
    void cleanupRollback() {
        // Phase2 回滚测试期望"零写库"，理论上无需清理；仍以前缀兜底防御式清理，避免测试失败时残留。
        for (String t : List.of("material_bom_item", "material_bom", "unit_price", "material_customer_map")) {
            em.createNativeQuery("DELETE FROM " + t + " WHERE customer_no LIKE 'UPD0723-%'").executeUpdate();
        }
        em.createNativeQuery("DELETE FROM pending_material_master_staging WHERE material_no LIKE 'UPD0723-%'")
          .executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE 'UPD0723-%'").executeUpdate();
        if (rollbackRecordId != null) {
            em.createNativeQuery("DELETE FROM import_record WHERE id=:r")
              .setParameter("r", rollbackRecordId).executeUpdate();
        }
    }

    // ===== 通用查询 helper =====

    @Transactional
    Object singleOrNull(String sql, Object... kv) {
        var q = em.createNativeQuery(sql);
        for (int i = 0; i < kv.length; i += 2) q.setParameter((String) kv[i], kv[i + 1]);
        List<?> r = q.getResultList();
        return r.isEmpty() ? null : r.get(0);
    }

    long count(String sql, Object... kv) {
        Object v = singleOrNull(sql, kv);
        return v == null ? 0L : ((Number) v).longValue();
    }

    String str(String sql, Object... kv) {
        Object v = singleOrNull(sql, kv);
        return v == null ? null : v.toString();
    }

    @Transactional
    UUID anyCustomerId(String code) {
        return (UUID) em.createNativeQuery("SELECT id FROM customer WHERE code=:c")
            .setParameter("c", code).getSingleResult();
    }

    @Transactional
    UUID anyUserId() {
        return (UUID) em.createNativeQuery(
            "SELECT imported_by FROM import_record WHERE imported_by IS NOT NULL LIMIT 1")
            .getSingleResult();
    }

    // =====================================================================
    // Test 1：黄金样例全链路验收
    // =====================================================================

    @Test
    void goldenTemplate_fullImport_endToEndAcceptance() throws Exception {
        UUID customerId = anyCustomerId(GOLDEN_CUSTOMER_NO);
        UUID user = anyUserId();
        byte[] bytes = Files.readAllBytes(GOLDEN_XLSX);
        assertTrue(bytes.length > 0, "黄金样例文件应可读: " + GOLDEN_XLSX.toAbsolutePath());

        goldenRecordId = svc.createImportRecord(customerId, "update0723-golden-test.xlsx", user);
        assertEquals("PROCESSING", str(
            "SELECT import_status FROM import_record WHERE id=:id", "id", goldenRecordId));

        long t0 = System.nanoTime();
        managedExecutor.runAsync(() ->
            svc.processImport(goldenRecordId, GOLDEN_CUSTOMER_NO, "update0723-golden-test.xlsx", bytes, user))
            .get(30, TimeUnit.SECONDS);
        double elapsedMs = (System.nanoTime() - t0) / 1e6;
        Log.infof("[update0723-test] 黄金样例导入 elapsed=%.0fms", elapsedMs);

        // ---- U6/U7：正常全过，非 PARTIAL ----
        String status = str("SELECT import_status FROM import_record WHERE id=:id", "id", goldenRecordId);
        assertEquals("SUCCESS", status, "原始模板应 SUCCESS（TC-U6-05/U7）");

        // ---- U1/U4：三态推断（TC-U1-01/03/05） ----
        assertEquals("RECIPE", str(
            "SELECT characteristic FROM material_bom_item WHERE customer_no=:c AND material_no='S-3120014539' " +
            "AND component_no='991' AND pending_quotation_id=:p",
            "c", GOLDEN_CUSTOMER_NO, "p", goldenRecordId), "991 应定型 RECIPE（TC-U1-01）");
        assertEquals("RECIPE", str(
            "SELECT characteristic FROM material_bom_item WHERE customer_no=:c AND material_no='S-3120014539' " +
            "AND component_no='992' AND pending_quotation_id=:p",
            "c", GOLDEN_CUSTOMER_NO, "p", goldenRecordId), "992 应定型 RECIPE（TC-U1-01）");
        assertEquals("ASSEMBLY", str(
            "SELECT characteristic FROM material_bom_item WHERE customer_no=:c AND material_no='S-3120014539' " +
            "AND component_no='S-80011' AND pending_quotation_id=:p",
            "c", GOLDEN_CUSTOMER_NO, "p", goldenRecordId), "S-80011 应定型 ASSEMBLY（TC-U1-03）");
        assertEquals("OUTSOURCED", str(
            "SELECT characteristic FROM material_bom_item WHERE customer_no=:c AND material_no='S-3120014539' " +
            "AND component_no='W-1001' AND pending_quotation_id=:p",
            "c", GOLDEN_CUSTOMER_NO, "p", goldenRecordId), "W-1001 应定型 OUTSOURCED（TC-U1-05）");

        // ---- B4/TC-U5-01：自制加工费工序反填 ----
        assertEquals("Z380", str(
            "SELECT operation_no FROM material_bom_item WHERE customer_no=:c AND material_no='S-3120014539' " +
            "AND component_no='S-80011' AND pending_quotation_id=:p",
            "c", GOLDEN_CUSTOMER_NO, "p", goldenRecordId), "S-80011 应反填 operation_no=Z380（TC-U5-01）");

        // ---- B3/TC-U0-03：composition_qty ----
        assertEquals(0, java.math.BigDecimal.valueOf(1).compareTo(new java.math.BigDecimal(str(
            "SELECT composition_qty FROM material_bom_item WHERE customer_no=:c AND material_no='S-3120014539' " +
            "AND component_no='S-80011' AND pending_quotation_id=:p",
            "c", GOLDEN_CUSTOMER_NO, "p", goldenRecordId))), "S-80011 composition_qty 应=1（TC-U0-03）");
        assertEquals(0, java.math.BigDecimal.valueOf(2).compareTo(new java.math.BigDecimal(str(
            "SELECT composition_qty FROM material_bom_item WHERE customer_no=:c AND material_no='S-3120014539' " +
            "AND component_no='W-1001' AND pending_quotation_id=:p",
            "c", GOLDEN_CUSTOMER_NO, "p", goldenRecordId))), "W-1001 composition_qty 应=2（TC-U0-03）");

        // ---- B3/TC-U0-05/TC-U5-05：issue_unit（ASSEMBLY/OUTSOURCED 兜底 PCS） ----
        assertEquals("PCS", str(
            "SELECT issue_unit FROM material_bom_item WHERE customer_no=:c AND material_no='S-3120014539' " +
            "AND component_no='S-80011' AND pending_quotation_id=:p",
            "c", GOLDEN_CUSTOMER_NO, "p", goldenRecordId), "S-80011 issue_unit 应兜底 PCS（TC-U5-05）");
        assertEquals("PCS", str(
            "SELECT issue_unit FROM material_bom_item WHERE customer_no=:c AND material_no='S-3120014539' " +
            "AND component_no='W-1001' AND pending_quotation_id=:p",
            "c", GOLDEN_CUSTOMER_NO, "p", goldenRecordId), "W-1001 issue_unit 应兜底 PCS（TC-U5-05）");

        // ---- R1/B6/TC-U2-04/05、TC-U3-01/02：落库矩阵 —— 落 staging，不落 material_master 正表 ----
        assertEquals("零件", str(
            "SELECT material_type FROM pending_material_master_staging WHERE quotation_id=:p AND material_no='S-80011'",
            "p", goldenRecordId), "S-80011 material_type 应='零件'（B6/TC-U3-01, 查 staging 非正表 R1）");
        assertEquals("外购件", str(
            "SELECT material_type FROM pending_material_master_staging WHERE quotation_id=:p AND material_no='W-1001'",
            "p", goldenRecordId), "W-1001 material_type 应='外购件'（B6/TC-U3-02, 查 staging 非正表 R1）");
        // 反向确认：material_master 正表本次导入不应新增这两个料号（核价审批通过前不落正表）。
        // （历史遗留：material_master 里此二料号此前从未被任何流程 promote 过，故直接断言=0 即可。）
        assertEquals(0L, count(
            "SELECT count(*) FROM material_master WHERE material_no IN ('S-80011','W-1001')"),
            "R1：material_master 正表本次导入不应落 S-80011/W-1001（核价审批前不 promote）");
        // 材质 991/992 不进 material_master 也不进 staging（TC-U3-03）。
        assertEquals(0L, count(
            "SELECT count(*) FROM pending_material_master_staging WHERE quotation_id=:p AND material_no IN ('991','992')",
            "p", goldenRecordId), "TC-U3-03：材质 991/992 不应进 staging");
        assertEquals(0L, count(
            "SELECT count(*) FROM material_master WHERE material_no IN ('991','992')"),
            "TC-U3-03：材质 991/992 不应进 material_master 正表");

        // ---- B5/TC-U0-04：Q13 item_seq 错位修正（getIntNth("项次",2) 非 3） ----
        Object itemSeqRaw = singleOrNull(
            "SELECT item_seq FROM unit_price WHERE system_type='QUOTE' AND customer_no=:c " +
            "AND price_type='COMPONENT_OTHER' AND code='W-1001' AND finished_material_no='S-3120014539' " +
            "AND pending_quotation_id=:p",
            "c", GOLDEN_CUSTOMER_NO, "p", goldenRecordId);
        assertNotNull(itemSeqRaw, "TC-U0-04：W-1001 组成件其他费用 item_seq 不应为 null（getIntNth 错位 bug 已修）");
        assertEquals(1, ((Number) itemSeqRaw).intValue(), "TC-U0-04：item_seq 应=1（项次第 2 个「项次」列）");

        // ---- U13 pending 语义（TC-U13-01/02）----
        assertNull(str("SELECT quotation_id FROM import_record WHERE id=:id", "id", goldenRecordId),
            "TC-U13-02：create-quotation 前 import_record.quotation_id 应为空");
        long pendingBomItemCount = count(
            "SELECT count(*) FROM material_bom_item WHERE material_no='S-3120014539' AND pending_quotation_id=:p",
            "p", goldenRecordId);
        assertTrue(pendingBomItemCount > 0, "TC-U13-01：material_bom_item 应有行 pending_quotation_id=importRecordId");

        // ---- U8 性能（TC-U8-01）----
        Log.infof("[update0723-test] 黄金样例（约 25 行有效数据）端到端 elapsed=%.0fms（阈值<2000ms，含远程DB网络往返）",
            elapsedMs);
        // 记录为观察项而非硬性 assert：远程 DB(10.177.152.12) 网络往返 + 共享库并发负载可能使本地阈值失真，
        // 由测试报告人工判读；若 > 2000ms 会在报告中如实标注（不静默通过）。

        // =====================================================================
        // U13 过户（TC-U13-03）：create-quotation 后 pending_quotation_id 从 importRecordId 过户为 quotationId
        // =====================================================================
        CreateQuotationFromImportRequest req = new CreateQuotationFromImportRequest();
        req.importRecordId = goldenRecordId;
        req.customerId = customerId;
        req.name = "update0723-测试报价单-" + goldenRecordId.toString().substring(0, 8);
        V6QuotationCommitService.CommitResult commit = commitService.createQuotation(req, user);
        goldenQuotationId = commit.quotationId;
        assertNotNull(goldenQuotationId, "createQuotation 应返回真实 quotationId");

        long stillOnRecordId = count(
            "SELECT count(*) FROM material_bom_item WHERE material_no='S-3120014539' AND pending_quotation_id=:p",
            "p", goldenRecordId);
        assertEquals(0L, stillOnRecordId, "TC-U13-03：过户后不应再有行 pending_quotation_id=importRecordId");
        long onQuotationId = count(
            "SELECT count(*) FROM material_bom_item WHERE material_no='S-3120014539' AND pending_quotation_id=:p",
            "p", goldenQuotationId);
        assertEquals(pendingBomItemCount, onQuotationId,
            "TC-U13-03：过户后 material_bom_item 行数应等量出现在 pending_quotation_id=quotationId 下");
    }

    // =====================================================================
    // Test 2：Phase2 中途失败 → 整单回滚（TC-U6-04，agent 自测盲区补测）
    // =====================================================================

    @Test
    void phase2CrossCustomerFailure_rollsBackEntireTransaction_noPartialWrites() throws Exception {
        final String ownerA = "UPD0723-OWNER-A";
        final String ownerB = "UPD0723-OWNER-B";
        final String xcustToken = "UPD0723-XTOK-1";
        final String mainB = "UPD0723-MAINB-1";
        final String subB = "UPD0723-SUBB-1";

        // 预置：xcustToken 已"合法归属"客户 A（模拟已提交/已生效的既有占号行，pending_quotation_id=NULL）。
        seedCrossCustomerOwnership(xcustToken, ownerA);

        // ---- 基线：客户 B 的所有相关表在导入前均为 0（全新客户，天然基线） ----
        long[] before = snapshotCounts(ownerB);
        assertArrayEquals(new long[]{0, 0, 0, 0}, before, "导入前客户B应无任何相关数据（新客户基线）");

        byte[] wb = buildRollbackWorkbook(mainB, subB, xcustToken);
        UUID customerId = anyCustomerId(GOLDEN_CUSTOMER_NO); // FK 占位，与 ctx.customerNo(ownerB) 解耦
        UUID user = anyUserId();

        rollbackRecordId = svc.createImportRecord(customerId, "update0723-rollback-test.xlsx", user);
        managedExecutor.runAsync(() ->
            svc.processImport(rollbackRecordId, ownerB, "update0723-rollback-test.xlsx", wb, user))
            .get(30, TimeUnit.SECONDS);

        // ---- ① status=FAILED，非 PARTIAL ----
        String status = str("SELECT import_status FROM import_record WHERE id=:id", "id", rollbackRecordId);
        assertEquals("FAILED", status, "Phase2 跨客户串号应导致整单 FAILED（非 PARTIAL）");
        assertNotEquals("PARTIAL", status);
        assertNotEquals("SUCCESS", status);

        // ---- ② 零写库：所有相关表 count 导入前后完全一致 ----
        long[] after = snapshotCounts(ownerB);
        assertArrayEquals(before, after,
            "整单回滚应零写库：material_bom/material_bom_item/unit_price/material_customer_map(customer=B) " +
            "计数应保持导入前基线，实际 before=" + java.util.Arrays.toString(before) +
            " after=" + java.util.Arrays.toString(after));

        long stagingCount = count(
            "SELECT count(*) FROM pending_material_master_staging WHERE quotation_id=:p", "p", rollbackRecordId);
        assertEquals(0L, stagingCount,
            "整单回滚应零写库：pending_material_master_staging(本次 recordId) 应=0（bomMerge/单重 写入的暂存也应回滚）");

        // 具体验证 bomMerge 本应写入的 material_bom_item(mainB/subB) 确实不存在（而非仅计数巧合为 0）
        assertEquals(0L, count(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m", "m", mainB));
        assertEquals(0L, count(
            "SELECT count(*) FROM material_bom WHERE material_no=:m", "m", mainB));
        // Q02 本应写入的客户料号映射也不存在
        assertEquals(0L, count(
            "SELECT count(*) FROM material_customer_map WHERE material_no=:m AND customer_no=:c",
            "m", mainB, "c", ownerB));

        // ---- ③ 客户 A 的既有归属不受影响（未被误改/误删）----
        String ownerOfToken = str(
            "SELECT customer_no FROM material_customer_map WHERE material_no=:m AND system_type='QUOTE'",
            "m", xcustToken);
        assertEquals(ownerA, ownerOfToken, "客户 A 对 xcustToken 的既有归属不应被本次失败导入影响");

        Log.infof("[update0723-test] TC-U6-04 整单回滚验证通过：status=%s, before=%s, after=%s, staging=%d",
            status, java.util.Arrays.toString(before), java.util.Arrays.toString(after), stagingCount);
    }

    /** [material_bom, material_bom_item, unit_price, material_customer_map] 按 customer_no 计数快照。 */
    long[] snapshotCounts(String customerNo) {
        return new long[]{
            count("SELECT count(*) FROM material_bom WHERE customer_no=:c", "c", customerNo),
            count("SELECT count(*) FROM material_bom_item WHERE customer_no=:c", "c", customerNo),
            count("SELECT count(*) FROM unit_price WHERE customer_no=:c", "c", customerNo),
            count("SELECT count(*) FROM material_customer_map WHERE customer_no=:c", "c", customerNo),
        };
    }

    @Transactional
    void seedCrossCustomerOwnership(String materialNo, String customerNo) {
        em.createNativeQuery(
            "INSERT INTO material_customer_map (system_type, material_no, customer_no, customer_product_no, " +
            "production_no, pending_quotation_id, created_at, updated_at) " +
            "VALUES ('QUOTE', :m, :c, NULL, NULL, NULL, NOW(), NOW())")
            .setParameter("m", materialNo).setParameter("c", customerNo).executeUpdate();
    }

    /**
     * 构造 Phase1 全通过、但 Phase2 第 12 个 handler（组成件其他费用/Q13）触发真实跨客户串号异常的
     * workbook：物料BOM(bomMerge,#1)/单重(#2)/客户料号与宏丰料号的关系(#3) 三个 handler 先写入真实数据
     * （orderedHandlers 顺序中排在 Q13 之前），组成件其他费用(#12) 引用一个已属另一客户的料号触发
     * {@code CrossCustomerQuoteNoException} → handler 内 recordError（不抛异常，per-row 跳过）→
     * writeAll 检测 failedRows>0 → throw QuoteImportWriteFailedException → 整个 REQUIRES_NEW
     * 事务回滚，含前面 3 个 handler 已写入的真实数据。
     */
    private byte[] buildRollbackWorkbook(String mainB, String subB, String xcustToken) throws Exception {
        LinkedHashMap<String, List<Object[]>> sheets = new LinkedHashMap<>();

        sheets.put("物料BOM", List.of(
            new Object[]{"销售料号", "项次", "投入料号", "投入料号名称", "产出料号类型", "组成数量",
                         "材料毛重", "材料净重", "重量单位", "损耗率（%）", "不良率（%）"},
            new Object[]{mainB, 1, subB, null, null, 1, null, null, null, null, null}
        ));
        sheets.put("单重", List.of(
            new Object[]{"销售料号", "单重"},
            new Object[]{mainB, 10.5}
        ));
        sheets.put("客户料号与宏丰料号的关系", List.of(
            new Object[]{"客户料号名称", "客户产品编号", "客户图号", "销售料号", "付款方式", "基础货币", "报价货币", "汇率"},
            new Object[]{null, "CPN-B-1", null, mainB, null, null, null, null}
        ));
        sheets.put("组成件其他费用", List.of(
            new Object[]{"销售料号", "项次", "组成件料号", "组成件名称", "供应商编号", "供应商名称",
                         "项次", "要素名称", "值", "货币", "计价单位"},
            new Object[]{mainB, 1, xcustToken, null, null, null, 1, "材料费", 10, "RMB", "PCS"}
        ));

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            for (Map.Entry<String, List<Object[]>> e : sheets.entrySet()) {
                Sheet sheet = wb.createSheet(e.getKey());
                int rIdx = 0;
                for (Object[] rowVals : e.getValue()) {
                    Row row = sheet.createRow(rIdx++);
                    for (int c = 0; c < rowVals.length; c++) {
                        Object v = rowVals[c];
                        if (v == null) continue;
                        Cell cell = row.createCell(c);
                        if (v instanceof Number n) cell.setCellValue(n.doubleValue());
                        else cell.setCellValue(v.toString());
                    }
                }
            }
            wb.write(bos);
            return bos.toByteArray();
        }
    }
}
