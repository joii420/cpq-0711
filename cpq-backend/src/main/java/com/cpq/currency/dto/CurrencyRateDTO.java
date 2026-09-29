package com.cpq.currency.dto;
import com.cpq.currency.entity.CurrencyExchangeRate;
import java.math.BigDecimal; import java.time.*;
public class CurrencyRateDTO { public String id,rate,displayRate,effectiveDate,source,status; public OffsetDateTime fetchedAt,updatedAt; public static CurrencyRateDTO from(CurrencyExchangeRate r){var d=new CurrencyRateDTO();d.id=r.id==null?null:r.id.toString();d.rate=r.rate.toPlainString();d.displayRate=r.rate.setScale(12).toPlainString();d.effectiveDate=r.effectiveDate.toString();d.source=r.source;d.status=r.status;d.fetchedAt=r.updatedAt;d.updatedAt=r.updatedAt;return d;} }
