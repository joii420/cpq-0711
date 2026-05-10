package com.cpq.datapath.fixture;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 路线 X 共用测试 Fixture — 14 张物理表种子数据生成器
 *
 * <p>此类为纯工具类，不强制调用——后续 X.2～X.7 的测试可按需复用。
 * 生成的数据对象为普通 POJO，不依赖 Panache / CDI，
 * 测试中需自行 persist 或通过 JDBC 插入。
 *
 * <p>Ref: docs/superpowers/specs/2026-04-25-cpq-design-v5.md §5
 *         docs/superpowers/specs/2026-04-26-cpq-design-v5.1.md §6.1
 */
public final class BasicDataFixture {

    private BasicDataFixture() { /* utility */ }

    // ── 固定 5 个样例客户 ID ─────────────────────────────────────────────
    public static final UUID CUSTOMER_A = UUID.fromString("11000000-0000-0000-0000-000000000001");
    public static final UUID CUSTOMER_B = UUID.fromString("11000000-0000-0000-0000-000000000002");
    public static final UUID CUSTOMER_C = UUID.fromString("11000000-0000-0000-0000-000000000003");
    public static final UUID CUSTOMER_D = UUID.fromString("11000000-0000-0000-0000-000000000004");
    public static final UUID CUSTOMER_E = UUID.fromString("11000000-0000-0000-0000-000000000005");

    public static final List<UUID> ALL_CUSTOMERS = List.of(
            CUSTOMER_A, CUSTOMER_B, CUSTOMER_C, CUSTOMER_D, CUSTOMER_E
    );

    // ── 固定样例料号 ─────────────────────────────────────────────────────
    public static final String PART_NO_1 = "3120012574";
    public static final String PART_NO_2 = "3120012575";
    public static final String PART_NO_3 = "3120099001";

    // ── 生成器入口 ───────────────────────────────────────────────────────

    /**
     * 为指定客户生成完整基础数据集合（含 14 张表对应的 POJO）。
     *
     * @param customerId 目标客户 ID
     * @return 包含所有 seed 数据的容器
     */
    public static CustomerBasicDataSet createCustomerWithBasicData(UUID customerId) {
        CustomerBasicDataSet set = new CustomerBasicDataSet(customerId);
        set.matParts        = createMatParts();
        set.matBoms         = createMatBoms();
        set.matProcesses    = createMatProcesses(customerId);
        set.platingPlans    = createPlatingPlans();
        set.matFees         = createMatFees(customerId);
        set.platingFees     = createPlatingFees(customerId);
        set.mappings        = createMappings(customerId);
        set.exchangeRates   = createExchangeRates(customerId);
        set.customerTaxes   = createCustomerTaxes(customerId);
        return set;
    }

    /**
     * 为所有 5 个样例客户生成基础数据集合。
     */
    public static List<CustomerBasicDataSet> createAllCustomersWithBasicData() {
        List<CustomerBasicDataSet> result = new ArrayList<>();
        for (UUID cid : ALL_CUSTOMERS) {
            result.add(createCustomerWithBasicData(cid));
        }
        return result;
    }

    // ── mat_part 生成器 ──────────────────────────────────────────────────

    public static List<MatPartRow> createMatParts() {
        return List.of(
            new MatPartRow(PART_NO_1, "银触点铆钉A型", "AgNi10", "Φ3×5", new BigDecimal("0.0050"), "KG", "Y"),
            new MatPartRow(PART_NO_2, "银触点铆钉B型", "AgCu5",  "Φ4×6", new BigDecimal("0.0080"), "KG", "Y"),
            new MatPartRow(PART_NO_3, "铜基底片",       "C19210", "T0.5", new BigDecimal("0.0120"), "KG", "Y")
        );
    }

    // ── mat_bom 生成器 ───────────────────────────────────────────────────

