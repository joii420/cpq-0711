package com.cpq.engine.discount;

import java.math.BigDecimal;
import java.util.UUID;

public interface DiscountCalculationService {
    DiscountResult calculate(UUID customerId, BigDecimal originalAmount);
}
