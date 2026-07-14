package com.cpq.basicdata.v6.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 工序主数据导入结果报告（task-0712 · childtask-1 · B1）。
 *
 * <p>约定：脏数据（工序编号/名称为空、同码重复）<b>不</b>报 400，走 200 + {@link #skipped} 逐条列明原因；
 * 400 仅用于"文件本身不可用"（非 xlsx / 缺必需 sheet / 空文件），对齐 {@code MaterialImportReportDTO} 惯例。
 */
public class ProcessMasterImportReportDTO {
    /** 数据行总数（不含表头，含被跳过的行） */
    public int totalRows;
    /** 新增的工序数 */
    public int insertedCount;
    /** 覆盖更新的工序数 */
    public int updatedCount;
    /** 跳过的行数（= skipped.size()） */
    public int skippedRowCount;
    /** 逐条跳过原因 */
    public List<SkippedRow> skipped = new ArrayList<>();
    /** 耗时（ms） */
    public long durationMs;

    public static class SkippedRow {
        /** Excel 行号（1-based，含表头行偏移） */
        public Integer row;
        public String reason;
        public String raw;

        public SkippedRow() {}

        public SkippedRow(Integer row, String reason, String raw) {
            this.row = row;
            this.reason = reason;
            this.raw = raw;
        }
    }
}
