package com.cpq.basicdata.v6.dto;

import com.cpq.basicdata.v6.parser.RowError;
import com.cpq.basicdata.v6.parser.SheetImportResult;

import java.util.List;
import java.util.Map;

public class SheetResultDTO {
    public String sheetName;
    public int totalRows;
    public int successRows;
    public int failedRows;
    public List<RowError> errors;
    public Map<String, Integer> writtenCounts;

    public static SheetResultDTO from(SheetImportResult r) {
        SheetResultDTO d = new SheetResultDTO();
        d.sheetName = r.sheetName;
        d.totalRows = r.totalRows;
        d.successRows = r.successRows;
        d.failedRows = r.failedRows;
        // 限制 errors 数量避免 metadata JSON 过大（前 50 条）
        d.errors = r.errors.size() > 50 ? r.errors.subList(0, 50) : r.errors;
        d.writtenCounts = r.writtenCounts;
        return d;
    }
}
