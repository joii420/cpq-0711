package com.cpq.quotation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 核价管理列表 DTO — 核价工作台列表视图所需的投影字段。
 *
 * <p>status 为派生字段，由 Quotation.status 映射：
 * <ul>
 *   <li>SUBMITTED        → 待核价</li>
 *   <li>COSTING_REJECTED → 核价驳回</li>
 *   <li>APPROVED/SENT/ACCEPTED → 核价通过</li>
 * </ul>
 */
public class CostingOrderListItemDTO {

    public UUID quotationId;
    public String quotationNumber;
    /** snapshot_customer_name 快照客户名 */
    public String customerName;
    public String submittedByName;
    /** 货币码，当前系统统一为 CNY（人民币）*/
    public String currency;
    /** 派生：待核价 / 核价驳回 / 核价通过 */
    public String status;
    /** costing_order.entered_costing_at */
    public OffsetDateTime createdAt;
}
