package com.cpq.elementprice.pricetable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 价格表明细行（task-0722 · B6，契约见 api.md §3.1）。*/
public class ElementPriceRowDTO {
    public String elementCode;
    public String elementName;
    public LocalDate priceDate;
    public UUID sourceId;
    public String sourceName;
    public String sourceStatus;
    public BigDecimal price;
    public String currency;
    public String priceUnit;
    public String operatorName;
    public OffsetDateTime updatedAt;
}
