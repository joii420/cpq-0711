package com.cpq.versioning.query;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.Session;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Read-only query service for versioned business tables (UI-5 / UI-6).
 *
 * <p>Supports three operations:
 * <ol>
 *   <li>{@link #listHistory} — paginated history of all versions for a business-key</li>
 *   <li>{@link #getRowDetail} — full column map for a single row by UUID</li>
 *   <li>{@link #compareVersions} — field-level diff between two version rows</li>
 * </ol>
 *
 * <p>Table names are validated against a hard-coded whitelist to prevent SQL injection.
 * Row UUIDs are passed as JDBC parameters.
 */
@ApplicationScoped
public class VersioningQueryService {

    private static final Logger LOG = Logger.getLogger(VersioningQueryService.class);

    /** Allowed table names (whitelist). */
    private static final Set<String> ALLOWED_TABLES =
            Set.of("mat_process", "mat_fee", "plating_fee");

    /**
     * Business key columns per table (used to build WHERE clause for history query).
     * Does NOT include customer_id / hf_part_no (added separately).
     */
    private static final Map<String, List<String>> BIZ_KEY_COLS = Map.of(
            "mat_process",  List.of("seq_no", "sub_seq_no"),
            "mat_fee",      List.of("fee_type"),
            "plating_fee",  List.of("plating_plan_code", "plan_version")
    );

    /**
     * Data columns per table (for compare diff — mirrors VersionedWriter.TABLE_META_MAP).
     */
    private static final Map<String, List<String>> DATA_COLS = Map.of(
            "mat_process",  List.of("process_code", "assembly_process", "component_part_no",
                                    "component_name", "supplier_code", "supplier_name",
                                    "quantity", "quantity_unit", "unit_price", "freight",
                                    "currency", "price_unit", "status"),
            "mat_fee",      List.of("seq_no", "fee_value", "fee_ratio", "currency", "price_unit",
                                    "dim_input_material_no", "dim_input_material_name",
                                    "dim_element_name", "dim_assembly_process", "dim_sub_seq_no",
                                    "price_floating", "settlement_rise_ratio", "fixed_rise_value",
                                    "rise_currency", "rise_unit", "reject_rate", "status"),
            "plating_fee",  List.of("plating_process_fee", "plating_material_fee", "currency",
                                    "price_unit", "defect_rate", "status")
    );

    @Inject
    EntityManager em;

    // ─────────────────────────────────────────────────────────────────────────
    // listHistory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all version rows for the given business key in descending version order.
     *
     * <p>The business key is determined by tableName:
     * <ul>
     *   <li>mat_process   → (customer_id, hf_part_no, seq_no, sub_seq_no) — seq_no/sub_seq_no
     *       not filterable via this API; uses only customer_id + hf_part_no as filter.</li>
     *   <li>mat_fee       → (customer_id, hf_part_no, fee_type)</li>
     *   <li>plating_fee   → (customer_id, hf_part_no, plating_plan_code, plan_version)</li>
     * </ul>
     * When hfPartNo is null/empty the query is not filtered by it.
     *
     * @param tableName  one of mat_process / mat_fee / plating_fee
     * @param customerId required customer UUID
     * @param hfPartNo   optional HF part number filter
     * @param page       0-based page index
     * @param size       page size [1,200]
     * @return paged result of history items ordered by version DESC
     */
    @Transactional
    public PageResult<VersionHistoryItemDTO> listHistory(
            String tableName, UUID customerId, String hfPartNo,
            int page, int size) {

        validateTableName(tableName);
        if (customerId == null) {
            throw new BusinessException(400, "customerId 不能为空");
        }

        // Build count query
        StringBuilder whereSb = new StringBuilder(" WHERE t.customer_id = :cid");
        if (hfPartNo != null && !hfPartNo.isBlank()) {
            whereSb.append(" AND t.hf_part_no = :pn");
        }

        String countSql = "SELECT COUNT(*) FROM " + tableName + " t" + whereSb;
        String dataSql  = "SELECT t.*, u.full_name AS _user_full_name" +
                          " FROM " + tableName + " t" +
                          " LEFT JOIN \"user\" u ON u.id = t.updated_by" +
                          whereSb +
                          " ORDER BY t.version DESC" +
                          " LIMIT :sz OFFSET :off";

        // Execute count
        var countQ = em.createNativeQuery(countSql);
        countQ.setParameter("cid", customerId);
        if (hfPartNo != null && !hfPartNo.isBlank()) {
            countQ.setParameter("pn", hfPartNo);
        }
        long total = ((Number) countQ.getSingleResult()).longValue();

        // Execute data
        List<VersionHistoryItemDTO> items = new ArrayList<>();

        em.unwrap(Session.class).doWork(conn -> {
            String sql = "SELECT t.*, u.full_name AS _user_full_name" +
                         " FROM " + tableName + " t" +
                         " LEFT JOIN \"user\" u ON u.id = t.updated_by" +
                         " WHERE t.customer_id = ?" +
                         (hfPartNo != null && !hfPartNo.isBlank() ? " AND t.hf_part_no = ?" : "") +
                         " ORDER BY t.version DESC" +
                         " LIMIT ? OFFSET ?";

            try (var ps = conn.prepareStatement(sql)) {
                int idx = 1;
                ps.setObject(idx++, customerId, java.sql.Types.OTHER);
                if (hfPartNo != null && !hfPartNo.isBlank()) {
                    ps.setString(idx++, hfPartNo);
                }
                ps.setInt(idx++, size);
                ps.setInt(idx++, page * size);

                try (var rs = ps.executeQuery()) {
                    var meta = rs.getMetaData();
                    int cols = meta.getColumnCount();

                    // Build column index map once
                    Map<String, Integer> colIdx = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        colIdx.put(meta.getColumnName(i).toLowerCase(), i);
                    }

                    List<String> bizKeyCols = BIZ_KEY_COLS.get(tableName);

                    while (rs.next()) {
                        VersionHistoryItemDTO dto = new VersionHistoryItemDTO();
                        dto.tableName  = tableName;

                        Object rawId = colIdx.containsKey("id") ? rs.getObject(colIdx.get("id")) : null;
                        dto.recordId  = rawId != null ? toUUID(rawId) : null;
                        dto.version   = colIdx.containsKey("version") ? rs.getInt(colIdx.get("version")) : 0;
                        dto.isCurrent = colIdx.containsKey("is_current") && rs.getBoolean(colIdx.get("is_current"));

                        Object cidObj = colIdx.containsKey("customer_id") ? rs.getObject(colIdx.get("customer_id")) : null;
                        dto.customerId = cidObj != null ? toUUID(cidObj) : null;
                        dto.hfPartNo   = colIdx.containsKey("hf_part_no") ? rs.getString(colIdx.get("hf_part_no")) : null;

                        // Build business key map
                        Map<String, Object> bk = new LinkedHashMap<>();
                        for (String bkCol : bizKeyCols) {
                            Integer ci = colIdx.get(bkCol);
                            if (ci != null) {
                                bk.put(bkCol, rs.getObject(ci));
                            }
                        }
                        dto.businessKey = bk;

                        // Timestamps
                        Integer updatedAtIdx = colIdx.get("updated_at");
                        if (updatedAtIdx != null) {
                            Object ts = rs.getObject(updatedAtIdx);
                            dto.updatedAt = ts != null ? ts.toString() : null;
                        }

                        Integer updatedByIdx = colIdx.get("updated_by");
                        if (updatedByIdx != null) {
                            Object ub = rs.getObject(updatedByIdx);
                            dto.updatedBy = ub != null ? toUUID(ub) : null;
                        }

                        Integer nameIdx = colIdx.get("_user_full_name");
                        if (nameIdx != null) {
                            dto.updatedByName = rs.getString(nameIdx);
                        }

                        items.add(dto);
                    }
                }
            }
        });

        return new PageResult<>(items, page, size, total);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getRowDetail
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all columns for a single row identified by its UUID primary key.
     *
     * @param tableName one of mat_process / mat_fee / plating_fee
     * @param recordId  primary key UUID
     * @return column name → value map (all columns)
     * @throws BusinessException 404 when no row found
     */
    @Transactional
    public Map<String, Object> getRowDetail(String tableName, UUID recordId) {
        validateTableName(tableName);
        if (recordId == null) {
            throw new BusinessException(400, "recordId 不能为空");
        }

        Map<String, Object> result = new LinkedHashMap<>();

        em.unwrap(Session.class).doWork(conn -> {
            String sql = "SELECT t.*, u.full_name AS _updated_by_name" +
                         " FROM " + tableName + " t" +
                         " LEFT JOIN \"user\" u ON u.id = t.updated_by" +
                         " WHERE t.id = ? LIMIT 1";

            try (var ps = conn.prepareStatement(sql)) {
                ps.setObject(1, recordId, java.sql.Types.OTHER);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        var meta = rs.getMetaData();
                        int cols = meta.getColumnCount();
                        for (int i = 1; i <= cols; i++) {
                            result.put(meta.getColumnName(i).toLowerCase(), rs.getObject(i));
                        }
                    }
                }
            }
        });

        if (result.isEmpty()) {
            throw new BusinessException(404, "记录不存在: " + tableName + "/" + recordId);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // compareVersions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compares two rows from the same table field by field.
     *
     * <p>Only data columns (from DATA_COLS) are included in the diff; identity and
     * audit columns (id, version, is_current, customer_id, hf_part_no, etc.) are excluded.
     *
     * @param tableName one of mat_process / mat_fee / plating_fee
     * @param recordIdA UUID of the first row (typically older version)
     * @param recordIdB UUID of the second row (typically newer version)
     * @return VersionCompareDTO with fieldDiffs list
     */
    @Transactional
    public VersionCompareDTO compareVersions(String tableName, UUID recordIdA, UUID recordIdB) {
        validateTableName(tableName);
        if (recordIdA == null || recordIdB == null) {
            throw new BusinessException(400, "recordIdA 和 recordIdB 都不能为空");
        }

        Map<String, Object> rowA = fetchRowById(tableName, recordIdA);
        Map<String, Object> rowB = fetchRowById(tableName, recordIdB);

        if (rowA == null) {
            throw new BusinessException(404, "记录 A 不存在: " + tableName + "/" + recordIdA);
        }
        if (rowB == null) {
            throw new BusinessException(404, "记录 B 不存在: " + tableName + "/" + recordIdB);
        }

        int versionA = rowA.containsKey("version") ? toInt(rowA.get("version")) : 0;
        int versionB = rowB.containsKey("version") ? toInt(rowB.get("version")) : 0;

        List<String> dataCols = DATA_COLS.get(tableName);
        List<VersionCompareDTO.FieldDiff> diffs = new ArrayList<>();

        for (String col : dataCols) {
            Object valA = rowA.get(col);
            Object valB = rowB.get(col);
            String strA = valA != null ? valA.toString() : null;
            String strB = valB != null ? valB.toString() : null;
            boolean same = Objects.equals(strA, strB);
            diffs.add(new VersionCompareDTO.FieldDiff(col, strA, strB, same));
        }

        VersionCompareDTO result = new VersionCompareDTO();
        result.versionA   = versionA;
        result.versionB   = versionB;
        result.fieldDiffs = diffs;
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void validateTableName(String tableName) {
        if (tableName == null || !ALLOWED_TABLES.contains(tableName)) {
            throw new BusinessException(400,
                    "不支持的表名: " + tableName + "，允许值: " + ALLOWED_TABLES);
        }
    }

    private Map<String, Object> fetchRowById(String tableName, UUID id) {
        Map<String, Object> result = new LinkedHashMap<>();
        em.unwrap(Session.class).doWork(conn -> {
            String sql = "SELECT * FROM " + tableName + " WHERE id = ? LIMIT 1";
            try (var ps = conn.prepareStatement(sql)) {
                ps.setObject(1, id, java.sql.Types.OTHER);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        var meta = rs.getMetaData();
                        int cols = meta.getColumnCount();
                        for (int i = 1; i <= cols; i++) {
                            result.put(meta.getColumnName(i).toLowerCase(), rs.getObject(i));
                        }
                    }
                }
            }
        });
        return result.isEmpty() ? null : result;
    }

    private UUID toUUID(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }

    private int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }
}
