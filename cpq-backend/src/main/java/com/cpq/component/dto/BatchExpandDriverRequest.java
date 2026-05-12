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
        /**
         * 料号版本号（可选）。传入后 ImplicitJoinRewriter 注入 AND part_version=N 谓词，
         * 只拉该版本数据，避免历史版本叠加重复。
         * null = 不注入版本过滤（向后兼容旧调用方）。
         */
        public Integer partVersion;
    }
}
