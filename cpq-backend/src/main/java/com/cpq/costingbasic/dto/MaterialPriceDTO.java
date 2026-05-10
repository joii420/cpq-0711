package com.cpq.costingbasic.dto;

import com.cpq.costingbasic.entity.CostingMaterialPrice;

import java.math.BigDecimal;
import java.util.UUID;

public class MaterialPriceDTO {
    public UUID id;
    public UUID versionId;
    public String materialNo;
    public String brandName;
    public String spec;
    public String dimension;
    public BigDecimal costingPrice;
    public BigDecimal marketRefPrice;
    public String sourceUrl;
    public String sourceName;
    public String sourceRule;
    public String currency;
    public String unit;
    public BigDecimal discountRate;
    public Integer sortOrder;

    public static MaterialPriceDTO from(CostingMaterialPrice m) {
        MaterialPriceDTO d = new MaterialPriceDTO();
        d.id = m.id;
        d.versionId = m.versionId;
        d.materialNo = m.materialNo;
        d.brandName = m.brandName;
        d.spec = m.spec;
        d.dimension = m.dimension;
        d.costingPrice = m.costingPrice;
        d.marketRefPrice = m.marketRefPrice;
        d.sourceUrl = m.sourceUrl;
        d.sourceName = m.sourceName;
        d.sourceRule = m.sourceRule;
        d.currency = m.currency;
        d.unit = m.unit;
        d.discountRate = m.discountRate;
        d.sortOrder = m.sortOrder;
        return d;
    }
}
