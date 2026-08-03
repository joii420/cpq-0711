package com.cpq.priceadjust.dto;

/** api.md §1.11 — POST /price-adjust/versions/generate 请求体。 */
public class GenerateVersionRequest {
    public String customerNo;
    public boolean confirmSupersede = false;
}
