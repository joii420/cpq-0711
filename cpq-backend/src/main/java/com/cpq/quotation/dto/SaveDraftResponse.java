package com.cpq.quotation.dto;

import com.cpq.quotation.entity.Quotation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * task-260901 B-4a：{@code PUT /api/cpq/quotations/{id}/draft} 的<b>轻量</b>响应体。
 *
 * <h3>为什么不复用 {@link QuotationDTO}</h3>
 * 原来 saveDraft 末尾 {@code dto.lineItems = loadLineItems(id)} 把整单 1845 行连同 9225 条
 * componentData（{@code snapshot_rows} 7.5 MB + {@code row_data} 1.8 MB）全查回来、实体化、
 * 再序列化——实测响应体 24.6 MB，经 1.74 MB/s 的链路要走十几秒。
 * 而前端对这份响应<b>只读 6 个字段</b>（{@code 证据/E3-前端消费点.md} 穷举确认）：
 * {@code id} / {@code partVersionLocked} / 4 份值快照。{@code componentData} 一个字节都没被读过。
 *
 * <p>所以这里只回传单头 + 变化行的那 6 个字段，目标 &lt; 500 KB（AC-15 / AC-16）。
 *
 * <p>单头字段与 {@link QuotationDTO#from(Quotation)} 一一对应——前端
 * {@code setQuotationPreservingStructures} 是把整个 data 展开合并进本地 quotation 状态的，
 * 少一个字段就会把本地的那个字段抹成 undefined。<b>改这里之前先想清楚前端会不会丢字段。</b>
 */
public class SaveDraftResponse {

    /**
     * 🆕 <b>D-42（甲-1）· 本次带 line payload 保存所触碰的明细行 id</b>（新增/修改 + 删除）。
     *
     * <h3>🚫 它不上线（{@code @JsonIgnore}），这是刻意的</h3>
     * {@code api.md §1.3} 的作用域表要求 <b>modified 行恰好 6 个键</b>（多回一个即违约，T-16）。
     * 本字段只在<b>进程内</b>从 {@code QuotationService.saveDraft} 传到 {@code QuotationResource}，
     * 供后者在 {@code snapshotQuotation(id, true)} <b>之后</b>调 {@code syncRecords} 时保住<b>增量语义</b>。
     *
     * <h3>为什么必须带出来，而不是在 Resource 里传 null 整单重算</h3>
     * {@code AC-2②} 断言「未变更产品的 {@code _record.updated_at} 逐字未变」。
     * 传 null = 整单重写 ⇒ 直接打破该 AC，且大单量下写放大（{@code task-260825} 栽过建单 N+1 超时）。
     *
     * <h3>🚨 为什么挂点不能留在 saveDraft 里面（D-42 实证）</h3>
     * {@code saveDraft} 对「payload 的 componentId 集合 ≠ 库里的」的行会<b>先整行删掉</b>组件数据
     * （{@code QuotationService#batchDeleteComponentDataByIds}），而重建发生在
     * {@code QuotationResource} 里紧随其后的 {@code snapshotQuotation(id, true)} ——
     * 即 {@code saveDraft} <b>返回之后</b>。⇒ 事务内任何位置调 {@code syncRecords} 都落在
     * 「旧行已删、新行未建」的空窗里，读到 0 行、打一条
     * 「命中 N 个轴值但<b>无组件数据</b>，跳过」就走了，<b>{@code _record} 永远写不出来</b>。
     * <p>⚠️ 三条代理线全部没碰到，是因为它们的夹具都在 payload 里直接塞 {@code componentData}
     * （那一刻数据在事务里存在）—— <b>而那不是用户的形状</b>。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public transient java.util.List<UUID> touchedLineItemIds;

    // ── 单头（与 QuotationDTO.from 同集合）────────────────────────────────────────────────────
    public UUID id;
    public String quotationNumber;
    public UUID customerId;
    public String name;
    public UUID contactId;
    public String contactName;
    public String contactPhone;
    public String contactEmail;
    public String projectName;
    public String opportunityId;
    public UUID salesRepId;
    public String quoteType;
    public String priority;
    public String stage;
    public LocalDate expectedCloseDate;
    public String status;
    public BigDecimal totalAmount;
    public LocalDate expiryDate;
    public String paymentTerms;
    public Integer deliveryCycle;
    public BigDecimal originalAmount;
    public BigDecimal systemDiscountRate;
    public BigDecimal finalDiscountRate;
    public BigDecimal taxRate;
    public BigDecimal taxAmount;
    public String discountAdjustmentReason;
    public Boolean isManuallyAdjusted;
    public UUID sourceQuotationId;
    public UUID assignedApproverId;
    public UUID customerTemplateId;
    public UUID categoryId;
    public UUID costingCardTemplateId;
    public String remarks;
    public String snapshotCustomerName;
    public String snapshotCustomerLevel;
    public String snapshotCustomerRegion;
    public String snapshotCustomerIndustry;
    public String snapshotCustomerAddress;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    /** task-260901 B-3：本次保存后的版本号。前端<b>必须</b>用它更新本地基线，否则下次保存必撞 409。 */
    public Integer userDataVersion;

    /** ⚠️ 只含本次 added + modified 的行，<b>不含</b>未变行，更不含 componentData。 */
    public List<Line> lineItems = new ArrayList<>();

    /**
     * 变化行的最小回传集。
     *
     * <p><b>键集按行的来源分作用域</b>（{@code api.md §1.3} 2026-09-01 收敛）：
     * <ul>
     *   <li>来自 {@code added} 的行 → 6 个键 <b>+ {@code tempId}</b>（共 7 键）。
     *       {@code tempId} 是新行认领 DB id 的<b>唯一</b>手段，删了它新行下次保存会重复插入（AC-17）。</li>
     *   <li>来自 {@code modified} 的行 → <b>恰好 6 个键</b>，不带 {@code tempId}（本来就有 id，按 id 匹配）。</li>
     * </ul>
     * 靠 {@code tempId} 字段上的 {@code NON_NULL} 实现：已持久化的行 tempId 为 null ⇒ 该键不出现。
     * 其余 6 个键即使值为 null 也照常出现（{@code quoteCardValues} 被失效时就是 null，
     * 前端按 {@code r[k] != null} 跳过，键在不在都不影响它，但 T-16 数键，所以不能省）。
     */
    public static class Line {
        /** DB id。{@code added} 行在这里拿到后端生成的新 id。 */
        public UUID id;
        /**
         * task-260901 B-4c：原样回传请求里的 {@code tempId}，前端按它认领新 id。
         * 🚫 不按数组顺序配对——那会重蹈下标耦合（AC-17 防的就是这个）。
         * 🔒 只对 {@code added} 行回传；{@code modified} 行恒 null ⇒ 经 NON_NULL 从 JSON 里消失，
         *    使该行的键集恰好是 6 个（T-16）。
         */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public String tempId;
        public Integer partVersionLocked;
        public String quoteCardValues;
        public String costingCardValues;
        public String quoteExcelValues;
        public String costingExcelValues;
    }

    public static SaveDraftResponse fromHeader(Quotation q) {
        SaveDraftResponse r = new SaveDraftResponse();
        r.id = q.id;
        r.quotationNumber = q.quotationNumber;
        r.customerId = q.customerId;
        r.name = q.name;
        r.contactId = q.contactId;
        r.contactName = q.contactName;
        r.contactPhone = q.contactPhone;
        r.contactEmail = q.contactEmail;
        r.projectName = q.projectName;
        r.opportunityId = q.opportunityId;
        r.salesRepId = q.salesRepId;
        r.quoteType = q.quoteType;
        r.priority = q.priority;
        r.stage = q.stage;
        r.expectedCloseDate = q.expectedCloseDate;
        r.status = q.status;
        r.totalAmount = q.totalAmount;
        r.expiryDate = q.expiryDate;
        r.paymentTerms = q.paymentTerms;
        r.deliveryCycle = q.deliveryCycle;
        r.originalAmount = q.originalAmount;
        r.systemDiscountRate = q.systemDiscountRate;
        r.finalDiscountRate = q.finalDiscountRate;
        r.taxRate = q.taxRate;
        r.taxAmount = q.taxAmount;
        r.discountAdjustmentReason = q.discountAdjustmentReason;
        r.isManuallyAdjusted = q.isManuallyAdjusted;
        r.sourceQuotationId = q.sourceQuotationId;
        r.assignedApproverId = q.assignedApproverId;
        r.customerTemplateId = q.customerTemplateId;
        r.categoryId = q.productCategoryId;
        r.costingCardTemplateId = q.costingCardTemplateId;
        r.remarks = q.remarks;
        r.snapshotCustomerName = q.snapshotCustomerName;
        r.snapshotCustomerLevel = q.snapshotCustomerLevel;
        r.snapshotCustomerRegion = q.snapshotCustomerRegion;
        r.snapshotCustomerIndustry = q.snapshotCustomerIndustry;
        r.snapshotCustomerAddress = q.snapshotCustomerAddress;
        r.createdAt = q.createdAt;
        r.updatedAt = q.updatedAt;
        return r;
    }
}
