package com.cpq.priceadjust.dto;

import java.math.BigDecimal;

/** task-0729 B0 · S1 读出的单个元素版本价（一套价，报价核价共用，E12）。 */
public class ElementPrice {
    public final BigDecimal price;
    public final String currency;

    public ElementPrice(BigDecimal price, String currency) {
        this.price = price;
        this.currency = currency;
    }
}
