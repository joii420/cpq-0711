package com.cpq.configure.dto;

import com.cpq.common.DecimalStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;

public class ElementOverride {
    public String elementCode;
    @JsonDeserialize(using = DecimalStringDeserializer.class)
    public BigDecimal pct;

    public ElementOverride() {}
    public ElementOverride(String elementCode, BigDecimal pct) {
        this.elementCode = elementCode;
        this.pct = pct;
    }
}
