package com.cpq.seltemplate.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "sel_template")
public class SelTemplate extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    // task-0712 update-071501: 换轴 industry_code(String) -> product_category_id(UUID)，一产品分类一套(UNIQUE)
    @Column(name = "product_category_id", nullable = false, unique = true) public UUID productCategoryId;
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
