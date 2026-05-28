package com.cpq.basicdata.v6.parser;

public class RowError {
    public int rowNo;        // 1-based, 含表头偏移
    public String column;    // 中文列名（找不到列名则为 "_row_"）
    public String message;

    public RowError() {}

    public RowError(int rowNo, String column, String message) {
        this.rowNo = rowNo;
        this.column = column;
        this.message = message;
    }
}