    public static List<MatBomRow> createMatBoms() {
        return List.of(
            // ELEMENT BOM for PART_NO_1
            new MatBomRow(PART_NO_1, "ELEMENT", 1, null, null,
                    new BigDecimal("0.005"), new BigDecimal("0.0045"), "KG", "KG",
                    null, null, "Ag", new BigDecimal("90.0")),
            new MatBomRow(PART_NO_1, "ELEMENT", 2, null, null,
                    new BigDecimal("0.0005"), new BigDecimal("0.00045"), "KG", "KG",
                    null, null, "Ni", new BigDecimal("10.0")),
            // INCOMING BOM for PART_NO_1
            new MatBomRow(PART_NO_1, "INCOMING", 1, "RAW-AG-001", "高纯银线",
                    new BigDecimal("0.006"), new BigDecimal("0.005"), "KG", "KG",
                    "AgNi10线材", new BigDecimal("0.02"), null, null)
        );
    }

    // ── mat_process 生成器 ───────────────────────────────────────────────

    public static List<MatProcessRow> createMatProcesses(UUID customerId) {
        return List.of(
            new MatProcessRow(customerId, PART_NO_1, 1, true, 1, "PR001", "组装工序",
                    1, "COMP-A", "铆钉帽", "SUP001", "供应商A",
                    new BigDecimal("1"), "PCS", new BigDecimal("0.50"), new BigDecimal("0"),
                    "CNY", "PCS/个"),
            new MatProcessRow(customerId, PART_NO_2, 1, true, 1, "PR002", "电镀工序",
                    1, null, null, null, null,
                    new BigDecimal("1"), "PCS", new BigDecimal("1.20"), new BigDecimal("0"),
                    "CNY", "PCS/个")
        );
    }

    // ── plating_plan 生成器 ──────────────────────────────────────────────

    public static List<PlatingPlanRow> createPlatingPlans() {
        return List.of(
            new PlatingPlanRow("PP-001", "V1", 1, "Ag", new BigDecimal("50.0"),
                    new BigDecimal("3.0"), "银镀层厚度 ≥3μm"),
            new PlatingPlanRow("PP-001", "V1", 2, "Ni", new BigDecimal("50.0"),
                    new BigDecimal("2.0"), "镍底层厚度 ≥2μm")
        );
    }

    // ── mat_fee 生成器 ───────────────────────────────────────────────────

    public static List<MatFeeRow> createMatFees(UUID customerId) {
        return List.of(
            new MatFeeRow(customerId, PART_NO_1, 1, true, "INCOMING_FIXED", 1,
                    new BigDecimal("350.0"), null, "CNY", "KG",
                    "Ag", "银原料", "Ag", null, null,
                    true, new BigDecimal("0.05"), null, null, null),
            new MatFeeRow(customerId, PART_NO_1, 1, true, "FINISHED_FIXED", 2,
                    new BigDecimal("5.0"), null, "CNY", "千件",
                    null, null, null, null, null,
                    false, null, null, null, null)
        );
    }

    // ── plating_fee 生成器 ───────────────────────────────────────────────

    public static List<PlatingFeeRow> createPlatingFees(UUID customerId) {
        return List.of(
            new PlatingFeeRow(customerId, PART_NO_1, 1, true, "PP-001", "V1",
                    new BigDecimal("2.5"), new BigDecimal("1.8"), "CNY", "PCS/个",
                    new BigDecimal("0.01"))
        );
    }

    // ── mat_customer_part_mapping 生成器 ─────────────────────────────────

    public static List<MappingRow> createMappings(UUID customerId) {
        return List.of(
            new MappingRow(customerId, "客户触点A", "C-PRD-001", "DWG-001", PART_NO_1, "月结", "USD", "CNY"),
            new MappingRow(customerId, "客户触点B", "C-PRD-002", "DWG-002", PART_NO_2, "月结", "USD", "CNY")
        );
    }

    // ── exchange_rate 生成器 ─────────────────────────────────────────────

    public static List<ExchangeRateRow> createExchangeRates(UUID customerId) {
        return List.of(
            new ExchangeRateRow(customerId, "USD", "CNY", new BigDecimal("7.2300"),
                    LocalDate.of(2026, 1, 1), true),
            new ExchangeRateRow(customerId, "EUR", "CNY", new BigDecimal("7.8500"),
                    LocalDate.of(2026, 1, 1), true),
            new ExchangeRateRow(customerId, "HKD", "CNY", new BigDecimal("0.9250"),
                    LocalDate.of(2026, 1, 1), true)
        );
    }

    // ── customer_tax 生成器 ──────────────────────────────────────────────

