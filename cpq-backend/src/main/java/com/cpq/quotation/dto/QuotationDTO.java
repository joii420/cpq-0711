package com.cpq.quotation.dto;

import com.cpq.quotation.entity.*;
import com.cpq.quotation.service.DriftDetectionService.RefVersionEntry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class QuotationDTO {

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
    public String assignedApproverName;
    public String remarks;
    /** Phase 2 渲染脱钩: 报价单级 4 份结构快照(创建即冻; getById 从 quotation_view_structure 读填充) */
    public com.fasterxml.jackson.databind.JsonNode quoteCardStructure;
    public com.fasterxml.jackson.databind.JsonNode quoteExcelStructure;
    public com.fasterxml.jackson.databind.JsonNode costingCardStructure;
    public com.fasterxml.jackson.databind.JsonNode costingExcelStructure;

    /**
     * 客户报价模板 ID(由 BasicDataImportV5ToQuotation 创建报价单时按
     * (customerId, categoryId) 自动匹配后写入)。
     * Step2 据此决定是否显示"批量从基础数据导入"按钮。
     */
    public UUID customerTemplateId;

    /**
     * V72：核价模板（template 表里 template_kind='COSTING' 的模板）。
     * 由「创建报价单」抽屉按 (categoryId + customerId) 匹配后写入；
     * 「核价单」视图据此渲染产品卡片的核价模板。
     */
    public UUID costingCardTemplateId;

    // Customer snapshot
    public String snapshotCustomerName;
    public String snapshotCustomerLevel;
    public String snapshotCustomerRegion;
    public String snapshotCustomerIndustry;
    public String snapshotCustomerAddress;

    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    // Nested data (populated in detail view)
    public List<LineItemDTO> lineItems;
    public List<ApprovalDTO> approvalHistory;

    // ---- v5.1 §6.6 DRAFT 漂移检测字段（仅 DRAFT 状态 getById 时填充）----

    /**
     * referencedVersions 快照（反序列化后）：表名 → 业务键 → RefVersionEntry{version, recordId}。
     * D-3：升级为含 recordId 的结构，前端 SnapshotTab 可直接用 recordId 调版本对比 API。
     */
    public Map<String, Map<String, RefVersionEntry>> referencedVersions;

    /** 是否存在漂移（仅 DRAFT 状态）*/
    public boolean hasDrift;

    /** 漂移明细列表（hasDrift=true 时非空）*/
    public List<DriftedRecordDTO> driftedRecords;

    public static QuotationDTO from(Quotation q) {
        QuotationDTO dto = new QuotationDTO();
        dto.id = q.id;
        dto.quotationNumber = q.quotationNumber;
        dto.customerId = q.customerId;
        dto.name = q.name;
        dto.contactId = q.contactId;
        dto.contactName = q.contactName;
        dto.contactPhone = q.contactPhone;
        dto.contactEmail = q.contactEmail;
        dto.projectName = q.projectName;
        dto.opportunityId = q.opportunityId;
        dto.salesRepId = q.salesRepId;
        dto.quoteType = q.quoteType;
        dto.priority = q.priority;
        dto.stage = q.stage;
        dto.expectedCloseDate = q.expectedCloseDate;
        dto.status = q.status;
        dto.totalAmount = q.totalAmount;
        dto.expiryDate = q.expiryDate;
        dto.paymentTerms = q.paymentTerms;
        dto.deliveryCycle = q.deliveryCycle;
        dto.originalAmount = q.originalAmount;
        dto.systemDiscountRate = q.systemDiscountRate;
        dto.finalDiscountRate = q.finalDiscountRate;
        dto.taxRate = q.taxRate;
        dto.taxAmount = q.taxAmount;
        dto.discountAdjustmentReason = q.discountAdjustmentReason;
        dto.isManuallyAdjusted = q.isManuallyAdjusted;
        dto.sourceQuotationId = q.sourceQuotationId;
        dto.assignedApproverId = q.assignedApproverId;
        dto.customerTemplateId = q.customerTemplateId;
        dto.costingCardTemplateId = q.costingCardTemplateId;
        dto.remarks = q.remarks;
        dto.snapshotCustomerName = q.snapshotCustomerName;
        dto.snapshotCustomerLevel = q.snapshotCustomerLevel;
        dto.snapshotCustomerRegion = q.snapshotCustomerRegion;
        dto.snapshotCustomerIndustry = q.snapshotCustomerIndustry;
        dto.snapshotCustomerAddress = q.snapshotCustomerAddress;
        dto.createdAt = q.createdAt;
        dto.updatedAt = q.updatedAt;
        return dto;
    }

    public static class LineItemDTO {
        public UUID id;
        public UUID productId;
        public UUID templateId;
        // 前端 driver 展开 / BASIC_DATA 路径解析全靠 productPartNo；DB 端没有冗余列，
        // 这里通过 product_id JOIN product 取，再回退 product_part_no_snapshot。
        public String productPartNo;
        public String productName;
        public String customerPartNo;
        // PRD：产品卡片"客户视角"展示——从 mat_customer_part_mapping 按 (customerId, hfPartNo)
        // 反查得到。前端有这两个字段时优先于 productName / productPartNo 展示。
        public String customerPartName;
        public String customerProductNo;
        public String customerDrawingNo;
        // PRD：产品卡片右侧"生产料号"详情卡片——按 productPartNo (=hf_part_no) 查 mat_part 主档
        public HfPartInfo hfPartInfo;
        public String productAttributeValues;
        public BigDecimal subtotal;
        public BigDecimal systemDiscountRate;
        public BigDecimal finalDiscountRate;
        public String discountAdjustmentReason;
        public Boolean isManuallyAdjusted;
        public Integer sortOrder;
        public List<ProcessDTO> processes;
        /** 选配-组合工艺 per-quote 步骤:[{defCode, seqNo, participatingParts[], paramValues{}}]。前端透传到 saveDraft 跨保存存活。 */
        public List<Map<String, Object>> compositeProcesses;
        public List<ComponentDataDTO> componentData;
        public SnapshotDTO snapshot;
        /** 料号版本锁定 (S5): 该行报价使用的 (customer_product_no, hf_part_no) 版本号 */
        public Integer partVersionLocked;
        /**
         * 产品类型 — 反查自 mat_part.product_type ('SIMPLE' / 'COMPOSITE').
         *
         * <p>用途: 前端 ProductCard 按产品类型条件渲染 Tab(COMPOSITE 专属 Tab
         * 如"组合工艺/子配件"在 SIMPLE 产品下隐藏).
         *
         * <p>注: 不读 quotation_line_item.composite_type — 该列在 saveDraft 全量
         * 重建时被前端 payload 覆盖回 'SIMPLE' (前端 buildDraftPayload 未透传),
         * 不可靠. mat_part.product_type 在选配落库时正确写入, 且不被报价单层修改.
         */
        public String productType;
        /** V169: 选配组合产品父子关系标识 - SIMPLE / COMPOSITE / PART */
        public String compositeType;
        /** V169: PART 行指向父级 line_item.id, 其他类型为 null */
        public java.util.UUID parentLineItemId;
        /** Phase 2 渲染脱钩: 行级 4 份值快照(JSON 字符串原样透传前端读) */
        public String quoteCardValues;
        public String quoteExcelValues;
        public String costingCardValues;
        public String costingExcelValues;

        // Step3 行级折扣（V302）
        public Integer annualVolume;
        public String discountSource;
        public java.math.BigDecimal discountBaseAmount;
        public java.math.BigDecimal discountRateApplied;
        public java.math.BigDecimal lineDiscountAmount;
        public java.math.BigDecimal lineUnitPrice;
        public java.math.BigDecimal lineFinalPrice;
        public java.math.BigDecimal lineTotalAmount;
        public String discountRuleCode;

        public static LineItemDTO from(QuotationLineItem li) {
            LineItemDTO dto = new LineItemDTO();
            dto.id = li.id;
            dto.productId = li.productId;
            dto.templateId = li.templateId;
            dto.customerPartNo = li.customerPartNo;
            dto.partVersionLocked = li.partVersionLocked;
            dto.productAttributeValues = li.productAttributeValues;
            dto.subtotal = li.subtotal;
            dto.systemDiscountRate = li.systemDiscountRate;
            dto.finalDiscountRate = li.finalDiscountRate;
            dto.discountAdjustmentReason = li.discountAdjustmentReason;
            dto.isManuallyAdjusted = li.isManuallyAdjusted;
            dto.sortOrder = li.sortOrder;
            // 优先走 product 表（v3 链路），缺失时回退到提交快照字段（V5 链路）
            if (li.productId != null) {
                com.cpq.product.entity.Product p = com.cpq.product.entity.Product.findById(li.productId);
                if (p != null) {
                    dto.productPartNo = p.partNo;
                    dto.productName = p.name;
                }
            }
            if (dto.productPartNo == null) dto.productPartNo = li.productPartNoSnapshot;
            if (dto.productName == null) dto.productName = li.productNameSnapshot;
            // productType 由 QuotationService 在 from() 之后通过批量 SQL 反查 mat_part 后填充
            // (避免 N+1 + LineItemDTO 解耦 EntityManager)
            // V169 选配组合关系直接透传 — saveDraft 不写这两列, 仅初次配置时写入
            dto.compositeType = li.compositeType;
            dto.parentLineItemId = li.parentLineItemId;
            dto.quoteCardValues = li.quoteCardValues;
            dto.quoteExcelValues = li.quoteExcelValues;
            dto.costingCardValues = li.costingCardValues;
            dto.costingExcelValues = li.costingExcelValues;
            dto.annualVolume = li.annualVolume;
            dto.discountSource = li.discountSource;
            dto.discountBaseAmount = li.discountBaseAmount;
            dto.discountRateApplied = li.discountRateApplied;
            dto.lineDiscountAmount = li.lineDiscountAmount;
            dto.lineUnitPrice = li.lineUnitPrice;
            dto.lineFinalPrice = li.lineFinalPrice;
            dto.lineTotalAmount = li.lineTotalAmount;
            dto.discountRuleCode = li.discountRuleCode;
            return dto;
        }
    }

    /**
     * 生产料号（HF 主档）信息——给前端"客户料号 → 生产料号"小卡片用。
     * 数据来源：mat_part 表 by hf_part_no。
     */
    public static class HfPartInfo {
        public String partNo;
        public String partName;
        public String specification;
        public String sizeInfo;
        public String statusCode;
    }

    public static class ProcessDTO {
        public UUID id;
        public UUID processId;

        public static ProcessDTO from(QuotationLineProcess p) {
            ProcessDTO dto = new ProcessDTO();
            dto.id = p.id;
            dto.processId = p.processId;
            return dto;
        }
    }

    public static class ComponentDataDTO {
        public UUID id;
        public UUID componentId;
        public String tabName;
        public String rowData;
        /** driver 默认行墓碑数组 JSON（[{effKey,fp}]），前端按此过滤被删行。默认 "[]"。 */
        public String deletedRowKeys;
        public BigDecimal subtotal;
        public Integer sortOrder;

        public static ComponentDataDTO from(QuotationLineComponentData cd) {
            ComponentDataDTO dto = new ComponentDataDTO();
            dto.id = cd.id;
            dto.componentId = cd.componentId;
            dto.tabName = cd.tabName;
            dto.rowData = cd.rowData;
            dto.deletedRowKeys = cd.deletedRowKeys;
            dto.subtotal = cd.subtotal;
            dto.sortOrder = cd.sortOrder;
            return dto;
        }
    }

    public static class SnapshotDTO {
        public UUID id;
        public String productPartNo;
        public String productCategory;
        public String productSpecification;

        public static SnapshotDTO from(QuotationLineItemSnapshot s) {
            SnapshotDTO dto = new SnapshotDTO();
            dto.id = s.id;
            dto.productPartNo = s.productPartNo;
            dto.productCategory = s.productCategory;
            dto.productSpecification = s.productSpecification;
            return dto;
        }
    }

    public static class ApprovalDTO {
        public UUID id;
        public UUID approverId;
        public String approverName;
        public String action;
        public String comment;
        public OffsetDateTime actedAt;
        public OffsetDateTime createdAt;

        public static ApprovalDTO from(QuotationApproval a) {
            ApprovalDTO dto = new ApprovalDTO();
            dto.id = a.id;
            dto.approverId = a.approverId;
            dto.action = a.action;
            dto.comment = a.comment;
            dto.actedAt = a.actedAt;
            dto.createdAt = a.createdAt;
            return dto;
        }
    }
}
