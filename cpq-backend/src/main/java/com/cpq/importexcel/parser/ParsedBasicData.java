package com.cpq.importexcel.parser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * v5.1 基础资料导入解析结果容器。
 *
 * <p>由 {@link com.cpq.importexcel.service.BasicDataImportServiceV5#parseExcel} 填充，
 * 由 BV-01 ~ BV-32 校验、写库阶段、差异/冲突检测共同消费。
 *
 * <p>设计要点：
 * <ul>
 *   <li>所有 List 字段在声明时初始化为 {@link ArrayList}，避免 NPE。</li>
 *   <li>所有内部行类使用 public 字段（参考 {@code BasicDataAttribute} 风格）。</li>
 *   <li>{@link #requiredErrors} 记录 V58 元数据驱动解析阶段的必填错误，
 *       由 {@code runAllValidations} 合并到 {@link com.cpq.importexcel.dto.ValidationResult}。</li>
 *   <li>{@link #skipFields} / {@link #skipRows} 承载 KEEP_OLD 决策，
 *       供写库阶段 COALESCE 跳过更新。</li>
 * </ul>
 */
public class ParsedBasicData {

    // ── 元数据 ─────────────────────────────────────────────────────────────

    /** Excel 中所有 sheet 的有效行总数（用于 ImportRecord.totalRows） */
    public int totalRows;

    /** 当前正在解析的行号（保留兼容字段） */
    public int rowNum;

    // ── 行数据列表 ─────────────────────────────────────────────────────────

    public List<MatPartRow> matParts = new ArrayList<>();
    public List<MatBomRow> matBoms = new ArrayList<>();
    public List<MatProcessRow> matProcesses = new ArrayList<>();
    public List<PlatingPlanRow> platingPlans = new ArrayList<>();
    public List<MatFeeRow> matFees = new ArrayList<>();
    public List<PlatingFeeRow> platingFees = new ArrayList<>();
    public List<MappingRow> mappings = new ArrayList<>();
    /** V90: 8 张 costing_part_* 表统一容器（核价基础数据导入用，按 targetTable 分发到 UPSERT 助手） */
    public List<CostingPartRow> costingPartRows = new ArrayList<>();

    /** V58 元数据驱动解析阶段产生的必填项错误，待校验阶段合并入 ValidationResult */
    public List<RequiredError> requiredErrors = new ArrayList<>();

    // ── KEEP_OLD 决策状态 ──────────────────────────────────────────────────

    /**
     * 字段级 KEEP_OLD 标记。
     * key  = "tableName:rowKey"
     * value = 该行需要跳过更新的字段名集合
     */
    public Map<String, Set<String>> skipFields = new HashMap<>();

    /**
     * 行级 KEEP_OLD 标记（整行跳过）。
     * 元素 = "tableName:rowKey"。
     */
    public Set<String> skipRows = new HashSet<>();

    // ── 辅助方法 ───────────────────────────────────────────────────────────

    /**
     * 添加 V58 必填项错误。
     * @param bvCode    业务校验码（如 BV-META-01）
     * @param rowNum    1-based sheet 行号
     * @param sheetName 业务 sheet 名
     * @param message   错误描述（中文）
     */
    public void addRequiredError(String bvCode, int rowNum, String sheetName, String message) {
        RequiredError re = new RequiredError();
        re.bvCode = bvCode;
        re.rowNum = rowNum;
        re.sheetName = sheetName;
        re.message = message;
        this.requiredErrors.add(re);
    }

    /**
     * 标记某表某行的某字段为 KEEP_OLD（写库阶段保留旧值）。
     */
    public void markSkipField(String tableName, String rowKey, String fieldName) {
        if (tableName == null || rowKey == null || fieldName == null) return;
        String key = tableName + ":" + rowKey;
        skipFields.computeIfAbsent(key, k -> new HashSet<>()).add(fieldName);
    }

    /**
     * 标记整行 KEEP_OLD（写库阶段直接跳过）。
     */
    public void markSkipRow(String tableName, String rowKey) {
        if (tableName == null || rowKey == null) return;
        skipRows.add(tableName + ":" + rowKey);
    }

    /**
     * 判断指定字段是否被标记为 KEEP_OLD。
     */
    public boolean shouldSkipField(String tableName, String rowKey, String fieldName) {
        if (tableName == null || rowKey == null || fieldName == null) return false;
        Set<String> fields = skipFields.get(tableName + ":" + rowKey);
        return fields != null && fields.contains(fieldName);
    }

    /**
     * 判断整行是否被标记为 KEEP_OLD。
     */
    public boolean shouldSkipRow(String tableName, String rowKey) {
        if (tableName == null || rowKey == null) return false;
        return skipRows.contains(tableName + ":" + rowKey);
    }

    // ── 内部行类：MatPartRow（mat_part / 单重 sheet）───────────────────────

    public static class MatPartRow {
        public int rowNum;
        public String partNo;
        public String partName;
        public String specification;
        public String sizeInfo;
        public BigDecimal unitWeight;
        public String weightUnit;
        public String statusCode;
    }

    // ── 内部行类：MatBomRow（mat_bom / BOM 清单 sheet）─────────────────────

    public static class MatBomRow {
        public int rowNum;
        public String hfPartNo;
        /** INCOMING / ELEMENT */
        public String bomType;
        public int seqNo;
        public String inputMaterialNo;
        public String inputMaterialName;
        public BigDecimal lossRate;
        public BigDecimal grossQty;
        public BigDecimal netQty;
        public String grossUnit;
        public String netUnit;
        public String outputMaterialType;
        public BigDecimal defectRate;
        public String elementName;
        public BigDecimal compositionPct;
    }

    // ── 内部行类：MatProcessRow（mat_process / 组成件 BOM 及单价）──────────

    public static class MatProcessRow {
        public int rowNum;
        public UUID customerId;
        public String hfPartNo;
        public int seqNo;
        public Integer subSeqNo;
        public String processCode;
        public String assemblyProcess;
        public String componentPartNo;
        public String componentName;
        public String supplierCode;
        public String supplierName;
        public BigDecimal quantity;
        public String quantityUnit;
        public BigDecimal unitPrice;
        public BigDecimal freight;
        public String currency;
        public String priceUnit;
    }

    // ── 内部行类：PlatingPlanRow（plating_plan / 电镀方案 sheet）───────────

    public static class PlatingPlanRow {
        public int rowNum;
        /** V125: 目标物理表 — "plating_plan" (旧) | "mat_plating_plan" (报价侧). */
        public String targetTable = "plating_plan";
        public String planCode;
        public String version;
        public int seqNo;
        public String platingElement;
        public BigDecimal platingArea;
        public BigDecimal coatingThickness;
        public String platingRequirement;
    }

    // ── 内部行类：MatFeeRow（mat_fee / 各类费用 sheet）─────────────────────

    public static class MatFeeRow {
        public int rowNum;
        public UUID customerId;
        public String hfPartNo;
        /** INCOMING_FIXED / INCOMING_OTHER / FINISHED_FIXED / ASSEMBLY_PROCESS ... */
        public String feeType;
        public int seqNo;
        public BigDecimal feeValue;
        public BigDecimal feeRatio;
        public String currency;
        public String priceUnit;
        public String dimInputMaterialNo;
        public String dimInputMaterialName;
        public String dimElementName;
        public String dimAssemblyProcess;
        public Integer dimSubSeqNo;
        public Boolean priceFloating;
        public BigDecimal settlementRiseRatio;
        public BigDecimal fixedRiseValue;
        public String riseCurrency;
        public String riseUnit;
        public BigDecimal rejectRate;
    }

    // ── 内部行类：PlatingFeeRow（plating_fee / 电镀费用 sheet）─────────────

    public static class PlatingFeeRow {
        public int rowNum;
        /** V125: 目标物理表 — "plating_fee" (旧) | "mat_plating_fee" (报价侧). */
        public String targetTable = "plating_fee";
        public UUID customerId;
        public String hfPartNo;
        public String platingPlanCode;
        public String planVersion;
        public BigDecimal platingProcessFee;
        public BigDecimal platingMaterialFee;
        public String currency;
        public String priceUnit;
        public BigDecimal defectRate;
    }

    // ── 内部行类：MappingRow（mat_customer_part_mapping）───────────────────

    public static class MappingRow {
        public int rowNum;
        public UUID customerId;
        public String customerProductNo;
        public String customerPartName;
        public String customerDrawingNo;
        public String hfPartNo;
        public String paymentMethod;
        public String baseCurrency;
        public String quoteCurrency;
    }

    // ── 内部行类：CostingPartRow（V90 — 8 张 costing_part_* 表共用容器）─────

    /**
     * V90: 通用核价料号级数据行。targetTable 决定写入哪张表：
     *   costing_part_process_cost / costing_part_tooling_cost /
     *   costing_part_material_bom / costing_part_element_bom /
     *   costing_part_quality_check / costing_part_plating /
     *   costing_part_design_cost / costing_part_weight
     * 字段值由 BDC 元数据驱动按 variable_code 解析为 Map(可空), 写库时由 UPSERT 助手按表抽列。
     */
    public static class CostingPartRow {
        public int rowNum;
        public String targetTable;
        public Map<String, String> discriminator;  // 如 {cost_type: 'LABOR'}, 由 sheet.target_discriminator 注入
        public Map<String, String> values;          // variable_code -> 原始字符串值
    }

    // ── 内部类：RequiredError（V58 必填项错误） ────────────────────────────

    /**
     * V58 元数据驱动解析阶段产生的必填项错误。
     * 由 {@link #addRequiredError} 创建，由 runAllValidations 合并入 ValidationResult.errors。
     */
    public static class RequiredError {
        public String bvCode;
        public int rowNum;
        public String sheetName;
        public String message;
    }
}
