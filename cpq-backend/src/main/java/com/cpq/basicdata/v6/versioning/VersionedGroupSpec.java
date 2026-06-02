package com.cpq.basicdata.v6.versioning;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 组级版本化写入参数。groupKeyColumns 唯一确定「哪一组」；newRows 是该组本次要写的整套行内容。 */
public class VersionedGroupSpec {
    /** 目标表名（白名单校验，防注入）。 */
    public final String tableName;
    /** 版本列名，如 "version_no" / "calc_version" / "characteristic"。 */
    public final String versionColumn;
    /** 分组键：列名→值（用于定位现有生效组 + 写入每行）。保持插入顺序。 */
    public final Map<String, Object> groupKeyColumns;
    /** 参与「内容是否相同」比较、且逐行可能不同的列（如 code/operation_no/seq_no）。 */
    public final List<String> contentColumns;
    /** 本次要写的整组行：每行 = 列名→值（含 contentColumns；不含 version/is_current/id）。 */
    public final List<Map<String, Object>> newRows;
    /**
     * 决定「是否升版」的触发列子集（必须 ⊆ contentColumns）。
     * <p>null = 退化为用 contentColumns 作触发列（默认行为；其余表保持现状：任何内容变化即升版）。
     * <p>非 null（如 capacity 传 [process_no, seq_no]）= 仅这些列变化才升版；其它写入列变化原地更新不升版。
     */
    public final List<String> versionTriggerColumns;

    /** 旧构造器：versionTriggerColumns 默认 null（行为不变）。 */
    public VersionedGroupSpec(String tableName, String versionColumn,
                              Map<String, Object> groupKeyColumns,
                              List<String> contentColumns,
                              List<Map<String, Object>> newRows) {
        this(tableName, versionColumn, groupKeyColumns, contentColumns, newRows, null);
    }

    /** 新构造器：显式指定升版触发列。 */
    public VersionedGroupSpec(String tableName, String versionColumn,
                              Map<String, Object> groupKeyColumns,
                              List<String> contentColumns,
                              List<Map<String, Object>> newRows,
                              List<String> versionTriggerColumns) {
        this.tableName = tableName;
        this.versionColumn = versionColumn;
        this.groupKeyColumns = new LinkedHashMap<>(groupKeyColumns);
        this.contentColumns = List.copyOf(contentColumns);
        this.newRows = List.copyOf(newRows);
        this.versionTriggerColumns = versionTriggerColumns == null ? null : List.copyOf(versionTriggerColumns);
    }
}
