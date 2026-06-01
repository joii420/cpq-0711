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

    public VersionedGroupSpec(String tableName, String versionColumn,
                              Map<String, Object> groupKeyColumns,
                              List<String> contentColumns,
                              List<Map<String, Object>> newRows) {
        this.tableName = tableName;
        this.versionColumn = versionColumn;
        this.groupKeyColumns = new LinkedHashMap<>(groupKeyColumns);
        this.contentColumns = List.copyOf(contentColumns);
        this.newRows = List.copyOf(newRows);
    }
}
