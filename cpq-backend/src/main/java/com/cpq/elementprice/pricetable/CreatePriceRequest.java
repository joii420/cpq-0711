package com.cpq.elementprice.pricetable;

import com.cpq.common.DecimalStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** 新建价格请求体（update-0724 · B4，契约见 api.md §1）。 */
public class CreatePriceRequest {
    public String elementCode;
    public UUID sourceId;
    public LocalDate priceDate;
    @JsonDeserialize(using = DecimalStringDeserializer.class)
    public BigDecimal price;
    public String currency;
    public String priceUnit;
}
