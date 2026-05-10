package com.cpq.masterdata.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.masterdata.dto.ColumnMetadataDTO;
import com.cpq.masterdata.dto.MasterDataOverviewDTO;
import com.cpq.masterdata.dto.PagedTableDataDTO;
import com.cpq.masterdata.dto.TableSummaryDTO;
import com.cpq.masterdata.registry.TableRegistry;
import com.cpq.masterdata.registry.TableRegistry.TableMeta;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Read-only service for the UI-4 Master Data Maintenance page.
 *
 * <p>All queries use raw (native) SQL via EntityManager — there are no JPA entities
 * for the 13 v5.1 physical business tables. This is an explicit architect decision.
 *
 * <p>Security note: table names and column names are validated against the hard-coded
 * {@link TableRegistry} before being interpolated into SQL strings.  User-supplied
 * search values are always passed as JDBC parameters (no SQL injection risk).
 */
@ApplicationScoped
public class MasterDataService {

    @Inject
    EntityManager em;

    @Inject
    TableRegistry registry;

    // -------------------------------------------------------------------------
    // Overview
    // -------------------------------------------------------------------------

    /**
     * Build the overview: one {@link TableSummaryDTO} per registered table.
     *
     * @param customerId optional; when non-null, CUSTOMER-scoped tables are filtered
     *                   by customer_id = customerId
     */
    @Transactional
    public MasterDataOverviewDTO getOverview(UUID customerId) {
        List<TableSummaryDTO> summaries = new ArrayList<>();

        for (TableMeta meta : registry.all()) {
            TableSummaryDTO dto = new TableSummaryDTO();
            dto.tableName       = meta.tableName();
            dto.displayName     = meta.displayName();
            dto.group           = meta.group();
            dto.customerScoped  = meta.customerScoped();
            dto.primaryKeyField = meta.primaryKeyField();

            if (!meta.v1Enabled()) {
                dto.v1Disabled = true;
                dto.rowCount = 0;
                dto.lastUpdatedAt = null;
            } else {
                dto.v1Disabled = false;
                boolean applyCustomer = meta.customerScoped() && customerId != null;
                try {
                    Object[] row = querySummaryRow(meta.tableName(), applyCustomer, customerId);
                    dto.rowCount = row[0] == null ? 0L : ((Number) row[0]).longValue();
                    dto.lastUpdatedAt = row[1] != null ? row[1].toString() : null;
                } catch (Exception ex) {
                    // Defensive: if the table or updated_at column doesn't exist in this env, skip gracefully
                    dto.rowCount = 0;
                    dto.lastUpdatedAt = null;
                }
            }
            summaries.add(dto);
        }

        MasterDataOverviewDTO overview = new MasterDataOverviewDTO();
        overview.customerId = customerId != null ? customerId.toString() : null;
        overview.tables = summaries;
        return overview;
    }

    @SuppressWarnings("unchecked")
    private Object[] querySummaryRow(String table, boolean applyCustomer, UUID customerId) {
        String sql = applyCustomer
                ? "SELECT COUNT(*) AS cnt, MAX(updated_at) AS last_updated FROM \"" + table + "\" WHERE customer_id = :cid"
                : "SELECT COUNT(*) AS cnt, MAX(updated_at) AS last_updated FROM \"" + table + "\"";

        jakarta.persistence.Query q = em.createNativeQuery(sql);
        if (applyCustomer) {
            q.setParameter("cid", customerId);
        }

        Object result = q.getSingleResult();
        if (result instanceof Object[]) {
            return (Object[]) result;
        }
        // Some drivers return a single value when only one column is selected
        return new Object[]{ result, null };
    }

    // -------------------------------------------------------------------------
    // Query table (paginated)
    // -------------------------------------------------------------------------

