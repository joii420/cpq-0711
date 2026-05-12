package com.cpq.costingpart.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "costing_part_material_bom")
public class CostingPartMaterialBom extends PanacheEntityBase {
    @Id @GeneratedValue
    public UUID id;

    @Column(name = "hf_part_no", nullable = false, length = 100)
    public String hfPartNo;

    /** 料号版本管理 (V153): 与 hf_part_no 共同组成业务唯一键, 默认 2000 */
    @Column(name = "part_version", nullable = false)
    public Integer partVersion = 2000;

    @Column(name = "seq_no", nullable = false)
    public Integer seqNo;

    @Column(name = "input_material_no", length = 100)
    public String inputMaterialNo;

    @Column(name = "process_no", length = 50)
    public String processNo;

    @Column(name = "process_name", length = 200)
    public String processName;

    @Column(name = "input_qty", precision = 18, scale = 6)
    public BigDecimal inputQty;

    @Column(name = "input_unit", length = 20)
    public String inputUnit;

    @Column(name = "output_qty", precision = 18, scale = 6)
    public BigDecimal outputQty;

    @Column(name = "output_unit", length = 20)
    public String outputUnit;

    @Column(name = "output_loss_rate", precision = 8, scale = 4)
    public BigDecimal outputLossRate;

    @Column(name = "fixed_loss_qty", precision = 18, scale = 6)
    public BigDecimal fixedLossQty;

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
