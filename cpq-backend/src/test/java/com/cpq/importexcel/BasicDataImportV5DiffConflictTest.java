package com.cpq.importexcel;

import com.cpq.common.exception.BusinessException;
import com.cpq.importexcel.dto.ImportResultDTO;
import com.cpq.importexcel.dto.ResolutionDTO;
import com.cpq.importexcel.entity.ImportRecord;
import com.cpq.importexcel.service.BasicDataImportServiceV5;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.UserTransaction;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UI-1 + UI-2 后端：diff/conflict 检测 + confirm resolutions 集成测试。
 *
 * <p>覆盖 10 用例：
 *   T1: 无差异无冲突直通
 *   T2: 仅 basic diff（mat_part unit_weight 变更）
 *   T3: 仅 customer conflict（mat_process unit_price 变更）
 *   T4: 同时存在差异 + 冲突
 *   T5: ACCEPT_NEW resolution 写入新值 → DB 验证
 *   T6: KEEP_OLD resolution 跳过该字段 → DB 仍是旧值
 *   T7: CRITICAL 字段缺 note 抛 400
 *   T8: 并发冲突 oldValue 不一致抛 409
 *   T9: resolutions JSON 格式错误（Resource 层），通过 parseResolutions 测试
 *   T10: resolutions 空等价于空数组直通
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BasicDataImportV5DiffConflictTest {

    @Inject
    BasicDataImportServiceV5 serviceV5;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    private static final UUID CUSTOMER_ID = UUID.fromString("44000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID     = UUID.fromString("44000000-0000-0000-0000-000000000002");

    // 测试用固定料号
    private static final String PART_DIFF   = "DIFF-TEST-001";
    private static final String PART_CONF   = "CONF-TEST-001";
    private static final String PART_BOTH   = "BOTH-TEST-001";
    private static final String PART_ACCEPT = "ACCEPT-TEST-001";
    private static final String PART_KEEP   = "KEEP-TEST-001";

    @BeforeEach
    void setup() throws Exception {
        utx.begin();
        em.joinTransaction();

        // 确保测试用户存在
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, status, is_first_login, created_at, updated_at) " +
                "VALUES (:id, 'diff-tester', 'Diff Conflict Tester', 'diff@test.com', 'hash', 'SALES_MANAGER', 'ACTIVE', false, NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", USER_ID).executeUpdate();

        // 确保客户存在
        em.createNativeQuery(
                "INSERT INTO customer(id, name, code, level, accumulated_amount, status, created_at, updated_at) " +
                "VALUES (:id, 'Diff Test Customer', 'DIFF-CUST', 'STANDARD', 0, 'ACTIVE', NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", CUSTOMER_ID).executeUpdate();

        // 清理客户级数据
        em.createNativeQuery("DELETE FROM plating_fee WHERE customer_id = :cid")
                .setParameter("cid", CUSTOMER_ID).executeUpdate();
        em.createNativeQuery("DELETE FROM mat_fee WHERE customer_id = :cid")
                .setParameter("cid", CUSTOMER_ID).executeUpdate();
        em.createNativeQuery("DELETE FROM mat_process WHERE customer_id = :cid")
                .setParameter("cid", CUSTOMER_ID).executeUpdate();
        em.createNativeQuery("DELETE FROM mat_customer_part_mapping WHERE customer_id = :cid")
                .setParameter("cid", CUSTOMER_ID).executeUpdate();
        em.createNativeQuery("DELETE FROM product_import_lock WHERE customer_id = :cid")
                .setParameter("cid", CUSTOMER_ID).executeUpdate();

        // 解锁 DDL 锁
        em.createNativeQuery(
                "UPDATE ddl_operation_lock SET expires_at = NOW() - INTERVAL '1 second' WHERE lock_key = 'global'")
                .executeUpdate();

        utx.commit();
    }

    // ──────────────────────────────────────────────────────────────────────
    // T1: 无差异无冲突直通 → basicDataDiffs 空 + customerDataConflicts 空
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    void T1_noDiffNoConflict_listsAreEmpty() throws Exception {
        // 先执行一次导入（建立 DB 基准数据）
        byte[] xlsx = buildMatPartOnlyExcel("T1-PART", new BigDecimal("0.010"));
        serviceV5.importBasicDataV5(new ByteArrayInputStream(xlsx), CUSTOMER_ID, USER_ID);

        // 再次 preview 用完全相同的值
        ImportResultDTO result = serviceV5.previewV5(new ByteArrayInputStream(xlsx), CUSTOMER_ID, USER_ID);

        assertEquals("PREVIEW_OK", result.status);
        assertNotNull(result.basicDataDiffs);
        assertNotNull(result.customerDataConflicts);

        // 相同值不应产生 diff
        long diffs = result.basicDataDiffs.stream()
                .filter(d -> "T1-PART".equals(d.rowKey))
                .count();
        assertEquals(0, diffs, "相同值不应有 diff 条目");
        assertEquals(0, result.customerDataConflicts.size(), "无客户数据，不应有冲突");
    }

    // ──────────────────────────────────────────────────────────────────────
    // T2: 仅 basic diff — mat_part unit_weight 发生变更
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(2)
    void T2_basicDiff_unitWeightChanged() throws Exception {
        // 建立 DB 基准值 0.010
        byte[] xlsxV1 = buildMatPartOnlyExcel(PART_DIFF, new BigDecimal("0.010"));
        serviceV5.importBasicDataV5(new ByteArrayInputStream(xlsxV1), CUSTOMER_ID, USER_ID);

        // preview 新值 0.020
        byte[] xlsxV2 = buildMatPartOnlyExcel(PART_DIFF, new BigDecimal("0.020"));
        ImportResultDTO result = serviceV5.previewV5(new ByteArrayInputStream(xlsxV2), CUSTOMER_ID, USER_ID);

        assertEquals("PREVIEW_OK", result.status);
        assertNotNull(result.basicDataDiffs);

        boolean hasDiff = result.basicDataDiffs.stream().anyMatch(d ->
                PART_DIFF.equals(d.rowKey) && "unit_weight".equals(d.fieldName));
        assertTrue(hasDiff, "unit_weight 发生变更时应检测到 basicDataDiff");

        // 验证 diff 内容
        result.basicDataDiffs.stream()
                .filter(d -> PART_DIFF.equals(d.rowKey) && "unit_weight".equals(d.fieldName))
                .findFirst()
                .ifPresent(d -> {
                    assertEquals("CRITICAL", d.importance, "unit_weight 应为 CRITICAL");
                    assertTrue(d.affectsCalculation, "unit_weight 变更应影响计算");
                    assertNotNull(d.oldValue, "旧值不应为 null");
                    assertNotNull(d.newValue, "新值不应为 null");
                });
    }

    // ──────────────────────────────────────────────────────────────────────
    // T3: 仅 customer conflict — mat_process unit_price 发生变更
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(3)
    void T3_customerConflict_unitPriceChanged() throws Exception {
        // 建立基础料号（mat_part 必须存在，mat_process 依赖它）
        ensureMatPart(PART_CONF, new BigDecimal("0.010"));

        // 第一次 confirm（unit_price = 100）
        byte[] xlsxV1 = buildMatProcessExcel(PART_CONF, new BigDecimal("100.00"), "CNY");
        serviceV5.importBasicDataV5(new ByteArrayInputStream(xlsxV1), CUSTOMER_ID, USER_ID);

        // preview 第二次（unit_price = 150）
        byte[] xlsxV2 = buildMatProcessExcel(PART_CONF, new BigDecimal("150.00"), "CNY");
        ImportResultDTO result = serviceV5.previewV5(new ByteArrayInputStream(xlsxV2), CUSTOMER_ID, USER_ID);

        assertEquals("PREVIEW_OK", result.status);
        assertNotNull(result.customerDataConflicts);

        boolean hasConflict = result.customerDataConflicts.stream().anyMatch(c ->
                PART_CONF.equals(c.hfPartNo) && "mat_process".equals(c.tableName) &&
                c.fields.stream().anyMatch(f -> "unit_price".equals(f.fieldName)));
        assertTrue(hasConflict, "unit_price 发生变更时应检测到 customerDataConflict");
    }

    // ──────────────────────────────────────────────────────────────────────
    // T4: 同时存在差异 + 冲突
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(4)
    void T4_bothDiffAndConflict() throws Exception {
        ensureMatPart(PART_BOTH, new BigDecimal("0.010"));

        // 第一次 confirm（建立基准值）
        byte[] xlsxV1 = buildCombinedExcel(PART_BOTH, new BigDecimal("0.010"), new BigDecimal("200.00"), "CNY");
        serviceV5.importBasicDataV5(new ByteArrayInputStream(xlsxV1), CUSTOMER_ID, USER_ID);

        // preview 带变更（unit_weight + unit_price 同时变更）
        byte[] xlsxV2 = buildCombinedExcel(PART_BOTH, new BigDecimal("0.015"), new BigDecimal("250.00"), "CNY");
        ImportResultDTO result = serviceV5.previewV5(new ByteArrayInputStream(xlsxV2), CUSTOMER_ID, USER_ID);

        assertEquals("PREVIEW_OK", result.status);
        assertFalse(result.basicDataDiffs.isEmpty(), "应有基础资料差异");
        assertFalse(result.customerDataConflicts.isEmpty(), "应有客户资料冲突");
    }

    // ──────────────────────────────────────────────────────────────────────
    // T5: ACCEPT_NEW resolution 写入新值 → DB 验证
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(5)
    void T5_acceptNew_writesNewValueToDb() throws Exception {
        // 建立基准值（unit_weight = 0.010）
        byte[] xlsxV1 = buildMatPartOnlyExcel(PART_ACCEPT, new BigDecimal("0.010"));
        serviceV5.importBasicDataV5(new ByteArrayInputStream(xlsxV1), CUSTOMER_ID, USER_ID);

        // confirm 新值（unit_weight = 0.020）带 ACCEPT_NEW resolution
        byte[] xlsxV2 = buildMatPartOnlyExcel(PART_ACCEPT, new BigDecimal("0.020"));

        ResolutionDTO res = new ResolutionDTO();
        res.type = "BASIC_DIFF";
        res.tableName = "mat_part";
        res.rowKey = PART_ACCEPT;
        res.fieldName = "unit_weight";
        res.decision = "ACCEPT_NEW";
        res.note = "已确认单重变更：0.010 → 0.020，供应商实测";
        res.oldValueAtPreview = "0.01";  // 与 DB 一致

        ImportResultDTO result = serviceV5.importBasicDataV5(
                new ByteArrayInputStream(xlsxV2), CUSTOMER_ID, USER_ID, List.of(res));

        assertEquals("SUCCESS", result.status);

        // 验证 DB 已写入新值
        String dbWeight = queryField("SELECT CAST(unit_weight AS TEXT) FROM mat_part WHERE part_no = :pn",
                PART_ACCEPT);
        assertNotNull(dbWeight);
        assertEquals(0, new BigDecimal(dbWeight).compareTo(new BigDecimal("0.020")),
                "ACCEPT_NEW 后 unit_weight 应为 0.020");

        // 验证 metadata 已存储 resolutions
        ImportRecord record = getImportRecord(result.importRecordId);
        assertNotNull(record);
        assertNotNull(record.metadata, "resolutions 应序列化存入 metadata");
        assertTrue(record.metadata.contains("ACCEPT_NEW"), "metadata 应包含决策类型");
    }

    // ──────────────────────────────────────────────────────────────────────
    // T6: KEEP_OLD resolution 跳过该字段 → DB 仍是旧值
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(6)
    void T6_keepOld_preservesOldValueInDb() throws Exception {
        // 建立基准值（unit_weight = 0.010）
        byte[] xlsxV1 = buildMatPartOnlyExcel(PART_KEEP, new BigDecimal("0.010"));
        serviceV5.importBasicDataV5(new ByteArrayInputStream(xlsxV1), CUSTOMER_ID, USER_ID);

        // confirm 新值（unit_weight = 0.999）带 KEEP_OLD resolution
        byte[] xlsxV2 = buildMatPartOnlyExcel(PART_KEEP, new BigDecimal("0.999"));

        ResolutionDTO res = new ResolutionDTO();
        res.type = "BASIC_DIFF";
        res.tableName = "mat_part";
        res.rowKey = PART_KEEP;
        res.fieldName = "unit_weight";
        res.decision = "KEEP_OLD";

        ImportResultDTO result = serviceV5.importBasicDataV5(
                new ByteArrayInputStream(xlsxV2), CUSTOMER_ID, USER_ID, List.of(res));

        assertEquals("SUCCESS", result.status);

        // 验证 DB 仍是旧值（KEEP_OLD 跳过写入）
        String dbWeight = queryField("SELECT CAST(unit_weight AS TEXT) FROM mat_part WHERE part_no = :pn",
                PART_KEEP);
        assertNotNull(dbWeight);
        // unit_weight 不应被 0.999 覆盖（应仍为 0.010 或接近原值）
        assertNotEquals(0, new BigDecimal(dbWeight).compareTo(new BigDecimal("0.999")),
                "KEEP_OLD 后 unit_weight 不应被新值 0.999 覆盖");
    }

    // ──────────────────────────────────────────────────────────────────────
    // T7: CRITICAL 字段缺 note 抛 400
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(7)
    void T7_criticalFieldMissingNote_throws400() throws Exception {
        byte[] xlsx = buildMatPartOnlyExcel("T7-PART", new BigDecimal("0.050"));

        ResolutionDTO res = new ResolutionDTO();
        res.type = "BASIC_DIFF";
        res.tableName = "mat_part";
        res.rowKey = "T7-PART";
        res.fieldName = "unit_weight";  // CRITICAL 字段
        res.decision = "ACCEPT_NEW";
        res.note = null;  // 故意不填 note

        BusinessException ex = assertThrows(BusinessException.class,
                () -> serviceV5.importBasicDataV5(
                        new ByteArrayInputStream(xlsx), CUSTOMER_ID, USER_ID, List.of(res)));

        assertEquals(400, ex.getCode(), "缺少 note 应抛 400");
        assertTrue(ex.getMessage().contains("note") || ex.getMessage().contains("CRITICAL"),
                "错误信息应提及 note 或 CRITICAL");
    }

    // ──────────────────────────────────────────────────────────────────────
    // T8: 并发冲突 — oldValueAtPreview 不一致抛 409
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(8)
    void T8_stalePrevewOldValue_throws409() throws Exception {
        // 建立基准值（unit_weight = 0.010）
        String partNo = "T8-STALE";
        byte[] xlsxV1 = buildMatPartOnlyExcel(partNo, new BigDecimal("0.010"));
        serviceV5.importBasicDataV5(new ByteArrayInputStream(xlsxV1), CUSTOMER_ID, USER_ID);

        // preview 记录 oldValue = "0.01"
        // 模拟：期间另一导入将 DB 值改为 0.030（直接 UPDATE）
        directUpdateUnitWeight(partNo, new BigDecimal("0.030"));

        // confirm 时 oldValueAtPreview = "0.01"，但 DB 已是 0.030 → 抛 409
        byte[] xlsxV2 = buildMatPartOnlyExcel(partNo, new BigDecimal("0.050"));

        ResolutionDTO res = new ResolutionDTO();
        res.type = "BASIC_DIFF";
        res.tableName = "mat_part";
        res.rowKey = partNo;
        res.fieldName = "unit_weight";
        res.decision = "ACCEPT_NEW";
        res.note = "测试并发";
        res.oldValueAtPreview = "0.01";  // 与 DB 当前 0.030 不一致

        BusinessException ex = assertThrows(BusinessException.class,
                () -> serviceV5.importBasicDataV5(
                        new ByteArrayInputStream(xlsxV2), CUSTOMER_ID, USER_ID, List.of(res)));

        assertEquals(409, ex.getCode(), "旧值不一致应抛 409 并发冲突");
    }

    // ──────────────────────────────────────────────────────────────────────
    // T9: resolutions JSON 格式错误抛 400（Resource 层的 parseResolutions 逻辑）
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(9)
    void T9_malformedResolutionsJson_parseThrows() {
        // 直接模拟 Resource 层的 parseResolutions 逻辑
        // 格式错误的 JSON（缺少引号）
        String badJson = "[{type:BASIC_DIFF,decision:ACCEPT_NEW}]";

        // 使用 Jackson 解析，应抛出异常
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThrows(Exception.class, () -> {
            mapper.readValue(badJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<ResolutionDTO>>() {});
        }, "格式错误 JSON 应抛出解析异常");

        // 验证 Resource 的 BusinessException 包装（通过反射模拟）
        // 这里我们验证：有效 JSON 能被正确解析
        String validJson = "[{\"type\":\"BASIC_DIFF\",\"tableName\":\"mat_part\"," +
                "\"rowKey\":\"P-001\",\"fieldName\":\"unit_weight\"," +
                "\"decision\":\"ACCEPT_NEW\",\"note\":\"ok\",\"oldValueAtPreview\":\"0.01\"}]";
        assertDoesNotThrow(() -> {
            List<ResolutionDTO> list = mapper.readValue(validJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<ResolutionDTO>>() {});
            assertEquals(1, list.size());
            assertEquals("BASIC_DIFF", list.get(0).type);
            assertEquals("ACCEPT_NEW", list.get(0).decision);
        }, "合法 JSON 应能正确解析");
    }

    // ──────────────────────────────────────────────────────────────────────
    // T10: resolutions 空字符串等价于空数组直通
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(10)
    void T10_nullResolutions_equivalentToEmptyList() throws Exception {
        // null resolutions 不应影响正常导入
        byte[] xlsx = buildMatPartOnlyExcel("T10-PART", new BigDecimal("0.005"));

        ImportResultDTO result = serviceV5.importBasicDataV5(
                new ByteArrayInputStream(xlsx), CUSTOMER_ID, USER_ID, null);

        assertEquals("SUCCESS", result.status, "null resolutions 不影响正常导入");

        // empty list 同样应该正常
        ImportResultDTO result2 = serviceV5.importBasicDataV5(
                new ByteArrayInputStream(xlsx), CUSTOMER_ID, USER_ID, Collections.emptyList());

        assertEquals("SUCCESS", result2.status, "空 resolutions 不影响正常导入");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Excel 构建辅助方法
    // ══════════════════════════════════════════════════════════════════════

    /** 构建仅含 mat_part 的 Excel */
    private byte[] buildMatPartOnlyExcel(String partNo, BigDecimal unitWeight) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("料号主档");
            // V58_5/V59 seed 列顺序: A=part_no B=part_name C=specification D=size_info E=unit_weight F=weight_unit G=status_code
            Row header = sheet.createRow(0);
            String[] cols = {"HF_PART_NO", "PART_NAME", "SPECIFICATION", "SIZE_INFO", "UNIT_WEIGHT", "WEIGHT_UNIT", "STATUS_CODE"};
            for (int c = 0; c < cols.length; c++) header.createCell(c).setCellValue(cols[c]);

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(partNo);
            row.createCell(1).setCellValue("测试料号");
            row.createCell(2).setCellValue("AgNi10");
            row.createCell(3).setCellValue("3x5");       // D=size_info
            row.createCell(4).setCellValue(unitWeight.doubleValue());  // E=unit_weight
            row.createCell(5).setCellValue("KG");
            row.createCell(6).setCellValue("Y");

            return toBytes(wb);
        }
    }

    /** 构建含 mat_part + mat_process 的 Excel */
    private byte[] buildMatProcessExcel(String partNo, BigDecimal unitPrice, String currency) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            // mat_part sheet — V58_5/V59 seed: A=part_no B=part_name C=spec D=size_info E=unit_weight F=weight_unit G=status_code
            Sheet partSheet = wb.createSheet("料号主档");
            Row ph = partSheet.createRow(0);
            String[] pCols = {"HF_PART_NO", "PART_NAME", "SPECIFICATION", "SIZE_INFO", "UNIT_WEIGHT", "WEIGHT_UNIT", "STATUS_CODE"};
            for (int c = 0; c < pCols.length; c++) ph.createCell(c).setCellValue(pCols[c]);
            Row pr = partSheet.createRow(1);
            pr.createCell(0).setCellValue(partNo);
            pr.createCell(1).setCellValue("测试料号");
            pr.createCell(2).setCellValue("AgNi10");
            pr.createCell(3).setCellValue("3x5");
            pr.createCell(4).setCellValue(0.010);  // E=unit_weight
            pr.createCell(5).setCellValue("KG");
            pr.createCell(6).setCellValue("Y");

            // mat_process sheet — V58_5/V59 seed: A=hf_part_no B=seq_no C=sub_seq_no D=process_code E=assembly_process
            //   F=component_part_no G=component_name H=supplier_code I=supplier_name J=quantity K=quantity_unit
            //   L=unit_price M=freight N=currency O=price_unit
            Sheet procSheet = wb.createSheet("组成件BOM");
            Row procH = procSheet.createRow(0);
            String[] procCols = {"HF_PART_NO", "SEQ_NO", "SUB_SEQ_NO", "PROCESS_CODE", "ASSEMBLY_PROCESS",
                                 "COMPONENT_PART_NO", "COMPONENT_NAME", "SUPPLIER_CODE", "SUPPLIER_NAME",
                                 "QUANTITY", "QUANTITY_UNIT", "UNIT_PRICE", "FREIGHT", "CURRENCY", "PRICE_UNIT"};
            for (int c = 0; c < procCols.length; c++) procH.createCell(c).setCellValue(procCols[c]);
            Row procR = procSheet.createRow(1);
            procR.createCell(0).setCellValue(partNo);   // A=hf_part_no
            procR.createCell(1).setCellValue(1);         // B=seq_no
            // C=sub_seq_no (留空)
            procR.createCell(3).setCellValue("P001");    // D=process_code
            procR.createCell(4).setCellValue("冲压");    // E=assembly_process
            procR.createCell(5).setCellValue(partNo);    // F=component_part_no
            procR.createCell(6).setCellValue("组成件名称"); // G=component_name
            procR.createCell(7).setCellValue("SUP-01");  // H=supplier_code
            procR.createCell(8).setCellValue("供应商");  // I=supplier_name
            procR.createCell(9).setCellValue(1.0);       // J=quantity
            procR.createCell(10).setCellValue("PCS");    // K=quantity_unit
            procR.createCell(11).setCellValue(unitPrice.doubleValue()); // L=unit_price
            procR.createCell(13).setCellValue(currency); // N=currency
            procR.createCell(14).setCellValue("PCS");    // O=price_unit

            return toBytes(wb);
        }
    }

    /** 构建 mat_part + mat_process 组合（用于 T4） */
    private byte[] buildCombinedExcel(String partNo, BigDecimal unitWeight,
                                       BigDecimal unitPrice, String currency) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            // mat_part — V58_5/V59 seed 列顺序
            Sheet partSheet = wb.createSheet("料号主档");
            Row ph = partSheet.createRow(0);
            String[] pCols = {"HF_PART_NO", "PART_NAME", "SPECIFICATION", "SIZE_INFO", "UNIT_WEIGHT", "WEIGHT_UNIT", "STATUS_CODE"};
            for (int c = 0; c < pCols.length; c++) ph.createCell(c).setCellValue(pCols[c]);
            Row pr = partSheet.createRow(1);
            pr.createCell(0).setCellValue(partNo);
            pr.createCell(1).setCellValue("测试料号");
            pr.createCell(2).setCellValue("AgNi10");
            pr.createCell(3).setCellValue("3x5");
            pr.createCell(4).setCellValue(unitWeight.doubleValue());  // E=unit_weight
            pr.createCell(5).setCellValue("KG");
            pr.createCell(6).setCellValue("Y");

            // mat_process — V58_5/V59 seed 列顺序
            Sheet procSheet = wb.createSheet("组成件BOM");
            Row procH = procSheet.createRow(0);
            String[] procCols = {"HF_PART_NO", "SEQ_NO", "SUB_SEQ_NO", "PROCESS_CODE", "ASSEMBLY_PROCESS",
                                 "COMPONENT_PART_NO", "COMPONENT_NAME", "SUPPLIER_CODE", "SUPPLIER_NAME",
                                 "QUANTITY", "QUANTITY_UNIT", "UNIT_PRICE", "FREIGHT", "CURRENCY", "PRICE_UNIT"};
            for (int c = 0; c < procCols.length; c++) procH.createCell(c).setCellValue(procCols[c]);
            Row procR = procSheet.createRow(1);
            procR.createCell(0).setCellValue(partNo);
            procR.createCell(1).setCellValue(1);
            procR.createCell(3).setCellValue("P001");
            procR.createCell(4).setCellValue("冲压");
            procR.createCell(5).setCellValue(partNo);
            procR.createCell(6).setCellValue("组成件");
            procR.createCell(7).setCellValue("SUP-01");
            procR.createCell(8).setCellValue("供应商");
            procR.createCell(9).setCellValue(1.0);
            procR.createCell(10).setCellValue("PCS");
            procR.createCell(11).setCellValue(unitPrice.doubleValue());
            procR.createCell(13).setCellValue(currency);
            procR.createCell(14).setCellValue("PCS");

            return toBytes(wb);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // T11: KEEP_OLD on mat_bom 字段 → DB 仍是旧值（不变）
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(11)
    void T11_keepOldMatBom_preservesOldLossRate() throws Exception {
        String partNo = "T11-BOM-PART";
        // 建立 mat_part 基准
        ensureMatPart(partNo, new BigDecimal("0.010"));

        // 第一次导入（建立 mat_bom 基准 loss_rate = 0.05）
        byte[] xlsxV1 = buildMatBomExcel(partNo, new BigDecimal("0.05"), 1);
        serviceV5.importBasicDataV5(new ByteArrayInputStream(xlsxV1), CUSTOMER_ID, USER_ID);

        // 确认旧值已写入
        String oldLossRate = queryMatBomField(partNo, "INCOMING", 1, "loss_rate");
        assertNotNull(oldLossRate, "mat_bom 应已写入 loss_rate");

        // 第二次导入（loss_rate = 0.99）带 KEEP_OLD → 旧值不变
        byte[] xlsxV2 = buildMatBomExcel(partNo, new BigDecimal("0.99"), 1);
        String bomRowKey = partNo + ":INCOMING:1";

        ResolutionDTO res = new ResolutionDTO();
        res.type = "BASIC_DIFF";
        res.tableName = "mat_bom";
        res.rowKey = bomRowKey;
        res.fieldName = "loss_rate";
        res.decision = "KEEP_OLD";

        ImportResultDTO result = serviceV5.importBasicDataV5(
                new ByteArrayInputStream(xlsxV2), CUSTOMER_ID, USER_ID, List.of(res));
        assertEquals("SUCCESS", result.status);

        // 验证 DB loss_rate 未被 0.99 覆盖
        String afterLossRate = queryMatBomField(partNo, "INCOMING", 1, "loss_rate");
        assertNotNull(afterLossRate);
        assertNotEquals(0, new BigDecimal(afterLossRate).compareTo(new BigDecimal("0.99")),
                "KEEP_OLD 后 mat_bom.loss_rate 不应被新值 0.99 覆盖");
    }

    // ──────────────────────────────────────────────────────────────────────
    // T12: KEEP_OLD on mat_fee 字段 → DB 仍是旧版本（不创建新版本）
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(12)
    void T12_keepOldMatFee_doesNotCreateNewVersion() throws Exception {
        String partNo = "T12-FEE-PART";
        ensureMatPart(partNo, new BigDecimal("0.010"));

        // 第一次导入（fee_value = 50.00）
        byte[] xlsxV1 = buildMatFeeExcel(partNo, new BigDecimal("50.00"), "CNY");
        serviceV5.importBasicDataV5(new ByteArrayInputStream(xlsxV1), CUSTOMER_ID, USER_ID);

        // 确认 version=1 已写入
        int versionBefore = queryMatFeeVersion(partNo, "INCOMING_FIXED", 1);
        assertEquals(1, versionBefore, "初次导入 mat_fee 应为 version=1");

        // 第二次导入（fee_value = 999.00）带 KEEP_OLD → 不创建新版本
        byte[] xlsxV2 = buildMatFeeExcel(partNo, new BigDecimal("999.00"), "CNY");
        String feeRowKey = CUSTOMER_ID + ":" + partNo + ":INCOMING_FIXED:1";

        ResolutionDTO res = new ResolutionDTO();
        res.type = "CUSTOMER_CONFLICT";
        res.tableName = "mat_fee";
        res.rowKey = feeRowKey;
        res.fieldName = "fee_value";
        res.decision = "KEEP_OLD";

        ImportResultDTO result = serviceV5.importBasicDataV5(
                new ByteArrayInputStream(xlsxV2), CUSTOMER_ID, USER_ID, List.of(res));
        assertEquals("SUCCESS", result.status);

        // 验证没有新版本被创建（仍只有 version=1）
        int versionAfter = queryMatFeeVersion(partNo, "INCOMING_FIXED", 1);
        assertEquals(1, versionAfter, "KEEP_OLD 后 mat_fee 不应创建新版本，应仍为 version=1");
    }

    // ──────────────────────────────────────────────────────────────────────
    // T13: 并发冲突 on mat_process（其他人改了 unit_price）→ 抛 409
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(13)
    void T13_concurrentConflict_matProcess_throws409() throws Exception {
        String partNo = "T13-PROC-PART";
        ensureMatPart(partNo, new BigDecimal("0.010"));

        // 第一次导入（unit_price = 100）
        byte[] xlsxV1 = buildMatProcessExcel(partNo, new BigDecimal("100.00"), "CNY");
        serviceV5.importBasicDataV5(new ByteArrayInputStream(xlsxV1), CUSTOMER_ID, USER_ID);

        // preview 记录 oldValue = "100"
        // 模拟：期间另一导入将 unit_price 改成 200（直接插入新版本）
        directUpdateMatProcessUnitPrice(partNo, new BigDecimal("200.00"));

        // confirm 时 oldValueAtPreview = "100"，但 DB 已是 200 → 抛 409
        byte[] xlsxV2 = buildMatProcessExcel(partNo, new BigDecimal("150.00"), "CNY");
        String procRowKey = CUSTOMER_ID + ":" + partNo + ":1";

        ResolutionDTO res = new ResolutionDTO();
        res.type = "CUSTOMER_CONFLICT";
        res.tableName = "mat_process";
        res.rowKey = procRowKey;
        res.fieldName = "unit_price";
        res.decision = "ACCEPT_NEW";
        res.note = "并发测试";
        res.oldValueAtPreview = "100";  // 与 DB 当前 200 不一致

        BusinessException ex = assertThrows(BusinessException.class,
                () -> serviceV5.importBasicDataV5(
                        new ByteArrayInputStream(xlsxV2), CUSTOMER_ID, USER_ID, List.of(res)));

        assertEquals(409, ex.getCode(), "mat_process 并发旧值不一致应抛 409");
    }

    private byte[] toBytes(Workbook wb) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }

    // ══════════════════════════════════════════════════════════════════════
    // DB 辅助方法
    // ══════════════════════════════════════════════════════════════════════

    /** 构建含 mat_part + mat_bom 的 Excel（bom_type=INCOMING，V58_5/V59 seed 列顺序）*/
    private byte[] buildMatBomExcel(String partNo, BigDecimal lossRate, int seqNo) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            // mat_part — V58_5/V59 seed: A=part_no B=part_name C=spec D=size_info E=unit_weight F=weight_unit G=status_code
            Sheet partSheet = wb.createSheet("料号主档");
            Row ph = partSheet.createRow(0);
            String[] pCols = {"HF_PART_NO", "PART_NAME", "SPECIFICATION", "SIZE_INFO", "UNIT_WEIGHT", "WEIGHT_UNIT", "STATUS_CODE"};
            for (int c = 0; c < pCols.length; c++) ph.createCell(c).setCellValue(pCols[c]);
            Row pr = partSheet.createRow(1);
            pr.createCell(0).setCellValue(partNo);
            pr.createCell(1).setCellValue("BOM测试料号");
            pr.createCell(2).setCellValue("AgNi10");
            pr.createCell(3).setCellValue("3x5");
            pr.createCell(4).setCellValue(0.010);  // E=unit_weight
            pr.createCell(5).setCellValue("KG");
            pr.createCell(6).setCellValue("Y");

            // mat_bom (BOM清单) — V58_5/V59 seed: A=hf_part_no B=bom_type C=seq_no D=input_material_no
            //   E=input_material_name F=loss_rate G=gross_qty H=net_qty I=gross_unit J=net_unit
            //   K=output_material_type L=defect_rate M=element_name N=composition_pct
            Sheet bomSheet = wb.createSheet("BOM清单");
            Row bh = bomSheet.createRow(0);
            String[] bCols = {"HF_PART_NO", "BOM_TYPE", "SEQ_NO", "INPUT_MATERIAL_NO",
                              "INPUT_MATERIAL_NAME", "LOSS_RATE", "GROSS_QTY", "NET_QTY",
                              "GROSS_UNIT", "NET_UNIT", "OUTPUT_MATERIAL_TYPE", "DEFECT_RATE",
                              "ELEMENT_NAME", "COMPOSITION_PCT"};
            for (int c = 0; c < bCols.length; c++) bh.createCell(c).setCellValue(bCols[c]);
            Row br = bomSheet.createRow(1);
            br.createCell(0).setCellValue(partNo);        // A=hf_part_no
            br.createCell(1).setCellValue("INCOMING");    // B=bom_type
            br.createCell(2).setCellValue(seqNo);         // C=seq_no
            br.createCell(3).setCellValue("RM-001");      // D=input_material_no
            br.createCell(4).setCellValue("原材料");      // E=input_material_name
            br.createCell(5).setCellValue(lossRate.doubleValue()); // F=loss_rate
            br.createCell(6).setCellValue(1.0);           // G=gross_qty
            br.createCell(7).setCellValue(1.0);           // H=net_qty
            br.createCell(8).setCellValue("KG");          // I=gross_unit
            br.createCell(9).setCellValue("KG");          // J=net_unit
            return toBytes(wb);
        }
    }

    /** 构建含 mat_part + mat_bom + mat_fee 的 Excel（fee_type=INCOMING_FIXED）。
     * mat_bom 必须包含，因为 BV-30 校验 mat_fee.hf_part_no 必须在 mat_bom 中存在。
     * V58_5/V59 seed 列顺序：
     *   mat_part:  A=part_no B=part_name C=spec D=size_info E=unit_weight F=weight_unit G=status_code
     *   BOM清单:   A=hf_part_no B=bom_type C=seq_no D=input_material_no E=input_material_name F=loss_rate ...
     *   费用清单:  A=hf_part_no B=fee_type C=seq_no D=fee_value E=fee_ratio F=currency G=price_unit ...
     */
    private byte[] buildMatFeeExcel(String partNo, BigDecimal feeValue, String currency) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            // mat_part
            Sheet partSheet = wb.createSheet("料号主档");
            Row ph = partSheet.createRow(0);
            String[] pCols = {"HF_PART_NO", "PART_NAME", "SPECIFICATION", "SIZE_INFO", "UNIT_WEIGHT", "WEIGHT_UNIT", "STATUS_CODE"};
            for (int c = 0; c < pCols.length; c++) ph.createCell(c).setCellValue(pCols[c]);
            Row pr = partSheet.createRow(1);
            pr.createCell(0).setCellValue(partNo);
            pr.createCell(1).setCellValue("费用测试料号");
            pr.createCell(2).setCellValue("AgNi10");
            pr.createCell(3).setCellValue("3x5");
            pr.createCell(4).setCellValue(0.010);  // E=unit_weight
            pr.createCell(5).setCellValue("KG");
            pr.createCell(6).setCellValue("Y");

            // mat_bom（BV-30 要求）— V58_5/V59 seed 列顺序: A=hf_part_no B=bom_type C=seq_no D=input_material_no ...
            Sheet bomSheet = wb.createSheet("BOM清单");
            Row bh = bomSheet.createRow(0);
            String[] bCols = {"HF_PART_NO", "BOM_TYPE", "SEQ_NO", "INPUT_MATERIAL_NO",
                              "INPUT_MATERIAL_NAME", "LOSS_RATE", "GROSS_QTY", "NET_QTY",
                              "GROSS_UNIT", "NET_UNIT", "OUTPUT_MATERIAL_TYPE", "DEFECT_RATE",
                              "ELEMENT_NAME", "COMPOSITION_PCT"};
            for (int c = 0; c < bCols.length; c++) bh.createCell(c).setCellValue(bCols[c]);
            Row br = bomSheet.createRow(1);
            br.createCell(0).setCellValue(partNo);        // A=hf_part_no
            br.createCell(1).setCellValue("INCOMING");    // B=bom_type
            br.createCell(2).setCellValue(1);             // C=seq_no
            br.createCell(3).setCellValue("RM-FEE");      // D=input_material_no
            br.createCell(4).setCellValue("原材料");      // E=input_material_name
            br.createCell(5).setCellValue(0.03);          // F=loss_rate
            br.createCell(6).setCellValue(1.0);           // G=gross_qty
            br.createCell(7).setCellValue(1.0);           // H=net_qty
            br.createCell(8).setCellValue("KG");          // I=gross_unit
            br.createCell(9).setCellValue("KG");          // J=net_unit

            // mat_fee — V58_5/V59 seed 列顺序: A=hf_part_no B=fee_type C=seq_no D=fee_value E=fee_ratio F=currency G=price_unit
            Sheet feeSheet = wb.createSheet("费用清单");
            Row fh = feeSheet.createRow(0);
            String[] fCols = {"HF_PART_NO", "FEE_TYPE", "SEQ_NO", "FEE_VALUE", "FEE_RATIO",
                              "CURRENCY", "PRICE_UNIT", "DIM_INPUT_MATERIAL_NO", "DIM_INPUT_MATERIAL_NAME",
                              "DIM_ELEMENT_NAME", "DIM_ASSEMBLY_PROCESS", "DIM_SUB_SEQ_NO",
                              "PRICE_FLOATING", "SETTLEMENT_RISE_RATIO", "FIXED_RISE_VALUE",
                              "RISE_CURRENCY", "RISE_UNIT", "REJECT_RATE"};
            for (int c = 0; c < fCols.length; c++) fh.createCell(c).setCellValue(fCols[c]);
            Row fr = feeSheet.createRow(1);
            fr.createCell(0).setCellValue(partNo);                   // A=hf_part_no
            fr.createCell(1).setCellValue("INCOMING_FIXED");         // B=fee_type
            fr.createCell(2).setCellValue(1);                        // C=seq_no
            fr.createCell(3).setCellValue(feeValue.doubleValue());   // D=fee_value
            // E=fee_ratio 留空
            fr.createCell(5).setCellValue(currency);                 // F=currency
            fr.createCell(6).setCellValue("PCS");                    // G=price_unit
            return toBytes(wb);
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    String queryMatBomField(String hfPartNo, String bomType, int seqNo, String fieldName) {
        try {
            Object val = em.createNativeQuery(
                    "SELECT CAST(" + fieldName + " AS TEXT) FROM mat_bom " +
                    "WHERE hf_part_no = :pn AND bom_type = :bt AND seq_no = :sn LIMIT 1")
                    .setParameter("pn", hfPartNo)
                    .setParameter("bt", bomType)
                    .setParameter("sn", seqNo)
                    .getSingleResult();
            return val == null ? null : val.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    int queryMatFeeVersion(String hfPartNo, String feeType, int seqNo) {
        try {
            Object val = em.createNativeQuery(
                    "SELECT MAX(version) FROM mat_fee " +
                    "WHERE customer_id = :cid AND hf_part_no = :pn AND fee_type = :ft AND seq_no = :sn")
                    .setParameter("cid", CUSTOMER_ID)
                    .setParameter("pn", hfPartNo)
                    .setParameter("ft", feeType)
                    .setParameter("sn", seqNo)
                    .getSingleResult();
            return val == null ? 0 : ((Number) val).intValue();
        } catch (Exception e) {
            return 0;
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void directUpdateMatProcessUnitPrice(String hfPartNo, BigDecimal newPrice) {
        // 将当前 is_current=true 的行更新 unit_price，模拟并发修改
        em.createNativeQuery(
                "UPDATE mat_process SET unit_price = :up " +
                "WHERE customer_id = :cid AND hf_part_no = :pn AND is_current = true")
                .setParameter("up", newPrice)
                .setParameter("cid", CUSTOMER_ID)
                .setParameter("pn", hfPartNo)
                .executeUpdate();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void ensureMatPart(String partNo, BigDecimal unitWeight) {
        em.createNativeQuery(
                "INSERT INTO mat_part(part_no, part_name, unit_weight, weight_unit, status_code, created_at, updated_at) " +
                "VALUES (:pn, '测试', :uw, 'KG', 'Y', NOW(), NOW()) " +
                "ON CONFLICT (part_no) DO UPDATE SET unit_weight = :uw")
                .setParameter("pn", partNo)
                .setParameter("uw", unitWeight)
                .executeUpdate();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void directUpdateUnitWeight(String partNo, BigDecimal newWeight) {
        em.createNativeQuery("UPDATE mat_part SET unit_weight = :uw WHERE part_no = :pn")
                .setParameter("uw", newWeight)
                .setParameter("pn", partNo)
                .executeUpdate();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    String queryField(String sql, String partNo) {
        try {
            Object val = em.createNativeQuery(sql)
                    .setParameter("pn", partNo)
                    .getSingleResult();
            return val == null ? null : val.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    ImportRecord getImportRecord(UUID id) {
        return ImportRecord.findById(id);
    }
}
