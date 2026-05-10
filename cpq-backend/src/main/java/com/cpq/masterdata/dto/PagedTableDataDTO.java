package com.cpq.masterdata.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Paginated data response for GET /api/cpq/master-data/table/{tableName}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PagedTableDataDTO {

    /** Physical table name. */
    public String tableName;

    /** Human-readable display name. */
    public String displayName;

    /** Current page (0-based). */
    public int page;

    /** Page size requested. */
    public int size;

    /** Total row count matching the current filter (ignoring pagination). */
    public long total;

    /** Column metadata in result-set order. */
    public List<ColumnMetadataDTO> columns;

    /** Data rows; each map key = physical column name, value = serialized value. */
    public List<Map<String, Object>> rows;

    /**
     * True when v1Enabled=false in the registry; in this case rows is empty and
     * total=0. HTTP status is still 200 — callers should check this flag.
     */
    public boolean v1Disabled;
}
