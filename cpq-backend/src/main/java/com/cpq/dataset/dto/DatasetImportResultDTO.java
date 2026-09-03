package com.cpq.dataset.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 导入成功响应体（api.md §1 · AC-11）。 */
public class DatasetImportResultDTO {

    public String dataset;
    public String fileName;
    public long durationMs;
    /** 导入历史记录 id（B-8：登记进现有 import_record 表）。 */
    public UUID importRecordId;
    public List<DatasetSheetSummaryDTO> summary = new ArrayList<>();
}
