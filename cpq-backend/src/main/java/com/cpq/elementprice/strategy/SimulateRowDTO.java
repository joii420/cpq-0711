package com.cpq.elementprice.strategy;

import java.math.BigDecimal;

/** 策略试算结果行（task-0722 · B10，契约见 api.md §6）。*/
public class SimulateRowDTO {
    public String elementCode;
    public String elementName;
    public String hitRule;      // EXCEPTION / DEFAULT
    public String sourceName;
    public String method;
    public BigDecimal rawValue; // 系数/加价之前
    public BigDecimal factor;
    public BigDecimal premium;
    public BigDecimal finalPrice; // 无价时 null
    public int sampleDays;
    public boolean hasPrice;
}
