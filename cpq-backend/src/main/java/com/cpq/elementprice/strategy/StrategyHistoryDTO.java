package com.cpq.elementprice.strategy;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 策略变更历史行（task-0722 · B9，契约见 api.md §7）。*/
public class StrategyHistoryDTO {
    public UUID id;
    public OffsetDateTime changedAt;
    public String changedByName;
    public String targetLabel;     // "客户级默认策略" 或 "元素例外 · Ag 银"
    public String elementCode;     // null = 默认策略
    public String action;          // CREATE / UPDATE / DELETE
    public List<StrategyChangeDTO> changes = new ArrayList<>();
    public JsonNode snapshot;      // action=CREATE/DELETE 时前端用它展示全量
}
