package com.cpq.system.ddl.service;

import com.cpq.basicdata.entity.BasicDataAttribute;
import com.cpq.basicdata.entity.BasicDataConfig;
import com.cpq.common.exception.BusinessException;
import com.cpq.system.ddl.dto.DdlOperationDTO;
import com.cpq.system.ddl.dto.ExtendColumnRequest;
import com.cpq.system.ddl.entity.DdlOperationHistory;
import com.cpq.system.entity.User;
import com.cpq.system.lock.service.DdlOperationLockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.Session;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core service for runtime ALTER TABLE ADD COLUMN operations.
 *
 * <p>Key design decisions (v5.1 §3.4 TECH-4):
 * <ul>
 *   <li>ALTER TABLE executes <b>outside</b> any JPA transaction (PostgreSQL DDL is auto-commit).
 *       We use {@code Session.doWork} to obtain a bare JDBC connection.</li>
 *   <li>Application-level "rollback" is DROP COLUMN IF EXISTS if a post-DDL step fails.</li>
 *   <li>DDL lock (DdlOperationLockService) wraps the entire operation.</li>
 *   <li>History rows and BasicDataAttribute records are written with REQUIRES_NEW so they
 *       survive independent of each other.</li>
 * </ul>
 */
@ApplicationScoped
public class DdlExtensionService {

    private static final Logger LOG = Logger.getLogger(DdlExtensionService.class);

    /** 15-table allowlist: 14 from V44 + basic_data_attribute */
    public static final Set<String> EXTENSIBLE_TABLES = Set.of(
            "mat_part",
            "mat_bom",
            "mat_process",
            "plating_plan",
            "mat_fee",
            "plating_fee",
            "mat_customer_part_mapping",
            "element_price_source",
            "element_price_fetch_rule",
            "element_price",
            "element_daily_price",
            "basic_data_change_log",
            "exchange_rate",
            "customer_tax",
            "basic_data_attribute"
    );

    /** Supported data type patterns (case-insensitive prefix matching) */
    private static final Set<String> SIMPLE_TYPES = Set.of(
            "TEXT", "INTEGER", "BOOLEAN", "DATE", "TIMESTAMPTZ"
    );

    @Inject
    EntityManager em;

    @Inject
    DdlOperationLockService ddlLockService;

    @Inject
    DdlOperationHistoryService historyService;

    // =========================================================================
    // Query methods
    // =========================================================================

