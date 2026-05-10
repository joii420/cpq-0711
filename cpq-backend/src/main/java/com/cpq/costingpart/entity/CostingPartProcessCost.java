package com.cpq.costingpart.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 工序级单价（8 种 cost_type 合一） */
@Entity
@Table(name = "costing_part_process_cost")
public class CostingPartProcessCost extends PanacheEntityBase {

    public static final java.util.Set<String> VALID_TYPES = java.util.Set.of(
            "LABOR", "DEPRECIATION", "ENERGY_DEDICATED", "ENERGY_SHARED",
            "CONSUMABLE", "MATERIAL_PROC", "SEMI_FINISHED_PROC", "POST_PROC");

    @Id @GeneratedValue
    public UUID id;

    @Column(name = "hf_part_no", nullable = false, length = 100)
    public String hfPartNo;

    @Column(name = "process_no", nullable = false, length = 50)
    public String processNo;

    @Column(name = "process_name", length = 200)
    public String processName;

    @Column(name = "cost_type", nullable = false, length = 30)
    public String costType;

    @Column(name = "unit_price", nullable = false, precision = 18, scale = 6)
    public BigDecimal unitPrice;

    @Column(nullable = false, length = 10)
    public String currency = "CNY";

    @Column(nullable = false, length = 20)
    public String unit = "KG";

    @Column(name = "ref_calc_version", length = 50)
    public String refCalcVersion;

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
