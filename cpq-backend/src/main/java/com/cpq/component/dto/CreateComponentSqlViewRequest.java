package com.cpq.component.dto;

/**
 * 创建 / 更新组件 SQL 视图的请求体。
 */
public class CreateComponentSqlViewRequest {

    /** BNF 引用名，必填，同组件内唯一（小写字母、下划线、数字）。 */
    public String sqlViewName;

    /** 含命名占位符的 SELECT SQL，必填。 */
    public String sqlTemplate;

    /** 命名空间：COMPONENT（默认）或 GLOBAL。 */
    public String scope = "COMPONENT";

    /** 可选描述。 */
    public String description;
}
