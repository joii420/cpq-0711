package com.cpq.priceadjust.dto;

import java.util.List;
import java.util.UUID;

/** api.md §1.9/§1.10 — 比对列配置读写。 */
public class ComparisonColumnsDTO {
    public boolean configured;
    public String customerNo;
    public UUID templateSeriesId;
    public List<ComparisonColumnDef> columns;
}
