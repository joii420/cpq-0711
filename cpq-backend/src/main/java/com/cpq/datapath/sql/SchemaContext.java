package com.cpq.datapath.sql;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Schema 上下文，提供逻辑名到物理名的映射。
 *
 * <p>在 v5.1 完整实现中，此信息来自 BasicDataConfig 表（sheet_name → physical table name）
 * 和 BasicDataAttribute 表（field alias → physical column name）。
 *
 * <p>X.2 阶段：此类作为简单 POJO，供测试直接构造。
 * X.3（缓存层）阶段：从 BasicDataConfig 预热并缓存。version 字段作为 SQL 缓存 key 的一部分，
 * 当 schema 变化（X.4/X.6 扩列）时更新 version → SQL cache 自然失效。
 */
public class SchemaContext {

    /** Schema 版本号，用于 CachedSqlCompiler 缓存 key。X.4/X.6 扩列时递增此值。 */
    private final String version;

    /**
     * 逻辑 Sheet 名 → 物理表名
     * 例如："元素BOM" → "mat_bom", "来料BOM" → "mat_bom"（注意同一物理表可有多个逻辑名）
     */
    private final Map<String, String> tableMapping;

    /**
     * 逻辑字段名 → 物理列名（按 "tableName.fieldName" 键）
     * 例如："元素BOM.组成含量" → "composition_pct"
     */
    private final Map<String, String> columnMapping;

    /**
     * 字段类型映射（物理列名 → SQL 类型提示，用于参数绑定）
     * 例如："mat_bom.composition_pct" → "DECIMAL"
     */
    private final Map<String, String> columnTypes;

    private SchemaContext(Builder builder) {
        this.version       = builder.version != null ? builder.version : "v1";
        this.tableMapping  = Map.copyOf(builder.tableMapping);
        this.columnMapping = Map.copyOf(builder.columnMapping);
        this.columnTypes   = Map.copyOf(builder.columnTypes);
    }

    /** 返回此 schema 的版本号，用作 SQL 缓存 key 的一部分。 */
    public String getVersion() {
        return version;
    }

    /**
     * 将逻辑 Sheet/Table 名解析为物理表名。
     *
     * @param logicalName 中文或英文逻辑名
     * @return 物理表名（Optional.empty() 表示未找到映射）
     */
    public Optional<String> resolveTable(String logicalName) {
        // 先精确匹配，若无则返回原名（英文路径直接是物理表名）
        String mapped = tableMapping.get(logicalName);
        if (mapped != null) return Optional.of(mapped);
        // 若不含中文，认为输入已经是物理表名
        if (isAsciiIdentifier(logicalName)) return Optional.of(logicalName);
        return Optional.empty();
    }

    /**
     * 将逻辑字段名解析为物理列名。
     *
     * @param logicalTableName 逻辑表名（用于 scope 查找）
     * @param logicalFieldName 逻辑字段名
     * @return 物理列名（Optional.empty() 表示未找到映射）
     */
    public Optional<String> resolveColumn(String logicalTableName, String logicalFieldName) {
        String key = logicalTableName + "." + logicalFieldName;
        String mapped = columnMapping.get(key);
        if (mapped != null) return Optional.of(mapped);
        // 无映射时，若字段名是英文标识符，认为已是物理列名
        if (isAsciiIdentifier(logicalFieldName)) return Optional.of(logicalFieldName);
        return Optional.empty();
    }

    /**
     * 获取物理列的类型提示。
     */
    public Optional<String> getColumnType(String physicalTable, String physicalColumn) {
        return Optional.ofNullable(columnTypes.get(physicalTable + "." + physicalColumn));
    }

    private static boolean isAsciiIdentifier(String s) {
        if (s == null || s.isBlank()) return false;
        return s.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_');
    }

    // ── Builder ───────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String version;
        private final Map<String, String> tableMapping  = new HashMap<>();
        private final Map<String, String> columnMapping = new HashMap<>();
        private final Map<String, String> columnTypes   = new HashMap<>();

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder tableMapping(String logicalName, String physicalTable) {
            tableMapping.put(logicalName, physicalTable);
            return this;
        }

        public Builder columnMapping(String logicalTable, String logicalField, String physicalColumn) {
            columnMapping.put(logicalTable + "." + logicalField, physicalColumn);
            return this;
        }

        public Builder columnType(String physicalTable, String physicalColumn, String type) {
            columnTypes.put(physicalTable + "." + physicalColumn, type);
            return this;
        }

        public SchemaContext build() {
            return new SchemaContext(this);
        }
    }

    // ── 内置默认上下文（14 张物理表的基本映射，来自 V44 schema） ─────────

    /**
     * 构建内置的 14 张物理表 Schema 上下文（仅包含已知的中文→物理名映射）。
     * X.3 阶段会替换为从 BasicDataConfig 动态加载的版本。
     */
    public static SchemaContext defaultContext() {
        return builder()
                .version("v1")
                // 中文 Sheet 名 → 物理表名
                .tableMapping("元素BOM",    "mat_bom")
                .tableMapping("来料BOM",    "mat_bom")
                .tableMapping("组成件BOM",  "mat_bom")
                .tableMapping("生产料号",    "mat_part")
                .tableMapping("工序资料",    "mat_process")
                .tableMapping("电镀方案",    "plating_plan")
                .tableMapping("料号费用",    "mat_fee")
                .tableMapping("电镀费用",    "plating_fee")
                .tableMapping("客户料号对应", "mat_customer_part_mapping")
                .tableMapping("汇率",       "exchange_rate")
                .tableMapping("客户税率",    "customer_tax")
                // 常用字段中文→物理列名
                .columnMapping("元素BOM", "元素",      "element_name")
                .columnMapping("元素BOM", "组成含量",   "composition_pct")
                .columnMapping("元素BOM", "组成含量(%)", "composition_pct")
                .columnMapping("来料BOM", "来料料号",    "input_material_no")
                .columnMapping("来料BOM", "来料名称",    "input_material_name")
                .columnMapping("生产料号", "料号",       "part_no")
                .columnMapping("生产料号", "料号名称",    "part_name")
                .columnMapping("生产料号", "单重",       "unit_weight")
                .build();
    }
}
