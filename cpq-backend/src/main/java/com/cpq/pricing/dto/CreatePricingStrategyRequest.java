package com.cpq.pricing.dto;

import com.cpq.common.DecimalStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
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

    @JsonDeserialize(using = DecimalStringDeserializer.class)
    public BigDecimal baseDiscount;

    @JsonDeserialize(using = DecimalStringDeserializer.class)
    public BigDecimal minOrderAmount;

    public LocalDate effectiveDate;

    public LocalDate expirationDate;

    public Integer priority;

    public List<RuleRequest> rules;

    public static class RuleRequest {
        public String ruleType;
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        public BigDecimal thresholdAmount;
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        public BigDecimal discountRate;
        public Integer sortOrder;
    }
}
