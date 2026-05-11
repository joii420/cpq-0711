package com.cpq.template.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * P0 — 公式修复建议 DTO.
 *
 * <p>附在 {@link FormulaErrorDTO#suggestions} 中，给业务用户提供可操作的修复方向。
 * 当 replacement 非 null 时，前端可提供"一键修复"按钮。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormulaSuggestionDTO {

    /** 修复建议说明，如"检查括号是否配对" */
    public String description;

    /**
     * 替换后的整段公式（机器可修复场景），null 表示仅给说明不提供替换。
     * 前端用此值替换整个 textarea 内容。
     */
    public String replacement;

    /** 建议在哪一字符位置插入/替换，null 表示不指定位置 */
    public Integer at;

    public FormulaSuggestionDTO() {}

    public FormulaSuggestionDTO(String description) {
        this.description = description;
    }

    public FormulaSuggestionDTO(String description, String replacement) {
        this.description = description;
        this.replacement = replacement;
    }
}
