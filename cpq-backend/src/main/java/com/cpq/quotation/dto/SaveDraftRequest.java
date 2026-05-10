package com.cpq.quotation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class SaveDraftRequest {

    // Header fields
    public String name;
    public UUID contactId;
    public String contactName;
    public String contactPhone;
    public String contactEmail;
    public String projectName;
    public String opportunityId;
    public String quoteType;
    public String priority;
    public String stage;
    public LocalDate expectedCloseDate;
    public String paymentTerms;
    public Integer deliveryCycle;
    public LocalDate expiryDate;
    public String remarks;

    // Pricing overrides
    public BigDecimal finalDiscountRate;
    public String discountAdjustmentReason;

    // Line items
    public List<LineItemDraft> lineItems;

    public static class LineItemDraft {
        public UUID productId;
        public UUID templateId;
        // V5 批量导入流程：productId 为空，但 partNo 来自 mat_part 主档。
        // 这里收下后写入 product_part_no_snapshot，确保刷新后 driver 展开可用。
        public String productPartNo;
        public String productName;
        public String customerPartNo;
        public String productAttributeValues;
        public BigDecimal subtotal;
        public Integer sortOrder;
        public List<UUID> processIds;
        public List<ComponentDataDraft> componentData;
    }

    public static class ComponentDataDraft {
        public UUID componentId;
        public String tabName;
        public String rowData;
        public BigDecimal subtotal;
        public Integer sortOrder;
    }
}
