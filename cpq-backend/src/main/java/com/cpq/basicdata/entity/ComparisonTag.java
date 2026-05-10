package com.cpq.basicdata.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "comparison_tag")
public class ComparisonTag extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, unique = true, length = 80)
    public String code;

    @Column(nullable = false, length = 200)
    public String label;

    @Column(name = "group_name", nullable = false, length = 100)
    public String groupName;

    @Column(name = "group_sort_order", nullable = false)
    public Integer groupSortOrder = 0;

    @Column(name = "tag_sort_order", nullable = false)
    public Integer tagSortOrder = 0;

    @Column(name = "is_builtin", nullable = false)
    public Boolean isBuiltin = false;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    @Column(columnDefinition = "TEXT")
    public String description;

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
