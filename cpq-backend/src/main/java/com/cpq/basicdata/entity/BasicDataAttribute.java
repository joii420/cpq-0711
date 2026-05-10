package com.cpq.basicdata.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "basic_data_attribute")
public class BasicDataAttribute extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "config_id", nullable = false)
    public UUID configId;

    @Column(name = "column_letter", nullable = false, length = 10)
    public String columnLetter;

    @Column(name = "column_title", nullable = false, length = 200)
    public String columnTitle;

    // V57: 从全局唯一改为 (config_id, variable_code) 复合唯一（uq_bda_config_var）
    @Column(name = "variable_code", nullable = false, length = 100)
    public String variableCode;

    @Column(name = "variable_label", nullable = false, length = 200)
    public String variableLabel;

    @Column(name = "data_type", nullable = false, length = 20)
    public String dataType = "VALUE";  // IDENTIFIER / VALUE

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

    /**
     * 字段重要性：CRITICAL / IMPORTANT / NORMAL
     * v5.1 §6.2 CONF-2
     */
    @Column(name = "importance_level", nullable = false, length = 16)
    public String importanceLevel = "NORMAL";

    /**
     * 字段变更是否触发公式重算
     * v5.1 §6.2 CONF-2
     */
    @Column(name = "affects_calculation", nullable = false)
    public Boolean affectsCalculation = false;

    /**
     * V58: 该列是否必填，解析时为空抛 ValidationResult.error。
     */
    @Column(name = "is_required", nullable = false)
    public Boolean isRequired = false;

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
