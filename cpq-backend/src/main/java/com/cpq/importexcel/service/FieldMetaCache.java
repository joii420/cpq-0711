package com.cpq.importexcel.service;

import io.agroal.api.AgroalDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;
// EntityManager 保留给 get() 方法不变；loadFromBasicDataAttribute 改用 AgroalDataSource 独立连接

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字段元数据缓存（ApplicationScoped 单例）。
 *
 * <p>缓存每张物理表每个列的：中文标签、重要程度、是否影响计算、比较器类型。
 * 优先从 basic_data_attribute 读取，缺失字段回退到硬编码默认表。
 *
 * <p>key = "tableName.columnName"（全小写）
 */
@ApplicationScoped
public class FieldMetaCache {

    private static final Logger LOG = Logger.getLogger(FieldMetaCache.class);

    /** 字段元数据记录 */
    public record FieldMeta(
            String label,
            String importance,        // CRITICAL / IMPORTANT / NORMAL
            boolean affectsCalculation,
            String comparator         // NUM / STR / DATE / BOOL
    ) {}

    private static final FieldMeta DEFAULT_META =
            new FieldMeta("未知字段", "NORMAL", false, "STR");

    private final ConcurrentHashMap<String, FieldMeta> cache = new ConcurrentHashMap<>();

    @Inject
    EntityManager em;

    @Inject
    AgroalDataSource dataSource;

    @PostConstruct
    void init() {
        // 1. 加载硬编码默认表
        loadHardcodedDefaults();

        // 2. 尝试从 basic_data_attribute 覆盖（优先级更高）
        // 注意：basic_data_attribute 当前无 table_name 列（RECORD.md 已知），
        //       此处降级为只用硬编码，不影响功能。
        try {
            loadFromBasicDataAttribute();
        } catch (Exception e) {
            LOG.warnf("FieldMetaCache: failed to load from basic_data_attribute, using defaults only. Cause: %s", e.getMessage());
        }

        LOG.infof("FieldMetaCache initialized with %d entries", cache.size());
    }

    /**
     * 获取字段元数据，永不返回 null。
     */
    public FieldMeta get(String tableName, String columnName) {
        String key = buildKey(tableName, columnName);
        return cache.getOrDefault(key, DEFAULT_META);
    }

    // ── 内部加载方法 ─────────────────────────────────────────────────────

    private void loadFromBasicDataAttribute() {
        // 使用独立 JDBC 连接（AgroalDataSource 直接获取），
        // 先检查 basic_data_attribute 是否有 table_name 列再查询，
        // 避免 SQL 错误污染 JTA 事务状态（已知：当前环境无该列）。
        final List<Object[]> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            // 先检查列是否存在（information_schema 查询，不影响事务）
            boolean hasTableName = false;
            try (ResultSet cols = conn.getMetaData().getColumns(null, null, "basic_data_attribute", "table_name")) {
                hasTableName = cols.next();
            }
            if (!hasTableName) {
                throw new RuntimeException("basic_data_attribute.table_name column not found");
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT table_name, attribute_key, display_name, importance, affects_calculation " +
                         "FROM basic_data_attribute " +
                         "WHERE table_name IS NOT NULL AND attribute_key IS NOT NULL")) {
                while (rs.next()) {
                    rows.add(new Object[]{
                            rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4),
                            rs.getObject(5)
                    });
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }

