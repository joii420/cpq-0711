package com.cpq.configtemplate.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * V203: 配置模板 - LIST_FORMULA 字段类型的数据源 (Phase B).
 *
 * <p>三态机:
 * <ul>
 *   <li>DRAFT - 初创态, 不能被组件字段引用</li>
 *   <li>PUBLISHED - 可用态</li>
 *   <li>ARCHIVED - 归档态, 历史 snapshot 保护可继续渲染, 不能新建绑定</li>
 * </ul>
 */
@Entity
@Table(name = "config_template")
public class ConfigTemplate extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, length = 50)
    public String code;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(nullable = false, length = 20)
    public String status = "DRAFT";

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "published_at")
    public OffsetDateTime publishedAt;

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
