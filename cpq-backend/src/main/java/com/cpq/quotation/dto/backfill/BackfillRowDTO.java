package com.cpq.quotation.dto.backfill;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** api.md §1.1 {@code groups[].rows[]}。 */
public class BackfillRowDTO {
    public String op;              // CHANGE / ADD / DELETE

    /** 前端契约字段名为 {@code __v6_id}（api.md §1.1 / costingOrderService.ts 已按此建成）。 */
    @JsonProperty("__v6_id")
    public UUID v6Id;              // null for ADD

    /** CHANGE 专用：列 → [旧值, 新值]。 */
    public Map<String, Object[]> changes = new LinkedHashMap<>();
    /** ADD/DELETE 专用：列 → 值（可读快照）。 */
    public Map<String, Object> values = new LinkedHashMap<>();
}
