package com.cpq.costingpart.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "costing_part_design_cost")
public class CostingPartDesignCost extends PanacheEntityBase {
    @Id @GeneratedValue
    public UUID id;

    @Column(name = "hf_part_no", nullable = false, length = 100)
    public String hfPartNo;

    @Column(name = "design_drawing_no", length = 100)
    public String designDrawingNo;

    @Column(name = "version_number", length = 50)
    public String versionNumber;

    @Column(name = "design_proc_fee", precision = 18, scale = 4)
    public BigDecimal designProcFee;

    @Column(name = "design_material_fee", precision = 18, scale = 4)
    public BigDecimal designMaterialFee;

    @Column(nullable = false, length = 10)
    public String currency = "CNY";

    @Column(nullable = false, length = 20)
    public String unit = "KG";

    @Column(name = "loss_rate", precision = 8, scale = 4)
    public BigDecimal lossRate;

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
