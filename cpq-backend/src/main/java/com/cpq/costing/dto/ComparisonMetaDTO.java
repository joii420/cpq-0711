package com.cpq.costing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * task-0717 比对视图 — 页签·可比对值目录（连线配置抽屉数据源）。
 * 契约见 dev-docs/task-0717-比对视图/api.md §1。与 bucket 无关（两侧目录对所有桶一致）。
 *
 * <p>task-0729 §1.10a 复用同一个 DTO（按 templateSeriesId 取，见
 * {@code PriceAdjustComparisonColumnService#getComparisonMeta}），仅多带一个溯源字段
 * {@link #costingSource}。
 */
public class ComparisonMetaDTO {

    public List<TabMeta> quoteTabs;
    public List<TabMeta> costingTabs;

    /**
     * task-0729 §1.10a 专用溯源标记：核价侧模板是<b>按哪一级降级</b>解析出来的
     * （{@code SERIES_LATEST} / {@code CUSTOMER_LATEST} / {@code AUTO_DEFAULT} / {@code NONE}，
     * 语义见 {@code PriceAdjustComparisonColumnService.CostingSource}）。
     *
     * <p><b>为什么必须回传</b>：四级取到的核价模板可能不同（实测同一系列跨过 2 个核价模板），
     * 出问题时得能一眼看出当时走的哪条，否则又是一个「结果对不上但查不出为什么」的现场。
     *
     * <p>🔒 {@code NON_NULL}：task-0717 的 {@code getMeta(quotationId)} 不设此字段，
     * 其响应体<b>逐字节不变</b>，老前端零影响。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String costingSource;

    public static class TabMeta {
        /** 页签(组件)ID，作为列配置里的稳定引用键。 */
        public String componentId;
        public String tabName;
        public Integer sortOrder;
        public List<MetricMeta> metrics;
    }

    public static class MetricMeta {
        /** 字段名（is_subtotal 列）或 {@code __TAB_TOTAL__}。 */
        public String key;
        public String label;
        /** SUBTOTAL_FIELD | TAB_TOTAL */
        public String type;
    }
}
