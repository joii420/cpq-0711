package com.cpq.costingbasic.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "costing_exchange_rate")
public class CostingExchangeRate extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "version_id", nullable = false)
    public UUID versionId;

    @Column(name = "from_currency", nullable = false, length = 10)
    public String fromCurrency;

    @Column(name = "to_currency", nullable = false, length = 10)
    public String toCurrency;

    @Column(name = "costing_rate", nullable = false, precision = 18, scale = 6)
    public BigDecimal costingRate;

    @Column(name = "market_rate", precision = 18, scale = 6)
    public BigDecimal marketRate;

    @Column(name = "rate_rule", columnDefinition = "TEXT")
    public String rateRule;

    @Column(name = "source_url", length = 500)
    public String sourceUrl;

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
