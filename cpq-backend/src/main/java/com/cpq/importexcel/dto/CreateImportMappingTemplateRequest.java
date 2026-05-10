package com.cpq.importexcel.dto;

import java.util.UUID;

public class CreateImportMappingTemplateRequest {

    public String name;
    public UUID excelTemplateId;
    public UUID templateId;
    public String columnMappings;
}
