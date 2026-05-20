package com.cpq.configtemplate.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * V203: 配置模板下的大类 (Phase B).
 *
 * <p>用户自由扩展, code 在同 template 内唯一.
 */
@Entity
@Table(name = "config_category")
public class ConfigCategory extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "template_id", nullable = false)
    public UUID templateId;

    @Column(nullable = false, length = 50)
    public String code;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
