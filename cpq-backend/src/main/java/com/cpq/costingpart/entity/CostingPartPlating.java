package com.cpq.costingpart.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "costing_part_plating")
public class CostingPartPlating extends PanacheEntityBase {
    @Id @GeneratedValue
    public UUID id;

    @Column(name = "plating_no", nullable = false, length = 100)
    public String platingNo;

    /** 料号版本管理 (V153): 与 plating_no 共同组成业务唯一键, 默认 2000 */
    @Column(name = "part_version", nullable = false)
    public Integer partVersion = 2000;

    @Column(name = "version_number", nullable = false, length = 50)
    public String versionNumber;

    @Column(name = "seq_no", nullable = false)
    public Integer seqNo;

    @Column(name = "element_attr", length = 100)
    public String elementAttr;

    @Column(name = "plating_area_cm2", precision = 18, scale = 6)
    public BigDecimal platingAreaCm2;

    @Column(name = "layer_thickness_um", precision = 18, scale = 6)
    public BigDecimal layerThicknessUm;

    @Column(columnDefinition = "TEXT")
    public String requirement;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist void prePersist() { var n = OffsetDateTime.now(); if (createdAt == null) createdAt = n; updatedAt = n; }
    @PreUpdate  void preUpdate()  { updatedAt = OffsetDateTime.now(); }
}
