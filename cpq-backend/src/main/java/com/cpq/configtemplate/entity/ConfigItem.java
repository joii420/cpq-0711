package com.cpq.configtemplate.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * V203: 配置模板大类下的明细项 (Phase B).
 *
 * <p>code 在同 category 内唯一. default_value 用于 LIST_FORMULA 渲染时
 * per_item_rules 缺项 / branches 全不匹配 / default_formula 也没配的兜底.
 */
@Entity
@Table(name = "config_item")
public class ConfigItem extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "category_id", nullable = false)
    public UUID categoryId;

    @Column(nullable = false, length = 50)
    public String code;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(name = "default_value", length = 500)
    public String defaultValue;

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
