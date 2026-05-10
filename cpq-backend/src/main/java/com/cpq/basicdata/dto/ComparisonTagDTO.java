package com.cpq.basicdata.dto;

import com.cpq.basicdata.entity.ComparisonTag;

import java.time.OffsetDateTime;
import java.util.UUID;

public class ComparisonTagDTO {

    public UUID id;
    public String code;
    public String label;
    public String groupName;
    public Integer groupSortOrder;
    public Integer tagSortOrder;
    public Boolean isBuiltin;
    public String status;
    public String description;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public static ComparisonTagDTO from(ComparisonTag t) {
        ComparisonTagDTO dto = new ComparisonTagDTO();
        dto.id = t.id;
        dto.code = t.code;
        dto.label = t.label;
        dto.groupName = t.groupName;
        dto.groupSortOrder = t.groupSortOrder;
        dto.tagSortOrder = t.tagSortOrder;
        dto.isBuiltin = t.isBuiltin;
        dto.status = t.status;
        dto.description = t.description;
        dto.createdAt = t.createdAt;
        dto.updatedAt = t.updatedAt;
        return dto;
    }
}
