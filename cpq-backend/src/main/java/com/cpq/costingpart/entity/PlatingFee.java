package com.cpq.costingpart.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 核价侧电镀费用 (V125 新表 costing_part_plating_fee).
 * 不带 customer / version 维度 — 与 costing_part_* 系列的"按 partNo 出厂级标准成本"模型一致.
 * 报价侧对应表 mat_plating_fee 走 VersionedWriter, 与本实体无关.
 */
@Entity
@Table(name = "costing_part_plating_fee")
public class PlatingFee extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "hf_part_no", nullable = false, length = 100)
    public String hfPartNo;

    /** 料号版本管理 (V153): 与 hf_part_no 共同组成业务唯一键, 默认 2000 */
    @Column(name = "part_version", nullable = false)
    public Integer partVersion = 2000;

    @Column(name = "plating_plan_code", length = 32)
    public String platingPlanCode;

    @Column(name = "plan_version", length = 16)
    public String planVersion;

    @Column(name = "plating_process_fee", precision = 18, scale = 4)
    public BigDecimal platingProcessFee;

    @Column(name = "plating_material_fee", precision = 18, scale = 4)
    public BigDecimal platingMaterialFee;

    @Column(length = 10)
    public String currency;

    @Column(name = "price_unit", length = 20)
    public String priceUnit;

    @Column(name = "defect_rate", precision = 10, scale = 4)
    public BigDecimal defectRate;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;

    @Column(columnDefinition = "TEXT")
    public String notes;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
