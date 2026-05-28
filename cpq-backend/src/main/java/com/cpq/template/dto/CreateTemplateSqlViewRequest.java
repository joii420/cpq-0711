package com.cpq.template.dto;

/**
 * 创建 / 更新产品卡片模板 SQL 视图的请求体。
 *
 * <p>结构与 {@link com.cpq.component.dto.CreateComponentSqlViewRequest} 对齐。
 * scope 字段保留但固定为 LOCAL（DDL CHECK 约束）。
 * V249 起替代 Phase 1 的 CreateCostingTemplateSqlViewRequest。
 */
public class CreateTemplateSqlViewRequest {

    /** BNF 引用名，必填，同模板内唯一（小写字母、下划线、数字）。 */
    public String sqlViewName;

    /** 含命名占位符的 SELECT SQL，必填。 */
    public String sqlTemplate;

    /**
     * 命名空间：当前只允许 LOCAL（模板不支持跨模板 GLOBAL 引用）。
     * 前端可省略此字段，后端默认填 LOCAL。
     */
    public String scope = "LOCAL";

    /** 可选描述。 */
    public String description;
}