    /**
     * Return a paginated slice of a physical table.
     *
     * @param tableName  registered table name
     * @param customerId optional filter for CUSTOMER-scoped tables
     * @param page       0-based page index
     * @param size       rows per page [1, 200]
     * @param search     optional ILIKE filter on the table's searchField
     */
    @Transactional
    public PagedTableDataDTO queryTable(String tableName, UUID customerId,
                                        int page, int size, String search) {
        if (size < 1 || size > 200) {
            throw new BusinessException(400, "size 必须在 [1, 200] 范围内，当前值: " + size);
        }
        if (page < 0) {
            throw new BusinessException(400, "page 不能为负数");
        }

        TableMeta meta = registry.requireEnabled(tableName);

        PagedTableDataDTO dto = new PagedTableDataDTO();
        dto.tableName   = meta.tableName();
        dto.displayName = meta.displayName();
        dto.page        = page;
        dto.size        = size;

        if (!meta.v1Enabled()) {
            dto.v1Disabled = true;
            dto.total   = 0;
            dto.columns = Collections.emptyList();
            dto.rows    = Collections.emptyList();
            return dto;
        }

        boolean applyCustomer = meta.customerScoped() && customerId != null;
        boolean applySearch   = search != null && !search.isBlank();

        // Build WHERE clause parts
        List<Object> params = new ArrayList<>();
        List<String> whereClauses = new ArrayList<>();

        if (applyCustomer) {
            whereClauses.add("customer_id = ?");
            params.add(customerId);
        }
        if (applySearch) {
            whereClauses.add("\"" + meta.searchField() + "\"::text ILIKE ?");
            params.add("%" + search.replace("%", "\\%").replace("_", "\\_") + "%");
        }

        String whereStr = whereClauses.isEmpty()
                ? ""
                : " WHERE " + String.join(" AND ", whereClauses);

        String quotedTable = "\"" + meta.tableName() + "\"";
        String quotedPk    = "\"" + meta.primaryKeyField() + "\"";

        // COUNT query
        String countSql = "SELECT COUNT(*) FROM " + quotedTable + whereStr;
        long total = executeCount(countSql, params);

        // Data query
        String dataSql = "SELECT * FROM " + quotedTable + whereStr
                + " ORDER BY " + quotedPk
                + " LIMIT " + size
                + " OFFSET " + ((long) page * size);

        List<ColumnMetadataDTO> columns = new ArrayList<>();
        List<Map<String, Object>> rows  = new ArrayList<>();
        executeDataQuery(dataSql, params, meta, columns, rows);

        dto.total   = total;
        dto.columns = columns;
        dto.rows    = rows;
        return dto;
    }

    // -------------------------------------------------------------------------
    // Row detail
    // -------------------------------------------------------------------------

    /**
     * Return a single row by primary key.
     *
     * @param tableName physical table name
     * @param rowId     primary key value (String for part_no PK, UUID otherwise)
     * @throws BusinessException 404 if the row does not exist
     * @throws BusinessException 404 if v1Disabled
     */
    @Transactional
    public Map<String, Object> getRowDetail(String tableName, String rowId) {
        TableMeta meta = registry.requireEnabled(tableName);

        if (!meta.v1Enabled()) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND: 表 '" + tableName + "' 在 v1 阶段未启用");
        }

        String quotedTable = "\"" + meta.tableName() + "\"";
        String quotedPk    = "\"" + meta.primaryKeyField() + "\"";

        // Determine PK type: only mat_part uses a String primary key (part_no)
        boolean pkIsString = "part_no".equals(meta.primaryKeyField());

        String sql = "SELECT * FROM " + quotedTable + " WHERE " + quotedPk + " = ?";

