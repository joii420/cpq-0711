package com.cpq.costingpart.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "costing_part_quality_check")
public class CostingPartQualityCheck extends PanacheEntityBase {

    public static final java.util.Set<String> VALID_STAGES = java.util.Set.of("INCOMING", "SEMI_FINISHED");

    @Id @GeneratedValue
    public UUID id;

    @Column(name = "hf_part_no", nullable = false, length = 100)
    public String hfPartNo;

    /** 料号版本管理 (V153): 与 hf_part_no 共同组成业务唯一键, 默认 2000 */
    @Column(name = "part_version", nullable = false)
    public Integer partVersion = 2000;

    @Column(nullable = false, length = 20)
    public String stage;

    @Column(name = "primary_seq_no")
    public Integer primarySeqNo;

    @Column(name = "seq_no", nullable = false)
    public Integer seqNo;

    @Column(name = "requirement_code", length = 100)
    public String requirementCode;

    @Column(name = "requirement_desc", columnDefinition = "TEXT")
    public String requirementDesc;

    @Column(name = "scrap_rate", precision = 8, scale = 4)
    public BigDecimal scrapRate;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;

    @Column(columnDefinition = "TEXT")
    public String notes;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist void prePersist() { var n = OffsetDateTime.now(); if (createdAt == null) createdAt = n; updatedAt = n; }
    @PreUpdate  void preUpdate()  { updatedAt = OffsetDateTime.now(); }
}
