package com.cpq.dataset.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Persisted sales-product VAT classification after a binding update. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DsTaxCategoryBindingResult {
    public String dataset;
    public String customerNo;
    public String axisValue;
    public UUID taxCategoryId;
    public String taxCategoryName;
    public BigDecimal vatRate;
    public OffsetDateTime updatedAt;
}
