package com.cpq.pricing.dto;

import com.cpq.pricing.entity.PricingRule;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public class PricingRuleDTO {

    public UUID id;
    public UUID strategyId;
    public String ruleType;
    public BigDecimal thresholdAmount;
    public BigDecimal discountRate;
    public Integer sortOrder;
    public OffsetDateTime createdAt;

    public static PricingRuleDTO from(PricingRule rule) {
        PricingRuleDTO dto = new PricingRuleDTO();
        dto.id = rule.id;
        dto.strategyId = rule.strategy != null ? rule.strategy.id : null;
        dto.ruleType = rule.ruleType;
        dto.thresholdAmount = rule.thresholdAmount;
        dto.discountRate = rule.discountRate;
        dto.sortOrder = rule.sortOrder;
        dto.createdAt = rule.createdAt;
        return dto;
    }
}
