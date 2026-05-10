package com.cpq.datasource.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "datasource_param")
public class DataSourceParam extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "datasource_id", nullable = false)
    public UUID datasourceId;

    @Column(name = "param_order", nullable = false)
    public Integer paramOrder;

    @Column(name = "param_code", nullable = false, length = 100)
    public String paramCode;

    @Column(name = "param_name", nullable = false, length = 200)
    public String paramName;

    @Column(name = "source_type", nullable = false, length = 20)
    public String sourceType;

    @Column(name = "system_param_code", length = 50)
    public String systemParamCode;

    @Column(name = "is_required", nullable = false)
    public Boolean isRequired = true;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
