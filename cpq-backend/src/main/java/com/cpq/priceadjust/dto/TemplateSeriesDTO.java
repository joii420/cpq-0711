package com.cpq.priceadjust.dto;

import java.util.UUID;

/** api.md §1.8 — 屏 1 比对列配置区的模板系列选择器数据源。 */
public class TemplateSeriesDTO {
    public UUID templateSeriesId;
    public String seriesName;
    public String latestVersion;
    public boolean isDefault;
    public int templateCount;
    public boolean hasComparisonConfig;
    public int columnCount;
}
