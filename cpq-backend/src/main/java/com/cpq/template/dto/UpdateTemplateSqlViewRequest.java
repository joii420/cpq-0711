package com.cpq.template.dto;

/**
 * 更新产品卡片模板 SQL 视图的请求体（与 Create 同结构，分开方便后续扩展）。
 * V249 起替代 Phase 1 的 UpdateCostingTemplateSqlViewRequest。
 */
public class UpdateTemplateSqlViewRequest {

    /** 新 BNF 引用名（可选，改名时填）。 */
    public String sqlViewName;

    /** 新 SQL 模板（可选，改 SQL 时触发 dry-run 重校验）。 */
    public String sqlTemplate;

    /** 可选描述。 */
    public String description;
}
