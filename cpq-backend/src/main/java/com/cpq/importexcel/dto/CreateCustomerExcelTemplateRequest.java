package com.cpq.importexcel.dto;

import java.util.List;
import java.util.UUID;

public class CreateCustomerExcelTemplateRequest {

    public String name;
    public UUID customerId;
    public String description;
    public int headerRowIndex = 1;
    public int dataStartRowIndex = 2;
    public int sheetIndex = 0;
    public String partNoColumn;
    public List<String> excelColumns;
}