        List<Object> params = new ArrayList<>();
        if (pkIsString) {
            params.add(rowId);
        } else {
            try {
                params.add(UUID.fromString(rowId));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "rowId 格式不合法 (期望 UUID): " + rowId);
            }
        }

        List<ColumnMetadataDTO> ignoredCols = new ArrayList<>();
        List<Map<String, Object>> rows      = new ArrayList<>();
        executeDataQuery(sql, params, meta, ignoredCols, rows);

        if (rows.isEmpty()) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND: 在表 '" + tableName + "' 中未找到主键 '" + rowId + "'");
        }
        return rows.get(0);
    }

    // -------------------------------------------------------------------------
    // Column metadata helpers
    // -------------------------------------------------------------------------

    /**
     * Attempt to load column metadata from basic_data_attribute joined via basic_data_config.
     * The basic_data_config.sheet_name is matched against the table's displayName.
     * Falls back to empty map when not found.
     */
    @SuppressWarnings("unchecked")
    private Map<String, ColumnMetadataDTO> loadAttributeMetadata(TableMeta meta) {
        Map<String, ColumnMetadataDTO> result = new LinkedHashMap<>();
        try {
            String sql =
                    "SELECT a.variable_code, a.variable_label, a.importance_level, a.data_type " +
                    "FROM basic_data_attribute a " +
                    "JOIN basic_data_config c ON c.id = a.config_id " +
                    "WHERE c.sheet_name = :sheetName AND a.status = 'ACTIVE' " +
                    "ORDER BY a.sort_order ASC";

            List<Object[]> rows = em.createNativeQuery(sql)
                    .setParameter("sheetName", meta.displayName())
                    .getResultList();

            for (Object[] row : rows) {
                String varCode = (String) row[0];
                ColumnMetadataDTO col = new ColumnMetadataDTO();
                col.columnName      = varCode != null ? varCode.toLowerCase() : "";
                col.label           = row[1] != null ? (String) row[1] : col.columnName;
                col.importanceLevel = row[2] != null ? (String) row[2] : "NORMAL";
                col.dataType        = row[3] != null ? (String) row[3] : null;
                result.put(col.columnName, col);
            }
        } catch (Exception ignored) {
            // Attribute metadata is optional; silently degrade
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Low-level JDBC helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private long executeCount(String sql, List<Object> params) {
        jakarta.persistence.Query q = em.createNativeQuery(sql);
        for (int i = 0; i < params.size(); i++) {
            q.setParameter(i + 1, params.get(i));
        }
        Object raw = q.getSingleResult();
        return raw == null ? 0L : ((Number) raw).longValue();
    }

    /**
     * Execute a native SELECT and populate {@code columns} and {@code rows}.
     * Column metadata is enriched from BasicDataAttribute when available.
     */
    private void executeDataQuery(String sql, List<Object> params,
                                  TableMeta meta,
                                  List<ColumnMetadataDTO> columnsOut,
                                  List<Map<String, Object>> rowsOut) {

        Map<String, ColumnMetadataDTO> attrMeta = loadAttributeMetadata(meta);

        // Use Hibernate's unwrapped NativeQuery to access ResultSetMetaData
        em.unwrap(org.hibernate.Session.class)
          .doWork(connection -> {
              try (var ps = connection.prepareStatement(sql)) {
                  // Bind parameters
                  for (int i = 0; i < params.size(); i++) {
                      Object p = params.get(i);
                      if (p instanceof UUID) {
                          ps.setObject(i + 1, p);
                      } else {
                          ps.setObject(i + 1, p);
                      }
                  }

                  try (var rs = ps.executeQuery()) {
                      ResultSetMetaData rsMeta = rs.getMetaData();
                      int colCount = rsMeta.getColumnCount();

                      // Build column list on first pass (from ResultSetMetaData)
                      if (columnsOut.isEmpty()) {
                          for (int c = 1; c <= colCount; c++) {
                              String colName = rsMeta.getColumnName(c).toLowerCase();
                              ColumnMetadataDTO col;
                              if (attrMeta.containsKey(colName)) {
                                  col = attrMeta.get(colName);
                                  col.columnName = colName; // ensure lower-case sync
                              } else {
                                  col = new ColumnMetadataDTO();
                                  col.columnName      = colName;
                                  col.label           = colName;
                                  col.importanceLevel = "NORMAL";
                                  col.dataType        = null;
                              }
                              columnsOut.add(col);
                          }
                      }

                      // Build rows
                      while (rs.next()) {
                          Map<String, Object> rowMap = new LinkedHashMap<>();
                          for (int c = 1; c <= colCount; c++) {
                              String colName = rsMeta.getColumnName(c).toLowerCase();
                              Object val = rs.getObject(c);
                              rowMap.put(colName, serialize(val));
                          }
                          rowsOut.add(rowMap);
                      }
                  }
              }
          });
    }

    /**
     * Serialize DB values to JSON-friendly types.
     * UUID, Timestamp, OffsetDateTime → String; everything else passes through.
     */
    private Object serialize(Object val) {
        if (val == null) return null;
        if (val instanceof UUID)            return val.toString();
        if (val instanceof Timestamp)       return val.toString();
        if (val instanceof OffsetDateTime)  return val.toString();
        if (val instanceof java.util.Date)  return val.toString();
        if (val instanceof BigDecimal bd)   return bd; // keep numeric precision
        return val;
    }
}
