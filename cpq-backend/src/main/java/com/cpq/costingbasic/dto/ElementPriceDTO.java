package com.cpq.costingbasic.dto;

import com.cpq.costingbasic.entity.CostingElementPrice;

import java.math.BigDecimal;
import java.util.UUID;

public class ElementPriceDTO {
    public UUID id;
    public UUID versionId;
    public String elementCode;
    public BigDecimal costingPrice;
    public BigDecimal marketRefPrice;
    public String sourceUrl;
    public String sourceName;
    public String sourceRule;
    public String currency;
    public String unit;
    public BigDecimal discountRate;
    public Integer sortOrder;

    public static ElementPriceDTO from(CostingElementPrice e) {
        ElementPriceDTO d = new ElementPriceDTO();
        d.id = e.id;
        d.versionId = e.versionId;
        d.elementCode = e.elementCode;
        d.costingPrice = e.costingPrice;
        d.marketRefPrice = e.marketRefPrice;
        d.sourceUrl = e.sourceUrl;
        d.sourceName = e.sourceName;
        d.sourceRule = e.sourceRule;
        d.currency = e.currency;
        d.unit = e.unit;
        d.discountRate = e.discountRate;
        d.sortOrder = e.sortOrder;
        return d;
    }
}
