package com.cpq.currency.dto;
import java.math.BigDecimal; import java.time.LocalDate;
public class RateRequest { public BigDecimal rate; public LocalDate effectiveDate; public String source; }
