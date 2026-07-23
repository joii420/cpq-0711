package com.cpq.elementprice.strategy;

import java.math.BigDecimal;
import java.util.UUID;

/** 策略新建/编辑请求（task-0722 · B8，契约见 api.md §5.2/§5.3）。例外行额外带 elementCode。*/
public class StrategyUpsertRequest {
    public String customerNo;
    public String elementCode;   // 仅例外端点必填；默认策略端点忽略此字段
    public UUID sourceId;
    public String method;        // LATEST / AVG / MAX / MIN
    public Integer windowNum;
    public String windowUnit;    // DAY / WEEK / MONTH / YEAR
    public BigDecimal factor;
    public BigDecimal premium;
}
