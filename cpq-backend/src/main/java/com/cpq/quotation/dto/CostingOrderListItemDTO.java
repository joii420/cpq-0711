package com.cpq.quotation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 核价管理列表 DTO — 核价工作台列表视图所需的投影字段。
 *
 * <p>status 为英文码，直接取自 costing_order.status，前端负责映射中文标签：
 * <ul>
 *   <li>PENDING    → 待核价</li>
 *   <li>APPROVED   → 核价通过</li>
 *   <li>REJECTED   → 核价驳回</li>
 *   <li>WITHDRAWN  → 已撤回</li>
 * </ul>
 * 前端单点映射中文，后端不再派生中文字符串。
 */
public class CostingOrderListItemDTO {

    /** 核价单 ID（costing_order.id） */
    public UUID costingOrderId;
    public UUID quotationId;
    /** 核价单编号，如 HJ-20260629-0001 */
    public String costingOrderNumber;
    public String quotationNumber;
    /** snapshot_customer_name 快照客户名 */
    public String customerName;
    public String submittedByName;
    /** 货币码，当前系统统一为 CNY（人民币）*/
    public String currency;
    /**
     * 英文码 PENDING/APPROVED/REJECTED/WITHDRAWN，前端单点映射中文。
     */
    public String status;
    /** 驳回原因（status=REJECTED 时非空） */
    public String rejectReason;
    /** costing_order.entered_costing_at */
    public OffsetDateTime createdAt;
    /** costing_order.updated_at */
    public OffsetDateTime updatedAt;
}
