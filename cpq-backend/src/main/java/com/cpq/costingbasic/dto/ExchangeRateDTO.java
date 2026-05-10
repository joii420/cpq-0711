package com.cpq.costingbasic.dto;

import com.cpq.costingbasic.entity.CostingExchangeRate;

import java.math.BigDecimal;
import java.util.UUID;

public class ExchangeRateDTO {
    public UUID id;
    public UUID versionId;
    public String fromCurrency;
    public String toCurrency;
    public BigDecimal costingRate;
    public BigDecimal marketRate;
    public String rateRule;
    public String sourceUrl;
    public Integer sortOrder;

    public static ExchangeRateDTO from(CostingExchangeRate r) {
        ExchangeRateDTO d = new ExchangeRateDTO();
        d.id = r.id;
        d.versionId = r.versionId;
        d.fromCurrency = r.fromCurrency;
        d.toCurrency = r.toCurrency;
        d.costingRate = r.costingRate;
        d.marketRate = r.marketRate;
        d.rateRule = r.rateRule;
        d.sourceUrl = r.sourceUrl;
        d.sortOrder = r.sortOrder;
        return d;
    }
}
