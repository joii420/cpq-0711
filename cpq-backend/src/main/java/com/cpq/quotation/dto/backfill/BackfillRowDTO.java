package com.cpq.quotation.dto.backfill;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * api.md §1.2 {@code groups[].rows[]}。
 *
 * <p>repair-0727 破坏性变更：{@code changes}/{@code values} 由 {@code Map<col,Object[]>}/
 * {@code Map<col,Object>} 改为带中文标签的数组（同批次发布，前端 F1/F2 同步改，无外部消费方，
 * 后端不保留旧形状）。
 */
public class BackfillRowDTO {
    public String op;              // CHANGE / ADD / DELETE

    /** 前端契约字段名为 {@code __v6_id}（api.md §1.1 / costingOrderService.ts 已按此建成）。 */
    @JsonProperty("__v6_id")
    public UUID v6Id;              // null for ADD

    /** repair-0727 B4：该行业务身份短语，前端直接展示（如「组成件 W-1001（外购件）」）。 */
    public String rowLabel;
    /** repair-0727 B3.2：同一 (v6Id, 列) 被多页签同时 patch 且值不同——本行存在被丢弃的冲突 patch。 */
    public boolean conflict;

    /** CHANGE 专用：列级差异，带中文标签。 */
    public List<ChangeEntry> changes = new ArrayList<>();
    /** ADD/DELETE 专用：列 → 值快照，带中文标签。 */
    public List<ValueEntry> values = new ArrayList<>();

    public static class ChangeEntry {
        public String column;
        public String label;
        public Object oldValue;
        public Object newValue;
    }

    public static class ValueEntry {
        public String column;
        public String label;
        public Object value;
    }
}
