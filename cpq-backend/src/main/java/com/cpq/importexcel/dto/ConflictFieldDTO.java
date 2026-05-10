package com.cpq.importexcel.dto;

/**
 * 单字段冲突详情（属于某个 CustomerDataConflictDTO 内）。
 */
public class ConflictFieldDTO {

    /** 列名（snake_case） */
    public String fieldName;

    /** 列中文标签 */
    public String fieldLabel;

    /** 数据库中已存在的值 */
    public String existingValue;

    /** 本次 Excel 带来的值 */
    public String importValue;

    /** 重要程度：CRITICAL / IMPORTANT / NORMAL */
    public String importance;

    /** 该字段变更是否影响公式计算结果 */
    public boolean affectsCalculation;
}
