package com.cpq.dataset.registry;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个 Excel sheet ↔ 一张主表 的元数据（task-260902 · backtask B-3 · 需求文档 R-1）。
 *
 * <p>🚨 {@link #sheetName} <b>必须与 Excel sheet 名逐字相等</b> —— 导入 Phase 1 靠它匹配 sheet
 * （不匹配就报 {@code sheet「X」不属于{数据集中文名}数据集}，AC-34）。
 * 同理 {@code ColumnDef.label} 必须与 Excel 表头中文列名逐字相等（表头校验 + 错误报告文案）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SheetDef {

    /** 每张主表统一追加的系统列（R-1），顺序与迁移一致。 */
    public static final List<String> SYSTEM_COLUMNS =
            List.of("source", "created_at", "created_by", "updated_at", "updated_by");
    /** 带版本表额外追加的两列（R-1）。 */
    public static final List<String> VERSION_COLUMNS = List.of("version_no", "row_fingerprint");
    /** _history 相对主表额外追加的三列（R-5）。 */
    public static final List<String> ARCHIVE_COLUMNS =
            List.of("archived_at", "archived_by", "archive_reason");
    /**
     * task-260907 · B-1：<b>报价侧</b>客户维度列。
     *
     * <p>🚨 <b>刻意做成「静态系统列」而不是 {@link ColumnDef}</b>：
     * {@code DatasetSheetParser} 要求 {@link #persistedColumns()} 的每一列都在 Excel 表头里出现，
     * 而 {@code customer_no} 的值来自<b>导入时选客户的下拉</b>，不来自 Excel
     * （16 个 sheet 里只有「客户料号」自带客户编号列）。声明成 {@code ColumnDef} 会让其余 15 张
     * sheet 表头校验全报「缺列」⇒ 整份 Excel 拒收。
     * 走静态系统列则「进启动自检、不进表头校验」，正是所需形状。
     *
     * <p>⚠️ 只对报价侧生效（{@link #customerScoped}）—— 本类三套数据集共用，无条件追加会让
     * 核价两套的启动自检也要求这一列，核价侧当场起不来。
     */
    public static final List<String> CUSTOMER_COLUMNS = List.of("customer_no");
    /** {@link #CUSTOMER_COLUMNS} 唯一那一列的列名，供写入器/SQL 拼接引用。 */
    public static final String CUSTOMER_COLUMN = "customer_no";

    public final String sheetKey;      // 大写下划线，如 MATERIAL_BOM
    public final String sheetName;     // Excel sheet 名，逐字相等
    public final String axisColumn;    // 带版本表的轴列；免版本表为 null
    public final String axisLabel;
    public final int sortOrder;
    public final boolean versioned;
    public final List<ColumnDef> columns;

    @JsonIgnore public final String tableName;
    /** 免版本表的主键（R-2）；带版本表为空。 */
    @JsonIgnore public final List<String> primaryKeyColumns;

    /**
     * task-260907 · B-1/B-2：本表是否带 {@code customer_no} 静态系统列（报价侧专属）。
     * <p>由 {@link AbstractDatasetRegistry#reg} 在登记时统一注入：
     * {@code 数据集是报价侧 && 该 sheet 自己没有声明 customer_no 业务列}。
     * 后一半是为了排除「客户料号」表 —— 它的 {@code customer_no} 来自 Excel、是真 {@link ColumnDef}，
     * 再叠一层系统列会在自检里出现重复列名。
     */
    @JsonIgnore private boolean customerScoped;
    /**
     * task-260907 · B-6：{@code customer_no} 是否参与本表的<b>业务唯一索引</b>（免版本表的 ON CONFLICT 目标）。
     * <p>只有各数据集的<b>物料表</b>为 true（{@code uq_ds_quote_material} 由 B-6 扩成
     * {@code (customer_no, material_no)}）。{@code ds_quote_plating_scheme} 的
     * {@code uq(scheme_no, scheme_version, item_seq)} 与客户无关，B-6 明令不动 ⇒ 它为 false。
     */
    @JsonIgnore private boolean customerKeyed;

    private SheetDef(String sheetKey, String sheetName, String tableName, boolean versioned, int sortOrder,
                     String axisColumn, String axisLabel, List<String> pk, List<ColumnDef> columns) {
        this.sheetKey = sheetKey;
        this.sheetName = sheetName;
        this.tableName = tableName;
        this.versioned = versioned;
        this.sortOrder = sortOrder;
        this.axisColumn = axisColumn;
        this.axisLabel = axisLabel;
        this.primaryKeyColumns = List.copyOf(pk);
        this.columns = List.copyOf(columns);
    }

    public static SheetDef versioned(String sheetKey, String sheetName, String tableName, int sortOrder,
                                     String axisColumn, String axisLabel, List<ColumnDef> columns) {
        return new SheetDef(sheetKey, sheetName, tableName, true, sortOrder, axisColumn, axisLabel,
                List.of(), columns);
    }

    public static SheetDef unversioned(String sheetKey, String sheetName, String tableName, int sortOrder,
                                       List<String> primaryKeyColumns, List<ColumnDef> columns) {
        return new SheetDef(sheetKey, sheetName, tableName, false, sortOrder, null, null,
                primaryKeyColumns, columns);
    }

    /**
     * 由 {@link AbstractDatasetRegistry#reg} 在登记期调用一次，注入客户维度开关。
     * <p>包内可见 + 幂等赋值：{@link SheetDef} 对外仍是不可变的元数据。
     */
    void applyCustomerScope(boolean scoped, boolean keyed) {
        this.customerScoped = scoped;
        this.customerKeyed = keyed;
    }

    /** 本表是否带 {@code customer_no} 静态系统列（报价侧 = true，核价两套 = false）。 */
    @JsonIgnore
    public boolean customerScoped() {
        return customerScoped;
    }

    /**
     * task-260907 · B-2：<b>复合轴</b>的列清单，顺序 = 写入器拼 SQL 谓词的顺序。
     * <p>报价侧带版本表 = {@code [customer_no, material_no]}；核价两套 = {@code [production_no]}。
     * 免版本表返回空（它们走 {@link #conflictKeyColumns()}）。
     *
     * <p>🚨 单列时<b>逐字退化成原来的形态</b> —— 核价两套的写入 SQL 与改动前一模一样（AC-6）。
     */
    @JsonIgnore
    public List<String> axisColumns() {
        if (axisColumn == null) return List.of();
        return customerScoped ? List.of(CUSTOMER_COLUMN, axisColumn) : List.of(axisColumn);
    }

    /**
     * 免版本表的 {@code ON CONFLICT} 目标列（= 库里那条业务唯一索引的列）。
     *
     * <p>🚫 <b>刻意不把 {@code customer_no} 塞进 {@link #primaryKeyColumns}</b>：那个字段还被
     * {@code DatasetImportValidator#validateDuplicateKeys} 用来做「同一份 Excel 内主键重复」的校验，
     * 而它按 {@code row.get(列名)} 取值 —— {@code customer_no} 不在 Excel 行里，取到空就会
     * <b>整表跳过查重</b>（AC-63 静默失效）。所以唯一索引的口径单独用本方法表达。
     */
    @JsonIgnore
    public List<String> conflictKeyColumns() {
        if (primaryKeyColumns.isEmpty()) return primaryKeyColumns;
        if (!customerKeyed) return primaryKeyColumns;
        List<String> out = new ArrayList<>(primaryKeyColumns.size() + 1);
        out.add(CUSTOMER_COLUMN);
        out.addAll(primaryKeyColumns);
        return List.copyOf(out);
    }

    /** {@code _history} 表名；免版本表返回 null（AC-4：这 6 张表没有 _history）。 */
    @JsonIgnore
    public String historyTable() {
        return versioned ? tableName + "_history" : null;
    }

    /** 建了 DB 字段的业务列（排除白底 NAME 列），顺序 = 建表字段顺序。 */
    @JsonIgnore
    public List<ColumnDef> persistedColumns() {
        List<ColumnDef> out = new ArrayList<>();
        for (ColumnDef c : columns) if (c.persisted) out.add(c);
        return out;
    }

    /**
     * 参与行指纹的列，<b>按 Registry 声明顺序 = 建表字段顺序</b>（R-3）。
     * 顺序即协议：换顺序 = 换指纹 = 全库虚假升版。
     */
    @JsonIgnore
    public List<ColumnDef> comparedColumns() {
        List<ColumnDef> out = new ArrayList<>();
        for (ColumnDef c : columns) if (c.persisted && c.compared) out.add(c);
        return out;
    }

    /** 白底名称列（不建字段，后端 JOIN 带出）。 */
    @JsonIgnore
    public List<ColumnDef> nameColumns() {
        List<ColumnDef> out = new ArrayList<>();
        for (ColumnDef c : columns) if (!c.persisted) out.add(c);
        return out;
    }

    @JsonIgnore
    public ColumnDef column(String name) {
        for (ColumnDef c : columns) if (c.name.equals(name)) return c;
        return null;
    }

    /** 主表在库里应有的<b>全部</b>列名（含 id / 版本列 / 系统列）—— 启动自检比对基准。 */
    @JsonIgnore
    public List<String> expectedTableColumns() {
        List<String> out = new ArrayList<>();
        out.add("id");
        for (ColumnDef c : persistedColumns()) out.add(c.name);
        if (versioned) out.addAll(VERSION_COLUMNS);
        out.addAll(SYSTEM_COLUMNS);
        if (customerScoped) out.addAll(CUSTOMER_COLUMNS);   // task-260907 · B-1
        return out;
    }

    /** {@code _history} 表应有的全部列名（主表 id → origin_id，本表自带新 id）+ 归档三列（R-5 / AC-5）。 */
    @JsonIgnore
    public List<String> expectedHistoryColumns() {
        if (!versioned) return List.of();
        List<String> out = new ArrayList<>();
        out.add("id");
        out.add("origin_id");
        for (ColumnDef c : persistedColumns()) out.add(c.name);
        out.addAll(VERSION_COLUMNS);
        out.addAll(SYSTEM_COLUMNS);
        out.addAll(ARCHIVE_COLUMNS);
        if (customerScoped) out.addAll(CUSTOMER_COLUMNS);   // task-260907 · B-1（镜像表与主表列定义逐字一致）
        return out;
    }
}
