package com.cpq.engine.discount;

import java.math.BigDecimal;

public class DiscountResult {

    public BigDecimal discountRate;
    public String matchedRuleName;
    public String ruleType;

    public DiscountResult() {
    }

    public DiscountResult(BigDecimal discountRate, String matchedRuleName, String ruleType) {
        this.discountRate = discountRate;
        this.matchedRuleName = matchedRuleName;
        this.ruleType = ruleType;
    }

    public static DiscountResult noDiscount() {
        return new DiscountResult(new BigDecimal("100"), null, "NO_STRATEGY");
    }
}
