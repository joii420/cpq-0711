package com.cpq.configure.dto;

import com.cpq.common.DecimalStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.util.List;

/**
 * 含量配置的新建 / 修改请求（task-260901，api.md §2.2）。
 *
 * <p>{@code configNo} / {@code seq} 由服务端管理，请求体不得携带。
 * 元素以 {@code elementNo} 为准；{@code elementCode} / {@code elementName} 由服务端从
 * {@code element} 主表回填，请求体传了也忽略。
 *
 * <p>🚨 {@code elements} 的元素集合必须与该材质的 {@code composition} <b>逐个相等</b>
 * ——多了、少了都返 400 {@code CONFIG_ELEMENT_SET_MISMATCH}。前端在这一层按 composition
 * 预填且只读，这条校验防的是绕过前端直接打接口。
 */
public class MaterialRecipeConfigUpsertRequest {
    public String remark;
    public List<ElementInput> elements;

    public static class ElementInput {
        public String elementNo;
        /** 兜底：只给符号时服务端按符号反查 element 主表 */
        public String elementCode;
        /** 100 制；接受去掉尾随零的写法（"75" 与 "75.000000000000" 等价） */
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        public BigDecimal defaultPct;
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        public BigDecimal pct;

        public BigDecimal effectivePct() {
            return defaultPct != null ? defaultPct : pct;
        }
    }
}
