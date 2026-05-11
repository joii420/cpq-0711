package com.cpq.template.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * P0 — 公式函数清单 DTO.
 *
 * <p>供前端公式编辑器展示函数帮助文档。
 * 每个实例对应一个支持的函数，包含签名、描述、参数说明和使用示例。
 *
 * <h2>category 取值</h2>
 * 聚合 / 条件 / 算术 / 数学
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormulaFunctionDTO {

    /** 函数名，如 "SUM_OVER" */
    public String name;

    /** 分类: "聚合" | "条件" | "算术" | "数学" */
    public String category;

    /** 函数签名，如 "SUM_OVER([组件] WHERE 条件, 表达式)" */
    public String signature;

    /** 功能描述（中文） */
    public String description;

    /** 使用示例列表 */
    public List<ExampleItem> examples;

    /** 参数说明列表 */
    public List<ParamItem> params;

    public FormulaFunctionDTO() {}

    // ─── 内嵌 DTO ─────────────────────────────────────────────────────────────

    /** 使用示例 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExampleItem {
        /** 示例表达式 */
        public String expression;
        /** 示例说明（中文） */
        public String explanation;

        public ExampleItem() {}

        public ExampleItem(String expression, String explanation) {
            this.expression = expression;
            this.explanation = explanation;
        }
    }

    /** 参数说明 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ParamItem {
        /** 参数名 */
        public String name;
        /** 参数类型，如 "Component" / "Expression" / "Number" */
        public String type;
        /** 是否必填 */
        public boolean required;
        /** 参数描述（中文） */
        public String description;

        public ParamItem() {}

        public ParamItem(String name, String type, boolean required, String description) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.description = description;
        }
    }
}
