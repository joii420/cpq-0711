package com.cpq.costingsummary.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "costing_summary_result")
public class CostingSummaryResult extends PanacheEntityBase {
    @Id @GeneratedValue
    public UUID id;

    @Column(name = "summary_id", nullable = false)
    public UUID summaryId;

    @Column(name = "metric_code", nullable = false, length = 80)
    public String metricCode;

    @Column(name = "metric_label", length = 200)
    public String metricLabel;

    @Column(precision = 18, scale = 6)
    public BigDecimal value;

    @Column(nullable = false, length = 10)
    public String currency = "USD";

    @Column(name = "formula_used", columnDefinition = "TEXT")
    public String formulaUsed;

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist void prePersist() { if (createdAt == null) createdAt = OffsetDateTime.now(); }
}
