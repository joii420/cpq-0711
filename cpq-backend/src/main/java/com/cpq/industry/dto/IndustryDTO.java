package com.cpq.industry.dto;

import com.cpq.industry.entity.Industry;

import java.time.OffsetDateTime;
import java.util.UUID;

public class IndustryDTO {
    public UUID id;
    public String code;
    public String name;
    public String status;
    public Integer version;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public static IndustryDTO from(Industry e) {
        IndustryDTO dto = new IndustryDTO();
        dto.id = e.id;
        dto.code = e.code;
        dto.name = e.name;
        dto.status = e.status;
        dto.version = e.version;
        dto.createdAt = e.createdAt;
        dto.updatedAt = e.updatedAt;
        return dto;
    }
}
