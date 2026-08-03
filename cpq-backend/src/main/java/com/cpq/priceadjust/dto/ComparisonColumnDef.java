package com.cpq.priceadjust.dto;

/**
 * task-0729 B4.3 · 比对列定义 —— 复用 task-0717 {@code ColumnDef} schema（前端
 * {@code comparisonViewService.ts ColumnDef}），供 {@code comparison_column_config.columns}
 * JSONB 反序列化。后端此前完全没有这个类型（{@code ComparisonConfigDTO.java:20} 注释
 * "后端只存不解释内容"；{@code com.cpq.basicdata.v6.maintenance.dto.ColumnDef} 是同名不同物，
 * 本类专属 task-0729/0717 比对列语义，不与之混用）。
 */
public class ComparisonColumnDef {
    public String id;
    /** PRODUCT_TOTAL | TAB_PAIR */
    public String kind;
    public int sortOrder;
    /** 差异阈值，默认 0 */
    public java.math.BigDecimal threshold = java.math.BigDecimal.ZERO;

    // kind=TAB_PAIR 时必填
    public String quoteComponentId;
    public String quoteMetric;
    public String quoteLabel;
    public String costingComponentId;
    public String costingMetric;
    public String costingLabel;

    public boolean isProductTotal() { return "PRODUCT_TOTAL".equals(kind); }
}
