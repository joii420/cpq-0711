package com.cpq.costing.dto;

import java.util.List;
import java.util.Map;

/**
 * 比对视图导出请求体 —— 前端已算好的双行对比模型。
 * 后端 ComparisonExportService 只按此写值+填色，不做任何路径/公式重算（保证与 Excel 视图逐格一致）。
 */
public class ComparisonExportRequest {

    public List<Column> columns;
    public List<Row> rows;

    public static class Column {
        public String tag;
        public String label;
        public String groupName;
    }

    public static class Cell {
        public Object quote;
        public Object costing;
        public boolean highlighted;
    }

    public static class Row {
        public String partNo;
        public String presence;   // BOTH | QUOTE_ONLY | COSTING_ONLY
        public Map<String, Cell> cells;  // key = tag
    }
}
