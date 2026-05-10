package com.cpq.pricing.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "pricing_rule")
public class PricingRule extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "strategy_id", nullable = false)
    public PricingStrategy strategy;

    @Column(name = "rule_type", nullable = false, length = 30)
    public String ruleType = "BULK_DISCOUNT";

    @Column(name = "threshold_amount", nullable = false, precision = 18, scale = 4)
    public BigDecimal thresholdAmount;

    @Column(name = "discount_rate", nullable = false, precision = 5, scale = 2)
    public BigDecimal discountRate;

    @Column(name = "sort_order")
    public Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
