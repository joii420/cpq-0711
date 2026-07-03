package com.cpq.component.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 全局可配置「核价树递归 SQL」配置。
 *
 * <p>全局同一时刻最多一条 {@code isActive=true}（DB 部分唯一索引 {@code ux_cbt_active} 保障）。
 * 递归 SQL 契约：输入具名参数 {@code :production_part_nos}（text[]），
 * 输出列逐字 {@code root_no / material_no / bom_version / parent_no}。
 * 详见 {@code docs/} 核价树渲染重构相关方案文档。
 */
@Entity
@Table(name = "costing_bom_tree_config")
public class CostingBomTreeConfig extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false)
    public String name;

    @Column(name = "sql_template", nullable = false, columnDefinition = "TEXT")
    public String sqlTemplate;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = false;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /** 当前全局生效配置；无 → null。 */
    public static CostingBomTreeConfig findActive() {
        return find("isActive = true").firstResult();
    }
}
