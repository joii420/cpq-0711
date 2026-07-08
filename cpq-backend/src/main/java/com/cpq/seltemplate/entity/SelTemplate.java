package com.cpq.seltemplate.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "sel_template")
public class SelTemplate extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @Column(name = "industry_code", nullable = false, unique = true, length = 50) public String industryCode;
    @Column(nullable = false, length = 100) public String name;
    @Column(nullable = false, length = 20) public String status = "ACTIVE";
    @Version @Column(nullable = false) public Integer version = 0;
    @Column(name = "created_at", nullable = false) public OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) public OffsetDateTime updatedAt;

    @PrePersist public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }
    @PreUpdate public void preUpdate() { updatedAt = OffsetDateTime.now(); }
}