    public static List<CustomerTaxRow> createCustomerTaxes(UUID customerId) {
        return List.of(
            new CustomerTaxRow(customerId, "VAT", new BigDecimal("0.13"),
                    LocalDate.of(2026, 1, 1), null, true, "增值税 13%"),
            new CustomerTaxRow(customerId, "WITHHOLDING", new BigDecimal("0.10"),
                    LocalDate.of(2026, 1, 1), null, true, "代扣税 10%")
        );
    }

    // ═══════════════════════════════════════════════════════════════════
    // 数据容器 POJO
    // ═══════════════════════════════════════════════════════════════════

    /** 某个客户的完整基础数据集合 */
    public static class CustomerBasicDataSet {
        public final UUID customerId;
        public List<MatPartRow>      matParts;
        public List<MatBomRow>       matBoms;
        public List<MatProcessRow>   matProcesses;
        public List<PlatingPlanRow>  platingPlans;
        public List<MatFeeRow>       matFees;
        public List<PlatingFeeRow>   platingFees;
        public List<MappingRow>      mappings;
        public List<ExchangeRateRow> exchangeRates;
        public List<CustomerTaxRow>  customerTaxes;

        public CustomerBasicDataSet(UUID customerId) {
            this.customerId = customerId;
        }
    }

    /** mat_part 行 */
    public record MatPartRow(
            String partNo, String partName, String specification, String sizeInfo,
            BigDecimal unitWeight, String weightUnit, String statusCode
    ) {}

    /** mat_bom 行 */
    public record MatBomRow(
            String hfPartNo, String bomType, int seqNo,
            String inputMaterialNo, String inputMaterialName,
            BigDecimal grossQty, BigDecimal netQty, String grossUnit, String netUnit,
            String outputMaterialType, BigDecimal defectRate,
            String elementName, BigDecimal compositionPct
    ) {}

    /** mat_process 行 */
    public record MatProcessRow(
            UUID customerId, String hfPartNo, int version, boolean isCurrent,
            int seqNo, String processCode, String assemblyProcess,
            Integer subSeqNo, String componentPartNo, String componentName,
            String supplierCode, String supplierName,
            BigDecimal quantity, String quantityUnit,
            BigDecimal unitPrice, BigDecimal freight,
            String currency, String priceUnit
    ) {}

    /** plating_plan 行 */
    public record PlatingPlanRow(
            String planCode, String version, int seqNo,
            String platingElement, BigDecimal platingArea, BigDecimal coatingThickness,
            String platingRequirement
    ) {}

    /** mat_fee 行 */
    public record MatFeeRow(
            UUID customerId, String hfPartNo, int version, boolean isCurrent,
            String feeType, int seqNo,
            BigDecimal feeValue, BigDecimal feeRatio, String currency, String priceUnit,
            String dimInputMaterialNo, String dimInputMaterialName,
            String dimElementName, String dimAssemblyProcess, Integer dimSubSeqNo,
            Boolean priceFloating, BigDecimal settlementRiseRatio,
            BigDecimal fixedRiseValue, String riseCurrency, String riseUnit
    ) {}

    /** plating_fee 行 */
    public record PlatingFeeRow(
            UUID customerId, String hfPartNo, int version, boolean isCurrent,
            String platingPlanCode, String planVersion,
            BigDecimal platingProcessFee, BigDecimal platingMaterialFee,
            String currency, String priceUnit, BigDecimal defectRate
    ) {}

    /** mat_customer_part_mapping 行 */
    public record MappingRow(
            UUID customerId, String customerPartName, String customerProductNo,
            String customerDrawingNo, String hfPartNo,
            String paymentMethod, String baseCurrency, String quoteCurrency
    ) {}

    /** exchange_rate 行 */
    public record ExchangeRateRow(
            UUID customerId, String fromCurrency, String toCurrency,
            BigDecimal rate, LocalDate effectiveDate, boolean isCurrent
    ) {}

    /** customer_tax 行 */
    public record CustomerTaxRow(
            UUID customerId, String taxType, BigDecimal taxRate,
            LocalDate effectiveDate, LocalDate expiryDate,
            boolean isCurrent, String description
    ) {}
}
