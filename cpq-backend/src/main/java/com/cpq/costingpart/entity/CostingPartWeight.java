package com.cpq.costingpart.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "costing_part_weight")
public class CostingPartWeight extends PanacheEntityBase {
    @Id @GeneratedValue
    public UUID id;

    @Column(name = "hf_part_no", nullable = false, length = 100, unique = true)
    public String hfPartNo;

    @Column(name = "weight_g_per_pcs", nullable = false, precision = 18, scale = 6)
    public BigDecimal weightGPerPcs;

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
