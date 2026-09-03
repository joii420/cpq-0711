package com.cpq.quotation.dto;

import com.cpq.common.DecimalStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
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
    @JsonDeserialize(using = DecimalStringDeserializer.class)
    public BigDecimal finalDiscountRate;
    public String discountAdjustmentReason;

    // 2026-05-18: 报价模板 / 核价模板 绑定 — 之前漏在 SaveDraft 透传, 导致 Step1 选模板后
    // 永远写不到 quotation.customer_template_id / costing_card_template_id, 刷新页面拿不到值.
    public UUID customerTemplateId;
    public UUID costingCardTemplateId;

    /**
     * task-0729: 建单时的产品分类。仅当非 null 时才覆盖 quotation.product_category_id
     * ——null 不清空已有值（前端旧版本 / 异常 payload 不该抹掉已存分类），见 QuotationService#saveDraft。
     */
    public UUID categoryId;

    /**
     * task-260901 B-3：前端最近一次从服务端拿到的 {@code quotation.user_data_version}（乐观并发基线）。
     * 与库中现值不等 → 409 {@code STALE_VERSION}（见 {@code api.md §1.4}）。
     * 走新三数组协议时必填；旧 {@code lineItems} 兼容模式下可空（不做版本校验）。
     */
    public Integer baseVersion;

    // ── task-260901 B-2 增量协议：三数组取代全量 lineItems ────────────────────────────────────
    /** 新增行。每个元素的 {@code id} 必须为 null（非 null → 400）；用 {@code tempId} 认领回传的新 id。 */
    public List<LineItemDraft> added;
    /** 修改行。每个元素的 {@code id} 必须非 null 且属于本单（否则 400）。 */
    public List<LineItemDraft> modified;
    /** 删除行的 id 列表。🚨 删除语义已从「payload 里没出现 = 删」改为「只删这里列出的」。 */
    public List<UUID> removed;

    /**
     * ⚠️ <b>旧全量协议，保留一个版本周期做回滚兜底</b>（task-260901 B-2a）。非 null 时按旧语义处理
     * （payload 未出现的行 = 用户删了）并打 WARN。不能与 {@code added/modified/removed} 同时出现。
     */
    public List<LineItemDraft> lineItems;

    public static class LineItemDraft {
        /**
         * task-260901 B-4c：前端为「尚未持久化的行」生成的稳定 key。后端<b>原样回传</b>，前端据此
         * 认领 DB 生成的新 id。🔒 不按数组下标配对——增量协议下下标已无语义（AC-17）。
         */
        public String tempId;
        /**
         * task-260901 B-2f：组合产品父子关系（取代 {@code tempParentIndex} 的下标耦合）。
         * 父行也在本次 {@code added} 里时，填父行的 {@code tempId}。与 {@code parentLineItemId} 互斥。
         */
        public String tempParentKey;
        /**
         * task-260901 B-2f：父行已持久化时直接给它的 DB id。与 {@code tempParentKey} 互斥。
         */
        public UUID parentLineItemId;
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
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        public BigDecimal subtotal;
        public Integer sortOrder;
        /**
         * task-0712 缺口1 遗留涟漪修复: 工序编号(process_master.process_no), 取代旧 UUID processIds。
         * saveDraft 每次都全量重建 quotation_line_process(先删后按此列表重插, 见 QuotationService
         * #processBatchStage1 / 逐行回落路径), 必须与 ConfigureProductService.insertQuotationLineProcesses
         * 同口径写 process_no, 否则前端回传空值会导致选配时已落库的工序在下一次 saveDraft 被静默清空。
         */
        public List<String> processNos;
        public List<ComponentDataDraft> componentData;
        /** V169 选配组合产品关系标识 SIMPLE / COMPOSITE / PART (saveDraft 全量重建时必须透传保留) */
        public String compositeType;
        /**
         * ⚠️ <b>已废弃（task-260901 B-2f）</b>：父级在前端 lineItems list 中的索引 (PART 子件用)。
         * 只在旧 {@code lineItems} 全量协议下仍被解释；新三数组协议下 payload 下标已无全局语义，
         * 一律改用 {@code tempParentKey} / {@code parentLineItemId}。
         */
        public Integer tempParentIndex;
        /**
         * 加产品整份快照 Phase 后续:导入来源标记。
         * 前端 BulkImportPartsDrawer 对"从基础数据导入加入报价单"的行设 true →
         * saveDraft 在该行无 processNos 时,从该料号基础工序(material_bom_item.operation_no)
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

        /**
         * 前端算好的报价 Excel 列值快照 {"rows":[{"col_key":value,...},...]}。
         * Phase 3（2026-06-21）：前端单引擎计算权威；后端原样落库 quote_excel_values，不重算。
         * snapshotLineValues 守卫：仅当 li.quoteExcelValues==null 时才调 buildExcelValues 兜底。
         */
        public String quoteExcelValues;

        // ─── Step3 行级折扣（V302；前端 buildDraftPayload 早已透传，后端此前丢弃）───
        public Integer annualVolume;
        public String discountSource;
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        public BigDecimal discountBaseAmount;
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        public BigDecimal discountRateApplied;
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        public BigDecimal lineDiscountAmount;
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        public BigDecimal lineUnitPrice;
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        public BigDecimal lineFinalPrice;
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        public BigDecimal lineTotalAmount;
        public String discountRuleCode;
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
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        public BigDecimal subtotal;
        public Integer sortOrder;
    }
}
