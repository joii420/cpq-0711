package com.cpq.quotation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    // 2026-05-18: 报价模板 / 核价模板 绑定 — 之前漏在 SaveDraft 透传, 导致 Step1 选模板后
    // 永远写不到 quotation.customer_template_id / costing_card_template_id, 刷新页面拿不到值.
    public UUID customerTemplateId;
    public UUID costingCardTemplateId;

    // Line items
    public List<LineItemDraft> lineItems;

    public static class LineItemDraft {
        /**
         * 2026-06-01: 已存在行的 line_item id。前端回传后, saveDraft 按 id 复用同一行(就地 UPDATE, 不换 UUID),
         * 消除"全删全建换新 id"造成的 editQuoteCardValue 撞已删 id(400)+ driver 缓存 churn。
         * 为空 = 新增行(后端生成新 id)。
         */
        public UUID id;
        public UUID productId;
        public UUID templateId;
        // V5 批量导入流程：productId 为空，但 partNo 来自 mat_part 主档。
        // 这里收下后写入 product_part_no_snapshot，确保刷新后 driver 展开可用。
        public String productPartNo;
        public String productName;
        public String customerPartNo;
        /**
         * V6 兼容字段: 前端 BulkImportPartsDrawer.buildLineItemFromTemplate 写入字段名是
         * customerProductNo (与 mat_customer_part_mapping.customer_product_no 对齐)。
         * 旧 QuotationWizard.buildDraftPayload 只读 customerPartNo, 漏这条路径,
         * 导致 SaveDraft 收到 customerPartNo=null → 跳过 part_version_locked 查询。
         * saveDraft 处理时 customerPartNo 为空 fallback 到 customerProductNo。
         */
        public String customerProductNo;
        public String productAttributeValues;
        public BigDecimal subtotal;
        public Integer sortOrder;
        public List<UUID> processIds;
        public List<ComponentDataDraft> componentData;
        /** V169 选配组合产品关系标识 SIMPLE / COMPOSITE / PART (saveDraft 全量重建时必须透传保留) */
        public String compositeType;
        /**
         * 父级在前端 lineItems list 中的索引 (PART 子件用).
         * saveDraft 全量重建时旧 parent_line_item_id 已被 CASCADE 删除 → 不能直接传旧 UUID,
         * 改传索引让后端按 newIds[tempParentIndex] 二阶段 UPDATE.
         */
        public Integer tempParentIndex;
        /**
         * 加产品整份快照 Phase 后续:导入来源标记。
         * 前端 BulkImportPartsDrawer 对"从基础数据导入加入报价单"的行设 true →
         * saveDraft 在该行无 processIds 时,从该料号基础工序(material_bom_item.operation_no)
         * seed 本行 quotation_line_process,使 [选配-工序列表] 与选配产品渲染一致。
         * 选配路径不设(保持"选配没选工序=空")。
         */
        public Boolean seedProcessesFromBase;

        /**
         * 选配-组合工艺 per-quote 步骤(已解析:participatingParts 为子件料号,非下标)。
         * 前端从 configure 响应/GET 带到本行,saveDraft 全量重建换 line id 后据此重写
         * quotation_line_composite_process,使组合工艺跨保存存活。
         */
        public List<CompositeProcessDraft> compositeProcesses;
    }

    public static class CompositeProcessDraft {
        public String defCode;
        public Integer seqNo;
        public List<String> participatingParts;
        public Map<String, Object> paramValues;
    }

    public static class ComponentDataDraft {
        public UUID componentId;
        public String tabName;
        public String rowData;
        public BigDecimal subtotal;
        public Integer sortOrder;
    }
}
