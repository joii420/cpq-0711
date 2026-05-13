package com.cpq.configure.dto;

import java.math.BigDecimal;

public class ElementOverride {
    public String elementCode;
    public BigDecimal pct;

    public ElementOverride() {}
    public ElementOverride(String elementCode, BigDecimal pct) {
        this.elementCode = elementCode;
        this.pct = pct;
    }
}
