package com.cpq.costingsummary.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "costing_summary_override")
public class CostingSummaryOverride extends PanacheEntityBase {
    @Id @GeneratedValue
    public UUID id;

    @Column(name = "summary_id", nullable = false)
    public UUID summaryId;

    @Column(name = "target_kind", nullable = false, length = 20)
    public String targetKind;       // ELEMENT / MATERIAL / EXCHANGE

    @Column(name = "target_key", nullable = false, length = 200)
    public String targetKey;

    @Column(name = "field_name", nullable = false, length = 80)
    public String fieldName;

    @Column(name = "override_value", nullable = false, precision = 18, scale = 6)
    public BigDecimal overrideValue;

    @Column(columnDefinition = "TEXT")
    public String notes;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist void prePersist() { var n = OffsetDateTime.now(); if (createdAt == null) createdAt = n; updatedAt = n; }
    @PreUpdate  void preUpdate()  { updatedAt = OffsetDateTime.now(); }
}
