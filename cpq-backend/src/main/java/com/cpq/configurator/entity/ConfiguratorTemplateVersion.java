package com.cpq.configurator.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "product_config_template_version",
       uniqueConstraints = @UniqueConstraint(columnNames = {"template_id", "version"}))
public class ConfiguratorTemplateVersion extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "template_id", nullable = false)
    public UUID templateId;

    @Column(nullable = false)
    public Integer version;

    @Column(length = 64)
    public String label;

    @Column(nullable = false, length = 16)
    public String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    public Map<String, Object> snapshot;

    @Column(name = "change_summary", columnDefinition = "TEXT")
    public String changeSummary;

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "published_at")
    public OffsetDateTime publishedAt;

    @Column(name = "archived_at")
    public OffsetDateTime archivedAt;
}
