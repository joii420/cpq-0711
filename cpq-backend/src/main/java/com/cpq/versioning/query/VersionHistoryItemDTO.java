package com.cpq.versioning.query;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.UUID;

/**
 * DTO for a single version row in the versioning history list (UI-5/UI-6).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VersionHistoryItemDTO {

    public String tableName;
    public UUID recordId;
    public int version;
    public boolean isCurrent;
    /** Business key fields (excluding customer_id and hf_part_no). */
    public Map<String, Object> businessKey;
    public UUID customerId;
    public String hfPartNo;
    public String updatedAt;
    public UUID updatedBy;
    public String updatedByName;
}
