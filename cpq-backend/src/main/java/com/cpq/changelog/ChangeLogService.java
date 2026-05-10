package com.cpq.changelog;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.StreamingOutput;
import org.hibernate.Session;
import org.jboss.logging.Logger;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Read-only service for the UI-7 Change Log page.
 *
 * <p>Queries basic_data_change_log (V44 schema + V52 field-level columns).
 * All SQL is executed via JDBC PreparedStatement (no SQL injection risk).
 */
@ApplicationScoped
public class ChangeLogService {

    private static final Logger LOG = Logger.getLogger(ChangeLogService.class);

    /** system_config key for export row limit (default 10000 if not configured). */
    private static final String CFG_EXPORT_MAX_ROWS = "business.export_max_rows";
    private static final int    DEFAULT_EXPORT_MAX  = 10000;

    /** CSV column headers. */
    private static final String[] CSV_HEADERS = {
        "id", "table_name", "record_id", "customer_id", "hf_part_no",
        "field_name", "old_value", "new_value",
        "importance", "affects_calculation", "change_source", "note",
        "changed_at", "changed_by", "changed_by_name", "import_record_id"
    };

    @Inject
    EntityManager em;

    // ─────────────────────────────────────────────────────────────────────────
    // search
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Paginated search of change log entries, ordered by changed_at DESC.
     *
     * @param params filter parameters (all optional, null means no filter)
     * @param page   0-based page index
     * @param size   page size [1,200]
     * @return paged result
     */
    @Transactional
    public PageResult<ChangeLogEntryDTO> search(ChangeLogSearchParams params, int page, int size) {
        WhereClause wc = buildWhere(params);

        long total = executeCount(wc);

        List<ChangeLogEntryDTO> items = new ArrayList<>();
        em.unwrap(Session.class).doWork(conn -> {
            String sql = "SELECT l.*, u.full_name AS _changed_by_name" +
                         " FROM basic_data_change_log l" +
                         " LEFT JOIN \"user\" u ON u.id = l.changed_by" +
                         wc.sql +
                         " ORDER BY l.changed_at DESC" +
                         " LIMIT ? OFFSET ?";

            try (var ps = conn.prepareStatement(sql)) {
                int idx = bindParams(ps, wc.orderedParams, 1);
                ps.setInt(idx++, size);
                ps.setInt(idx,   page * size);

                try (var rs = ps.executeQuery()) {
                    Map<String, Integer> colIdx = buildColumnIndex(rs.getMetaData());
                    while (rs.next()) {
                        items.add(mapRow(colIdx, rs));
                    }
                }
            }
        });

        return new PageResult<>(items, page, size, total);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // export
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Streaming export of change log entries (CSV or XLSX).
     *
     * <p>Row limit is read from system_config key {@code business.export_max_rows}
     * (defaults to 10000 if key absent). If the matching row count exceeds the limit,
     * a 422 BusinessException is thrown before streaming starts.
     *
     * @param params search filter
     * @param format "csv" or "xlsx"
     * @return StreamingOutput for JAX-RS Response
     */
    @Transactional
    public StreamingOutput export(ChangeLogSearchParams params, String format) {
        if (format == null || (!format.equalsIgnoreCase("csv") && !format.equalsIgnoreCase("xlsx"))) {
            throw new BusinessException(400, "format 只支持 csv 或 xlsx");
        }

        int maxRows = resolveExportMaxRows();

        WhereClause wc = buildWhere(params);
        long total = executeCount(wc);

        if (total > maxRows) {
            throw new BusinessException(422,
                    "导出行数 " + total + " 超过上限 " + maxRows + "，请缩小筛选范围后重试");
        }

        List<ChangeLogEntryDTO> rows = fetchAll(wc);

        if (format.equalsIgnoreCase("csv")) {
            return buildCsvStream(rows);
        } else {
            return buildXlsxStream(rows);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private: WHERE builder
    // ─────────────────────────────────────────────────────────────────────────

    private record WhereClause(String sql, List<Object> orderedParams) {}

    private WhereClause buildWhere(ChangeLogSearchParams p) {
        List<String> conditions = new ArrayList<>();
        List<Object> params     = new ArrayList<>();

        if (p != null && p.customerId != null) {
            conditions.add("l.customer_id = ?");
            params.add(p.customerId);
        }
        if (p != null && p.hfPartNo != null && !p.hfPartNo.isBlank()) {
            conditions.add("l.hf_part_no = ?");
            params.add(p.hfPartNo);
        }
        if (p != null && p.tableName != null && !p.tableName.isBlank()) {
            conditions.add("l.table_name = ?");
            params.add(p.tableName);
        }
        if (p != null && p.fieldName != null && !p.fieldName.isBlank()) {
            conditions.add("l.field_name = ?");
            params.add(p.fieldName);
        }
        if (p != null && p.changedAtFrom != null) {
            conditions.add("l.changed_at >= ?");
            params.add(p.changedAtFrom);
        }
        if (p != null && p.changedAtTo != null) {
            conditions.add("l.changed_at <= ?");
            params.add(p.changedAtTo);
        }
        if (p != null && p.importance != null && !p.importance.isBlank()) {
            conditions.add("l.importance = ?");
            params.add(p.importance);
        }
        if (p != null && p.changeSource != null && !p.changeSource.isBlank()) {
            conditions.add("l.change_source = ?");
            params.add(p.changeSource);
        }

        String whereSql = conditions.isEmpty() ? "" :
                " WHERE " + String.join(" AND ", conditions);

        return new WhereClause(whereSql, params);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private: helpers
    // ─────────────────────────────────────────────────────────────────────────

    private long executeCount(WhereClause wc) {
        long[] result = {0L};
        em.unwrap(Session.class).doWork(conn -> {
            String sql = "SELECT COUNT(*) FROM basic_data_change_log l" + wc.sql;
            try (var ps = conn.prepareStatement(sql)) {
                bindParams(ps, wc.orderedParams, 1);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) result[0] = rs.getLong(1);
                }
            }
        });
        return result[0];
    }

    private List<ChangeLogEntryDTO> fetchAll(WhereClause wc) {
        List<ChangeLogEntryDTO> rows = new ArrayList<>();
        em.unwrap(Session.class).doWork(conn -> {
            String sql = "SELECT l.*, u.full_name AS _changed_by_name" +
                         " FROM basic_data_change_log l" +
                         " LEFT JOIN \"user\" u ON u.id = l.changed_by" +
                         wc.sql +
                         " ORDER BY l.changed_at DESC";
            try (var ps = conn.prepareStatement(sql)) {
                bindParams(ps, wc.orderedParams, 1);
                try (var rs = ps.executeQuery()) {
                    Map<String, Integer> colIdx = buildColumnIndex(rs.getMetaData());
                    while (rs.next()) rows.add(mapRow(colIdx, rs));
                }
            }
        });
        return rows;
    }

    private ChangeLogEntryDTO mapRow(Map<String, Integer> colIdx, java.sql.ResultSet rs)
            throws java.sql.SQLException {
        ChangeLogEntryDTO dto = new ChangeLogEntryDTO();
        dto.id             = getUUID(colIdx, rs, "id");
        dto.tableName      = getString(colIdx, rs, "table_name");
        dto.recordId       = getUUID(colIdx, rs, "record_id");
        dto.customerId     = getUUID(colIdx, rs, "customer_id");
        dto.hfPartNo       = getString(colIdx, rs, "hf_part_no");
        dto.fieldName      = getString(colIdx, rs, "field_name");
        dto.oldValue       = getString(colIdx, rs, "old_value");
        dto.newValue       = getString(colIdx, rs, "new_value");
        dto.importance     = getString(colIdx, rs, "importance");

        Integer acIdx = colIdx.get("affects_calculation");
        if (acIdx != null) {
            Object ac = rs.getObject(acIdx);
            dto.affectsCalculation = ac != null ? Boolean.parseBoolean(ac.toString()) : null;
        }

        dto.changeSource   = getString(colIdx, rs, "change_source");
        dto.note           = getString(colIdx, rs, "note");

        Integer caIdx = colIdx.get("changed_at");
        if (caIdx != null) {
            Object ts = rs.getObject(caIdx);
            dto.changedAt = ts != null ? ts.toString() : null;
        }

        dto.changedBy      = getUUID(colIdx, rs, "changed_by");
        dto.changedByName  = getString(colIdx, rs, "_changed_by_name");
        dto.importRecordId = getUUID(colIdx, rs, "import_record_id");
        return dto;
    }

    private int resolveExportMaxRows() {
        try {
            Object val = em.createNativeQuery(
                    "SELECT config_value FROM system_config WHERE config_key = :k")
                    .setParameter("k", CFG_EXPORT_MAX_ROWS)
                    .getSingleResult();
            return Integer.parseInt(val.toString());
        } catch (Exception e) {
            LOG.debugf("system_config key '%s' not found, using default %d",
                    CFG_EXPORT_MAX_ROWS, DEFAULT_EXPORT_MAX);
            return DEFAULT_EXPORT_MAX;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Streaming builders
    // ─────────────────────────────────────────────────────────────────────────

    private StreamingOutput buildCsvStream(List<ChangeLogEntryDTO> rows) {
        return out -> {
            try (var writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
                writer.write('\uFEFF'); // BOM for Excel CSV compatibility
                writer.write(String.join(",", CSV_HEADERS));
                writer.newLine();
                for (ChangeLogEntryDTO row : rows) {
                    writer.write(csvRow(row));
                    writer.newLine();
                }
            }
        };
    }

    private String csvRow(ChangeLogEntryDTO r) {
        return String.join(",",
                csvCell(r.id),
                csvCell(r.tableName),
                csvCell(r.recordId),
                csvCell(r.customerId),
                csvCell(r.hfPartNo),
                csvCell(r.fieldName),
                csvCell(r.oldValue),
                csvCell(r.newValue),
                csvCell(r.importance),
                csvCell(r.affectsCalculation),
                csvCell(r.changeSource),
                csvCell(r.note),
                csvCell(r.changedAt),
                csvCell(r.changedBy),
                csvCell(r.changedByName),
                csvCell(r.importRecordId)
        );
    }

    private String csvCell(Object val) {
        if (val == null) return "";
        String s = val.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private StreamingOutput buildXlsxStream(List<ChangeLogEntryDTO> rows) {
        return out -> {
            try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
                var sheet = wb.createSheet("change_log");
                var headerRow = sheet.createRow(0);
                for (int i = 0; i < CSV_HEADERS.length; i++) {
                    headerRow.createCell(i).setCellValue(CSV_HEADERS[i]);
                }
                int rowIdx = 1;
                for (ChangeLogEntryDTO r : rows) {
                    var row = sheet.createRow(rowIdx++);
                    Object[] vals = {
                        r.id, r.tableName, r.recordId, r.customerId, r.hfPartNo,
                        r.fieldName, r.oldValue, r.newValue, r.importance,
                        r.affectsCalculation, r.changeSource, r.note,
                        r.changedAt, r.changedBy, r.changedByName, r.importRecordId
                    };
                    for (int i = 0; i < vals.length; i++) {
                        row.createCell(i).setCellValue(vals[i] != null ? vals[i].toString() : "");
                    }
                }
                wb.write(out);
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JDBC utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Binds ordered params to a PreparedStatement starting at {@code startIdx} (1-based).
     * Returns the next unused parameter index.
     */
    private int bindParams(java.sql.PreparedStatement ps, List<Object> params, int startIdx)
            throws java.sql.SQLException {
        int idx = startIdx;
        for (Object val : params) {
            if (val instanceof UUID u) {
                ps.setObject(idx++, u, java.sql.Types.OTHER);
            } else if (val instanceof OffsetDateTime odt) {
                ps.setObject(idx++, odt);
            } else {
                ps.setString(idx++, val.toString());
            }
        }
        return idx;
    }

    private Map<String, Integer> buildColumnIndex(java.sql.ResultSetMetaData meta)
            throws java.sql.SQLException {
        Map<String, Integer> idx = new LinkedHashMap<>();
        int cols = meta.getColumnCount();
        for (int i = 1; i <= cols; i++) {
            idx.put(meta.getColumnName(i).toLowerCase(), i);
        }
        return idx;
    }

    private UUID getUUID(Map<String, Integer> colIdx, java.sql.ResultSet rs, String col)
            throws java.sql.SQLException {
        Integer i = colIdx.get(col);
        if (i == null) return null;
        Object v = rs.getObject(i);
        if (v == null) return null;
        if (v instanceof UUID u) return u;
        return UUID.fromString(v.toString());
    }

    private String getString(Map<String, Integer> colIdx, java.sql.ResultSet rs, String col)
            throws java.sql.SQLException {
        Integer i = colIdx.get(col);
        if (i == null) return null;
        return rs.getString(i);
    }
}
