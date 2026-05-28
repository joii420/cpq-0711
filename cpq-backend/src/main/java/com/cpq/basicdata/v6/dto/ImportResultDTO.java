package com.cpq.basicdata.v6.dto;

import java.util.List;
import java.util.UUID;

public class ImportResultDTO {
    public UUID importRecordId;
    public String systemType;           // QUOTE / PRICING
    public String status;               // SUCCESS / PARTIAL_SUCCESS / FAILED
    public int totalSuccessRows;
    public int totalFailedRows;
    public List<SheetResultDTO> sheetResults;
}
