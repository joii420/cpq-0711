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
