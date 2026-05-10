package com.cpq.product.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "product")
public class Product extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(name = "part_no", nullable = false, unique = true, length = 100)
    public String partNo;

    @Column(nullable = false, length = 30)
    public String category;

    @Column(name = "category_id")
    public UUID categoryId;

    @Column(length = 500)
    public String specification;

    @Column(name = "drawing_no", length = 200)
    public String drawingNo;

    @Column(length = 200)
    public String dimension;

    @Column(length = 200)
    public String material;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public String tags = "[]";

    @Column(name = "external_id", length = 200)
    public String externalId;

    @Column(name = "last_synced_at")
    public OffsetDateTime lastSyncedAt;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
