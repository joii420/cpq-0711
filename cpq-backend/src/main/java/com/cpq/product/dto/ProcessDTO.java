package com.cpq.product.dto;

import com.cpq.product.entity.Process;

import java.time.OffsetDateTime;
import java.util.UUID;

public class ProcessDTO {

    public UUID id;
    public String code;
    public String name;
    public String description;
    public String category;
    public boolean isRequired;
    public int sortOrder;
    public String status;
    public OffsetDateTime createdAt;

    public static ProcessDTO from(Process process) {
        ProcessDTO dto = new ProcessDTO();
        dto.id = process.id;
        dto.code = process.code;
        dto.name = process.name;
        dto.description = process.description;
        dto.category = process.category;
        dto.isRequired = process.isRequired;
        dto.sortOrder = process.sortOrder;
        dto.status = process.status;
        dto.createdAt = process.createdAt;
        return dto;
    }
}