    public List<String> listExtensibleTables() {
        return EXTENSIBLE_TABLES.stream().sorted().collect(Collectors.toList());
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<String> listExistingColumns(String tableName) {
        if (!EXTENSIBLE_TABLES.contains(tableName)) {
            throw new BusinessException(400, "表 " + tableName + " 不在可扩列白名单中");
        }
        @SuppressWarnings("unchecked")
        List<String> cols = em.createNativeQuery(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = :tbl " +
                "ORDER BY ordinal_position")
                .setParameter("tbl", tableName)
                .getResultList();
        return cols;
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<DdlOperationDTO> listHistory(int page, int size, String statusFilter) {
        return DdlOperationHistory.listPaged(statusFilter, page, size)
                .stream().map(DdlOperationDTO::from).collect(Collectors.toList());
    }

    // =========================================================================
    // Core extend-column — intentionally NOT annotated @Transactional
    // =========================================================================

    /**
     * Executes ALTER TABLE ADD COLUMN outside any JPA transaction.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Validate tableName, dataType, columnName uniqueness, importance.</li>
     *   <li>Acquire DDL lock (checks for active import locks, throws 423/409).</li>
     *   <li>Execute ALTER via bare JDBC connection (doWork — no JTA).</li>
     *   <li>Register column in basic_data_attribute (REQUIRES_NEW tx).</li>
     *   <li>Write SUCCESS history (REQUIRES_NEW tx).</li>
     *   <li>Release DDL lock.</li>
     * </ol>
     * If step 3 succeeds but a later step fails, DROP COLUMN is attempted before
     * writing FAILED history.
     */
    public DdlOperationDTO extendColumn(ExtendColumnRequest req, UUID userId) {
        // ---- 1. Validate inputs ----
        validateTableName(req.tableName);
        validateDataType(req.dataType);
        validateImportance(req.importance);
        validateColumnNotExists(req.tableName, req.columnName);

        // ---- Resolve user display name ----
        String userFullName = resolveUserName(userId);

        // ---- 2. Build SQL ----
        String defaultLiteral = buildDefaultLiteral(req.dataType, req.defaultValue);
        String alterSql = buildAlterSql(req.tableName, req.columnName, req.dataType, defaultLiteral);
        String commentSql = buildCommentSql(req.tableName, req.columnName, userFullName);
        String migrationContent = alterSql + "\n" + commentSql;
        String flywayVersionHint = resolveNextFlywayVersion();

        // ---- 3. Acquire DDL lock (throws 423 if import lock active, 409 if DDL locked) ----
        ddlLockService.acquire(userId, "ADD COLUMN " + req.tableName + "." + req.columnName);

        boolean alterExecuted = false;
        try {
            // ---- 4. Execute ALTER outside JPA transaction via bare JDBC ----
            em.unwrap(Session.class).doWork(conn -> {
                try (java.sql.Statement st = conn.createStatement()) {
                    LOG.infof("Executing DDL: %s", alterSql);
                    st.executeUpdate(alterSql);
                    LOG.infof("Executing COMMENT: %s", commentSql);
                    st.executeUpdate(commentSql);
                }
            });
            alterExecuted = true;

            // ---- 5. Register in basic_data_attribute (REQUIRES_NEW) ----
            registerBasicDataAttribute(req, userId, userFullName);

            // ---- 6. Write SUCCESS history (REQUIRES_NEW) ----
            DdlOperationHistory history = historyService.recordSuccess(
                    req, userId, userFullName, migrationContent, flywayVersionHint);

            LOG.infof("DDL extension SUCCESS: table=%s column=%s by=%s",
                    req.tableName, req.columnName, userId);
            return DdlOperationDTO.from(history);

        } catch (Exception e) {
            LOG.errorf(e, "DDL extension FAILED: table=%s column=%s", req.tableName, req.columnName);

            // ---- Compensate: DROP COLUMN if ALTER already executed ----
            if (alterExecuted) {
                tryDropColumn(req.tableName, req.columnName);
            }

            // ---- Write FAILED history (REQUIRES_NEW) ----
            historyService.recordFailure(
                    req, userId, userFullName, migrationContent, flywayVersionHint,
                    e.getMessage());

            throw new BusinessException(500, "扩列失败: " + e.getMessage());
        } finally {
            ddlLockService.release(userId);
        }
    }

    // =========================================================================
    // Validation helpers
    // =========================================================================

    private void validateTableName(String tableName) {
        if (!EXTENSIBLE_TABLES.contains(tableName)) {
            throw new BusinessException(400, "表 " + tableName + " 不在可扩列白名单中，允许扩列的表: " +
                    EXTENSIBLE_TABLES.stream().sorted().collect(Collectors.joining(", ")));
        }
    }

    private void validateDataType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            throw new BusinessException(400, "dataType 不能为空");
        }
        String upper = dataType.trim().toUpperCase();

        // Simple types — exact match
        if (SIMPLE_TYPES.contains(upper)) return;

        // VARCHAR(N)
        if (upper.matches("VARCHAR\\(\\d+\\)")) {
            int n = Integer.parseInt(upper.replaceAll("\\D+", ""));
            if (n < 1 || n > 10485760) {
                throw new BusinessException(400, "VARCHAR 长度必须在 1~10485760 之间");
            }
            return;
        }

        // DECIMAL(p,s) or NUMERIC(p,s)
        if (upper.matches("(DECIMAL|NUMERIC)\\(\\d+,\\d+\\)")) {
            return;
        }

        throw new BusinessException(400, "不支持的数据类型: " + dataType +
                "。支持: VARCHAR(N) / TEXT / DECIMAL(p,s) / INTEGER / BOOLEAN / DATE / TIMESTAMPTZ");
    }

    private void validateImportance(String importance) {
        if (importance == null) return;
        String upper = importance.toUpperCase();
        if (!Set.of("CRITICAL", "IMPORTANT", "NORMAL").contains(upper)) {
            throw new BusinessException(400, "importance 必须是 CRITICAL / IMPORTANT / NORMAL 之一");
        }
    }

