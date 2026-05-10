package com.cpq.pricing.dto;

import com.cpq.pricing.entity.PricingRule;
import com.cpq.pricing.entity.PricingStrategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class PricingStrategyDTO {

    public UUID id;
    public UUID customerId;
    public String name;
    public String type;
    public BigDecimal baseDiscount;
    public BigDecimal minOrderAmount;
    public LocalDate effectiveDate;
    public LocalDate expirationDate;
    public Integer priority;
    public String status;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    public List<PricingRuleDTO> rules;

    public static PricingStrategyDTO from(PricingStrategy strategy, List<PricingRule> rules) {
        PricingStrategyDTO dto = new PricingStrategyDTO();
        dto.id = strategy.id;
        dto.customerId = strategy.customerId;
        dto.name = strategy.name;
        dto.type = strategy.type;
        dto.baseDiscount = strategy.baseDiscount;
        dto.minOrderAmount = strategy.minOrderAmount;
        dto.effectiveDate = strategy.effectiveDate;
        dto.expirationDate = strategy.expirationDate;
        dto.priority = strategy.priority;
        dto.status = strategy.status;
        dto.createdAt = strategy.createdAt;
        dto.updatedAt = strategy.updatedAt;
        dto.rules = rules != null
                ? rules.stream().map(PricingRuleDTO::from).collect(Collectors.toList())
                : Collections.emptyList();
        return dto;
    }
}
