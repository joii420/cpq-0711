package com.cpq.approval.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "approval_rule")
public class ApprovalRule extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "rule_type", nullable = false, length = 20)
    public String ruleType;

    @Column(name = "approver_id")
    public UUID approverId;

    @Column(name = "match_field", length = 20)
    public String matchField;

    @Column(name = "match_value_id")
    public UUID matchValueId;

    @Column(nullable = false)
    public Integer priority = 100;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
