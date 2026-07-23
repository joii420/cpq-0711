package com.cpq.elementprice.priceimport;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 价格导入结果汇总（task-0722 · B5，契约见 api.md §2.2）。*/
public class PriceImportResultDTO {
    public UUID sourceId;
    public String sourceName;
    public LocalDate priceDate;
    public String operatorName;
    public long elapsedMs;
    public int createdCount;
    public int updatedCount;
    public int failedCount;
    public List<PriceImportRowDTO> rows = new ArrayList<>();
}
