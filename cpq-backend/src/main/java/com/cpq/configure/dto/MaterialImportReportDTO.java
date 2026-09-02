package com.cpq.configure.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 材质库导入结果报告（task-0708 · B4/B5；task-260901 · B-12 扩展）。
 *
 * <p>约定：脏数据<b>不</b>报 400，走 200 + {@link #skipped} 逐条列明原因（供维护者回去修 Excel）。
 * 400 仅用于"文件本身不可用"（非 xlsx / 旧模板 / 表头不符 / 空文件）。
 * 表头正确但零数据行<b>不算失败</b>：返 200 + 全 0 报告（AC-23）。
 */
public class MaterialImportReportDTO {
    /** 读到的数据行数（不含表头、不含全空行） */
    public int totalRows;

    /**
     * ★task-260901（原 materialsUpserted 重命名，语义由 upsert 改为 created）：
     * 只统计真正落库的<b>新建</b>材质；被整体跳过的材质不计、也不消耗编号。
     */
    public int recipesCreated;

    /** ★新增：本次新建的含量配置组数 */
    public int configsCreated;

    /** ★新增：内容已存在（M-4 逐值相同）而跳过的配置组数 */
    public int configsSkippedAsDuplicate;

    /** 落库元素明细行数 */
    public int elementRowsInserted;

    /** ★新增（X-2）：本次自动建档的元素，供业务复核 */
    public List<CreatedElement> createdElements = new ArrayList<>();

    /** ★新增：新增配置明细 */
    public List<CreatedConfig> createdConfigs = new ArrayList<>();

    /** 跳过的行/材质/配置组条目数（= skipped.size()） */
    public int skippedRowCount;

    /** 逐条跳过原因 */
    public List<SkippedRow> skipped = new ArrayList<>();

    /** 耗时（ms，性能自检可见） */
    public long durationMs;

    public static class CreatedElement {
        public String elementNo;
        public String elementCode;
        public String elementName;
        /** Excel 行号（1-based） */
        public Integer sourceRow;
        public String sourceRecipe;

        public CreatedElement() {}

        public CreatedElement(String elementNo, String elementCode, String elementName,
                              Integer sourceRow, String sourceRecipe) {
            this.elementNo = elementNo;
            this.elementCode = elementCode;
            this.elementName = elementName;
            this.sourceRow = sourceRow;
            this.sourceRecipe = sourceRecipe;
        }
    }

    public static class CreatedConfig {
        public String recipeCode;
        public String recipeSymbol;
        public String configNo;
        /** 'Ag 90% / Ni 10%' */
        public String summary;
        public boolean recipeIsNew;

        public CreatedConfig() {}

        public CreatedConfig(String recipeCode, String recipeSymbol, String configNo,
                             String summary, boolean recipeIsNew) {
            this.recipeCode = recipeCode;
            this.recipeSymbol = recipeSymbol;
            this.configNo = configNo;
            this.summary = summary;
            this.recipeIsNew = recipeIsNew;
        }
    }

    public static class SkippedRow {
        public String sheet;
        /** Excel 行号（1-based）；组级/材质级跳过无单行归属时为 null */
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
