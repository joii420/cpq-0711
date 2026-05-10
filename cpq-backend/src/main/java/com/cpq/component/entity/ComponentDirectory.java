package com.cpq.component.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "component_directory")
public class ComponentDirectory extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "parent_id")
    public UUID parentId;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(name = "sort_order")
    public Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
