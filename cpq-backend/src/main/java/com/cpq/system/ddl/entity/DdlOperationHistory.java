package com.cpq.system.ddl.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persists the result of every runtime ALTER TABLE ADD COLUMN operation.
 * v5.1 §3.4 TECH-4 方案 B — migration SQL is stored in migration_content,
 * no physical .sql files are written.
 */
@Entity
@Table(name = "ddl_operation_history")
public class DdlOperationHistory extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "table_name", nullable = false, length = 64)
    public String tableName;

    @Column(name = "column_name", nullable = false, length = 64)
    public String columnName;

    @Column(name = "data_type", nullable = false, length = 64)
    public String dataType;

    @Column(name = "default_value", nullable = false)
    public String defaultValue;

    @Column(name = "importance", nullable = false, length = 16)
    public String importance = "NORMAL";

    @Column(name = "affects_calculation", nullable = false)
    public Boolean affectsCalculation = false;

    /** SUCCESS | FAILED */
    @Column(name = "status", nullable = false, length = 16)
    public String status;

    @Column(name = "error_message")
    public String errorMessage;

    /** Full ALTER TABLE + COMMENT SQL text for copy-to-git */
    @Column(name = "migration_content", nullable = false)
    public String migrationContent;

    /** Suggested next Flyway version, e.g. V56 */
    @Column(name = "flyway_version_hint", length = 32)
    public String flywayVersionHint;

    @Column(name = "created_by", nullable = false)
    public UUID createdBy;

    @Column(name = "created_by_name", length = 128)
    public String createdByName;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    // =========================================================================
    // Panache finders
    // =========================================================================

    public static List<DdlOperationHistory> listPaged(String statusFilter, int page, int size) {
        if (statusFilter != null && !statusFilter.isBlank()) {
            return find("status = ?1 ORDER BY createdAt DESC", statusFilter)
                    .page(page, size).list();
        }
        return find("ORDER BY createdAt DESC").page(page, size).list();
    }

    public static long countFiltered(String statusFilter) {
        if (statusFilter != null && !statusFilter.isBlank()) {
            return count("status = ?1", statusFilter);
        }
        return count();
    }
}
