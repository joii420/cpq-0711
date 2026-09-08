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

    /**
     * 🆕 <b>task-260907 · B-16</b>：出错单元格的<b>原始值</b>（{@code api.md §2} 的 {@code value}）。
     *
     * <p><b>为什么要有它</b>：1845 行的文件里，「第 3 行 客户编号 未登记」和
     * 「第 3 行 客户编号 {@code 8000142} 未登记」的排错成本差一个量级 —— 前者还要用户自己回去翻 Excel。
     *
     * <p><b>可以为 null，且 null 是有意义的</b>（{@code NON_NULL} 下不出现在 JSON 里）：
     * sheet 级错误（「sheet 不属于本数据集」）与表头级错误（「缺少某列」）<b>没有对应的单元格</b>，
     * 🚫 那时留 null，不要编一个值凑上去。
     *
     * <p>🚫 <b>4 参构造器必须保留</b> —— 那是「纯加法」与「破坏性变更」的分界线：
     * 保留它，既有调用点一行不改；改签名则全部调用点跟着改，就不是加法了。
     */
    public String value;

    public String reason;

    /** 原 4 参构造器，<b>签名不许动</b>：{@link #value} 留 null。 */
    public DsValidationError(String sheet, int row, String column, String reason) {
        this(sheet, row, column, null, reason);
    }

    /** B-16 新增：带出错单元格原始值。{@code value} 允许为 null（无对应单元格时）。 */
    public DsValidationError(String sheet, int row, String column, String value, String reason) {
        this.sheet = sheet;
        this.row = row;
        this.column = column;
        this.value = value;
        this.reason = reason;
    }
}
