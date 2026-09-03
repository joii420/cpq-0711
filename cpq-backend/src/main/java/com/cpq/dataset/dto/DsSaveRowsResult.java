package com.cpq.dataset.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** api.md §7 保存结果三态（UNCHANGED / UPGRADED / CREATED）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DsSaveRowsResult {

    public String result;
    public Integer versionNo;
    public int rowCount;
    public String message;

    public DsSaveRowsResult(String result, Integer versionNo, int rowCount, String message) {
        this.result = result;
        this.versionNo = versionNo;
        this.rowCount = rowCount;
        this.message = message;
    }
}
