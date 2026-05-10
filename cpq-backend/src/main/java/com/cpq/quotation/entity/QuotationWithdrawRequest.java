package com.cpq.quotation.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "quotation_withdraw_request")
public class QuotationWithdrawRequest extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "quotation_id", nullable = false)
    public UUID quotationId;

    @Column(name = "requested_by", nullable = false)
    public UUID requestedBy;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String reason;

    @Column(nullable = false, length = 20)
    public String status = "PENDING";  // PENDING / APPROVED / REJECTED

    @Column(name = "decided_by")
    public UUID decidedBy;

    @Column(name = "decided_at")
    public OffsetDateTime decidedAt;

    @Column(name = "decision_note", columnDefinition = "TEXT")
    public String decisionNote;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
