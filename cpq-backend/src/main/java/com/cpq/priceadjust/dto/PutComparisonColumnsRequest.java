package com.cpq.priceadjust.dto;

import java.util.List;
import java.util.UUID;

/** api.md §1.10 — PUT /price-adjust/comparison-columns 请求体。 */
public class PutComparisonColumnsRequest {
    public String customerNo;
    public UUID templateSeriesId;
    public List<ComparisonColumnDef> columns;
}
