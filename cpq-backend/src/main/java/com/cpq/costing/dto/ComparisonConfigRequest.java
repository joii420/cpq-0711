package com.cpq.costing.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * task-0717 比对视图 — PUT /comparison-view/config 请求体：{@code { columns: [...] } }。
 * 契约见 dev-docs/task-0717-比对视图/api.md §4。
 */
public class ComparisonConfigRequest {
    public JsonNode columns;
}
