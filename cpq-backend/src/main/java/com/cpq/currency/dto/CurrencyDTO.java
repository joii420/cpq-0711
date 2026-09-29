package com.cpq.currency.dto;
import com.cpq.currency.entity.Currency;
public class CurrencyDTO { public String code,name,symbol; public boolean enabled,isBase; public int sortOrder; public String latestRate,latestRateDate; public static CurrencyDTO from(Currency c){var d=new CurrencyDTO();d.code=c.code;d.name=c.name;d.symbol=c.symbol;d.enabled=Boolean.TRUE.equals(c.enabled);d.isBase=Boolean.TRUE.equals(c.isBase);d.sortOrder=c.sortOrder==null?0:c.sortOrder;return d;} }
