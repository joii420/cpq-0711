package com.cpq.system.ddl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/system/ddl/extend-column.
 * v5.1 §3.4 TECH-4
 */
public class ExtendColumnRequest {

    @NotBlank(message = "tableName 不能为空")
    @Size(max = 64)
    public String tableName;

    @NotBlank(message = "columnName 不能为空")
    @Size(max = 64)
    @Pattern(regexp = "^[a-z][a-z0-9_]*$",
             message = "columnName 只允许小写字母、数字、下划线，且以小写字母开头")
    public String columnName;

    /**
     * Supported types: VARCHAR(N) / TEXT / DECIMAL(p,s) / INTEGER / BOOLEAN / DATE / TIMESTAMPTZ
     */
    @NotBlank(message = "dataType 不能为空")
    @Size(max = 64)
    public String dataType;

    /**
     * Default value literal (string representation before SQL escaping).
     * Required to maintain consistency in existing rows (NOT NULL ADD COLUMN needs a DEFAULT).
     * Empty string is a valid DEFAULT for VARCHAR/TEXT columns.
     */
    @NotNull(message = "defaultValue 不能为 null，需为旧行提供一致性默认值")
    public String defaultValue;

    /** CRITICAL | IMPORTANT | NORMAL */
    public String importance = "NORMAL";

    public boolean affectsCalculation = false;
}
