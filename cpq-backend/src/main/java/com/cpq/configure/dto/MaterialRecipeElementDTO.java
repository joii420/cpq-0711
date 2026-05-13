package com.cpq.configure.dto;

import java.math.BigDecimal;

public class MaterialRecipeElementDTO {
    public String elementCode;
    public String elementName;
    public BigDecimal defaultPct;
    public BigDecimal minPct;
    public BigDecimal maxPct;
    public boolean isLocked;
    public int sortOrder;
}
