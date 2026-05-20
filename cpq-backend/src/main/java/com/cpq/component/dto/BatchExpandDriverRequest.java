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
        /**
         * V195 hotfix: template snapshot 覆盖 component 表的 dataDriverPath (可空).
         *   非空 → expand 用此 driver path 而不是 component.dataDriverPath
         *   null → 沿用 component 表 (向后兼容)
         *
         * <p>背景: V195 改组合产品模板 snapshot 的 driver_path = v_composite_child_*,
         * 但 component 表的 dataDriverPath 没改 (单一产品模板复用同组件), expand 读
         * component 表跑了错的 driver → 0 行 → 前端走 globalPathCache 拉多行 array 显示 "(共 N 项)".
         */
        public String overrideDataDriverPath;
        /**
         * V195 hotfix: template snapshot 覆盖 component 表的 fields JSON (可空).
         *   非空 → expand 按此 fields 收集 BASIC_DATA paths
         *   null → 沿用 component 表
         */
        public String overrideFieldsJson;
    }
}
