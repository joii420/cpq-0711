package com.cpq.system.ddl.service;

import com.cpq.system.ddl.dto.ExtendColumnRequest;
import com.cpq.system.ddl.entity.DdlOperationHistory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persists DDL operation history in independent transactions (REQUIRES_NEW)
 * so that audit records survive even when the caller rolls back.
 * v5.1 §3.4 TECH-4
 */
@ApplicationScoped
public class DdlOperationHistoryService {

    /**
     * Record a successful ALTER TABLE operation.
     * Runs in its own transaction so the history row is committed
     * regardless of what happens in the calling context.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public DdlOperationHistory recordSuccess(ExtendColumnRequest req,
                                             UUID userId,
                                             String userFullName,
                                             String migrationContent,
                                             String flywayVersionHint) {
        DdlOperationHistory h = new DdlOperationHistory();
        h.tableName = req.tableName;
        h.columnName = req.columnName;
        h.dataType = req.dataType;
        h.defaultValue = req.defaultValue;
        h.importance = resolveImportance(req.importance);
        h.affectsCalculation = req.affectsCalculation;
        h.status = "SUCCESS";
        h.migrationContent = migrationContent;
        h.flywayVersionHint = flywayVersionHint;
        h.createdBy = userId;
        h.createdByName = userFullName;
        h.createdAt = OffsetDateTime.now();
        h.persist();
        return h;
    }

    /**
     * Record a failed ALTER TABLE operation.
     * Runs in its own transaction.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public DdlOperationHistory recordFailure(ExtendColumnRequest req,
                                             UUID userId,
                                             String userFullName,
                                             String migrationContent,
                                             String flywayVersionHint,
                                             String errorMessage) {
        DdlOperationHistory h = new DdlOperationHistory();
        h.tableName = req.tableName;
        h.columnName = req.columnName;
        h.dataType = req.dataType;
        h.defaultValue = req.defaultValue;
        h.importance = resolveImportance(req.importance);
        h.affectsCalculation = req.affectsCalculation;
        h.status = "FAILED";
        h.errorMessage = errorMessage;
        h.migrationContent = migrationContent;
        h.flywayVersionHint = flywayVersionHint;
        h.createdBy = userId;
        h.createdByName = userFullName;
        h.createdAt = OffsetDateTime.now();
        h.persist();
        return h;
    }

    private String resolveImportance(String val) {
        if (val == null) return "NORMAL";
        return switch (val.toUpperCase()) {
            case "CRITICAL", "IMPORTANT" -> val.toUpperCase();
            default -> "NORMAL";
        };
    }
}
