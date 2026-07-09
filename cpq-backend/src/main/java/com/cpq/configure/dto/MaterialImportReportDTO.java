package com.cpq.configure.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 材质库导入结果报告（task-0708 · B4/B5）。
 *
 * <p>约定：脏数据<b>不</b>报 400，走 200 + {@link #skipped} 逐条列明原因（供维护者回去修 Excel）。
 * 400 仅用于"文件本身不可用"（非 xlsx / 缺必需 sheet / 空文件）。
 */
public class MaterialImportReportDTO {
    /** 材质对应元素 sheet 的数据行数（不含表头） */
    public int totalRows;
    /** 落库材质数（新增 + 覆盖） */
    public int materialsUpserted;
    /** 落库元素明细行数 */
    public int elementRowsInserted;
    /** 元素主表新增/更新数 */
    public int elementMasterUpserted;
    /** 跳过的行/材质条目数（= skipped.size()） */
    public int skippedRowCount;
    /** 逐条跳过原因 */
    public List<SkippedRow> skipped = new ArrayList<>();
    /** 耗时（ms，性能自检可见） */
    public long durationMs;

    public static class SkippedRow {
        public String sheet;
        /** Excel 行号（1-based）；材质级跳过（如 Σ≠1）无单行归属时为 null */
        public Integer row;
        public String reason;
        public String raw;

        public SkippedRow() {}

        public SkippedRow(String sheet, Integer row, String reason, String raw) {
            this.sheet = sheet;
            this.row = row;
            this.reason = reason;
            this.raw = raw;
        }
    }
}
