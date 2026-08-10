package com.cpq.system.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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

    /**
     * task-0806（V382 加法式新增列）：结构化 diff（改前改后），供模板发布全量冻结的
     * admin 后门审计使用（TEMPLATE_SNAPSHOT_FORCE_REFRESH / TEMPLATE_TC_DELETE /
     * TEMPLATE_OVERRIDE_PROMOTE）。可空，不影响 CustomerService 等既有写入方。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
