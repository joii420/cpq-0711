package com.cpq.currency.dto;
import com.cpq.currency.entity.CurrencyExchangeRate;
import java.math.BigDecimal; import java.time.*;
public class CurrencyRateDTO { public String rate,displayRate,effectiveDate,source,status; public OffsetDateTime fetchedAt; public static CurrencyRateDTO from(CurrencyExchangeRate r){var d=new CurrencyRateDTO();d.rate=r.rate.toPlainString();d.displayRate=r.rate.setScale(12).toPlainString();d.effectiveDate=r.effectiveDate.toString();d.source=r.source;d.status=r.status;d.fetchedAt=r.updatedAt;return d;} }
