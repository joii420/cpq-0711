package com.cpq.importexcel.dto;

import com.cpq.importexcel.entity.CustomerExcelTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

public class CustomerExcelTemplateDTO {

    public UUID id;
    public String name;
    public UUID customerId;
    public String description;
    public int headerRowIndex;
    public int dataStartRowIndex;
    public int sheetIndex;
    public String partNoColumn;
    public String excelColumns;
    public String sampleFileName;
    public UUID createdBy;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public static CustomerExcelTemplateDTO from(CustomerExcelTemplate t) {
        CustomerExcelTemplateDTO dto = new CustomerExcelTemplateDTO();
        dto.id = t.id;
        dto.name = t.name;
        dto.customerId = t.customerId;
        dto.description = t.description;
        dto.headerRowIndex = t.headerRowIndex;
        dto.dataStartRowIndex = t.dataStartRowIndex;
        dto.sheetIndex = t.sheetIndex;
        dto.partNoColumn = t.partNoColumn;
        dto.excelColumns = t.excelColumns;
        dto.sampleFileName = t.sampleFileName;
        dto.createdBy = t.createdBy;
        dto.createdAt = t.createdAt;
        dto.updatedAt = t.updatedAt;
        return dto;
    }
}
