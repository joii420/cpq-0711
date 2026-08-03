package com.cpq.priceadjust.dto;

import java.math.BigDecimal;

/** api.md §1.13 — 版本明细（元素级）。 */
public class VersionItemDTO {
    public String elementCode;
    public String elementName;
    public BigDecimal currentPrice;
    public BigDecimal previousPrice;
    public BigDecimal changeRate;
    public String currency;
    public String priceUnit;
    public boolean noPrice;
    public boolean inheritedFromPrevious;
}
