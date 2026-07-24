package com.cpq.elementprice.pricetable;

import java.math.BigDecimal;

/**
 * 修改价格请求体（update-0724 · B4，契约见 api.md §2）。
 *
 * <p>⚠️ 刻意<b>不声明</b> {@code elementCode}/{@code sourceId}/{@code priceDate} 三个键字段——
 * 不是"声明后忽略"，是根本不存在，前端多传时被 Jackson 直接丢弃，键锁定是后端硬保证（U4）。
 */
public class UpdatePriceRequest {
    public BigDecimal price;
    public String currency;
    public String priceUnit;
}