    private void validateColumnNotExists(String tableName, String columnName) {
        long count = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = :tbl AND column_name = :col")
                .setParameter("tbl", tableName)
                .setParameter("col", columnName.toLowerCase())
                .getSingleResult()).longValue();
        if (count > 0) {
            throw new BusinessException(400, "列 " + columnName + " 在表 " + tableName + " 中已存在");
        }
    }

    // =========================================================================
    // SQL building helpers
    // =========================================================================

    /**
     * Build the DEFAULT literal for SQL, applying proper quoting by type.
     * VARCHAR/TEXT/DATE/TIMESTAMPTZ → single-quoted string.
     * INTEGER/DECIMAL/NUMERIC/BOOLEAN → raw value (no quotes).
     */
    String buildDefaultLiteral(String dataType, String defaultValue) {
        String upper = dataType.trim().toUpperCase();
        boolean isString = upper.startsWith("VARCHAR") || upper.equals("TEXT")
                || upper.equals("DATE") || upper.equals("TIMESTAMPTZ");
        if (isString) {
            // Escape single quotes in value
            String escaped = defaultValue.replace("'", "''");
            return "'" + escaped + "'";
        }
        // Numeric / boolean: pass through as-is (basic safety check)
        if (!defaultValue.matches("[0-9a-zA-Z_.\\-]+")) {
            throw new BusinessException(400, "默认值 '" + defaultValue + "' 包含非法字符（数值/布尔类型不允许引号）");
        }
        return defaultValue;
    }

    private String buildAlterSql(String tableName, String columnName,
                                  String dataType, String defaultLiteral) {
        return "ALTER TABLE " + tableName + " ADD COLUMN " + columnName
                + " " + dataType + " NOT NULL DEFAULT " + defaultLiteral;
    }

    private String buildCommentSql(String tableName, String columnName, String userName) {
        String ts = OffsetDateTime.now().toString();
        return "COMMENT ON COLUMN " + tableName + "." + columnName
                + " IS 'Runtime extension by " + userName + " at " + ts + "'";
    }

    // =========================================================================
    // Flyway version hint
    // =========================================================================

    @Transactional(Transactional.TxType.SUPPORTS)
    String resolveNextFlywayVersion() {
        try {
            Object result = em.createNativeQuery(
                    "SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history " +
                    "WHERE version ~ '^[0-9]+$'")
                    .getSingleResult();
            if (result == null) return "V56";
            int nextVersion = ((Number) result).intValue() + 1;
            return "V" + nextVersion;
        } catch (Exception e) {
            LOG.warnf("Could not resolve Flyway version hint: %s", e.getMessage());
            return "V_NEXT";
        }
    }

    // =========================================================================
    // BasicDataAttribute registration (REQUIRES_NEW delegate to avoid tx mixing)
    // =========================================================================

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void registerBasicDataAttribute(ExtendColumnRequest req, UUID userId, String userName) {
        // Find or create a BasicDataConfig entry for the target table
        BasicDataConfig config = (BasicDataConfig) BasicDataConfig
                .find("sheetName = ?1", req.tableName).firstResult();

        if (config == null) {
            // Auto-create a minimal config entry for this table if it doesn't exist
            config = new BasicDataConfig();
            config.sheetName = req.tableName;
            config.sheetIndex = 0;
            config.headerRowIndex = 0;
            config.dataStartRowIndex = 1;
            config.description = "Auto-created for DDL extension of table " + req.tableName;
            config.status = "ACTIVE";
            config.sortOrder = 999;
            config.persist();
        }

        String variableCode = req.tableName + "." + req.columnName;

        // Idempotent: skip if already registered (safe re-entry on retry)
        long existing = BasicDataAttribute.count("variableCode = ?1", variableCode);
        if (existing > 0) {
            LOG.infof("BasicDataAttribute for %s already exists — skipping", variableCode);
            return;
        }

        // Determine next sort_order for this config
        long maxOrder = BasicDataAttribute.count("configId = ?1", config.id);

        // column_letter has max 10 chars in BasicDataAttribute
        String letterPrefix = req.columnName.toUpperCase();
        String columnLetter = letterPrefix.length() <= 10
                ? letterPrefix
                : letterPrefix.substring(0, 10);

        BasicDataAttribute attr = new BasicDataAttribute();
        attr.configId = config.id;
        attr.columnLetter = columnLetter;
        attr.columnTitle = req.columnName;
        attr.variableCode = variableCode;
        attr.variableLabel = req.columnName;
        attr.dataType = "VALUE";
        attr.status = "ACTIVE";
        attr.sortOrder = (int) (maxOrder + 1);
        attr.importanceLevel = resolveImportanceLevel(req.importance);
        attr.affectsCalculation = req.affectsCalculation;
        attr.persist();

        LOG.infof("Registered BasicDataAttribute for %s.%s", req.tableName, req.columnName);
    }

    private String resolveImportanceLevel(String importance) {
        if (importance == null) return "NORMAL";
        return switch (importance.toUpperCase()) {
            case "CRITICAL", "IMPORTANT" -> importance.toUpperCase();
            default -> "NORMAL";
        };
    }

    // =========================================================================
    // Compensation: drop column if ALTER succeeded but a later step failed
    // =========================================================================

    private void tryDropColumn(String tableName, String columnName) {
        try {
            em.unwrap(Session.class).doWork(conn -> {
                String dropSql = "ALTER TABLE " + tableName +
                        " DROP COLUMN IF EXISTS " + columnName;
                LOG.infof("Compensating DROP COLUMN: %s", dropSql);
                try (java.sql.Statement st = conn.createStatement()) {
                    st.executeUpdate(dropSql);
                }
            });
            LOG.infof("Compensation DROP COLUMN succeeded for %s.%s", tableName, columnName);
        } catch (Exception dropEx) {
            LOG.errorf(dropEx, "Compensation DROP COLUMN failed for %s.%s — manual cleanup may be required",
                    tableName, columnName);
        }
    }

    // =========================================================================
    // User name resolver
    // =========================================================================

    @Transactional(Transactional.TxType.SUPPORTS)
    String resolveUserName(UUID userId) {
        try {
            User user = User.findById(userId);
            if (user != null) return user.fullName != null ? user.fullName : user.username;
        } catch (Exception e) {
            LOG.warnf("Could not resolve user name for %s: %s", userId, e.getMessage());
        }
        return userId.toString();
    }
}
