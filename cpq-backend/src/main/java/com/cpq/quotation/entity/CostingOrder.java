package com.cpq.quotation.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 核价单（精简）— 报价单提交时自动建，锚定核价流程元数据。
 * 表 costing_order 由 V304 迁移创建，包含 UNIQUE(quotation_id) 约束保证幂等。
 */
@Entity
@Table(name = "costing_order")
public class CostingOrder extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "quotation_id", nullable = false)
    public UUID quotationId;

    @Column(name = "submitted_by")
    public UUID submittedBy;

    @Column(name = "entered_costing_at", nullable = false)
    public OffsetDateTime enteredCostingAt;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (enteredCostingAt == null) enteredCostingAt = now;
        if (createdAt == null) createdAt = now;
    }

    /**
     * 按报价单 ID 查核价单（幂等校验用）。
     */
    public static CostingOrder findByQuotation(UUID quotationId) {
        return find("quotationId", quotationId).firstResult();
    }
}
