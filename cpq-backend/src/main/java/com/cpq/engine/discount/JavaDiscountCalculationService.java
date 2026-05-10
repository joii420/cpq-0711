package com.cpq.engine.discount;

import com.cpq.pricing.entity.PricingRule;
import com.cpq.pricing.entity.PricingStrategy;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Blocking
public class JavaDiscountCalculationService implements DiscountCalculationService {

    private static final Logger LOG = Logger.getLogger(JavaDiscountCalculationService.class);

    @Inject
    EntityManager em;

    @Override
    public DiscountResult calculate(UUID customerId, BigDecimal originalAmount) {
        if (customerId == null || originalAmount == null) {
            return DiscountResult.noDiscount();
        }

        List<PricingStrategy> strategies;
        try {
            strategies = em.createQuery(
                "SELECT ps FROM PricingStrategy ps WHERE ps.customerId = :customerId " +
                "AND ps.status = 'ACTIVE' ORDER BY ps.priority ASC",
                PricingStrategy.class)
                .setParameter("customerId", customerId)
                .getResultList();
        } catch (Exception e) {
            LOG.warn("Failed to load pricing strategies (table may not exist yet): " + e.getMessage());
            return DiscountResult.noDiscount();
        }

        if (strategies.isEmpty()) {
            return DiscountResult.noDiscount();
        }

        // Find first strategy where originalAmount >= min_order_amount
        PricingStrategy matched = null;
        for (PricingStrategy ps : strategies) {
            if (originalAmount.compareTo(ps.minOrderAmount) >= 0) {
                matched = ps;
                break;
            }
        }

        if (matched == null) {
            return DiscountResult.noDiscount();
        }

        // Within matched strategy, find all BULK_DISCOUNT rules where threshold_amount <= originalAmount
        List<PricingRule> bulkRules;
        try {
            bulkRules = em.createQuery(
                "SELECT pr FROM PricingRule pr WHERE pr.strategy.id = :strategyId " +
                "AND pr.ruleType = 'BULK_DISCOUNT' AND pr.thresholdAmount <= :amount " +
                "ORDER BY pr.discountRate ASC",
                PricingRule.class)
                .setParameter("strategyId", matched.id)
                .setParameter("amount", originalAmount)
                .getResultList();
        } catch (Exception e) {
            LOG.warn("Failed to load pricing rules: " + e.getMessage());
            return new DiscountResult(matched.baseDiscount, matched.name, "BASE_DISCOUNT");
        }

        if (!bulkRules.isEmpty()) {
            // Pick the one with lowest discount_rate (most favorable to customer)
            PricingRule bestRule = bulkRules.get(0);
            return new DiscountResult(bestRule.discountRate, matched.name, "BULK_DISCOUNT");
        }

        // No bulk rule matches, use base_discount
        return new DiscountResult(matched.baseDiscount, matched.name, "BASE_DISCOUNT");
    }
}
