package com.cpq.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class TaxCategoryVatVersionSaveRequest {
    public BigDecimal vatRate;
    public LocalDate effectiveFrom;
    public LocalDate effectiveTo;
    public String status;
}
