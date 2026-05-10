package com.cpq.changelog;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * DTO for a single basic_data_change_log row (UI-7 Change Log).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChangeLogEntryDTO {

    public UUID   id;
    public String tableName;
    public UUID   recordId;
    public UUID   customerId;
    public String hfPartNo;
    public String fieldName;
    public String oldValue;
    public String newValue;
    public String importance;
    public Boolean affectsCalculation;
    public String changeSource;
    public String note;
    public String changedAt;
    public UUID   changedBy;
    public String changedByName;
    public UUID   importRecordId;
}
