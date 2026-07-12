package com.cpq.basicdata.v6.maintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** §2 sheets 元数据响应包裹：api.md §2 约定 data 为 {"sheets":[...]}（与裸数组区分，前端按 data.sheets 取）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SheetsResponse {

    public List<SheetMetaDTO> sheets;

    public SheetsResponse(List<SheetMetaDTO> sheets) {
        this.sheets = sheets;
    }
}
