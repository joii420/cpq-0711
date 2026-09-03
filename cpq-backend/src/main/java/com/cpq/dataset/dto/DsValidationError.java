package com.cpq.dataset.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * api.md §1 / §7 的单条校验错误。
 *
 * <p>{@code reason} 取值必须落在 api.md §1 的<b>封闭集</b>内 —— 见
 * {@link com.cpq.dataset.service.DatasetValidationReasons}。
 * {@code column} 是 <b>Excel 中文列名</b>（{@code ColumnDef.label}），不是 DB 列名。
 * {@code row}：导入 = Excel 物理行号；保存 = 数组下标 + 1（api.md §7）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DsValidationError {

    public String sheet;
    public int row;
    public String column;
    public String reason;

    public DsValidationError(String sheet, int row, String column, String reason) {
        this.sheet = sheet;
        this.row = row;
        this.column = column;
        this.reason = reason;
    }
}
