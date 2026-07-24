package com.cpq.basicdata.v6.dto;

import java.util.List;
import java.util.UUID;

public class ImportResultDTO {
    public UUID importRecordId;
    public String systemType;           // QUOTE / PRICING
    public String status;               // PROCESSING / SUCCESS / FAILED（QUOTE 侧 update-0723 起不再产生 PARTIAL，见 U7）
    public int totalSuccessRows;
    public int totalFailedRows;
    public List<SheetResultDTO> sheetResults;
}
