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

    /** 调试: true 时每个 task 的响应回传 driver 改写后的最终 SQL (data.debugSql), 供前端 console 打印。默认 false。 */
    public boolean debugSql;

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
        /**
         * Bug B: 报价单 line item UUID（可选）。
         * 非空时 batch-expand 在查 mat_process 等版本化表时优先返回 quotation_line_item_id=lineItemId
         * 的行，fallback 到 quotation_line_item_id IS NULL 的主数据行。
         * null = 老行为（不区分 lineItem，使用主数据）。
         */
        public UUID lineItemId;
        /**
         * lineItem 类型（可选）。用于 v_composite_child_* 聚合视图的 lineItemId 注入策略：
         * - "COMPOSITE": 父级聚合视图，跳过 lineItemId 注入（让 hf_part_no 聚合子件工序）
         * - "SIMPLE" 或 null: 普通单产品，必须注入 lineItemId 限定专属工序行
         * null = 老调用路径，默认按 SIMPLE 处理（注入 lineItemId，比之前统一跳过更安全）
         */
        public String compositeType;
        /**
         * COMPOSITE 父级的子件 lineItem UUID 列表（可选）。
         * compositeType="COMPOSITE" 时，后端向 v_composite_child_* 路径注入
         * quotation_line_item_id IN (childLineItemIds) OR quotation_line_item_id IS NULL
         * 谓词，只返回当前报价单子件自己的工序行，消除历史累积行。
         * null 或空 = 不注入 IN 谓词（fallback：返回 hf_part_no 的全量历史行）。
         */
        public java.util.List<java.util.UUID> childLineItemIds;

        /**
         * 报价单 id。统一渲染协议(2026-05-30):前端 useDriverExpansions 每个 task 透传
         * 当前报价单 id;后端 batchExpand 入口绑到 ThreadLocal QuotationIdContext,
         * DataLoader 自动注入到 RuntimeContext.quotation.id → 视图通过 {@code :quotationId}
         * 占位符使用。配合 :customerCode + :hfPartNos 三参数,所有 mirror 同协议跑,
         * 不再需要 :lineItemId 标量。空可兼容旧前端(老协议路径继续工作)。
         */
        public UUID quotationId;
    }
}
