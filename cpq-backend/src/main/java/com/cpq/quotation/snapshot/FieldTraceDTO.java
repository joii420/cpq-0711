package com.cpq.quotation.snapshot;

import java.util.Map;

/**
 * 字段级追溯结果 DTO — v5.1 §4.9 field-trace API 返回。
 */
public class FieldTraceDTO {

    /** 被追溯的字段路径，原样回显 */
    public String fieldPath;

    /** 该字段在提交快照中的值（可为 null） */
    public Object currentValue;

    /**
     * 来源类型：
     * FORMULA / MANUAL_INPUT / MASTER_DATA / CUSTOMER_DATA / ELEMENT_PRICE
     */
    public String sourceType;

    /** 引用版本号（如 "mat_fee v3"），不适用时为 null */
    public String referencedVersion;

    /** 适用时的公式表达式字符串（computation JSON） */
    public String formula;

    /** 公式计算的输入变量值映射（适用时非空） */
    public Map<String, Object> formulaInputs;

    /** 最后修改者（v1 简化：从快照推断，无精确信息时为 null） */
    public String lastModifiedBy;

    /** 最后修改时间（v1 简化：使用 snapshotAt） */
    public String lastModifiedAt;
}
