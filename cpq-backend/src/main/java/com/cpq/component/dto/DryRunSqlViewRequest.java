package com.cpq.component.dto;

/**
 * POST /dry-run 请求体。
 */
public class DryRunSqlViewRequest {

    /** 待校验的 SQL 模板（含命名占位符）。 */
    public String sqlTemplate;
}
