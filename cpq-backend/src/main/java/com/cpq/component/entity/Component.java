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

    /**
     * 行键配置（报价单整份快照 Phase 1）。
     * JSON 数组，元素为 fields[].name 中存在的字段名，作为该组件 driver 行的业务标识。
     * 例如 ["子件","元素"] 或哨兵 ["__seq_no__"]（显式豁免，按行号对齐）。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "row_key_fields")
    public String rowKeyFields;

    /**
     * 树表配置(纯展示,可选)。JSON 对象 {idField,parentField,defaultExpanded}。
     * 非空 → 报价/核价/详情渲染时按邻接表重排成树;不改行集合/行序。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tree_config")
    public String treeConfig;

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
