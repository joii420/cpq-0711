package com.cpq.basicdata.v6.maintenance;

import com.cpq.basicdata.v6.maintenance.dto.ColumnDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个"版本组"（sheetKey）的元数据登记。
 *
 * <p><b>同源纪律（backtask §B2 强约束）</b>：{@link #contentColumns} / groupKey 各常量必须逐列对齐对应
 * {@code P*Handler} 传给 {@code VersionedV6Writer} 的 groupKey/content，见各字段处标注的「对应 PxxHandler」。
 * 核价侧无触发列（trigger=content），本类不配 versionTriggerColumns。
 *
 * <p>完整 groupKey 组装：{@code system_type='PRICING'} + (priceTypeConst? {@code price_type=X}) +
 * {@link #fixedGroupKey} + {@code anchorColumn=materialNo}（顺序不影响 SQL 语义，写入器按 Map 遍历）。
 */
public final class PricingSheetDef {

    public static final String SYSTEM_TYPE = "PRICING";

    public final String sheetKey;
    public final String tabName;
    public final String group;              // FEE / BOM / CAPACITY_ENERGY / TOOLING
    public final int order;
    public final boolean masterDetail;

    // ---- 组定位（单表 / 主从主表）----
    public final String tableName;          // unit_price / capacity / material_bom / element_bom ...
    public final String versionColumn;      // version_no / calc_version / bom_version / characteristic
    public final String anchorColumn;       // code / finished_material_no / material_no（销售料号锚）
    public final String priceTypeConst;     // 可空：unit_price 8 组 + production_energy 2 组固定 price_type
    public final Map<String, Object> fixedGroupKey;   // 除 system_type/priceType/anchor 之外恒定的 gk 列

    // ---- 写入内容 ----
    public final List<String> contentColumns;   // 单表=handler CONTENT；主从=childContent（严格同源）
    public final List<String> rowKeyColumns;    // 组内行去重键（继承 production_no 描述列用）
    public final List<String> descriptorColumns;// 描述列（写入不比对），单表=[production_no]；BOM 见下
    public final Map<String, Integer> decimalScales; // content 里 decimal 列 → DB 列 scale（保存归一，防虚假升版）

    // ---- 主从专用 ----
    public final String childTable;             // material_bom_item / element_bom_item
    public final String childVersionColumn;     // bom_version / characteristic
    public final Map<String, Object> childFixedGroupKey; // 子 gk 除 anchor 外固定（customer_no [+ characteristic=null]）
    public final Map<String, Object> masterFixedColumns; // 主表额外固定列（element_bom 的 bom_type=MATERIAL）
    public final List<String> masterDescriptorColumns;   // 主表描述列（material_bom 的 production_no，per-material）
    public final List<String> extraAnchorColumns;        // element_bom 的 [material_part_no]（合并展示的第二维度）

    // ---- 前端渲染 + 校验 ----
    public final List<ColumnDef> columns;

    private PricingSheetDef(Builder b) {
        this.sheetKey = b.sheetKey;
        this.tabName = b.tabName;
        this.group = b.group;
        this.order = b.order;
        this.masterDetail = b.masterDetail;
        this.tableName = b.tableName;
        this.versionColumn = b.versionColumn;
        this.anchorColumn = b.anchorColumn;
        this.priceTypeConst = b.priceTypeConst;
        // groupKey 类 Map 可能含 null 值（如 MATERIAL_BOM 子表 characteristic=null），Map.copyOf 拒绝 null → 用 LinkedHashMap 包装。
        this.fixedGroupKey = Collections.unmodifiableMap(new LinkedHashMap<>(b.fixedGroupKey));
        this.contentColumns = List.copyOf(b.contentColumns);
        this.rowKeyColumns = List.copyOf(b.rowKeyColumns);
        this.descriptorColumns = List.copyOf(b.descriptorColumns);
        this.decimalScales = Map.copyOf(b.decimalScales);
        this.childTable = b.childTable;
        this.childVersionColumn = b.childVersionColumn;
        this.childFixedGroupKey = Collections.unmodifiableMap(new LinkedHashMap<>(b.childFixedGroupKey));
        this.masterFixedColumns = Collections.unmodifiableMap(new LinkedHashMap<>(b.masterFixedColumns));
        this.masterDescriptorColumns = List.copyOf(b.masterDescriptorColumns);
        this.extraAnchorColumns = List.copyOf(b.extraAnchorColumns);
        this.columns = List.copyOf(b.columns);
    }

    /** 完整 groupKey（单表 / 主从主表通用；anchor=materialNo，extraAnchor 由调用方按需追加）。 */
    public LinkedHashMap<String, Object> completeGroupKey(String materialNo) {
        LinkedHashMap<String, Object> gk = new LinkedHashMap<>();
        gk.put("system_type", SYSTEM_TYPE);
        if (priceTypeConst != null) gk.put("price_type", priceTypeConst);
        gk.putAll(fixedGroupKey);
        gk.put(anchorColumn, materialNo);
        return gk;
    }

    /** 子表 groupKey（主从）：system_type + childFixedGroupKey + anchor=materialNo。 */
    public LinkedHashMap<String, Object> childGroupKey(String materialNo) {
        LinkedHashMap<String, Object> gk = new LinkedHashMap<>();
        gk.put("system_type", SYSTEM_TYPE);
        gk.putAll(childFixedGroupKey);
        gk.put(anchorColumn, materialNo);
        return gk;
    }

    /** SUBDIM（子维度编码列）名单——保存时 MASTER 类校验存在性用。 */
    public List<ColumnDef> subDimColumns() {
        List<ColumnDef> out = new ArrayList<>();
        for (ColumnDef c : columns) if ("SUBDIM".equals(c.role)) out.add(c);
        return out;
    }

    // ------------------------------------------------------------------
    public static Builder builder(String sheetKey, String tabName, String group, int order) {
        return new Builder(sheetKey, tabName, group, order);
    }

    public static final class Builder {
        private final String sheetKey, tabName, group;
        private final int order;
        private boolean masterDetail = false;
        private String tableName, versionColumn, anchorColumn, priceTypeConst;
        private final Map<String, Object> fixedGroupKey = new LinkedHashMap<>();
        private List<String> contentColumns = List.of();
        private List<String> rowKeyColumns = List.of();
        private List<String> descriptorColumns = List.of("production_no");
        private final Map<String, Integer> decimalScales = new LinkedHashMap<>();
        private String childTable, childVersionColumn;
        private final Map<String, Object> childFixedGroupKey = new LinkedHashMap<>();
        private final Map<String, Object> masterFixedColumns = new LinkedHashMap<>();
        private List<String> masterDescriptorColumns = List.of();
        private List<String> extraAnchorColumns = List.of();
        private List<ColumnDef> columns = List.of();

        private Builder(String sheetKey, String tabName, String group, int order) {
            this.sheetKey = sheetKey; this.tabName = tabName; this.group = group; this.order = order;
        }

        public Builder table(String tableName, String versionColumn, String anchorColumn) {
            this.tableName = tableName; this.versionColumn = versionColumn; this.anchorColumn = anchorColumn; return this;
        }
        public Builder priceType(String c) { this.priceTypeConst = c; return this; }
        public Builder fixedGk(String k, Object v) { this.fixedGroupKey.put(k, v); return this; }
        public Builder content(String... cols) { this.contentColumns = List.of(cols); return this; }
        public Builder rowKeys(String... cols) { this.rowKeyColumns = List.of(cols); return this; }
        public Builder descriptors(String... cols) { this.descriptorColumns = List.of(cols); return this; }
        public Builder scale(String col, int scale) { this.decimalScales.put(col, scale); return this; }
        public Builder columns(ColumnDef... c) { this.columns = List.of(c); return this; }

        public Builder masterDetail(String childTable, String childVersionColumn) {
            this.masterDetail = true; this.childTable = childTable; this.childVersionColumn = childVersionColumn; return this;
        }
        public Builder childFixedGk(String k, Object v) { this.childFixedGroupKey.put(k, v); return this; }
        public Builder masterFixed(String k, Object v) { this.masterFixedColumns.put(k, v); return this; }
        public Builder masterDescriptors(String... cols) { this.masterDescriptorColumns = List.of(cols); return this; }
        public Builder extraAnchor(String... cols) { this.extraAnchorColumns = List.of(cols); return this; }

        public PricingSheetDef build() { return new PricingSheetDef(this); }
    }
}
