package com.cpq.masterdata.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Summary row for one physical table shown in the overview panel.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableSummaryDTO {

    /** Physical table name (snake_case). */
    public String tableName;

    /** Human-readable Chinese display name. */
    public String displayName;

    /** Logical group: GLOBAL | CUSTOMER | ELEMENT. */
    public String group;

    /** Whether the table is filtered by customer_id when a customerId is provided. */
    public boolean customerScoped;

    /**
     * Total row count; 0 when v1Disabled=true (query skipped).
     */
    public long rowCount;

    /**
     * MAX(updated_at) from the table; null when v1Disabled=true.
     */
    public String lastUpdatedAt;

    /**
     * True when v1Enabled=false in the registry — this table is not yet
     * activated in Phase 1 and no DB query was executed.
     */
    public boolean v1Disabled;

    /**
     * Primary key field name (e.g. "part_no" for mat_part, "id" for most others).
     * Used by frontend to identify PK column in table data view + row detail navigation.
     */
    public String primaryKeyField;
}
