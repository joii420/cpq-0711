package com.cpq.changelog;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Search/filter parameters for change-log queries.
 * All fields are optional; null means "no filter on this field".
 */
public class ChangeLogSearchParams {

    public UUID   customerId;
    public String hfPartNo;
    public String tableName;
    public String fieldName;
    /** ISO-8601 start of changed_at range (inclusive). */
    public OffsetDateTime changedAtFrom;
    /** ISO-8601 end of changed_at range (inclusive). */
    public OffsetDateTime changedAtTo;
    public String importance;
    public String changeSource;
}
