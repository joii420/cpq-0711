package com.cpq.importexcel.dto;

/**
 * 基础资料字段差异（mat_part / mat_bom / plating_plan 全局表，与库中现有值对比）。
 *
 * <p>UI-1 使用此 DTO 展示"本次导入将覆盖哪些全局字段值"。
 */
public class BasicDataDiffDTO {

    /** 物理表名（snake_case），如 "mat_part" */
    public String tableName;

    /** 行业务主键，如料号 "P-001" 或 "P-001:INCOMING:1" */
    public String rowKey;

    /** 列名（snake_case），如 "unit_weight" */
    public String fieldName;

    /** 列中文标签，如 "单重" */
    public String fieldLabel;

    /** 数据库中当前值（字符串化） */
    public String oldValue;

    /** 本次 Excel 带来的新值（字符串化） */
    public String newValue;

    /**
     * 重要程度：CRITICAL / IMPORTANT / NORMAL
     * CRITICAL = 影响公式计算的关键字段（如 unit_weight）
     */
    public String importance;

    /** 该字段变更是否影响公式计算结果 */
    public boolean affectsCalculation;
}
