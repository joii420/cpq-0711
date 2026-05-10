package com.cpq.component.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "component")
public class Component extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "directory_id")
    public UUID directoryId;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(nullable = false, unique = true, length = 100)
    public String code;

    @Column(name = "column_count", nullable = false)
    public Integer columnCount = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public String fields = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public String formulas = "[]";

    @Column(name = "component_type", nullable = false, length = 20)
    public String componentType = "NORMAL";

    /**
     * Y1.5 行驱动 BNF 路径(可选)。
     * 非空 → 组件展开为该路径返回的 N 行,字段路径自动隐式 JOIN driver 行字段。
     */
    @Column(name = "data_driver_path", columnDefinition = "TEXT")
    public String dataDriverPath;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

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
}
