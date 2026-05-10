package com.cpq.datasource.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "datasource")
public class DataSource extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, unique = true, length = 100)
    public String code;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(nullable = false, length = 10)
    public String type;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "sql_query", columnDefinition = "TEXT")
    public String sqlQuery;

    @Column(name = "sql_result_column", length = 100)
    public String sqlResultColumn;

    @Column(name = "api_url", length = 1000)
    public String apiUrl;

    @Column(name = "api_method", length = 10)
    public String apiMethod;

    @Column(name = "api_headers", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public String apiHeaders = "[]";

    @Column(name = "api_body_template", columnDefinition = "TEXT")
    public String apiBodyTemplate;

    @Column(name = "api_result_path", length = 500)
    public String apiResultPath;

    @Column(name = "api_timeout_seconds")
    public Integer apiTimeoutSeconds = 5;

    @Column(name = "created_by")
    public UUID createdBy;

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
