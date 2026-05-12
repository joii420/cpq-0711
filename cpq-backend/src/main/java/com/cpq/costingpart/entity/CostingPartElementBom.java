package com.cpq.costingpart.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "costing_part_element_bom")
public class CostingPartElementBom extends PanacheEntityBase {
    @Id @GeneratedValue
    public UUID id;

    @Column(name = "input_material_no", nullable = false, length = 100)
    public String inputMaterialNo;

    /** 料号版本管理 (V153): 与 input_material_no 共同组成业务唯一键, 默认 2000 */
    @Column(name = "part_version", nullable = false)
    public Integer partVersion = 2000;

    @Column(name = "seq_no", nullable = false)
    public Integer seqNo;

    @Column(name = "element_code", nullable = false, length = 50)
    public String elementCode;

    @Column(name = "composition_pct", nullable = false, precision = 8, scale = 4)
    public BigDecimal compositionPct;

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
