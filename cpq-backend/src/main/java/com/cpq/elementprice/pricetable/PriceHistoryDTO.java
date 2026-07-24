package com.cpq.elementprice.pricetable;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 价格变更历史行（update-0724 · B5，契约见 api.md §5；形状逐字比照 StrategyHistoryDTO）。*/
public class PriceHistoryDTO {
    public UUID id;
    public OffsetDateTime changedAt;
    public String changedByName;
    public String action;          // CREATE / UPDATE / DELETE
    public String elementCode;
    public String elementName;
    public UUID sourceId;
    public String sourceName;
    public LocalDate priceDate;
    public String targetLabel;     // "{元素符号} {中文名} · {源名} · {价格日期}"
    public List<PriceChangeDTO> changes = new ArrayList<>();
    public JsonNode snapshot;      // action=CREATE/UPDATE 存变更后值；DELETE 存删除前值
}
