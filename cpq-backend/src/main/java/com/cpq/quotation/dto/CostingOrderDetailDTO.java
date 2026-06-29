package com.cpq.quotation.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 核价单详情 DTO — 财务核价工作台详情页（含冻结副本）。
 *
 * <p>frozenDto 为 JSON 字符串，直接透传 costing_order.frozen_dto 原始内容，
 * 前端负责反序列化渲染核价卡片结构。
 */
public class CostingOrderDetailDTO {

    /** 核价单 ID（costing_order.id） */
    public UUID costingOrderId;

    /** 关联报价单 ID */
    public UUID quotationId;

    /** 核价单编号，如 HJ-20260629-0001 */
    public String costingOrderNumber;

    /**
     * 英文码 PENDING/APPROVED/REJECTED/WITHDRAWN，前端单点映射中文。
     */
    public String status;

    /** 驳回原因（status=REJECTED 时非空） */
    public String rejectReason;

    /** 核价总金额 */
    public BigDecimal totalAmount;

    /**
     * 冻结副本 JSON（costing_order.frozen_dto 原始字符串）。
     * 包含 costingCardStructure / gvDefs 等键，前端直接反序列化。
     */
    public String frozenDto;

    /** 核价单创建时间（entered_costing_at） */
    public OffsetDateTime createdAt;

    /** 审核时间（reviewed_at） */
    public OffsetDateTime reviewedAt;
}
