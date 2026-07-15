package com.cpq.quotation.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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

    // ── task-0713 B5 新增（D1 落定）：核价侧 live 重算 + 结果缓存 ─────────────────────

    /**
     * 核价侧渲染缓存（costing_order.costing_render 解析后的对象，已应用本单 override）：
     * {@code {lineItemId: {costingCardValues, costingExcelValues}}}。<b>已应用</b>本核价单
     * 当前的版本 override。报价侧仍读 {@link #frozenDto}，两轴物理隔离（守 T2 报价侧隔离）。
     * 打开永远读此缓存，绝不 on-open 重算（守 BL-0010）。
     */
    public JsonNode costingRender;

    /**
     * 核价侧单据总价 = Σ 核价成本 subtotal，不含 Step3 折扣（与 {@link #totalAmount} 报价总额
     * 是两列两值，语义不同，不可混用）。
     */
    public BigDecimal costingTotalAmount;

    /** 本单当前所有版本 override，供前端标记"已切版本"（api.md §4）。 */
    public List<VersionOverrideItem> versionOverrides;

    /** {@code = status==='PENDING'}（角色已由 {@code @RoleAllowed} 端点门禁保证）。 */
    public boolean editable;

    /** api.md §4 override 元素结构。 */
    public static class VersionOverrideItem {
        public String componentId;
        public String partNo;
        public String viewVersion;

        public VersionOverrideItem() {}

        public VersionOverrideItem(String componentId, String partNo, String viewVersion) {
            this.componentId = componentId;
            this.partNo = partNo;
            this.viewVersion = viewVersion;
        }
    }
}