        for (Object[] row : rows) {
            String table = str(row[0]);
            String attrKey = str(row[1]);
            String displayName = str(row[2]);
            String importance = str(row[3]);
            Boolean affectsCalc = row[4] instanceof Boolean b ? b : false;

            if (table == null || attrKey == null) continue;

            // attribute_key 推断 column_name：去掉前缀（如 "mat_part."）取后段，或直接用 attrKey 转小写
            String column = attrKey.contains(".") ? attrKey.substring(attrKey.lastIndexOf('.') + 1) : attrKey;
            column = column.toLowerCase();

            String importanceStr = (importance != null && !importance.isBlank()) ? importance : "NORMAL";
            boolean ac = affectsCalc != null && affectsCalc;

            // 比较器根据列名推断
            String comparator = inferComparator(column);

            String key = buildKey(table.toLowerCase(), column);
            if (displayName != null && !displayName.isBlank()) {
                cache.put(key, new FieldMeta(displayName, importanceStr, ac, comparator));
            }
        }
    }

    private void loadHardcodedDefaults() {
        // ── mat_part ─────────────────────────────────────────────────────
        put("mat_part", "part_no",        "HF料号",      "CRITICAL",  false, "STR");
        put("mat_part", "part_name",      "料号名称",    "IMPORTANT", false, "STR");
        put("mat_part", "specification",  "规格",        "NORMAL",    false, "STR");
        put("mat_part", "size_info",      "尺寸信息",    "NORMAL",    false, "STR");
        put("mat_part", "unit_weight",    "单重",        "CRITICAL",  true,  "NUM");
        put("mat_part", "weight_unit",    "重量单位",    "IMPORTANT", true,  "STR");
        put("mat_part", "status_code",    "状态",        "NORMAL",    false, "STR");

        // ── mat_bom ──────────────────────────────────────────────────────
        put("mat_bom", "hf_part_no",          "HF料号",     "CRITICAL",  false, "STR");
        put("mat_bom", "bom_type",            "BOM类型",    "CRITICAL",  true,  "STR");
        put("mat_bom", "seq_no",              "序号",       "NORMAL",    false, "NUM");
        put("mat_bom", "input_material_no",   "投入物料号", "IMPORTANT", true,  "STR");
        put("mat_bom", "input_material_name", "投入物料名", "NORMAL",    false, "STR");
        put("mat_bom", "loss_rate",           "损耗率",     "CRITICAL",  true,  "NUM");
        put("mat_bom", "gross_qty",           "毛用量",     "CRITICAL",  true,  "NUM");
        put("mat_bom", "net_qty",             "净用量",     "CRITICAL",  true,  "NUM");
        put("mat_bom", "gross_unit",          "毛用量单位", "IMPORTANT", true,  "STR");
        put("mat_bom", "net_unit",            "净用量单位", "IMPORTANT", true,  "STR");
        put("mat_bom", "defect_rate",         "不良率",     "IMPORTANT", true,  "NUM");
        put("mat_bom", "composition_pct",     "元素含量%",  "CRITICAL",  true,  "NUM");
        put("mat_bom", "element_name",        "元素名称",   "CRITICAL",  true,  "STR");

        // ── plating_plan (V125 弃用, 保留只读) ────────────────────────────
        put("plating_plan", "plan_code",           "方案编码",   "CRITICAL",  false, "STR");
        put("plating_plan", "version",             "版本",       "CRITICAL",  false, "STR");
        put("plating_plan", "seq_no",              "序号",       "NORMAL",    false, "NUM");
        put("plating_plan", "plating_element",     "电镀元素",   "CRITICAL",  true,  "STR");
        put("plating_plan", "plating_area",        "电镀面积",   "CRITICAL",  true,  "NUM");
        put("plating_plan", "coating_thickness",   "镀层厚度",   "CRITICAL",  true,  "NUM");
        put("plating_plan", "plating_requirement", "电镀要求",   "NORMAL",    false, "STR");

        // ── mat_plating_plan (V125 报价侧电镀方案库) ──────────────────────
        put("mat_plating_plan", "plan_code",           "方案编码",   "CRITICAL",  false, "STR");
        put("mat_plating_plan", "version",             "版本",       "CRITICAL",  false, "STR");
        put("mat_plating_plan", "seq_no",              "序号",       "NORMAL",    false, "NUM");
        put("mat_plating_plan", "plating_element",     "电镀元素",   "CRITICAL",  true,  "STR");
        put("mat_plating_plan", "plating_area",        "电镀面积",   "CRITICAL",  true,  "NUM");
        put("mat_plating_plan", "coating_thickness",   "镀层厚度",   "CRITICAL",  true,  "NUM");
        put("mat_plating_plan", "plating_requirement", "电镀要求",   "NORMAL",    false, "STR");

        // ── costing_part_plating (V76, 核价侧电镀方案库) ──────────────────
        put("costing_part_plating", "plating_no",         "方案编码",   "CRITICAL",  false, "STR");
        put("costing_part_plating", "version_number",     "版本",       "CRITICAL",  false, "STR");
        put("costing_part_plating", "seq_no",             "序号",       "NORMAL",    false, "NUM");
        put("costing_part_plating", "element_attr",       "电镀元素",   "CRITICAL",  true,  "STR");
        put("costing_part_plating", "plating_area_cm2",   "电镀面积",   "CRITICAL",  true,  "NUM");
        put("costing_part_plating", "layer_thickness_um", "镀层厚度",   "CRITICAL",  true,  "NUM");
        put("costing_part_plating", "requirement",        "电镀要求",   "NORMAL",    false, "STR");

        // ── mat_customer_part_mapping ────────────────────────────────────
        put("mat_customer_part_mapping", "customer_product_no", "客户料号",     "CRITICAL",  false, "STR");
        put("mat_customer_part_mapping", "customer_part_name",  "客户料号名称", "IMPORTANT", false, "STR");
        put("mat_customer_part_mapping", "customer_drawing_no", "客户图号",     "NORMAL",    false, "STR");
        put("mat_customer_part_mapping", "hf_part_no",          "HF料号",       "CRITICAL",  false, "STR");
        put("mat_customer_part_mapping", "payment_method",      "结算方式",     "IMPORTANT", false, "STR");
        put("mat_customer_part_mapping", "base_currency",       "基础货币",     "IMPORTANT", true,  "STR");
        put("mat_customer_part_mapping", "quote_currency",      "报价货币",     "IMPORTANT", true,  "STR");

        // ── mat_process ──────────────────────────────────────────────────
        put("mat_process", "hf_part_no",        "HF料号",     "CRITICAL",  false, "STR");
        put("mat_process", "seq_no",            "序号",       "NORMAL",    false, "NUM");
        put("mat_process", "sub_seq_no",        "子序号",     "NORMAL",    false, "NUM");
        put("mat_process", "process_code",      "工序代码",   "IMPORTANT", true,  "STR");
        put("mat_process", "assembly_process",  "组装工序",   "IMPORTANT", true,  "STR");
        put("mat_process", "component_part_no", "组成件料号", "CRITICAL",  true,  "STR");
        put("mat_process", "component_name",    "组成件名称", "NORMAL",    false, "STR");
        put("mat_process", "supplier_code",     "供应商代码", "IMPORTANT", false, "STR");
        put("mat_process", "supplier_name",     "供应商名称", "NORMAL",    false, "STR");
        put("mat_process", "quantity",          "数量",       "CRITICAL",  true,  "NUM");
        put("mat_process", "quantity_unit",     "数量单位",   "IMPORTANT", true,  "STR");
        put("mat_process", "unit_price",        "单价",       "CRITICAL",  true,  "NUM");
        put("mat_process", "freight",           "运费",       "IMPORTANT", true,  "NUM");
        put("mat_process", "currency",          "货币",       "IMPORTANT", true,  "STR");
        put("mat_process", "price_unit",        "单价单位",   "IMPORTANT", true,  "STR");

        // ── mat_fee ──────────────────────────────────────────────────────
        put("mat_fee", "hf_part_no",              "HF料号",     "CRITICAL",  false, "STR");
        put("mat_fee", "fee_type",                "费用类型",   "CRITICAL",  true,  "STR");
        put("mat_fee", "seq_no",                  "序号",       "NORMAL",    false, "NUM");
        put("mat_fee", "fee_value",               "费用金额",   "CRITICAL",  true,  "NUM");
        put("mat_fee", "fee_ratio",               "费用比例",   "CRITICAL",  true,  "NUM");
        put("mat_fee", "currency",                "货币",       "IMPORTANT", true,  "STR");
        put("mat_fee", "price_unit",              "价格单位",   "IMPORTANT", true,  "STR");
        put("mat_fee", "settlement_rise_ratio",   "结算涨价比", "CRITICAL",  true,  "NUM");
        put("mat_fee", "fixed_rise_value",        "固定涨价值", "CRITICAL",  true,  "NUM");
        put("mat_fee", "reject_rate",             "报废率",     "CRITICAL",  true,  "NUM");

        // ── plating_fee (V125 弃用, 保留只读) ──────────────────────────────
        put("plating_fee", "hf_part_no",           "HF料号",     "CRITICAL",  false, "STR");
        put("plating_fee", "plating_plan_code",    "电镀方案编码", "CRITICAL", true,  "STR");
        put("plating_fee", "plan_version",         "方案版本",   "CRITICAL",  true,  "STR");
        put("plating_fee", "plating_process_fee",  "电镀工费",   "CRITICAL",  true,  "NUM");
        put("plating_fee", "plating_material_fee", "电镀材料费", "CRITICAL",  true,  "NUM");
        put("plating_fee", "currency",             "货币",       "IMPORTANT", true,  "STR");
        put("plating_fee", "price_unit",           "价格单位",   "IMPORTANT", true,  "STR");
        put("plating_fee", "defect_rate",          "不良率",     "CRITICAL",  true,  "NUM");

        // ── mat_plating_fee (V125 报价侧电镀费用) ─────────────────────────
        put("mat_plating_fee", "hf_part_no",           "HF料号",     "CRITICAL",  false, "STR");
        put("mat_plating_fee", "plating_plan_code",    "电镀方案编码", "CRITICAL", true,  "STR");
        put("mat_plating_fee", "plan_version",         "方案版本",   "CRITICAL",  true,  "STR");
        put("mat_plating_fee", "plating_process_fee",  "电镀工费",   "CRITICAL",  true,  "NUM");
        put("mat_plating_fee", "plating_material_fee", "电镀材料费", "CRITICAL",  true,  "NUM");
        put("mat_plating_fee", "currency",             "货币",       "IMPORTANT", true,  "STR");
        put("mat_plating_fee", "price_unit",           "价格单位",   "IMPORTANT", true,  "STR");
        put("mat_plating_fee", "defect_rate",          "不良率",     "CRITICAL",  true,  "NUM");

        // ── costing_part_plating_fee (V125 核价侧电镀费用) ────────────────
        put("costing_part_plating_fee", "hf_part_no",           "HF料号",     "CRITICAL",  false, "STR");
        put("costing_part_plating_fee", "plating_plan_code",    "电镀方案编码", "CRITICAL", true,  "STR");
        put("costing_part_plating_fee", "plan_version",         "方案版本",   "CRITICAL",  true,  "STR");
        put("costing_part_plating_fee", "plating_process_fee",  "电镀工费",   "CRITICAL",  true,  "NUM");
        put("costing_part_plating_fee", "plating_material_fee", "电镀材料费", "CRITICAL",  true,  "NUM");
        put("costing_part_plating_fee", "currency",             "货币",       "IMPORTANT", true,  "STR");
        put("costing_part_plating_fee", "price_unit",           "价格单位",   "IMPORTANT", true,  "STR");
        put("costing_part_plating_fee", "defect_rate",          "不良率",     "CRITICAL",  true,  "NUM");
    }

    private void put(String table, String column, String label,
                     String importance, boolean affectsCalculation, String comparator) {
        cache.put(buildKey(table, column),
                new FieldMeta(label, importance, affectsCalculation, comparator));
    }

    private String buildKey(String table, String column) {
        return table.toLowerCase() + "." + column.toLowerCase();
    }

    private String str(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private String inferComparator(String column) {
        if (column.endsWith("_at") || column.endsWith("_date") || column.equals("date")) {
            return "DATE";
        }
        if (column.endsWith("_flag") || column.startsWith("is_") || column.startsWith("has_")) {
            return "BOOL";
        }
        if (column.endsWith("_no") || column.endsWith("_code") || column.endsWith("_name")
                || column.endsWith("_key") || column.endsWith("_type") || column.endsWith("_unit")
                || column.endsWith("_status") || column.equals("currency") || column.equals("status")) {
            return "STR";
        }
        // 数值类默认
        return "NUM";
    }
}
