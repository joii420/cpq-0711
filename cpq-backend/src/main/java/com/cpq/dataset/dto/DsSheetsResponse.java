package com.cpq.dataset.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** api.md §2 响应包裹：{@code data = { "sheets": [...] }}（与裸数组区分，前端按 data.sheets 取）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DsSheetsResponse {

    public List<DsSheetMeta> sheets;

    public DsSheetsResponse(List<DsSheetMeta> sheets) {
        this.sheets = sheets;
    }
}
