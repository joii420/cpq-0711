package com.cpq.system.config.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "system_config")
public class SystemConfig extends PanacheEntityBase {

    @Id
    @Column(name = "config_key", length = 128)
    public String configKey;

    @Column(name = "config_value", nullable = false, columnDefinition = "TEXT")
    public String configValue;

    @Column(name = "default_value", nullable = false, columnDefinition = "TEXT")
    public String defaultValue;

    @Column(name = "data_type", nullable = false, length = 16)
    public String dataType;

    @Column(name = "category", nullable = false, length = 32)
    public String category;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "modifiable_by", nullable = false, length = 32)
    public String modifiableBy = "SYSTEM_ADMIN";

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "updated_by")
    public UUID updatedBy;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // ---- static finders ----

    public static SystemConfig findByKey(String key) {
        return findById(key);
    }

    public static List<SystemConfig> listByCategory(String category) {
        return list("category", category);
    }

}
