package com.cpq.template.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * P0 — 公式自动补全数据 DTO.
 *
 * <p>供前端 textarea 公式编辑器渲染补全候选项。
 * 包含当前模板的所有公式、绑定的组件及其字段、以及系统全局变量。
 *
 * <h2>使用场景</h2>
 * 用户在 [xxx] 或 @xxx 位置触发自动补全时，前端从此响应中检索候选项并按类别分组展示。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormulaCompletionDTO {

    /** 当前模板的所有公式（可用于 [公式名] 引用） */
    public List<FormulaItem> templateFormulas;

    /** 当前模板绑定的组件及其字段（可用于 [组件code.字段名] 引用） */
    public List<ComponentItem> components;

    /** 系统全局变量（可用于 @变量名 引用） */
    public List<GlobalVariableItem> globalVariables;

    // ─── 内嵌 DTO ─────────────────────────────────────────────────────────────

    /** 模板公式条目 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FormulaItem {
        /** 公式名（中文），与 [公式名] 中括号内文字一致 */
        public String name;
        /** 数据类型，如 "DECIMAL(18,4)" */
        public String dataType;
        /** 公式描述说明 */
        public String description;

        public FormulaItem() {}

        public FormulaItem(String name, String dataType, String description) {
            this.name = name;
            this.dataType = dataType;
            this.description = description;
        }
    }

    /** 组件条目（含字段列表） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ComponentItem {
        /** 组件 code，如 "COMP-V5-RAW-BOM" */
        public String code;
        /** 组件名称（中文） */
        public String name;
        /** 该组件的字段列表（来自 component.fields JSONB） */
        public List<FieldItem> fields;

        public ComponentItem() {}

        public ComponentItem(String code, String name, List<FieldItem> fields) {
            this.code = code;
            this.name = name;
            this.fields = fields;
        }
    }

    /** 组件字段条目 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldItem {
        /** 字段物理名（英文），用于 SUM_OVER 中的行表达式 */
        public String name;
        /** 字段显示标签（中文） */
        public String label;
        /** 数据类型，如 "NUMERIC" / "TEXT" */
        public String dataType;

        public FieldItem() {}

        public FieldItem(String name, String label, String dataType) {
            this.name = name;
            this.label = label;
            this.dataType = dataType;
        }
    }

    /** 全局变量条目 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GlobalVariableItem {
        /** 变量显示名（中文），与 @变量名 中的名称一致 */
        public String name;
        /** 变量系统编码 */
        public String code;
        /** 数据类型，固定为 "DECIMAL" */
        public String dataType;
        /** 当前值（SCALAR 类型取第一行值；LOOKUP_TABLE 类型为 null） */
        public Object currentValue;
        /** 变量描述 */
        public String description;
        /** 变量单位，如 "%" */
        public String unit;
        /** 变量类型: "SCALAR" | "LOOKUP_TABLE" */
        public String varType;

        public GlobalVariableItem() {}
    }
}
