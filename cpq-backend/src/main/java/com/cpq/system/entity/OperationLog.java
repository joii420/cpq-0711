package com.cpq.system.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "operation_log")
public class OperationLog extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public UUID id;

    @Column(name = "operator_id", nullable = false)
    public UUID operatorId;

    @Column(name = "operation_type", nullable = false, length = 50)
    public String operationType;

    @Column(name = "target_type", nullable = false, length = 50)
    public String targetType;

    @Column(name = "target_id")
    public UUID targetId;

    @Column
    public String summary;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
