package com.cpq.basicdata.dto;

import java.util.List;

public class ParsedExcelStructureDTO {

    public List<ParsedSheetDTO> sheets;

    public static class ParsedSheetDTO {
        public Integer sheetIndex;
        public String sheetName;
        public Integer headerRowIndex;  // 1-based default
        public List<ParsedColumnDTO> columns;
    }

    public static class ParsedColumnDTO {
        public String columnLetter;  // A, B, C, ...
        public Integer columnIndex;  // 0-based
        public String columnTitle;   // 表头原文
    }
}
