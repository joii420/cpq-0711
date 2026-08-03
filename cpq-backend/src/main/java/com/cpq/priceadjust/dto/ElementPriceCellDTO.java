package com.cpq.priceadjust.dto;

import java.math.BigDecimal;

/**
 * api.md §1.5 元素矩阵单元格（前端 {@code ElementPriceCellDTO}）。
 * 🔒 {@code priceState} 两种空值语义必须分开（§11.2.4）：
 * NOT_IN_LIST=该元素当时不在调价清单里（{@code element_price_version_item} 无该行）；
 * NO_PRICE=在清单里但取价策略算不出值（有行但 {@code currentPrice} 为 null）。
 */
public class ElementPriceCellDTO {
    public static final String NORMAL = "NORMAL";
    public static final String NOT_IN_LIST = "NOT_IN_LIST";
    public static final String NO_PRICE = "NO_PRICE";

    public BigDecimal unitPrice;
    public BigDecimal changeRate;
    public String priceState;

    public static ElementPriceCellDTO notInList() {
        ElementPriceCellDTO c = new ElementPriceCellDTO();
        c.priceState = NOT_IN_LIST;
        return c;
    }

    public static ElementPriceCellDTO noPrice() {
        ElementPriceCellDTO c = new ElementPriceCellDTO();
        c.priceState = NO_PRICE;
        return c;
    }

    public static ElementPriceCellDTO normal(BigDecimal unitPrice, BigDecimal changeRate) {
        ElementPriceCellDTO c = new ElementPriceCellDTO();
        c.priceState = NORMAL;
        c.unitPrice = unitPrice;
        c.changeRate = changeRate;
        return c;
    }
}
