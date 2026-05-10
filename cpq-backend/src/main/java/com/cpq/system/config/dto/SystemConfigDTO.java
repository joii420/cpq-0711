package com.cpq.system.config.dto;

import com.cpq.system.config.entity.SystemConfig;

import java.time.OffsetDateTime;

public class SystemConfigDTO {

    public String configKey;
    public String configValue;
    public String defaultValue;
    public String dataType;
    public String category;
    public String description;
    public String modifiableBy;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public static SystemConfigDTO from(SystemConfig e) {
        SystemConfigDTO dto = new SystemConfigDTO();
        dto.configKey = e.configKey;
        dto.configValue = e.configValue;
        dto.defaultValue = e.defaultValue;
        dto.dataType = e.dataType;
        dto.category = e.category;
        dto.description = e.description;
        dto.modifiableBy = e.modifiableBy;
        dto.createdAt = e.createdAt;
        dto.updatedAt = e.updatedAt;
        return dto;
    }
}
