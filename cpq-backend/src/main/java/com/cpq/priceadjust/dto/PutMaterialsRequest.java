package com.cpq.priceadjust.dto;

import java.util.List;

/** api.md §1.4 请求体（前端 {@code MaterialsSaveRequest}）。 */
public class PutMaterialsRequest {
    public List<String> materialNos;
    public boolean confirmRemoval;
}
