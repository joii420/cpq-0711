package com.cpq.importexcel.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "internal_material")
public class InternalMaterial extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "material_no", nullable = false, unique = true, length = 100)
    public String materialNo;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(length = 500)
    public String specification;

    @Column(length = 200)
    public String size;

    @Column(name = "status_code", nullable = false, length = 10)
    public String statusCode = "Y";

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
