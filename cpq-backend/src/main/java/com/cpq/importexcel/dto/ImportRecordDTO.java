package com.cpq.importexcel.dto;

import com.cpq.importexcel.entity.ImportRecord;

import java.time.OffsetDateTime;
import java.util.UUID;

public class ImportRecordDTO {

    public UUID id;
    public UUID quotationId;
    public UUID customerId;
    public String customerName;
    // v3: template-based fields
    public UUID templateId;
    public String templateName;
    public String configSnapshot;
    // backward compat: v1/v2 fields (optional)
    public UUID excelTemplateId;
    public String excelTemplateName;
    public UUID mappingTemplateId;
    public String mappingTemplateName;
    public String mappingSnapshot;
    public String originalFileName;
    public String originalFilePath;
    public Integer totalRows;
    public Integer successRows;
    public Integer matchedRows;
    public Integer unmatchedRows;
    public String importStatus;
    public String errorDetail;
    public UUID importedBy;
    public String importedByName;
    public OffsetDateTime createdAt;

    public static ImportRecordDTO from(ImportRecord r) {
        ImportRecordDTO dto = new ImportRecordDTO();
        dto.id = r.id;
        dto.quotationId = r.quotationId;
        dto.customerId = r.customerId;
        dto.templateId = r.templateId;
        dto.configSnapshot = r.configSnapshot;
        dto.excelTemplateId = r.excelTemplateId;
        dto.mappingTemplateId = r.mappingTemplateId;
        dto.mappingSnapshot = r.mappingSnapshot;
        dto.originalFileName = r.originalFileName;
        dto.originalFilePath = r.originalFilePath;
        dto.totalRows = r.totalRows;
        dto.successRows = r.successRows;
        dto.matchedRows = r.matchedRows;
        dto.unmatchedRows = r.unmatchedRows;
        dto.importStatus = r.importStatus;
        dto.errorDetail = r.errorDetail;
        dto.importedBy = r.importedBy;
        dto.createdAt = r.createdAt;
        return dto;
    }
}
