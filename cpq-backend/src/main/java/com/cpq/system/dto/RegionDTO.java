package com.cpq.system.dto;

import com.cpq.system.entity.Region;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.UUID;

public class RegionDTO {

    public UUID id;

    @NotBlank
    public String code;

    @NotBlank
    public String name;

    public Integer sortOrder;

    public String status;

    public OffsetDateTime createdAt;

    public static RegionDTO from(Region region) {
        RegionDTO dto = new RegionDTO();
        dto.id = region.id;
        dto.code = region.code;
        dto.name = region.name;
        dto.sortOrder = region.sortOrder;
        dto.status = region.status;
        dto.createdAt = region.createdAt;
        return dto;
    }
}
