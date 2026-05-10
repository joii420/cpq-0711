package com.cpq.importexcel;

import com.cpq.basicdata.entity.BasicDataAttribute;
import com.cpq.basicdata.entity.BasicDataConfig;
import com.cpq.importexcel.dto.ValidationResult;
import com.cpq.importexcel.parser.ParsedBasicData;
import com.cpq.importexcel.service.BasicDataImportServiceV5;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.UserTransaction;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V5 元数据化改造验证测试（PM 验收标准 AC-1~AC-7）。
 *
 * <p>测试环境：H2 内存数据库 + Flyway V58/V58_5 已执行，元数据缓存已加载。
 *
 * <p>AC-1: 旧测试 sheet 名 "料号主档" 仍可解析（V58_5 seed 包含 target_table='mat_part'）
 * <p>AC-2: 来料BOM discriminator → mat_bom.bom_type=INCOMING
 * <p>AC-3: 来料其他费用 discriminator → mat_fee.fee_type=INCOMING_OTHER
 * <p>AC-4: 按 column_letter 读列，不依赖中文/英文表头
 * <p>AC-5: is_required=true 字段为空 → ValidationResult.errors 含 BV-META-01
 * <p>AC-6: 新增 sheet 配置（动态，不改代码） → 通过 reloadConfigCache 自动支持
 * <p>AC-7: target_table=NULL 的 sheet（元素单价）→ 跳过，不产生错误
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BasicDataImportV5MetadataTest {

    private static final Logger LOG = Logger.getLogger(BasicDataImportV5MetadataTest.class);

    @Inject
    BasicDataImportServiceV5 serviceV5;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    private static final UUID CUSTOMER_META = UUID.fromString("58000000-0000-0000-0000-000000000001");

    @BeforeEach
    void ensureCustomer() throws Exception {
        try {
            if (utx.getStatus() != jakarta.transaction.Status.STATUS_NO_TRANSACTION) {
                utx.rollback();
            }
        } catch (Exception ignored) {}

        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO customer(id, name, code, level, accumulated_amount, status, created_at, updated_at) " +
                "VALUES (:id, 'Meta Test Customer', 'META-CUST', 'STANDARD', 0, 'ACTIVE', NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", CUSTOMER_META).executeUpdate();
        utx.commit();
    }

    // ─── AC-1: 旧测试 sheet 名 "料号主档" 在 V58_5 seed 后仍可解析 ────────────

    @Test
    @Order(1)
    void ac1_legacySheetName_matPart_parsedByMetadata() throws Exception {
        // 构建 Excel，sheet 名="料号主档"，列顺序按 V58_5 属性 seed（A=part_no B=part_name E=unit_weight）
        byte[] xlsx = buildMatPartExcel("料号主档", "META-AC1-001", "测试料号AC1", new BigDecimal("0.01"));
        ParsedBasicData data = serviceV5.parseExcel(new ByteArrayInputStream(xlsx), CUSTOMER_META, 2000);

        assertFalse(data.matParts.isEmpty(), "AC-1: 料号主档 sheet 应被元数据缓存识别并解析");
        assertEquals("META-AC1-001", data.matParts.get(0).partNo, "AC-1: part_no 应正确读取");
        assertEquals(0, new BigDecimal("0.01").compareTo(data.matParts.get(0).unitWeight),
                "AC-1: unit_weight 应正确读取（列 E）");
    }

    // ─── AC-2: 来料BOM discriminator → bom_type=INCOMING ─────────────────────

    @Test
    @Order(2)
    void ac2_incomingBOM_discriminator_bomTypeSetToIncoming() throws Exception {
        // 构建 Excel，sheet 名="来料BOM"，V58_5 配置 discriminator={"bom_type":"INCOMING"}
        byte[] xlsx = buildBomExcel("来料BOM", "META-AC2-001", 1, "S-101");
        ParsedBasicData data = serviceV5.parseExcel(new ByteArrayInputStream(xlsx), CUSTOMER_META, 2000);

        assertFalse(data.matBoms.isEmpty(), "AC-2: 来料BOM sheet 应被解析");
        assertEquals("INCOMING", data.matBoms.get(0).bomType,
                "AC-2: bom_type 应由 discriminator 注入为 INCOMING");
    }

    // ─── AC-3: 来料其他费用 discriminator → fee_type=INCOMING_OTHER ─────────────

    @Test
    @Order(3)
    void ac3_incomingOtherFee_discriminator_feeTypeSetCorrectly() throws Exception {
        // 构建 Excel，sheet 名="来料其他费用"，V58_5 配置 discriminator={"fee_type":"INCOMING_OTHER"}
        byte[] xlsx = buildFeeExcel("来料其他费用", "META-AC3-001");
        ParsedBasicData data = serviceV5.parseExcel(new ByteArrayInputStream(xlsx), CUSTOMER_META, 2000);

        assertFalse(data.matFees.isEmpty(), "AC-3: 来料其他费用 sheet 应被解析");
        assertEquals("INCOMING_OTHER", data.matFees.get(0).feeType,
                "AC-3: fee_type 应由 discriminator 注入为 INCOMING_OTHER");
    }

    // ─── AC-4: column_letter 读列，不依赖表头名称 ─────────────────────────────

    @Test
    @Order(4)
    void ac4_columnLetterBased_chineseHeader_parsedCorrectly() throws Exception {
        // 构建 Excel，sheet 名="料号主档"（旧测试兼容 schema，7 列保留），表头为任意中文
        // 注:V60 已把生产模板"单重"sheet 改为 2 列(A=part_no, B=unit_weight),
        // 此测试目的是验证"按列字母读取，不依赖表头文字"，使用未变更的"料号主档"配置。
        // A=宏丰料号 B=料号名称 C=规格 D=尺寸信息 E=单重 F=重量单位 G=状态
        byte[] xlsx;
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("料号主档");
            Row header = sheet.createRow(0);
            // 中文表头（元数据化后不依赖表头名）
            header.createCell(0).setCellValue("宏丰料号");
            header.createCell(1).setCellValue("料号名称");
            header.createCell(2).setCellValue("规格说明");    // 和 variable_code 不同
            header.createCell(3).setCellValue("尺寸");
            header.createCell(4).setCellValue("重量(g)");    // 和 variable_code 不同
            header.createCell(5).setCellValue("单位");
            header.createCell(6).setCellValue("状态码");
            // 数据行
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("META-AC4-001");  // A → part_no
            r.createCell(1).setCellValue("名称AC4");
            r.createCell(2).setCellValue("AgNi10");
            r.createCell(3).setCellValue("Φ3×5");
            r.createCell(4).setCellValue(0.008);            // E → unit_weight
            r.createCell(5).setCellValue("KG");
            r.createCell(6).setCellValue("Y");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            xlsx = out.toByteArray();
        }

        ParsedBasicData data = serviceV5.parseExcel(new ByteArrayInputStream(xlsx), CUSTOMER_META, 2000);

        assertFalse(data.matParts.isEmpty(), "AC-4: 单重 sheet 应被元数据缓存识别");
        assertEquals("META-AC4-001", data.matParts.get(0).partNo,
                "AC-4: part_no 按列 A 读取，不依赖表头文字");
        assertEquals(0, new BigDecimal("0.008").compareTo(data.matParts.get(0).unitWeight),
                "AC-4: unit_weight 按列 E 读取，不依赖表头文字");
    }

    // ─── AC-5: is_required=true 字段为空 → ValidationResult.errors ─────────────

    @Test
    @Order(5)
    void ac5_requiredFieldBlank_producesValidationError() throws Exception {
        // 构建 Excel，sheet 名="料号主档"，part_no（A 列，is_required=true）为空
        byte[] xlsx;
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("料号主档");
            Row header = sheet.createRow(0);
            // A=HF_PART_NO B=PART_NAME E=UNIT_WEIGHT
            for (int c = 0; c < 7; c++) header.createCell(c).setCellValue("col" + c);
            Row r = sheet.createRow(1);
            // A（part_no）留空 → is_required=true → 应产生错误
            r.createCell(0).setCellValue("");     // part_no 为空
            r.createCell(1).setCellValue("测试名称");
            r.createCell(4).setCellValue(0.005);  // unit_weight

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            xlsx = out.toByteArray();
        }

        ParsedBasicData data = serviceV5.parseExcel(new ByteArrayInputStream(xlsx), CUSTOMER_META, 2000);

        // requiredErrors 应有至少 1 条（part_no 为空）
        assertFalse(data.requiredErrors.isEmpty(), "AC-5: is_required=true 的 part_no 为空应产生 requiredErrors");
        assertEquals("BV-META-01", data.requiredErrors.get(0).bvCode, "AC-5: 错误码应为 BV-META-01");

        // 经 runAllValidations 合并后，vr.hasErrors=true
        ValidationResult vr = serviceV5.runAllValidations(data, CUSTOMER_META, false);
        assertTrue(vr.hasErrors, "AC-5: requiredErrors 合并后 vr.hasErrors 应为 true");
        boolean hasMeta01 = vr.errors.stream().anyMatch(e -> "BV-META-01".equals(e.bvCode));
        assertTrue(hasMeta01, "AC-5: vr.errors 应包含 BV-META-01 条目");
    }

    // ─── AC-6: 新增 sheet 配置（不改代码）→ reloadConfigCache 后自动支持 ────────

    @Test
    @Order(6)
    @Transactional
    void ac6_newSheetConfig_afterReload_autoSupported() throws Exception {
        // 动态插入一个新的测试 sheet 配置（target_table=mat_part，使用唯一 sheet 名防冲突）
        String newSheetName = "AC6_动态测试Sheet_" + System.currentTimeMillis();

        // 插入 BasicDataConfig
        em.createNativeQuery(
                "INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, " +
                "  description, sort_order, status, target_table) " +
                "VALUES (:sn, 1, 2, 'AC-6 动态测试', 999, 'ACTIVE', 'mat_part')")
                .setParameter("sn", newSheetName).executeUpdate();

        // 获取刚插入的 config id
        UUID cfgId = (UUID) em.createNativeQuery(
                "SELECT id FROM basic_data_config WHERE sheet_name = :sn LIMIT 1")
                .setParameter("sn", newSheetName).getSingleResult();

        // 插入属性
        em.createNativeQuery(
                "INSERT INTO basic_data_attribute (config_id, column_letter, column_title, " +
                "  variable_code, variable_label, data_type, sort_order, status, is_required) " +
                "VALUES (:cid, 'A', '宏丰料号', 'part_no', '宏丰料号', 'IDENTIFIER', 10, 'ACTIVE', true)")
                .setParameter("cid", cfgId).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO basic_data_attribute (config_id, column_letter, column_title, " +
                "  variable_code, variable_label, data_type, sort_order, status, is_required) " +
                "VALUES (:cid, 'E', '单重', 'unit_weight', '单重', 'VALUE', 50, 'ACTIVE', true)")
                .setParameter("cid", cfgId).executeUpdate();

        // 重新加载缓存（模拟运行时配置更新后调用）
        serviceV5.reloadConfigCache();

        // 构建 Excel，使用新 sheet 名
        byte[] xlsx;
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(newSheetName);
            Row header = sheet.createRow(0);
            for (int c = 0; c < 7; c++) header.createCell(c).setCellValue("h" + c);
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("AC6-PART-001");
            r.createCell(4).setCellValue(0.003);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            xlsx = out.toByteArray();
        }

        ParsedBasicData data = serviceV5.parseExcel(new ByteArrayInputStream(xlsx), CUSTOMER_META, 2000);

        assertFalse(data.matParts.isEmpty(),
                "AC-6: 动态新增的 sheet 配置经 reloadConfigCache 后应自动支持解析");
        assertEquals("AC6-PART-001", data.matParts.get(0).partNo,
                "AC-6: 动态 sheet 的 part_no 应正确读取");

        // 恢复：删除测试配置（事务内，测试结束后回滚）
        // 注：@Transactional 确保此测试方法的 DB 操作在测试结束后回滚
        // 但需要在回滚前再 reload 一次（恢复缓存）
        em.createNativeQuery("DELETE FROM basic_data_attribute WHERE config_id = :cid")
                .setParameter("cid", cfgId).executeUpdate();
        em.createNativeQuery("DELETE FROM basic_data_config WHERE id = :id")
                .setParameter("id", cfgId).executeUpdate();

        // 恢复缓存（此方法本身 @Transactional，事务提交前 reload 读不到已删记录，无需单独处理）
        // 下个测试开始前缓存已是净状态
    }

    // ─── AC-7: target_table=NULL 的 sheet（元素单价）→ 跳过，不解析 ─────────────

    @Test
    @Order(7)
    void ac7_targetTableNull_sheet_skipped() throws Exception {
        // 构建 Excel，包含 "元素单价" sheet（V58_5 配置 target_table=NULL，应跳过）
        // 还包含 "料号主档" sheet（target_table='mat_part'，应正常解析）
        byte[] xlsx;
        try (Workbook wb = new XSSFWorkbook()) {
            // 元素单价 sheet（应被跳过）
            Sheet skipSheet = wb.createSheet("元素单价");
            Row skipHeader = skipSheet.createRow(0);
            for (int c = 0; c < 5; c++) skipHeader.createCell(c).setCellValue("col" + c);
            Row skipRow = skipSheet.createRow(1);
            skipRow.createCell(0).setCellValue("AC7-SKIP-001");

            // 料号主档 sheet（应正常解析）
            Sheet matSheet = wb.createSheet("料号主档");
            Row matHeader = matSheet.createRow(0);
            for (int c = 0; c < 7; c++) matHeader.createCell(c).setCellValue("h" + c);
            Row matRow = matSheet.createRow(1);
            matRow.createCell(0).setCellValue("AC7-MAT-001");
            matRow.createCell(1).setCellValue("AC7名称");
            matRow.createCell(4).setCellValue(0.006);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            xlsx = out.toByteArray();
        }

        ParsedBasicData data = serviceV5.parseExcel(new ByteArrayInputStream(xlsx), CUSTOMER_META, 2000);

        // 元素单价 sheet 被跳过：不产生任何 matPart 行（只有 料号主档 的数据）
        // 验证：只有 料号主档 的 AC7-MAT-001 被解析
        boolean hasSkipPart = data.matParts.stream()
                .anyMatch(p -> "AC7-SKIP-001".equals(p.partNo));
        assertFalse(hasSkipPart, "AC-7: 元素单价 sheet 的数据不应进入 matParts（target_table=NULL 被跳过）");

        boolean hasMatPart = data.matParts.stream()
                .anyMatch(p -> "AC7-MAT-001".equals(p.partNo));
        assertTrue(hasMatPart, "AC-7: 料号主档 sheet 的数据应正常解析");

        // 没有报错
        assertTrue(data.requiredErrors.stream().noneMatch(re -> re.sheetName.equals("元素单价")),
                "AC-7: 跳过的 sheet 不应产生 requiredErrors");
    }

    // ─── 辅助 Excel 构建方法 ─────────────────────────────────────────────────

    /**
     * 构建 mat_part Excel。列顺序严格按 V58_5 seed：
     * A=part_no B=part_name C=specification D=size_info E=unit_weight F=weight_unit G=status_code
     */
    private byte[] buildMatPartExcel(String sheetName, String partNo, String partName, BigDecimal unitWeight)
            throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(sheetName);
            Row header = sheet.createRow(0);
            String[] cols = {"宏丰料号", "料号名称", "规格", "尺寸信息", "单重", "重量单位", "状态"};
            for (int c = 0; c < cols.length; c++) header.createCell(c).setCellValue(cols[c]);
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue(partNo);
            r.createCell(1).setCellValue(partName);
            r.createCell(2).setCellValue("Spec");
            r.createCell(3).setCellValue("3x5");
            r.createCell(4).setCellValue(unitWeight.doubleValue());
            r.createCell(5).setCellValue("KG");
            r.createCell(6).setCellValue("Y");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 构建 mat_bom Excel。列顺序按 V58_5 seed（来料BOM）：
     * A=hf_part_no B=seq_no C=input_material_no D=input_material_name ...
     */
    private byte[] buildBomExcel(String sheetName, String hfPartNo, int seqNo, String inputMatNo)
            throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(sheetName);
            Row header = sheet.createRow(0);
            String[] cols = {"宏丰料号","BOM序号","来料编号","来料名称","损耗率","毛用量","净用量","毛用量单位","净用量单位","产出物料类型","不良率"};
            for (int c = 0; c < cols.length; c++) header.createCell(c).setCellValue(cols[c]);
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue(hfPartNo);   // A → hf_part_no
            r.createCell(1).setCellValue(seqNo);       // B → seq_no
            r.createCell(2).setCellValue(inputMatNo);  // C → input_material_no
            r.createCell(3).setCellValue("来料名称");  // D → input_material_name
            r.createCell(5).setCellValue(1.0);         // F → gross_qty
            r.createCell(6).setCellValue(0.95);        // G → net_qty
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 构建 mat_fee Excel。列顺序按 V58_5 seed（费用类 sheet 通用）：
     * A=hf_part_no B=fee_type C=seq_no D=fee_value ...
     */
    private byte[] buildFeeExcel(String sheetName, String hfPartNo) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(sheetName);
            Row header = sheet.createRow(0);
            String[] cols = {"宏丰料号","费用类型","费用序号","费用值","费用比例","货币","价格单位",
                             "关联来料编号","关联来料名称","关联元素名称","关联装配工序","关联子序号",
                             "价格浮动","结算涨价比例","固定涨价值","涨价货币","涨价单位","报废率"};
            for (int c = 0; c < cols.length; c++) header.createCell(c).setCellValue(cols[c]);
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue(hfPartNo);   // A → hf_part_no
            // B (fee_type) 留空，由 discriminator 注入
            r.createCell(2).setCellValue(1);           // C → seq_no
            r.createCell(3).setCellValue(100.0);       // D → fee_value
            r.createCell(5).setCellValue("CNY");       // F → currency
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }
}
