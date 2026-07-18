package com.cpq.costing.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * task-0717 比对视图 — 比对列配置读写 DTO。
 * 契约见 dev-docs/task-0717-比对视图/api.md §3/§4。
 *
 * <p>{@code columns=null} 表示该（报价单 × 桶）从未保存过配置 —— 前端自行种入默认列
 * （产品卡片总计），不落库直到用户首次保存（api.md §3）。
 */
public class ComparisonConfigDTO {

    public UUID quotationId;
    public String bucket;

    /** ColumnDef[]（api.md §5）；从未保存过 → null。后端只存不解释内容。 */
    public JsonNode columns;

    public OffsetDateTime updatedAt;
}
