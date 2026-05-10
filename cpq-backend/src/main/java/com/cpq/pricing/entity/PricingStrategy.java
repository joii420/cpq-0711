package com.cpq.pricing.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pricing_strategy")
public class PricingStrategy extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "customer_id", nullable = false)
    public UUID customerId;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(nullable = false, length = 20)
    public String type = "DISCOUNT";

    @Column(name = "base_discount", nullable = false, precision = 5, scale = 2)
    public BigDecimal baseDiscount = new BigDecimal("100");

    @Column(name = "min_order_amount", nullable = false, precision = 18, scale = 4)
    public BigDecimal minOrderAmount = BigDecimal.ZERO;

    @Column(name = "effective_date")
    public LocalDate effectiveDate;

    @Column(name = "expiration_date")
    public LocalDate expirationDate;

    @Column(nullable = false)
    public Integer priority = 1;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "strategy", fetch = FetchType.LAZY)
    public List<PricingRule> rules;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
