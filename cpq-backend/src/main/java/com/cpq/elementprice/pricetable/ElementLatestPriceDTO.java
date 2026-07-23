package com.cpq.elementprice.pricetable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** 某元素在某个源下的最新价格（task-0722 · B7.1，契约见 api.md §4.1）。*/
public class ElementLatestPriceDTO {
    public UUID sourceId;
    public String sourceName;
    public String sourceStatus;
    public BigDecimal price;
    public String currency;
    public String priceUnit;
    public LocalDate priceDate;
}
