package com.cpq.tax.dto;

import com.cpq.tax.entity.TaxCategoryVatVersion;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public class TaxCategoryVatVersionDTO {
    public UUID id;
    public UUID taxCategoryId;
    public BigDecimal vatRate;
    public LocalDate effectiveFrom;
    public LocalDate effectiveTo;
    public String status;
    public OffsetDateTime createdAt;
    public static TaxCategoryVatVersionDTO from(TaxCategoryVatVersion v) {
        TaxCategoryVatVersionDTO d = new TaxCategoryVatVersionDTO();
        d.id = v.id; d.taxCategoryId = v.taxCategoryId; d.vatRate = v.vatRate;
        d.effectiveFrom = v.effectiveFrom; d.effectiveTo = v.effectiveTo; d.status = v.status; d.createdAt = v.createdAt;
        return d;
    }
}
