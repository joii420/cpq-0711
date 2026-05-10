package com.cpq.template.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "product_template_binding")
public class ProductTemplateBinding extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "product_id", nullable = false)
    public UUID productId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "process_ids", nullable = false, columnDefinition = "jsonb")
    public String processIds = "[]";

    @Column(name = "process_ids_hash", nullable = false, length = 64)
    public String processIdsHash;

    @Column(name = "template_id", nullable = false)
    public UUID templateId;

    @Column(name = "is_default", nullable = false)
    public Boolean isDefault = false;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
