package com.cpq.elementprice.strategy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 策略（默认行或例外行）DTO（task-0722 · B8，契约见 api.md §5）。*/
public class StrategyDTO {
    public UUID id;
    public String elementCode;    // 默认行为 null
    public String elementName;    // 默认行为 null；例外行为元素中文名
    public UUID sourceId;
    public String sourceName;
    public String method;
    public Integer windowNum;
    public String windowUnit;
    public BigDecimal factor;
    public BigDecimal premium;
    public OffsetDateTime updatedAt;
    public String updatedByName;
}
