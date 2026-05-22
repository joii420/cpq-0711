package com.cpq.template.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * V212: 模板 ↔ 全局变量 绑定实体.
 *
 * <p>FK 引用 global_variable_definition.code (VARCHAR(64) 主键, V104).
 * 不存在 UUID 主键列 — ADR-002 §2 决策点 2 明确规范.
 */
@Entity
@Table(name = "template_global_variable_binding")
public class TemplateGlobalVariableBinding extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    /** FK → template.id ON DELETE CASCADE */
    @Column(name = "template_id", nullable = false)
    public UUID templateId;

    /** FK → global_variable_definition.code (VARCHAR(64) 业务编码, 非 UUID) */
    @Column(name = "global_variable_code", nullable = false, length = 64)
    public String globalVariableCode;

    /** 引用数据 Tab 中的卡片渲染顺序, 0-based 升序 */
    @Column(name = "display_order", nullable = false)
    public int displayOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
