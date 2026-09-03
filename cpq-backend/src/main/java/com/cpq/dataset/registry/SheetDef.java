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
        return out;
    }
}
