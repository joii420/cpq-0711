package com.cpq.template.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * P0 — 公式错误结构化 DTO.
 *
 * <p>用于把 JEXL / BusinessException 翻译成业务用户可读的中文错误描述。
 * 替代原来直接 throw RuntimeException 的做法，允许前端精确定位并给出修复建议。
 *
 * <h2>severity</h2>
 * <ul>
 *   <li>ERROR   — 公式无法求值，必须修复</li>
 *   <li>WARNING — 公式可求值但存在潜在问题（如引用了不确定的 fallback）</li>
 * </ul>
 *
 * <h2>code</h2>
 * <ul>
 *   <li>PARSE_ERROR      — 语法解析失败（括号不配对、操作符缺失等）</li>
 *   <li>UNKNOWN_GLOBAL   — @变量名 不存在于 global_variable_definition</li>
 *   <li>UNKNOWN_FIELD    — 字段在指定组件中不存在</li>
 *   <li>UNKNOWN_FUNCTION — 调用了不支持的函数名</li>
 *   <li>CIRCULAR_DEP     — 公式之间存在循环依赖</li>
 *   <li>RUNTIME_ERROR    — 运行时异常（除零、类型不匹配等）</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormulaErrorDTO {

    /** 1-based 行号，null 表示位置未知 */
    public Integer line;

    /** 1-based 列号，null 表示位置未知 */
    public Integer column;

    /** 严重级别: "ERROR" | "WARNING" */
    public String severity;

    /**
     * 错误代码: "PARSE_ERROR" | "UNKNOWN_GLOBAL" | "UNKNOWN_FIELD" |
     *            "UNKNOWN_FUNCTION" | "CIRCULAR_DEP" | "RUNTIME_ERROR"
     */
    public String code;

    /** 中文错误描述（面向业务用户） */
    public String message;

    /** 修复建议列表，可为 null */
    public List<FormulaSuggestionDTO> suggestions;

    public FormulaErrorDTO() {}

    public FormulaErrorDTO(String code, String message) {
        this.code = code;
        this.message = message;
        this.severity = "ERROR";
    }

    public FormulaErrorDTO(String code, String message, Integer line, Integer column) {
        this.code = code;
        this.message = message;
        this.severity = "ERROR";
        this.line = line;
        this.column = column;
    }

    public FormulaErrorDTO withSuggestions(List<FormulaSuggestionDTO> suggestions) {
        this.suggestions = suggestions;
        return this;
    }

    public FormulaErrorDTO asSeverity(String severity) {
        this.severity = severity;
        return this;
    }
}
