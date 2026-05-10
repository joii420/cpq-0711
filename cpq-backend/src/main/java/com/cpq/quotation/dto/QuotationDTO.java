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
        public List<ComponentDataDTO> componentData;
        public SnapshotDTO snapshot;

        public static LineItemDTO from(QuotationLineItem li) {
            LineItemDTO dto = new LineItemDTO();
            dto.id = li.id;
            dto.productId = li.productId;
            dto.templateId = li.templateId;
            dto.customerPartNo = li.customerPartNo;
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
        public BigDecimal subtotal;
        public Integer sortOrder;

        public static ComponentDataDTO from(QuotationLineComponentData cd) {
            ComponentDataDTO dto = new ComponentDataDTO();
            dto.id = cd.id;
            dto.componentId = cd.componentId;
            dto.tabName = cd.tabName;
            dto.rowData = cd.rowData;
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
