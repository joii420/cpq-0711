package com.cpq.component.dto;

import java.util.List;
import java.util.UUID;

/**
 * POST /api/cpq/components/batch-expand 请求体。
 *
 * <p>单次 batch 最多 100 个 task，防止滥用。
 */
public class BatchExpandDriverRequest {

    public List<Task> tasks;

    public static class Task {
        /** 组件 ID（必填） */
        public UUID componentId;
        /** 客户 ID（可选） */
        public UUID customerId;
        /** 料号（可选） */
        public String partNo;
    }
}
