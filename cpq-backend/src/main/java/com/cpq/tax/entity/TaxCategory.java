package com.cpq.tax.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "tax_category")
public class TaxCategory extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @Column(nullable = false, unique = true, length = 64) public String code;
    @Column(nullable = false, length = 128) public String name;
    @Column(nullable = false, length = 16) public String status = "ACTIVE";
    @Column(name = "is_default", nullable = false) public Boolean isDefault = false;
    @Column(columnDefinition = "TEXT") public String description;
    @Column(name = "created_at", nullable = false) public OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) public OffsetDateTime updatedAt;
    @Column(name = "created_by") public UUID createdBy;
    @Column(name = "updated_by") public UUID updatedBy;

    @PrePersist public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }
    @PreUpdate public void preUpdate() { updatedAt = OffsetDateTime.now(); }
}
