package com.cpq.priceadjust.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/** api.md §1.7 策略变更历史行（前端 {@code StrategyLogDTO}）。 */
public class StrategyLogDTO {
    public UUID id;
    public OffsetDateTime changedAt;
    public String changedBy;
    public String changeType;
    public String summary;
    public JsonNode beforeSnapshot;
    public JsonNode afterSnapshot;
}
