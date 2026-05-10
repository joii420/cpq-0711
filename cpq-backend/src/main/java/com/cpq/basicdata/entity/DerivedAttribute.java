package com.cpq.basicdata.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "derived_attribute")
public class DerivedAttribute extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "host_sheet_id", nullable = false)
    public UUID hostSheetId;

    @Column(name = "variable_code", nullable = false, unique = true, length = 100)
    public String variableCode;

    @Column(name = "variable_label", nullable = false, length = 200)
    public String variableLabel;

    @Column(name = "data_type", nullable = false, length = 20)
    public String dataType = "VALUE";

    @Column(name = "computation_type", nullable = false, length = 30)
    public String computationType;  // LOOKUP / EXPRESSION / AGGREGATE

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public String computation;  // JSON 字符串，根据 computationType 不同结构

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

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
