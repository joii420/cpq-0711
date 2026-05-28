package com.cpq.basicdata.v6.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SheetImportResult {
    public String sheetName;
    public int totalRows;
    public int successRows;
    public int failedRows;
    public final List<RowError> errors = new ArrayList<>();
    /** 写入计数 by table: {"material_master": 5, "material_bom_item": 23} */
    public final Map<String, Integer> writtenCounts = new HashMap<>();

    public SheetImportResult(String sheetName) {
        this.sheetName = sheetName;
    }

    public void recordWrite(String table, int n) {
        writtenCounts.merge(table, n, Integer::sum);
    }

    public void recordError(int rowNo, String column, String msg) {
        errors.add(new RowError(rowNo, column, msg));
        failedRows++;
    }

    public boolean isSuccess() {
        return failedRows == 0;
    }
}
