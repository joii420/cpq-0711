package com.cpq.priceadjust.dto;

import java.util.List;

/** api.md §1.6 请求体（前端 {@code ElementsSaveRequest}）。 */
public class PutElementsRequest {
    public List<String> elementCodes;
    public boolean confirmUnselect;
}
