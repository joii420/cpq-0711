package com.cpq.template.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * V145 (Stage 1) — 模板公式 DTO.
 *
 * <p>对应 template.formulas JSONB 数组中的一个元素:
 * <pre>{
 *   "name": "管理费",
 *   "expression": "([纯材料成本] + [加工费]) * @管理费比例",
 *   "data_type": "DECIMAL(18,4)",
 *   "depends_on": ["纯材料成本", "加工费", "@管理费比例"],
 *   "description": "管理费 = 加价基数 × 管理费比例"
 * }</pre>
 *
 * <p>字段约定:
 * <ul>
 *   <li>{@code name}        — 公式名（同模板内唯一），可中文，会被 [name] 引用</li>
 *   <li>{@code expression}  — 表达式，长度上限 5000；支持简单算术 + 函数（Stage 1 拒绝聚合 SUM_OVER）</li>
 *   <li>{@code dataType}    — 结果数据类型（"DECIMAL(18,4)" / "STRING" / "INTEGER" / "BOOLEAN"）</li>
 *   <li>{@code dependsOn}   — 自动检测填充：从 expression 中扫出的所有 [名称]/@变量 引用，保存时由 service 覆写</li>
 *   <li>{@code description} — 业务说明，可空</li>
 * </ul>
 *
 * <p>JSONB 序列化时 service 层负责 camelCase ⇄ snake_case 转换（dataType ↔ data_type，dependsOn ↔ depends_on）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TemplateFormulaDTO {

    public String name;
    public String expression;
    public String dataType;
    public List<String> dependsOn = new ArrayList<>();
    public String description;

    public TemplateFormulaDTO() {}

    public TemplateFormulaDTO(String name, String expression, String dataType,
                              List<String> dependsOn, String description) {
        this.name = name;
        this.expression = expression;
        this.dataType = dataType;
        this.dependsOn = dependsOn != null ? dependsOn : new ArrayList<>();
        this.description = description;
    }
}
