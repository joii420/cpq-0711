package com.cpq.importexcel;

import com.cpq.importexcel.dto.ValidationResult;
import com.cpq.importexcel.parser.ParsedBasicData;
import com.cpq.importexcel.service.BasicDataImportServiceV5;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BV-01~BV-32 业务校验单元测试（不依赖数据库查询，queryDb=false）。
 * 覆盖 v5.1 §2.5 的业务校验规则清单。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BasicDataImportV5ValidationTest {

    @Inject
    BasicDataImportServiceV5 serviceV5;

    // 阈值（与 system_config seed 数据一致）
    private static final BigDecimal COMPOSITION_TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal LOSS_RATE_MAX = new BigDecimal("0.5");
    private static final BigDecimal DEFECT_RATE_MAX = new BigDecimal("0.3");
    private static final BigDecimal ASSEMBLY_REJECT_MAX = new BigDecimal("0.3");
    private static final BigDecimal PRICE_RISE_MIN = new BigDecimal("-0.5");
    private static final BigDecimal PRICE_RISE_MAX = new BigDecimal("1.0");

    private static final UUID CUSTOMER_A = UUID.fromString("11000000-0000-0000-0000-000000000001");

    // ─── BV-01: 元素 BOM 含量合计 = 100% ───────────────────────────────────

    @Test
    @Order(1)
    void bv01_elementBomCompositionSum_withinTolerance_noWarning() {
        ParsedBasicData data = new ParsedBasicData();
        // sum = 100% (Ag 90 + Ni 10)
        data.matBoms.add(makeBom("ELEMENT", "P001", 1, null, null, null, null, "Ag", new BigDecimal("90")));
        data.matBoms.add(makeBom("ELEMENT", "P001", 2, null, null, null, null, "Ni", new BigDecimal("10")));

        ValidationResult vr = serviceV5.validateBasicLayer(data, COMPOSITION_TOLERANCE,
                LOSS_RATE_MAX, DEFECT_RATE_MAX);

        assertFalse(vr.hasWarnings, "BV-01: 100% 合计在容差内不应产生警告");
    }

    @Test
    @Order(2)
    void bv01_elementBomCompositionSum_outOfTolerance_warning() {
        ParsedBasicData data = new ParsedBasicData();
        // sum = 80% (严重不足)
        data.matBoms.add(makeBom("ELEMENT", "P002", 1, null, null, null, null, "Ag", new BigDecimal("80")));

        ValidationResult vr = serviceV5.validateBasicLayer(data, COMPOSITION_TOLERANCE,
                LOSS_RATE_MAX, DEFECT_RATE_MAX);

        assertTrue(vr.hasWarnings, "BV-01: 80% 合计超出容差应产生警告");
        assertEquals("BV-01", vr.warnings.get(0).bvCode);
    }

    // ─── BV-02: 单重 > 0 ───────────────────────────────────────────────────

    @Test
    @Order(3)
    void bv02_unitWeightZero_isBlockingError() {
        ParsedBasicData data = new ParsedBasicData();
        data.matParts.add(makePart("P003", BigDecimal.ZERO));

        ValidationResult vr = serviceV5.validateBasicLayer(data, COMPOSITION_TOLERANCE,
                LOSS_RATE_MAX, DEFECT_RATE_MAX);

        assertTrue(vr.hasErrors, "BV-02: 单重=0 应产生阻塞错误");
        assertEquals("BV-02", vr.errors.get(0).bvCode);
    }

    @Test
    @Order(4)
    void bv02_unitWeightNull_isBlockingError() {
        ParsedBasicData data = new ParsedBasicData();
        data.matParts.add(makePart("P004", null));

        ValidationResult vr = serviceV5.validateBasicLayer(data, COMPOSITION_TOLERANCE,
                LOSS_RATE_MAX, DEFECT_RATE_MAX);

        assertTrue(vr.hasErrors, "BV-02: 单重=null 应产生阻塞错误");
        assertEquals("BV-02", vr.errors.get(0).bvCode);
    }

    @Test
    @Order(5)
    void bv02_unitWeightPositive_noError() {
        ParsedBasicData data = new ParsedBasicData();
        data.matParts.add(makePart("P005", new BigDecimal("0.005")));

        ValidationResult vr = serviceV5.validateBasicLayer(data, COMPOSITION_TOLERANCE,
                LOSS_RATE_MAX, DEFECT_RATE_MAX);

        assertFalse(vr.hasErrors, "BV-02: 单重=0.005 不应产生错误");
    }

    // ─── BV-03: 来料 BOM 损耗率范围 ────────────────────────────────────────

    @Test
    @Order(6)
    void bv03_lossRateExceedsMax_warning() {
        ParsedBasicData data = new ParsedBasicData();
        // loss_rate = 0.6 > 0.5
        data.matBoms.add(makeBom("INCOMING", "P006", 1, new BigDecimal("0.6"), null,
                new BigDecimal("1.0"), new BigDecimal("0.94"), null, null));

        ValidationResult vr = serviceV5.validateBasicLayer(data, COMPOSITION_TOLERANCE,
                LOSS_RATE_MAX, DEFECT_RATE_MAX);

        assertTrue(vr.hasWarnings, "BV-03: 损耗率超上限应产生警告");
        assertEquals("BV-03", vr.warnings.get(0).bvCode);
    }

    @Test
    @Order(7)
    void bv03_lossRateNegative_warning() {
        ParsedBasicData data = new ParsedBasicData();
        data.matBoms.add(makeBom("INCOMING", "P007", 1, new BigDecimal("-0.01"), null,
                new BigDecimal("1.0"), new BigDecimal("1.0"), null, null));

        ValidationResult vr = serviceV5.validateBasicLayer(data, COMPOSITION_TOLERANCE,
                LOSS_RATE_MAX, DEFECT_RATE_MAX);

        assertTrue(vr.hasWarnings, "BV-03: 损耗率负数应产生警告");
    }

    // ─── BV-04: 净用量 ≤ 毛用量 ────────────────────────────────────────────

    @Test
    @Order(8)
    void bv04_netQtyGreaterThanGrossQty_isBlockingError() {
        ParsedBasicData data = new ParsedBasicData();
        // net=1.2 > gross=1.0
        data.matBoms.add(makeBom("INCOMING", "P008", 1, new BigDecimal("0.01"), null,
                new BigDecimal("1.0"), new BigDecimal("1.2"), null, null));

        ValidationResult vr = serviceV5.validateBasicLayer(data, COMPOSITION_TOLERANCE,
                LOSS_RATE_MAX, DEFECT_RATE_MAX);

        assertTrue(vr.hasErrors, "BV-04: 净用量>毛用量应产生阻塞错误");
        assertEquals("BV-04", vr.errors.get(0).bvCode);
    }

    @Test
    @Order(9)
    void bv04_netQtyEqualGrossQty_noError() {
        ParsedBasicData data = new ParsedBasicData();
        data.matBoms.add(makeBom("INCOMING", "P009", 1, null, null,
                new BigDecimal("1.0"), new BigDecimal("1.0"), null, null));

        ValidationResult vr = serviceV5.validateBasicLayer(data, COMPOSITION_TOLERANCE,
                LOSS_RATE_MAX, DEFECT_RATE_MAX);

        assertFalse(vr.hasErrors, "BV-04: 净用量=毛用量不应报错");
    }

    // ─── BV-05: 镀层厚度 > 0 ───────────────────────────────────────────────

    @Test
    @Order(10)
    void bv05_coatingThicknessZero_isBlockingError() {
        ParsedBasicData data = new ParsedBasicData();
        data.platingPlans.add(makePlatingPlan("PP-001", "V1", 1, BigDecimal.ZERO));

        ValidationResult vr = serviceV5.validateBasicLayer(data, COMPOSITION_TOLERANCE,
                LOSS_RATE_MAX, DEFECT_RATE_MAX);

        assertTrue(vr.hasErrors, "BV-05: 镀层厚度=0 应产生阻塞错误");
        assertEquals("BV-05", vr.errors.get(0).bvCode);
    }

    // ─── BV-06: 客户料号映射唯一 ───────────────────────────────────────────

    @Test
    @Order(11)
    void bv06_duplicateMappingKey_isBlockingError() {
        ParsedBasicData data = new ParsedBasicData();
        data.mappings.add(makeMapping(CUSTOMER_A, "C-PRD-001", "P001"));
        data.mappings.add(makeMapping(CUSTOMER_A, "C-PRD-001", "P002"));  // duplicate

        ValidationResult vr = serviceV5.validateBasicLayer(data, COMPOSITION_TOLERANCE,
                LOSS_RATE_MAX, DEFECT_RATE_MAX);

        assertTrue(vr.hasErrors, "BV-06: 客户料号重复应产生阻塞错误");
        assertEquals("BV-06", vr.errors.get(0).bvCode);
    }

    @Test
    @Order(12)
    void bv06_uniqueMappingKeys_noError() {
        ParsedBasicData data = new ParsedBasicData();
        data.mappings.add(makeMapping(CUSTOMER_A, "C-PRD-001", "P001"));
        data.mappings.add(makeMapping(CUSTOMER_A, "C-PRD-002", "P002"));

        ValidationResult vr = serviceV5.validateBasicLayer(data, COMPOSITION_TOLERANCE,
                LOSS_RATE_MAX, DEFECT_RATE_MAX);

        assertFalse(vr.hasErrors, "BV-06: 不同客户料号不应报错");
    }

    // ─── BV-12: 组成件 BOM 序号唯一 ───────────────────────────────────────

    @Test
    @Order(13)
    void bv12_duplicateProcessSeqNo_isBlockingError() {
        ParsedBasicData data = new ParsedBasicData();
        data.matProcesses.add(makeProcess(CUSTOMER_A, "P001", 1, 1));
        data.matProcesses.add(makeProcess(CUSTOMER_A, "P001", 1, 1));  // duplicate seq_no

        ValidationResult vr = serviceV5.validateCustomerLayer(data, ASSEMBLY_REJECT_MAX,
                PRICE_RISE_MIN, PRICE_RISE_MAX, java.util.Set.of(), java.util.Set.of(), false, CUSTOMER_A);

        assertTrue(vr.hasErrors, "BV-12: 重复 seq_no 应产生阻塞错误");
        assertEquals("BV-12", vr.errors.get(0).bvCode);
    }

    // ─── BV-13: 涨价比例范围 ───────────────────────────────────────────────

    @Test
    @Order(14)
    void bv13_settlementRiseRatioExceedsMax_warning() {
        ParsedBasicData data = new ParsedBasicData();
        data.matFees.add(makeMatFee(CUSTOMER_A, "P001", "INCOMING_FIXED", 1,
                new BigDecimal("1.5")));  // > 1.0

        ValidationResult vr = serviceV5.validateCustomerLayer(data, ASSEMBLY_REJECT_MAX,
                PRICE_RISE_MIN, PRICE_RISE_MAX, java.util.Set.of(), java.util.Set.of(), false, CUSTOMER_A);

        assertTrue(vr.hasWarnings, "BV-13: 涨价比例超上限应产生警告");
        assertEquals("BV-13", vr.warnings.get(0).bvCode);
    }

    @Test
    @Order(15)
    void bv13_settlementRiseRatioBelowMin_warning() {
        ParsedBasicData data = new ParsedBasicData();
        data.matFees.add(makeMatFee(CUSTOMER_A, "P001", "INCOMING_FIXED", 1,
                new BigDecimal("-0.6")));  // < -0.5

        ValidationResult vr = serviceV5.validateCustomerLayer(data, ASSEMBLY_REJECT_MAX,
                PRICE_RISE_MIN, PRICE_RISE_MAX, java.util.Set.of(), java.util.Set.of(), false, CUSTOMER_A);

        assertTrue(vr.hasWarnings, "BV-13: 涨价比例低于下限应产生警告");
    }

    // ─── BV-14: 组装报废率范围 ─────────────────────────────────────────────

    @Test
    @Order(16)
    void bv14_assemblyRejectRateExceedsMax_warning() {
        ParsedBasicData data = new ParsedBasicData();
        ParsedBasicData.MatFeeRow fee = new ParsedBasicData.MatFeeRow();
        fee.rowNum = 2;
        fee.customerId = CUSTOMER_A;
        fee.hfPartNo = "P001";
        fee.feeType = "ASSEMBLY_PROCESS";
        fee.seqNo = 1;
        fee.rejectRate = new BigDecimal("0.5");  // > 0.3

        data.matFees.add(fee);

        ValidationResult vr = serviceV5.validateCustomerLayer(data, ASSEMBLY_REJECT_MAX,
                PRICE_RISE_MIN, PRICE_RISE_MAX, java.util.Set.of(), java.util.Set.of(), false, CUSTOMER_A);

        assertTrue(vr.hasWarnings, "BV-14: 组装报废率超上限应产生警告");
        assertEquals("BV-14", vr.warnings.get(0).bvCode);
    }

    // ─── BV-15: 货币代码合法 ───────────────────────────────────────────────

    @Test
    @Order(17)
    void bv15_invalidCurrency_isBlockingError() {
        ParsedBasicData data = new ParsedBasicData();
        ParsedBasicData.MatProcessRow mp = new ParsedBasicData.MatProcessRow();
        mp.rowNum = 2;
        mp.customerId = CUSTOMER_A;
        mp.hfPartNo = "P001";
        mp.seqNo = 1;
        mp.currency = "XYZ";  // not in allowed list

        data.matProcesses.add(mp);

        ValidationResult vr = serviceV5.validateCustomerLayer(data, ASSEMBLY_REJECT_MAX,
                PRICE_RISE_MIN, PRICE_RISE_MAX,
                java.util.Set.of("USD", "CNY", "EUR"),
                java.util.Set.of(), false, CUSTOMER_A);

        assertTrue(vr.hasErrors, "BV-15: 非法货币代码应产生阻塞错误");
        assertEquals("BV-15", vr.errors.get(0).bvCode);
    }

    @Test
    @Order(18)
    void bv15_validCurrency_noError() {
        ParsedBasicData data = new ParsedBasicData();
        ParsedBasicData.MatProcessRow mp = new ParsedBasicData.MatProcessRow();
        mp.rowNum = 2;
        mp.customerId = CUSTOMER_A;
        mp.hfPartNo = "P001";
        mp.seqNo = 1;
        mp.currency = "CNY";

        data.matProcesses.add(mp);

        ValidationResult vr = serviceV5.validateCustomerLayer(data, ASSEMBLY_REJECT_MAX,
                PRICE_RISE_MIN, PRICE_RISE_MAX,
                java.util.Set.of("USD", "CNY", "EUR"),
                java.util.Set.of(), false, CUSTOMER_A);

        assertFalse(vr.hasErrors, "BV-15: CNY 在允许列表中不应报错");
    }

    // ─── BV-30: 费用引用的料号必须在基础料号主档(mat_part)登记 ──────────────────────────────────
    // 规则于「Step 2 字典/规则放宽」修订:旧规则要求 mat_bom 存在,
    // 但成品(组装件)与纯电镀件不一定在 mat_bom 出现,改为以 mat_part 为准。

    @Test
    @Order(19)
    void bv30_feePartNoNotInMatPart_isBlockingError() {
        ParsedBasicData data = new ParsedBasicData();
        // 没有 mat_part 主档行,但有 mat_fee 行
        ParsedBasicData.MatFeeRow fee = new ParsedBasicData.MatFeeRow();
        fee.rowNum = 2;
        fee.customerId = CUSTOMER_A;
        fee.hfPartNo = "P-MISSING";
        fee.feeType = "INCOMING_FIXED";
        fee.seqNo = 1;
        data.matFees.add(fee);

        ValidationResult vr = serviceV5.validateCrossTable(data, CUSTOMER_A, false);

        assertTrue(vr.hasErrors, "BV-30: 费用引用未登记主档的料号应产生阻塞错误");
        assertEquals("BV-30", vr.errors.get(0).bvCode);
    }

    @Test
    @Order(20)
    void bv30_feePartNoInMatPart_noError() {
        ParsedBasicData data = new ParsedBasicData();
        // mat_part 主档已登记
        data.matParts.add(makePart("P-REGISTERED", new BigDecimal("0.005")));
        // 费用行(无需 mat_bom 也通过)
        ParsedBasicData.MatFeeRow fee = new ParsedBasicData.MatFeeRow();
        fee.rowNum = 2;
        fee.customerId = CUSTOMER_A;
        fee.hfPartNo = "P-REGISTERED";
        fee.feeType = "INCOMING_FIXED";
        fee.seqNo = 1;
        data.matFees.add(fee);

        ValidationResult vr = serviceV5.validateCrossTable(data, CUSTOMER_A, false);

        assertFalse(vr.hasErrors, "BV-30: 费用行料号已在主档登记不应报错(纯电镀件/组装件不需要 mat_bom)");
    }

    // ─── BV-31: 客户 ID 一致性 ────────────────────────────────────────────

    @Test
    @Order(21)
    void bv31_processMismatchedCustomerId_isBlockingError() {
        UUID otherCustomer = UUID.fromString("22000000-0000-0000-0000-000000000001");
        ParsedBasicData data = new ParsedBasicData();
        data.matProcesses.add(makeProcess(otherCustomer, "P001", 1, 1));

        ValidationResult vr = serviceV5.validateCrossTable(data, CUSTOMER_A, false);

        assertTrue(vr.hasErrors, "BV-31: 客户 ID 不匹配应产生阻塞错误");
        assertEquals("BV-31", vr.errors.get(0).bvCode);
    }

    // ─── BV-04 与 BV-02 多行聚合测试 ─────────────────────────────────────

    @Test
    @Order(22)
    void multipleErrors_collectedNotFailFast() {
        ParsedBasicData data = new ParsedBasicData();
        // BV-02: 两个料号单重为 0
        data.matParts.add(makePart("ERR-01", BigDecimal.ZERO));
        data.matParts.add(makePart("ERR-02", BigDecimal.ZERO));
        // BV-04: 净 > 毛
        data.matBoms.add(makeBom("INCOMING", "ERR-01", 1, null, null,
                new BigDecimal("1.0"), new BigDecimal("2.0"), null, null));

        ValidationResult vr = serviceV5.validateBasicLayer(data, COMPOSITION_TOLERANCE,
                LOSS_RATE_MAX, DEFECT_RATE_MAX);

        assertTrue(vr.hasErrors);
        // 应收集到 3 条错误（2 x BV-02 + 1 x BV-04）
        assertEquals(3, vr.errors.size(), "应收集所有错误，不 fail-fast");
    }

    // ─── 辅助构建器 ──────────────────────────────────────────────────────────

    private ParsedBasicData.MatPartRow makePart(String partNo, BigDecimal unitWeight) {
        ParsedBasicData.MatPartRow r = new ParsedBasicData.MatPartRow();
        r.rowNum = 2;
        r.partNo = partNo;
        r.unitWeight = unitWeight;
        r.statusCode = "Y";
        return r;
    }

    private ParsedBasicData.MatBomRow makeBom(String bomType, String hfPartNo, int seqNo,
                                               BigDecimal lossRate, BigDecimal defectRate,
                                               BigDecimal grossQty, BigDecimal netQty,
                                               String elementName, BigDecimal compositionPct) {
        ParsedBasicData.MatBomRow r = new ParsedBasicData.MatBomRow();
        r.rowNum = 2;
        r.bomType = bomType;
        r.hfPartNo = hfPartNo;
        r.seqNo = seqNo;
        r.lossRate = lossRate;
        r.defectRate = defectRate;
        r.grossQty = grossQty;
        r.netQty = netQty;
        r.elementName = elementName;
        r.compositionPct = compositionPct;
        return r;
    }

    private ParsedBasicData.PlatingPlanRow makePlatingPlan(String planCode, String version,
                                                            int seqNo, BigDecimal coatingThickness) {
        ParsedBasicData.PlatingPlanRow r = new ParsedBasicData.PlatingPlanRow();
        r.rowNum = 2;
        r.planCode = planCode;
        r.version = version;
        r.seqNo = seqNo;
        r.coatingThickness = coatingThickness;
        return r;
    }

    private ParsedBasicData.MappingRow makeMapping(UUID customerId, String customerProductNo, String hfPartNo) {
        ParsedBasicData.MappingRow r = new ParsedBasicData.MappingRow();
        r.rowNum = 2;
        r.customerId = customerId;
        r.customerProductNo = customerProductNo;
        r.hfPartNo = hfPartNo;
        return r;
    }

    private ParsedBasicData.MatProcessRow makeProcess(UUID customerId, String hfPartNo,
                                                       int seqNo, Integer subSeqNo) {
        ParsedBasicData.MatProcessRow r = new ParsedBasicData.MatProcessRow();
        r.rowNum = 2;
        r.customerId = customerId;
        r.hfPartNo = hfPartNo;
        r.seqNo = seqNo;
        r.subSeqNo = subSeqNo;
        return r;
    }

    private ParsedBasicData.MatFeeRow makeMatFee(UUID customerId, String hfPartNo,
                                                  String feeType, int seqNo,
                                                  BigDecimal settlementRiseRatio) {
        ParsedBasicData.MatFeeRow r = new ParsedBasicData.MatFeeRow();
        r.rowNum = 2;
        r.customerId = customerId;
        r.hfPartNo = hfPartNo;
        r.feeType = feeType;
        r.seqNo = seqNo;
        r.settlementRiseRatio = settlementRiseRatio;
        return r;
    }
}
