package com.cpq.configure.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 材质库导入结果报告（task-0708 · B4/B5；task-260901 · B-12 扩展）。
 *
 * <p>文件结构错误、元素缺失/停用、元素组成不一致或行级校验失败均返回 400，
 * 并在错误 detail 中聚合明细；事务不会写入部分数据。成功时返回本报告，
 * {@link #skipped} 仅用于兼容既有报告结构。
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

    /** 兼容旧客户端；材质导入不再创建元素，恒为空。 */
    @Deprecated
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
