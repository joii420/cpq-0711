package com.cpq.elementprice;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request body for POST /api/cpq/element-prices/manual
 */
public class UpsertManualPriceRequest {

    @NotBlank(message = "元素名称不能为空")
    public String elementName;

    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.000001", message = "价格必须大于 0")
    public BigDecimal price;

    public String currency = "CNY";

    public String unit;

    /** Optional note/remark from the admin. */
    public String note;
}
