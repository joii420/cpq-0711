package com.cpq.elementprice.priceimport;

import java.math.BigDecimal;

/** 导入结果单行（task-0722 · B5，契约见 api.md §2.2）。result: CREATED / UPDATED / FAILED。*/
public class PriceImportRowDTO {
    public int rowNo;             // Excel 物理行号（1-based）
    public String elementCode;
    public BigDecimal price;
    public String currency;
    public String priceUnit;
    public String result;         // CREATED / UPDATED / FAILED
    public String message;        // FAILED=失败原因；UPDATED="原值 X → 新值 Y"；CREATED=null
}
