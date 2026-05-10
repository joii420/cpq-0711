package com.cpq.importexcel.dto;

import com.cpq.importexcel.entity.ImportMappingTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

public class ImportMappingTemplateDTO {

    public UUID id;
    public String name;
    public UUID excelTemplateId;
    public UUID templateId;
    public String templateName;
    public String columnMappings;
    public UUID createdBy;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public static ImportMappingTemplateDTO from(ImportMappingTemplate m) {
        ImportMappingTemplateDTO dto = new ImportMappingTemplateDTO();
        dto.id = m.id;
        dto.name = m.name;
        dto.excelTemplateId = m.excelTemplateId;
        dto.templateId = m.templateId;
        dto.columnMappings = m.columnMappings;
        dto.createdBy = m.createdBy;
        dto.createdAt = m.createdAt;
        dto.updatedAt = m.updatedAt;
        return dto;
    }
}
