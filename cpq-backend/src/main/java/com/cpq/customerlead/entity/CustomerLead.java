package com.cpq.customerlead.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 客户线索 — 客户自助提交时的中间层，避免 customer 主表污染。
 *
 * <p>状态机：PENDING_REVIEW → CONVERTED / REJECTED
 *
 * <p>编号规则：{@code lead_code = "LEAD-" + yyyyMM + "-" + lpad(seq, 4, '0')}
 *
 * <p>详见 docs/3D产品选配方案.md §17.5
 */
@Entity
@Table(name = "customer_lead")
public class CustomerLead extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "lead_code", nullable = false, unique = true, length = 40)
    public String leadCode;

    @Column(name = "source_type", nullable = false, length = 32)
    public String sourceType;  // CUSTOMER_SELF / SHARED_LINK / IMPORT_BATCH

    @Column(name = "share_token", length = 64)
    public String shareToken;

    @Column(name = "contact_name", nullable = false, length = 128)
    public String contactName;

    @Column(name = "contact_phone", nullable = false, length = 40)
    public String contactPhone;

    @Column(name = "contact_email", length = 128)
    public String contactEmail;

    @Column(name = "company_name", length = 255)
    public String companyName;

    @Column(columnDefinition = "TEXT")
    public String note;

    @Column(nullable = false, length = 20)
    public String status = "PENDING_REVIEW";

    @Column(name = "reviewed_by")
    public UUID reviewedBy;

    @Column(name = "reviewed_at")
    public OffsetDateTime reviewedAt;

    @Column(name = "review_action", length = 32)
    public String reviewAction;  // BIND_EXISTING / CREATE_NEW / REJECT

    @Column(name = "bound_customer_id")
    public UUID boundCustomerId;

    @Column(name = "review_note", columnDefinition = "TEXT")
    public String reviewNote;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
