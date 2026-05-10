package com.cpq.quotation.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "quotation_approval")
public class QuotationApproval extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "quotation_id", nullable = false)
    public UUID quotationId;

    @Column(name = "approver_id", nullable = false)
    public UUID approverId;

    @Column(nullable = false, length = 20)
    public String action;

    @Column(columnDefinition = "TEXT")
    public String comment;

    @Column(name = "acted_at")
    public OffsetDateTime actedAt;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
