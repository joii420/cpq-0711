package com.cpq.elementprice.pricetable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 矩阵行（task-0722 · B6，契约见 api.md §3.2）。prices 与 PriceMatrixDTO.dates 等长、按下标对齐。*/
public class PriceMatrixRowDTO {
    public String elementCode;
    public String elementName;
    public List<BigDecimal> prices = new ArrayList<>();  // 无记录为 null，不补零
}
