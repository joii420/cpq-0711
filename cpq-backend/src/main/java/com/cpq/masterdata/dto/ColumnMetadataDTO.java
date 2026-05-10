package com.cpq.masterdata.dto;

/**
 * Metadata for a single column in a physical table.
 *
 * <p>Sources (in priority order):
 * <ol>
 *   <li>BasicDataAttribute row joined via BasicDataConfig — provides variableLabel + importanceLevel</li>
 *   <li>ResultSetMetaData fallback — column name only, importanceLevel defaults to "NORMAL"</li>
 * </ol>
 */
public class ColumnMetadataDTO {

    /** Physical column name (snake_case). */
    public String columnName;

    /**
     * Human-readable label from basic_data_attribute.variable_label; falls back to
     * the physical column name when no attribute seed exists for this table.
     */
    public String label;

    /**
     * Importance level: CRITICAL | IMPORTANT | NORMAL.
     * Defaults to "NORMAL" when attribute metadata is unavailable.
     */
    public String importanceLevel;

    /** Data type hint from basic_data_attribute.data_type (IDENTIFIER | VALUE); null if unknown. */
    public String dataType;
}
