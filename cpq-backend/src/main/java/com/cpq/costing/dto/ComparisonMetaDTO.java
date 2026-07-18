package com.cpq.costing.dto;

import java.util.List;

/**
 * task-0717 比对视图 — 页签·可比对值目录（连线配置抽屉数据源）。
 * 契约见 dev-docs/task-0717-比对视图/api.md §1。与 bucket 无关（两侧目录对所有桶一致）。
 */
public class ComparisonMetaDTO {

    public List<TabMeta> quoteTabs;
    public List<TabMeta> costingTabs;

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
