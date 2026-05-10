package com.cpq.basicdata.dto;

import com.cpq.basicdata.entity.DerivedAttribute;

import java.time.OffsetDateTime;
import java.util.UUID;

public class DerivedAttributeDTO {

    public UUID id;
    public UUID hostSheetId;
    public String variableCode;
    public String variableLabel;
    public String dataType;
    public String computationType;
    public String computation;  // 原 JSON 字符串，前端 JSON.parse
    public String status;
    public Integer sortOrder;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public static DerivedAttributeDTO from(DerivedAttribute d) {
        DerivedAttributeDTO dto = new DerivedAttributeDTO();
        dto.id = d.id;
        dto.hostSheetId = d.hostSheetId;
        dto.variableCode = d.variableCode;
        dto.variableLabel = d.variableLabel;
        dto.dataType = d.dataType;
        dto.computationType = d.computationType;
        dto.computation = d.computation;
        dto.status = d.status;
        dto.sortOrder = d.sortOrder;
        dto.createdAt = d.createdAt;
        dto.updatedAt = d.updatedAt;
        return dto;
    }
}
