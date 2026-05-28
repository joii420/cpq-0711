package com.cpq.template.dto;

/**
 * POST /sql-views/dry-run 请求体（产品卡片模板 SQL 视图）。
 * 形态与 {@link com.cpq.component.dto.DryRunSqlViewRequest} 完全相同，
 * 单独定义避免跨包依赖。
 * V249 起替代 Phase 1 的 DryRunCostingTemplateSqlViewRequest。
 */
public class DryRunTemplateSqlViewRequest {

    /** 待校验的 SQL 模板（含命名占位符）。 */
    public String sqlTemplate;
}
