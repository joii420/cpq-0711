package com.cpq.importexcel.dto;

import java.util.List;
import java.util.Map;

public class ImportPreviewDTO {

    /** Each row is a map from col_key to the cell value read from Excel. */
    public List<Map<String, Object>> rows;

    /** Part-number match result per row: { rowIndex, customerPartNo, matched, materialNo, materialName } */
    public List<Map<String, Object>> matchResults;

    public int totalRows;
    public int matchedRows;
    public int unmatchedRows;

    /** Row-level parse errors: { row, error } */
    public List<Map<String, String>> errors;
}
