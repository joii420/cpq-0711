package com.cpq.system.ddl.dto;

import com.cpq.system.ddl.entity.DdlOperationHistory;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response DTO for DDL operation history entries.
 * v5.1 §3.4 TECH-4
 */
public class DdlOperationDTO {

    public UUID id;
    public String tableName;
    public String columnName;
    public String dataType;
    public String defaultValue;
    public String importance;
    public boolean affectsCalculation;
    public String status;
    public String errorMessage;
    /** Full ALTER TABLE + COMMENT SQL — UI shows [复制 migration] button */
    public String migrationContent;
    public String flywayVersionHint;
    public UUID createdBy;
    public String createdByName;
    public OffsetDateTime createdAt;

    public static DdlOperationDTO from(DdlOperationHistory h) {
        DdlOperationDTO dto = new DdlOperationDTO();
        dto.id = h.id;
        dto.tableName = h.tableName;
        dto.columnName = h.columnName;
        dto.dataType = h.dataType;
        dto.defaultValue = h.defaultValue;
        dto.importance = h.importance;
        dto.affectsCalculation = Boolean.TRUE.equals(h.affectsCalculation);
        dto.status = h.status;
        dto.errorMessage = h.errorMessage;
        dto.migrationContent = h.migrationContent;
        dto.flywayVersionHint = h.flywayVersionHint;
        dto.createdBy = h.createdBy;
        dto.createdByName = h.createdByName;
        dto.createdAt = h.createdAt;
        return dto;
    }
}
