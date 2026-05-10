package com.cpq.pricing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class CreatePricingStrategyRequest {

    @NotNull
    public UUID customerId;

    @NotBlank
    public String name;

    public String type;

    public BigDecimal baseDiscount;

    public BigDecimal minOrderAmount;

    public LocalDate effectiveDate;

    public LocalDate expirationDate;

    public Integer priority;

    public List<RuleRequest> rules;

    public static class RuleRequest {
        public String ruleType;
        public BigDecimal thresholdAmount;
        public BigDecimal discountRate;
        public Integer sortOrder;
    }
}
