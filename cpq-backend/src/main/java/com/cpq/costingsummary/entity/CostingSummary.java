package com.cpq.costingsummary.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "costing_summary")
public class CostingSummary extends PanacheEntityBase {

    public static final String STATUS_DRAFT     = "DRAFT";
    public static final String STATUS_COMPUTED  = "COMPUTED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ARCHIVED  = "ARCHIVED";

    @Id @GeneratedValue
    public UUID id;

    @Column(name = "summary_no", nullable = false, length = 50, unique = true)
    public String summaryNo;

    @Column(name = "hf_part_no", nullable = false, length = 100)
    public String hfPartNo;

    @Column(name = "element_version_id", nullable = false)
    public UUID elementVersionId;

    @Column(name = "material_version_id", nullable = false)
    public UUID materialVersionId;

    @Column(name = "exchange_version_id", nullable = false)
    public UUID exchangeVersionId;

    @Column(nullable = false, length = 20)
    public String status = STATUS_DRAFT;

    @Column(name = "quote_currency", nullable = false, length = 10)
    public String quoteCurrency = "USD";

    @Column(columnDefinition = "TEXT")
    public String notes;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "computed_at")
    public OffsetDateTime computedAt;

    @Column(name = "published_at")
    public OffsetDateTime publishedAt;

    @Column(name = "published_by")
    public UUID publishedBy;

    @PrePersist void prePersist() { var n = OffsetDateTime.now(); if (createdAt == null) createdAt = n; updatedAt = n; }
    @PreUpdate  void preUpdate()  { updatedAt = OffsetDateTime.now(); }
}
