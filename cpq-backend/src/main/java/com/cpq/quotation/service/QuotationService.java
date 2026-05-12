package com.cpq.quotation.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.customer.entity.Customer;
import com.cpq.engine.approval.ApprovalRoutingService;
import com.cpq.engine.discount.DiscountCalculationService;
import com.cpq.engine.discount.DiscountResult;
import com.cpq.formula.FormulaError;
import com.cpq.formula.calculator.DerivedAttributeCalculatorV5;
import com.cpq.basicdata.entity.DerivedAttribute;
import com.cpq.importexcel.dto.ImportResultDTO;
import com.cpq.importexcel.entity.ImportRecord;
import com.cpq.importexcel.service.BasicDataImportServiceV5;
import com.cpq.product.entity.Product;
import com.cpq.quotation.dto.CreateQuotationRequest;
import com.cpq.quotation.dto.DriftedRecordDTO;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.entity.*;
import com.cpq.quotation.service.DriftDetectionService;
import com.cpq.quotation.service.DriftDetectionService.DriftDetectionResult;
import com.cpq.quotation.snapshot.FieldTraceDTO;
import com.cpq.quotation.snapshot.SnapshotCollectorService;
import com.cpq.quotation.snapshot.SnapshotCollectorService.SubmissionSnapshot;
import com.cpq.system.entity.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.InputStream;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class QuotationService {

    private static final Logger LOG = Logger.getLogger(QuotationService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    DiscountCalculationService discountCalculationService;

    @Inject
    ApprovalRoutingService approvalRoutingService;

    @Inject
    DriftDetectionService driftDetectionService;

    @Inject
    DerivedAttributeCalculatorV5 derivedAttributeCalculatorV5;

    @Inject
    SnapshotCollectorService snapshotCollectorService;

    @Inject
    BasicDataImportServiceV5 basicDataImportServiceV5;

    @Inject
    EntityManager em;

    private static final java.util.Set<String> VALID_QUOTATION_STATUSES = java.util.Set.of(
            "DRAFT", "SUBMITTED", "APPROVED", "SENT", "ACCEPTED", "REJECTED", "EXPIRED", "CANCELLED"
    );

    public PageResult<QuotationDTO> list(int page, int size, String status, UUID salesRepId, UUID assignedApproverId, String keyword) {
        page = com.cpq.common.dto.Pagination.clampPage(page);
        size = com.cpq.common.dto.Pagination.clampSize(size);
        StringBuilder where = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (status != null && !status.isBlank()) {
            if (!VALID_QUOTATION_STATUSES.contains(status)) {
                throw new BusinessException(400,
                        "Invalid status value: " + status + ". Allowed: " + VALID_QUOTATION_STATUSES);
            }
            where.append(" AND status = :status");
            params.put("status", status);
        }
        if (salesRepId != null) {
            where.append(" AND salesRepId = :salesRepId");
            params.put("salesRepId", salesRepId);
        }
        if (assignedApproverId != null) {
            where.append(" AND assignedApproverId = :approverId");
            params.put("approverId", assignedApproverId);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (LOWER(name) LIKE :keyword OR LOWER(quotationNumber) LIKE :keyword OR LOWER(snapshotCustomerName) LIKE :keyword)");
            params.put("keyword", "%" + keyword.toLowerCase() + "%");
        }

        String query = where + " ORDER BY updatedAt DESC";
        long total = Quotation.count(where.toString(), params);
        List<QuotationDTO> content = Quotation.find(query, params)
                .page(page, size)
                .<Quotation>list()
                .stream()
                .map(QuotationDTO::from)
                .collect(Collectors.toList());

        return new PageResult<>(content, page, size, total);
    }

    public QuotationDTO getById(UUID id) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }
        QuotationDTO dto = QuotationDTO.from(q);
        if (q.assignedApproverId != null) {
            User approver = User.findById(q.assignedApproverId);
            if (approver != null) {
                dto.assignedApproverName = approver.fullName;
            }
        }
        dto.lineItems = loadLineItems(id);
        dto.approvalHistory = loadApprovalHistory(id);

        // v5.1 §6.6 DRAFT 漂移检测：仅 DRAFT 状态触发
        if ("DRAFT".equals(q.status)) {
            populateDriftInfo(dto, q);
        }

        return dto;
    }

    /**
     * 填充漂移检测信息到 DTO。
     * D-3：referencedVersions 反序列化为新格式 Map&lt;table, Map&lt;bk, RefVersionEntry&gt;&gt;。
     */
    private void populateDriftInfo(QuotationDTO dto, Quotation q) {
        // D-3：反序列化 referencedVersions 到 DTO（兼容旧格式 int + 新格式 object）
        if (q.referencedVersions != null && !q.referencedVersions.isBlank()) {
            try {
                dto.referencedVersions = driftDetectionService.parseReferencedVersions(q.referencedVersions);
            } catch (Exception e) {
                LOG.warnf("Failed to deserialize referencedVersions for quotation=%s: %s", q.id, e.getMessage());
            }
        }

        // 漂移检测
        DriftDetectionResult result = driftDetectionService.detect(q.referencedVersions);
        dto.hasDrift = result.hasDrift();
        dto.driftedRecords = result.driftedRecords();
    }

    @Transactional
    public QuotationDTO create(CreateQuotationRequest request, UUID salesRepId) {
        Quotation q = new Quotation();
        q.quotationNumber = generateQuotationNumber();
        q.customerId = request.customerId;
        q.name = request.name;
        q.contactId = request.contactId;
        q.contactName = request.contactName;
        q.contactPhone = request.contactPhone;
        q.contactEmail = request.contactEmail;
        q.projectName = request.projectName;
        q.opportunityId = request.opportunityId;
        q.salesRepId = salesRepId;
        if (request.quoteType != null) q.quoteType = request.quoteType;
        if (request.priority != null) q.priority = request.priority;
        if (request.stage != null) q.stage = request.stage;
        q.expectedCloseDate = request.expectedCloseDate;
        // 客户报价模板:由前端按 (customerId + categoryId) 通过 match-customer-quote 匹配后传入
        if (request.customerTemplateId != null) q.customerTemplateId = request.customerTemplateId;
        q.status = "DRAFT";
        q.expiryDate = LocalDate.now().plusDays(30);

        // Snapshot customer info
        Customer customer = Customer.findById(request.customerId);
        if (customer == null) {
            throw new BusinessException(400, "Customer not found: " + request.customerId);
        }
        q.snapshotCustomerName = customer.name;
        q.snapshotCustomerLevel = customer.level;
        q.snapshotCustomerRegion = customer.region;
        q.snapshotCustomerIndustry = customer.industry;
        q.snapshotCustomerAddress = customer.address;

        q.persist();
        LOG.infof("Created quotation id=%s number=%s customer=%s", q.id, q.quotationNumber, q.customerId);

        // V72：双模板体系——核价模板从 template 表（template_kind='COSTING'）取，写入 quotation.costing_card_template_id。
        // 不再创建空 costing_sheet（那是 Excel 模板配置的职责，独立体系）。
        if (request.costingTemplateId != null) {
            com.cpq.template.entity.Template tpl = com.cpq.template.entity.Template.findById(request.costingTemplateId);
            if (tpl == null) {
                LOG.warnf("Quotation create: ignore non-existent costing card template id=%s", request.costingTemplateId);
            } else if (!"COSTING".equals(tpl.templateKind)) {
                throw new BusinessException(400,
                        "选中的核价模板 templateKind 不是 COSTING：id=" + tpl.id + ", kind=" + tpl.templateKind);
            } else if (!"PUBLISHED".equals(tpl.status)) {
                throw new BusinessException(400,
                        "选中的核价模板未发布（status=" + tpl.status + "），无法用于新建报价单");
            } else {
                q.costingCardTemplateId = tpl.id;
                q.persist();
                LOG.infof("Quotation %s bound costing card template %s (%s)", q.id, tpl.id, tpl.name);
            }
        }

        return QuotationDTO.from(q);
    }

    @Transactional
    public QuotationDTO saveDraft(UUID id, SaveDraftRequest request) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }
        if (!"DRAFT".equals(q.status)) {
            throw new BusinessException(400, "Only DRAFT quotations can be edited");
        }

        // Update header fields
        if (request.name != null) q.name = request.name;
        if (request.contactId != null) q.contactId = request.contactId;
        if (request.contactName != null) q.contactName = request.contactName;
        if (request.contactPhone != null) q.contactPhone = request.contactPhone;
        if (request.contactEmail != null) q.contactEmail = request.contactEmail;
        if (request.projectName != null) q.projectName = request.projectName;
        if (request.opportunityId != null) q.opportunityId = request.opportunityId;
        if (request.quoteType != null) q.quoteType = request.quoteType;
        if (request.priority != null) q.priority = request.priority;
        if (request.stage != null) q.stage = request.stage;
        if (request.expectedCloseDate != null) q.expectedCloseDate = request.expectedCloseDate;
        if (request.paymentTerms != null) q.paymentTerms = request.paymentTerms;
        if (request.deliveryCycle != null) q.deliveryCycle = request.deliveryCycle;
        if (request.expiryDate != null) q.expiryDate = request.expiryDate;
        if (request.remarks != null) q.remarks = request.remarks;

        // Pricing overrides
        if (request.finalDiscountRate != null) {
            q.finalDiscountRate = request.finalDiscountRate;
            q.isManuallyAdjusted = true;
            q.discountAdjustmentReason = request.discountAdjustmentReason;
        }

        // Replace line items (last-write-wins)
        if (request.lineItems != null) {
            deleteLineItems(id);
            BigDecimal total = BigDecimal.ZERO;
            Set<String> collectedPartNos = new LinkedHashSet<>();

            for (int i = 0; i < request.lineItems.size(); i++) {
                SaveDraftRequest.LineItemDraft liDraft = request.lineItems.get(i);
                QuotationLineItem li = new QuotationLineItem();
                li.quotationId = id;
                li.productId = liDraft.productId;
                li.templateId = liDraft.templateId;
                if (liDraft.productAttributeValues != null) li.productAttributeValues = liDraft.productAttributeValues;
                if (liDraft.subtotal != null) li.subtotal = liDraft.subtotal;
                li.sortOrder = liDraft.sortOrder != null ? liDraft.sortOrder : i;
                // V5 批量导入：productId 为空时，把前端送来的 partNo / name 直接写入 snapshot 列，
                // 否则刷新后前端 li.productPartNo 永远为空，driver 展开失败 → BASIC_DATA 列全空。
                if (liDraft.productPartNo != null && !liDraft.productPartNo.isBlank()) {
                    li.productPartNoSnapshot = liDraft.productPartNo;
                }
                if (liDraft.productName != null && !liDraft.productName.isBlank()) {
                    li.productNameSnapshot = liDraft.productName;
                }
                if (liDraft.customerPartNo != null && !liDraft.customerPartNo.isBlank()) {
                    li.customerPartNo = liDraft.customerPartNo;
                }
                li.persist();

                // 料号版本管理 (S5): 拷贝 mat_customer_part_mapping.current_version → part_version_locked
                // 业务功能不变 — 仅写入 line_item 的版本快照, 读路径仍按旧方式工作
                if (li.customerPartNo != null && !li.customerPartNo.isBlank()
                        && li.productPartNoSnapshot != null && !li.productPartNoSnapshot.isBlank()) {
                    try {
                        Object cur = em.createNativeQuery(
                                "SELECT current_version FROM mat_customer_part_mapping " +
                                "WHERE customer_product_no = :cpn AND hf_part_no = :hf LIMIT 1")
                                .setParameter("cpn", li.customerPartNo)
                                .setParameter("hf", li.productPartNoSnapshot)
                                .getResultList().stream().findFirst().orElse(null);
                        if (cur != null) {
                            li.partVersionLocked = ((Number) cur).intValue();
                        }
                    } catch (Exception e) {
                        // 失败保留默认 2000, 不阻塞报价单创建
                    }
                }

                // 收集 partNo 供版本快照（通过 Product 查询 HF 料号）
                if (liDraft.productId != null) {
                    Product product = Product.findById(liDraft.productId);
                    if (product != null && product.partNo != null) {
                        collectedPartNos.add(product.partNo);
                        // 把 productPartNo / productName 同步到快照列，
                        // 这样即便后续 product 行被改、被删，列表 DTO 仍能给出料号。
                        li.productPartNoSnapshot = product.partNo;
                        li.productNameSnapshot = product.name;

                        // v5.1 §6.6 公式引擎接入：计算衍生属性（每个 lineItem）
                        // 查询此 product 对应的衍生属性定义
                        try {
                            List<DerivedAttribute> derivedAttrs = loadDerivedAttributes(product.partNo);
                            if (!derivedAttrs.isEmpty()) {
                                Map<String, Object> calcResults = derivedAttributeCalculatorV5.calculate(
                                        q.customerId, product.partNo, derivedAttrs);
                                // 将计算结果合并到 productAttributeValues（JSON 字符串）
                                if (!calcResults.isEmpty()) {
                                    li.productAttributeValues = mergeFormulaResults(
                                            li.productAttributeValues, calcResults);
                                    // flush 已 persist 的 li，更新 productAttributeValues
                                    em.flush();
                                }
                                logFormulaErrors(calcResults, q.id, product.partNo);
                            }
                        } catch (Exception e) {
                            LOG.warnf("FormulaEngine calculation failed for quotation=%s partNo=%s: %s",
                                    q.id, product.partNo, e.getMessage());
                            // 公式计算失败不阻塞保存
                        }
                    }
                }

                if (liDraft.subtotal != null) {
                    total = total.add(liDraft.subtotal);
                }

                // Save processes
                if (liDraft.processIds != null) {
                    for (UUID processId : liDraft.processIds) {
                        QuotationLineProcess lp = new QuotationLineProcess();
                        lp.lineItemId = li.id;
                        lp.processId = processId;
                        lp.persist();
                    }
                }

                // Save component data
                if (liDraft.componentData != null) {
                    for (int j = 0; j < liDraft.componentData.size(); j++) {
                        SaveDraftRequest.ComponentDataDraft cdDraft = liDraft.componentData.get(j);
                        QuotationLineComponentData cd = new QuotationLineComponentData();
                        cd.lineItemId = li.id;
                        cd.componentId = cdDraft.componentId;
                        cd.tabName = cdDraft.tabName;
                        if (cdDraft.rowData != null) cd.rowData = cdDraft.rowData;
                        if (cdDraft.subtotal != null) cd.subtotal = cdDraft.subtotal;
                        cd.sortOrder = cdDraft.sortOrder != null ? cdDraft.sortOrder : j;
                        cd.persist();
                    }
                }
            }

            q.originalAmount = total;
            q.totalAmount = total.multiply(q.finalDiscountRate).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);

            // v5.1 §6.6 收集版本快照
            if (!collectedPartNos.isEmpty()) {
                String versionsJson = driftDetectionService.collectReferencedVersions(
                        q.customerId, new ArrayList<>(collectedPartNos));
                q.referencedVersions = versionsJson;
                LOG.debugf("Recorded referencedVersions for quotation=%s partNos=%s", q.id, collectedPartNos);
            }
        }

        q.persist();
        LOG.infof("Saved draft for quotation id=%s", id);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);

        // 漂移信息（saveDraft 后返回最新状态）
        populateDriftInfo(dto, q);

        return dto;
    }

    /**
     * 记录/更新 DRAFT 报价单的 referenced_versions 快照（不重算公式）。
     * 仅在创建时或手动刷新前调用。
     */
    @Transactional
    public QuotationDTO recordReferencedVersions(UUID quotationId) {
        Quotation q = Quotation.findById(quotationId);
        if (q == null) throw new BusinessException(404, "Quotation not found: " + quotationId);
        if (!"DRAFT".equals(q.status)) throw new BusinessException(400, "只有 DRAFT 状态的报价单可更新版本快照");

        List<String> partNos = collectPartNosFromLineItems(quotationId);
        if (!partNos.isEmpty()) {
            q.referencedVersions = driftDetectionService.collectReferencedVersions(q.customerId, partNos);
        }
        q.persist();

        QuotationDTO dto = QuotationDTO.from(q);
        populateDriftInfo(dto, q);
        return dto;
    }

    /**
     * 用户接受漂移后：重新计算公式 + 更新 referenced_versions 为当前版本。
     *
     * @param quotationId   报价单 ID
     * @param currentUserId 当前操作用户（需为 SALES_REP）
     */
    @Transactional
    public QuotationDTO refreshVersions(UUID quotationId, UUID currentUserId) {
        Quotation q = Quotation.findById(quotationId);
        if (q == null) throw new BusinessException(404, "Quotation not found: " + quotationId);
        // v5.1 §10 卫语句：SUBMITTED 报价单不允许刷新版本（快照已冻结）
        if ("SUBMITTED".equals(q.status)) throw new BusinessException(409, "SUBMITTED 报价不可刷新版本");
        if (!"DRAFT".equals(q.status)) throw new BusinessException(400, "只有 DRAFT 状态的报价单可刷新版本");

        // 权限校验：仅 SALES_REP（或 SYSTEM_ADMIN）可调用
        if (currentUserId != null) {
            User user = User.findById(currentUserId);
            if (user == null || (!"SALES_REP".equals(user.role) && !"SYSTEM_ADMIN".equals(user.role))) {
                throw new BusinessException(403, "仅销售代表（SALES_REP）可刷新报价版本");
            }
        }

        List<String> partNos = collectPartNosFromLineItems(quotationId);

        // 重新计算公式
        List<QuotationLineItem> lineItems = QuotationLineItem.list("quotationId = ?1 ORDER BY sortOrder ASC", quotationId);
        for (QuotationLineItem li : lineItems) {
            if (li.productId == null) continue;
            Product product = Product.findById(li.productId);
            if (product == null || product.partNo == null) continue;
            try {
                List<DerivedAttribute> derivedAttrs = loadDerivedAttributes(product.partNo);
                if (!derivedAttrs.isEmpty()) {
                    Map<String, Object> calcResults = derivedAttributeCalculatorV5.calculate(
                            q.customerId, product.partNo, derivedAttrs);
                    if (!calcResults.isEmpty()) {
                        li.productAttributeValues = mergeFormulaResults(li.productAttributeValues, calcResults);
                    }
                    logFormulaErrors(calcResults, q.id, product.partNo);
                }
            } catch (Exception e) {
                LOG.warnf("refreshVersions formula failed quotation=%s partNo=%s: %s",
                        q.id, product.partNo, e.getMessage());
            }
        }

        // 更新版本快照为当前 current 版本
        if (!partNos.isEmpty()) {
            q.referencedVersions = driftDetectionService.collectReferencedVersions(q.customerId, partNos);
        }
        q.persist();
        em.flush();

        LOG.infof("refreshVersions done for quotation=%s", quotationId);

        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(quotationId);
        populateDriftInfo(dto, q);
        return dto;
    }

    @Transactional
    public QuotationDTO calculateDiscount(UUID id, BigDecimal originalAmount) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }

        DiscountResult result = discountCalculationService.calculate(q.customerId, originalAmount);
        q.originalAmount = originalAmount;
        q.systemDiscountRate = result.discountRate;
        if (!Boolean.TRUE.equals(q.isManuallyAdjusted)) {
            q.finalDiscountRate = result.discountRate;
        }
        q.totalAmount = originalAmount.multiply(q.finalDiscountRate).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);

        LOG.infof("Calculated discount for quotation id=%s rate=%s rule=%s", id, result.discountRate, result.matchedRuleName);
        return QuotationDTO.from(q);
    }

    /**
     * 提交报价单（不带用户 ID，兼容旧调用）。
     * DRAFT→SUBMITTED + 写入提交快照。
     */
    @Transactional
    public QuotationDTO submit(UUID id) {
        return submit(id, null);
    }

    /**
     * 提交报价单（带用户 ID）— v5.1 §10 主入口。
     * DRAFT→SUBMITTED + 调 SnapshotCollectorService → 写入 quotation.submission_snapshot。
     * 重复提交（DRAFT 重提交）覆盖快照（PM 决策允许）。
     *
     * @param id     报价单 ID
     * @param userId 当前操作用户 ID（可为 null，向后兼容）
     */
    @Transactional
    public QuotationDTO submit(UUID id, UUID userId) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }
        if ("SUBMITTED".equals(q.status)) {
            throw new BusinessException(409, "报价单已处于 SUBMITTED 状态，不可重复提交");
        }
        if (!"DRAFT".equals(q.status)) {
            throw new BusinessException(400, "Only DRAFT quotations can be submitted");
        }

        // Refresh customer snapshot
        Customer customer = Customer.findById(q.customerId);
        if (customer != null) {
            q.snapshotCustomerName = customer.name;
            q.snapshotCustomerLevel = customer.level;
            q.snapshotCustomerRegion = customer.region;
            q.snapshotCustomerIndustry = customer.industry;
            q.snapshotCustomerAddress = customer.address;
        }

        // Create product snapshots for all line items
        List<QuotationLineItem> lineItems = QuotationLineItem.list("quotationId = ?1", id);
        for (QuotationLineItem li : lineItems) {
            // Delete existing snapshot if any
            QuotationLineItemSnapshot.delete("lineItemId = ?1", li.id);
            Product product = Product.findById(li.productId);
            if (product != null) {
                QuotationLineItemSnapshot snapshot = new QuotationLineItemSnapshot();
                snapshot.lineItemId = li.id;
                snapshot.productPartNo = product.partNo;
                snapshot.productCategory = product.category;
                snapshot.productSpecification = product.specification;
                snapshot.persist();
            }
        }

        // Route approver
        User salesRep = User.findById(q.salesRepId);
        if (salesRep != null) {
            try {
                UUID approverId = approvalRoutingService.routeApprover(salesRep.regionId, salesRep.departmentId);
                q.assignedApproverId = approverId;
            } catch (Exception e) {
                LOG.warnf("Failed to route approver for quotation id=%s: %s", id, e.getMessage());
            }
        }

        // v5.1 §10 提交快照：冻结全量数据快照
        try {
            SubmissionSnapshot snap = snapshotCollectorService.collect(id, q.referencedVersions, q.customerId);
            q.submissionSnapshot = snapshotCollectorService.toJson(snap);
            LOG.infof("SubmissionSnapshot written for quotation id=%s snapshotAt=%s", id, snap.snapshotAt());
        } catch (Exception e) {
            LOG.warnf("SubmissionSnapshot collection failed for quotation=%s (non-blocking): %s", id, e.getMessage());
            // 快照失败不阻塞提交流程
        }

        q.status = "SUBMITTED";
        LOG.infof("Submitted quotation id=%s number=%s approver=%s", id, q.quotationNumber, q.assignedApproverId);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        return dto;
    }

    /**
     * 获取报价单提交快照 JSON（原始字符串）。
     *
     * @param quotationId 报价单 ID
     * @return submission_snapshot JSON 字符串（可能为 null）
     */
    public String getSnapshot(UUID quotationId) {
        Quotation q = Quotation.findById(quotationId);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + quotationId);
        }
        return q.submissionSnapshot;
    }

    /**
     * 字段级追溯 — v5.1 §4.9。
     *
     * <p>fieldPath 格式：
     * <ul>
     *   <li>{@code lineItems[0].componentData[1].rowData.unit_price} — 行内字段</li>
     *   <li>{@code mat_part.HF-001.unit_weight} — 全局表字段</li>
     *   <li>{@code mat_fee.{customerId}|{hfPartNo}|{feeType}.unit_price} — 客户级字段</li>
     * </ul>
     *
     * @param quotationId 报价单 ID
     * @param fieldPath   字段路径表达式
     * @return FieldTraceDTO
     */
    public FieldTraceDTO getFieldTrace(UUID quotationId, String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) {
            throw new BusinessException(400, "fieldPath 不能为空");
        }

        Quotation q = Quotation.findById(quotationId);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + quotationId);
        }

        if (q.submissionSnapshot == null || q.submissionSnapshot.isBlank()) {
            throw new BusinessException(404, "报价单尚未提交，无快照数据");
        }

        // 解析快照
        Map<String, Object> snapshotMap;
        try {
            snapshotMap = MAPPER.readValue(q.submissionSnapshot,
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new BusinessException(500, "快照 JSON 解析失败: " + e.getMessage());
        }

        FieldTraceDTO trace = new FieldTraceDTO();
        trace.fieldPath = fieldPath;
        trace.lastModifiedAt = (String) snapshotMap.getOrDefault("snapshotAt", null);

        // ── 路径解析 ────────────────────────────────────────────────────────
        String prefix = fieldPath.split("\\.")[0];

        if (fieldPath.startsWith("lineItems[")) {
            // lineItems[N].componentData[M].rowData.<fieldName>
            resolveLineItemField(fieldPath, snapshotMap, trace);

        } else if (fieldPath.startsWith("mat_part.")) {
            // mat_part.<partNo>.<fieldName>
            resolveMasterDataField(fieldPath, "mat_part", snapshotMap, trace);
            trace.sourceType = "MASTER_DATA";

        } else if (fieldPath.startsWith("mat_bom.")) {
            resolveMasterDataField(fieldPath, "mat_bom", snapshotMap, trace);
            trace.sourceType = "MASTER_DATA";

        } else if (fieldPath.startsWith("plating_plan.")) {
            resolveMasterDataField(fieldPath, "plating_plan", snapshotMap, trace);
            trace.sourceType = "MASTER_DATA";

        } else if (fieldPath.startsWith("mat_fee.") || fieldPath.startsWith("mat_customer_part_mapping.")) {
            // mat_fee.<customerId>|<hfPartNo>|<feeType>.<fieldName>
            String tablePrefix = fieldPath.startsWith("mat_fee.") ? "mat_fee" : "mat_customer_part_mapping";
            resolveMasterDataField(fieldPath, tablePrefix, snapshotMap, trace);
            trace.sourceType = "CUSTOMER_DATA";
            // 追加引用版本
            trace.referencedVersion = resolveReferencedVersion(fieldPath, snapshotMap);

        } else if (fieldPath.startsWith("element_price.") || fieldPath.startsWith("elementActualPrices.")) {
            resolveElementPriceField(fieldPath, snapshotMap, trace);
            trace.sourceType = "ELEMENT_PRICE";

        } else if (fieldPath.startsWith("formulaDefinitions.")) {
            resolveFormulaField(fieldPath, snapshotMap, trace);
            trace.sourceType = "FORMULA";

        } else {
            throw new BusinessException(400,
                    "无法识别的 fieldPath 前缀: " + prefix +
                    "。支持: lineItems[N]/mat_part/mat_bom/plating_plan/mat_fee/element_price/formulaDefinitions");
        }

        return trace;
    }

    // ── fieldTrace 解析辅助 ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void resolveLineItemField(String fieldPath, Map<String, Object> snapshotMap, FieldTraceDTO trace) {
        // fieldPath: lineItems[N].componentData[M].rowData.<fieldName>
        // 在 elementActualPrices 中查找，确定是否为 ELEMENT_PRICE
        // 否则推断为 MANUAL_INPUT
        String fieldName = fieldPath.substring(fieldPath.lastIndexOf('.') + 1);
        boolean isElementPrice = fieldName.startsWith("element_actual_") || fieldName.startsWith("element_");

        if (isElementPrice) {
            trace.sourceType = "ELEMENT_PRICE";
        } else if (fieldName.startsWith("formula_") || fieldName.startsWith("derived_")) {
            trace.sourceType = "FORMULA";
            resolveFormulaForField(fieldName, snapshotMap, trace);
        } else {
            trace.sourceType = "MANUAL_INPUT";
        }

        // 尝试从 elementActualPrices 找值
        Object epMap = snapshotMap.get("elementActualPrices");
        if (epMap instanceof Map<?, ?> epMapCast) {
            // 通过 fieldName 后缀匹配（composite key 末段包含 fieldName）
            for (Map.Entry<?, ?> entry : epMapCast.entrySet()) {
                if (entry.getKey().toString().endsWith("." + fieldName)) {
                    trace.currentValue = entry.getValue();
                    return;
                }
            }
        }
        // 若未在 elementActualPrices 找到，currentValue 保持 null（v1 简化）
    }

    @SuppressWarnings("unchecked")
    private void resolveMasterDataField(String fieldPath, String tableKey,
                                         Map<String, Object> snapshotMap, FieldTraceDTO trace) {
        // fieldPath: <tableKey>.<businessKey>.<fieldName>
        // 分割：去掉前缀后剩余 "<businessKey>.<fieldName>"
        String afterPrefix = fieldPath.substring(tableKey.length() + 1); // +1 for the dot
        int lastDot = afterPrefix.lastIndexOf('.');
        if (lastDot < 0) {
            throw new BusinessException(400, "fieldPath 格式错误，缺少字段名: " + fieldPath);
        }
        String businessKey = afterPrefix.substring(0, lastDot);
        String fieldName = afterPrefix.substring(lastDot + 1);

        Object masterData = snapshotMap.get("masterDataSnapshot");
        if (masterData instanceof Map<?, ?> masterMap) {
            Object tableData = ((Map<?, ?>) masterMap).get(tableKey);
            if (tableData instanceof Map<?, ?> tableMap) {
                Object record = tableMap.get(businessKey);
                if (record instanceof Map<?, ?> recordMap) {
                    trace.currentValue = recordMap.get(fieldName);
                    return;
                }
            }
        }
        // 未找到时 currentValue 为 null（字段可能不存在于快照）
    }

    @SuppressWarnings("unchecked")
    private void resolveElementPriceField(String fieldPath, Map<String, Object> snapshotMap, FieldTraceDTO trace) {
        String fieldName = fieldPath.substring(fieldPath.lastIndexOf('.') + 1);
        Object epMap = snapshotMap.get("elementActualPrices");
        if (epMap instanceof Map<?, ?> epMapCast) {
            for (Map.Entry<?, ?> entry : epMapCast.entrySet()) {
                if (entry.getKey().toString().endsWith("." + fieldName)) {
                    trace.currentValue = entry.getValue();
                    return;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void resolveFormulaField(String fieldPath, Map<String, Object> snapshotMap, FieldTraceDTO trace) {
        // fieldPath: formulaDefinitions.<variableCode>
        String varCode = fieldPath.substring("formulaDefinitions.".length());
        Object defs = snapshotMap.get("formulaDefinitions");
        if (defs instanceof List<?> defList) {
            for (Object item : defList) {
                if (item instanceof Map<?, ?> defMap) {
                    if (varCode.equals(defMap.get("variableCode"))) {
                        trace.currentValue = defMap.get("variableCode");
                        Object comp = defMap.get("computation");
                        trace.formula = comp != null ? comp.toString() : null;
                        return;
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void resolveFormulaForField(String fieldName, Map<String, Object> snapshotMap, FieldTraceDTO trace) {
        Object defs = snapshotMap.get("formulaDefinitions");
        if (defs instanceof List<?> defList) {
            for (Object item : defList) {
                if (item instanceof Map<?, ?> defMap) {
                    if (fieldName.equals(defMap.get("variableCode"))) {
                        Object comp = defMap.get("computation");
                        trace.formula = comp != null ? comp.toString() : null;
                        return;
                    }
                }
            }
        }
    }

    /**
     * 从 referencedVersions 推断 mat_fee 等客户级表的引用版本号。
     * fieldPath: mat_fee.<customerId>|<hfPartNo>|<feeType>.<fieldName>
     * 业务键格式 in referencedVersions: <hfPartNo>|<customerId>
     */
    @SuppressWarnings("unchecked")
    private String resolveReferencedVersion(String fieldPath, Map<String, Object> snapshotMap) {
        try {
            // 从 fieldPath 解析表名 + 业务键
            String[] parts = fieldPath.split("\\.", 3);
            if (parts.length < 3) return null;
            String tableKey = parts[0]; // mat_fee 等
            String businessKeyInPath = parts[1]; // <customerId>|<hfPartNo>|<feeType>

            Object refVersionsRaw = snapshotMap.get("referencedVersions");
            if (!(refVersionsRaw instanceof Map<?, ?> refVMap)) return null;

            Object tableVersions = refVMap.get(tableKey);
            if (!(tableVersions instanceof Map<?, ?> tvMap)) return null;

            // 尝试匹配：referencedVersions 中的 key 格式是 <hfPartNo>|<customerId>
            // 从 businessKeyInPath 中提取 hfPartNo（第二段，如 "customer_id|hf-001|FEE_TYPE"）
            String[] bkParts = businessKeyInPath.split("\\|");
            if (bkParts.length >= 2) {
                // 尝试几种组合
                for (Map.Entry<?, ?> entry : tvMap.entrySet()) {
                    String bk = entry.getKey().toString();
                    // 如果 businessKeyInPath 包含 bk 的片段，视为匹配
                    boolean match = false;
                    for (String p : bkParts) {
                        if (!p.isBlank() && bk.contains(p)) { match = true; break; }
                    }
                    if (match) {
                        return tableKey + " v" + entry.getValue();
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    @Transactional
    public QuotationDTO approve(UUID id, String comment, UUID currentUserId) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }
        if (!"SUBMITTED".equals(q.status)) {
            throw new BusinessException(400, "Only SUBMITTED quotations can be approved");
        }
        User currentUser = User.findById(currentUserId);
        boolean isAdmin = currentUser != null && "SYSTEM_ADMIN".equals(currentUser.role);
        boolean isAssignedApprover = currentUserId.equals(q.assignedApproverId);
        if (!isAdmin && !isAssignedApprover) {
            throw new BusinessException(403, "You are not authorized to approve this quotation");
        }

        q.status = "APPROVED";

        QuotationApproval approval = new QuotationApproval();
        approval.quotationId = id;
        approval.approverId = currentUserId;
        approval.action = "APPROVED";
        approval.comment = comment;
        approval.actedAt = OffsetDateTime.now();
        approval.persist();

        LOG.infof("Approved quotation id=%s number=%s by=%s", id, q.quotationNumber, currentUserId);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        return dto;
    }

    @Transactional
    public QuotationDTO reject(UUID id, String comment, UUID currentUserId) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }
        if (!"SUBMITTED".equals(q.status)) {
            throw new BusinessException(400, "Only SUBMITTED quotations can be rejected");
        }
        User currentUser = User.findById(currentUserId);
        boolean isAdmin = currentUser != null && "SYSTEM_ADMIN".equals(currentUser.role);
        boolean isAssignedApprover = currentUserId.equals(q.assignedApproverId);
        if (!isAdmin && !isAssignedApprover) {
            throw new BusinessException(403, "You are not authorized to reject this quotation");
        }

        q.status = "DRAFT";

        QuotationApproval approval = new QuotationApproval();
        approval.quotationId = id;
        approval.approverId = currentUserId;
        approval.action = "REJECTED";
        approval.comment = comment;
        approval.actedAt = OffsetDateTime.now();
        approval.persist();

        LOG.infof("Rejected quotation id=%s number=%s reason=%s by=%s", id, q.quotationNumber, comment, currentUserId);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        return dto;
    }

    @Transactional
    public QuotationDTO withdraw(UUID id, UUID currentUserId) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }
        if (!"SUBMITTED".equals(q.status)) {
            throw new BusinessException(400, "Only SUBMITTED quotations can be withdrawn");
        }
        if (!q.salesRepId.equals(currentUserId)) {
            throw new BusinessException(403, "Only the creator can withdraw this quotation");
        }

        q.status = "DRAFT";
        q.assignedApproverId = null;

        QuotationApproval record = new QuotationApproval();
        record.quotationId = id;
        record.approverId = currentUserId;
        record.action = "WITHDRAWN";
        record.comment = "销售代表撤回";
        record.actedAt = OffsetDateTime.now();
        record.persist();

        LOG.infof("Withdrawn quotation id=%s number=%s by user=%s", id, q.quotationNumber, currentUserId);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        return dto;
    }

    @Transactional
    public QuotationDTO copy(UUID id) {
        Quotation source = Quotation.findById(id);
        if (source == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }

        Quotation copy = new Quotation();
        copy.quotationNumber = generateQuotationNumber();
        copy.customerId = source.customerId;
        copy.name = source.name + " (Copy)";
        copy.contactId = source.contactId;
        copy.contactName = source.contactName;
        copy.contactPhone = source.contactPhone;
        copy.contactEmail = source.contactEmail;
        copy.projectName = source.projectName;
        copy.opportunityId = source.opportunityId;
        copy.salesRepId = source.salesRepId;
        copy.quoteType = source.quoteType;
        copy.priority = source.priority;
        copy.stage = source.stage;
        copy.expectedCloseDate = source.expectedCloseDate;
        copy.status = "DRAFT";
        copy.expiryDate = LocalDate.now().plusDays(30);
        copy.paymentTerms = source.paymentTerms;
        copy.deliveryCycle = source.deliveryCycle;
        copy.originalAmount = source.originalAmount;
        copy.systemDiscountRate = source.systemDiscountRate;
        copy.finalDiscountRate = source.finalDiscountRate;
        copy.totalAmount = source.totalAmount;
        copy.sourceQuotationId = source.id;
        copy.snapshotCustomerName = source.snapshotCustomerName;
        copy.snapshotCustomerLevel = source.snapshotCustomerLevel;
        copy.snapshotCustomerRegion = source.snapshotCustomerRegion;
        copy.snapshotCustomerIndustry = source.snapshotCustomerIndustry;
        copy.snapshotCustomerAddress = source.snapshotCustomerAddress;
        copy.persist();

        // Copy line items
        List<QuotationLineItem> sourceItems = QuotationLineItem.list("quotationId = ?1 ORDER BY sortOrder ASC", id);
        for (QuotationLineItem srcLi : sourceItems) {
            QuotationLineItem newLi = new QuotationLineItem();
            newLi.quotationId = copy.id;
            newLi.productId = srcLi.productId;
            newLi.templateId = srcLi.templateId;
            newLi.productAttributeValues = srcLi.productAttributeValues;
            newLi.subtotal = srcLi.subtotal;
            newLi.sortOrder = srcLi.sortOrder;
            newLi.persist();

            // Copy processes
            List<QuotationLineProcess> srcProcesses = QuotationLineProcess.list("lineItemId = ?1", srcLi.id);
            for (QuotationLineProcess srcP : srcProcesses) {
                QuotationLineProcess newP = new QuotationLineProcess();
                newP.lineItemId = newLi.id;
                newP.processId = srcP.processId;
                newP.persist();
            }

            // Copy component data
            List<QuotationLineComponentData> srcData = QuotationLineComponentData.list("lineItemId = ?1 ORDER BY sortOrder ASC", srcLi.id);
            for (QuotationLineComponentData srcCd : srcData) {
                QuotationLineComponentData newCd = new QuotationLineComponentData();
                newCd.lineItemId = newLi.id;
                newCd.componentId = srcCd.componentId;
                newCd.tabName = srcCd.tabName;
                newCd.rowData = srcCd.rowData;
                newCd.subtotal = srcCd.subtotal;
                newCd.sortOrder = srcCd.sortOrder;
                newCd.persist();
            }
        }

        LOG.infof("Copied quotation from id=%s to id=%s number=%s", id, copy.id, copy.quotationNumber);
        QuotationDTO dto = QuotationDTO.from(copy);
        dto.lineItems = loadLineItems(copy.id);
        return dto;
    }

    @Transactional
    public void delete(UUID id) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }
        if (!"DRAFT".equals(q.status)) {
            throw new BusinessException(400, "Only DRAFT quotations can be deleted");
        }

        deleteLineItems(id);
        QuotationApproval.delete("quotationId = ?1", id);
        // v4: also clean withdraw requests (no DB cascade) and detach import records (永久保留)
        QuotationWithdrawRequest.delete("quotationId = ?1", id);
        em.createNativeQuery("UPDATE import_record SET quotation_id = NULL WHERE quotation_id = :qid")
                .setParameter("qid", id)
                .executeUpdate();
        // costing_sheet has ON DELETE CASCADE (V30) — auto-deleted with quotation
        q.delete();
        LOG.infof("Deleted quotation id=%s number=%s", id, q.quotationNumber);
    }

    @Transactional
    public QuotationDTO send(UUID id) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }
        if (!"APPROVED".equals(q.status)) {
            throw new BusinessException(400, "Only APPROVED quotations can be sent");
        }
        q.status = "SENT";
        LOG.infof("Sent quotation id=%s number=%s", id, q.quotationNumber);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        dto.approvalHistory = loadApprovalHistory(id);
        return dto;
    }

    @Transactional
    public QuotationDTO extend(UUID id, java.time.LocalDate newExpiryDate) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }
        if (!"SENT".equals(q.status) && !"APPROVED".equals(q.status)) {
            throw new BusinessException(400, "Only SENT or APPROVED quotations can be extended");
        }
        q.expiryDate = newExpiryDate;
        LOG.infof("Extended quotation id=%s number=%s newExpiryDate=%s", id, q.quotationNumber, newExpiryDate);
        return QuotationDTO.from(q);
    }

    @Transactional
    public QuotationDTO accept(UUID id, UUID currentUserId) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }
        if (!"SENT".equals(q.status)) {
            throw new BusinessException(400, "Only SENT quotations can be accepted");
        }
        if (currentUserId != null && !q.salesRepId.equals(currentUserId)) {
            throw new BusinessException(403, "仅报价单创建人可执行此操作");
        }
        q.status = "ACCEPTED";

        // Update customer accumulated_amount atomically (avoid read-then-write race condition)
        if (q.totalAmount != null) {
            em.createNativeQuery("UPDATE customer SET accumulated_amount = accumulated_amount + :amount WHERE id = :id")
                .setParameter("amount", q.totalAmount)
                .setParameter("id", q.customerId)
                .executeUpdate();
        }

        LOG.infof("Accepted quotation id=%s number=%s totalAmount=%s", id, q.quotationNumber, q.totalAmount);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        dto.approvalHistory = loadApprovalHistory(id);
        return dto;
    }

    @Transactional
    public QuotationDTO rejectByCustomer(UUID id, String comment, UUID currentUserId) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }
        if (!"SENT".equals(q.status)) {
            throw new BusinessException(400, "Only SENT quotations can be rejected by customer");
        }
        if (currentUserId != null && !q.salesRepId.equals(currentUserId)) {
            throw new BusinessException(403, "仅报价单创建人可执行此操作");
        }
        q.status = "REJECTED";
        LOG.infof("Customer rejected quotation id=%s number=%s comment=%s", id, q.quotationNumber, comment);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        dto.approvalHistory = loadApprovalHistory(id);
        return dto;
    }

    /**
     * PERF-FULL-RECALC-10: 全表重算当前 DRAFT 报价单的所有公式字段。
     * <p>
     * 重新遍历每个 lineItem，触发 DerivedAttributeCalculatorV5 全量重算，
     * 将结果合并回 productAttributeValues，并刷新 totalAmount。
     * 不改变 status，不创建新版本。仅 DRAFT 状态可用。
     *
     * @param id 报价单 ID
     * @return 更新后的 QuotationDTO
     */
    @Transactional
    public QuotationDTO recalculate(UUID id) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }
        if (!"DRAFT".equals(q.status)) {
            throw new BusinessException(400, "已提交报价单不可重算");
        }

        List<QuotationLineItem> lineItems = QuotationLineItem.list(
                "quotationId = ?1 ORDER BY sortOrder ASC", id);

        BigDecimal total = BigDecimal.ZERO;
        for (QuotationLineItem li : lineItems) {
            if (li.productId != null) {
                Product product = Product.findById(li.productId);
                if (product != null && product.partNo != null) {
                    try {
                        List<DerivedAttribute> derivedAttrs = loadDerivedAttributes(product.partNo);
                        if (!derivedAttrs.isEmpty()) {
                            Map<String, Object> calcResults = derivedAttributeCalculatorV5.calculate(
                                    q.customerId, product.partNo, derivedAttrs);
                            if (!calcResults.isEmpty()) {
                                li.productAttributeValues = mergeFormulaResults(
                                        li.productAttributeValues, calcResults);
                            }
                            logFormulaErrors(calcResults, q.id, product.partNo);
                        }
                    } catch (Exception e) {
                        LOG.warnf("recalculate formula failed quotation=%s partNo=%s: %s",
                                q.id, product.partNo, e.getMessage());
                    }
                }
            }
            if (li.subtotal != null) {
                total = total.add(li.subtotal);
            }
        }

        // Refresh totalAmount based on current line item subtotals and discount
        q.originalAmount = total;
        if (q.finalDiscountRate != null) {
            q.totalAmount = total.multiply(q.finalDiscountRate)
                    .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        } else {
            q.totalAmount = total;
        }
        em.flush();

        LOG.infof("recalculate done for quotation=%s lineItems=%d totalAmount=%s",
                id, lineItems.size(), q.totalAmount);

        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        populateDriftInfo(dto, q);
        return dto;
    }

    // --- Private helpers ---

    /**
     * 从 QuotationLineItem 中收集本报价单涉及的所有 hf_part_no。
     */
    private List<String> collectPartNosFromLineItems(UUID quotationId) {
        List<QuotationLineItem> items = QuotationLineItem.list("quotationId = ?1", quotationId);
        Set<String> partNos = new LinkedHashSet<>();
        for (QuotationLineItem li : items) {
            if (li.productId != null) {
                Product product = Product.findById(li.productId);
                if (product != null && product.partNo != null) {
                    partNos.add(product.partNo);
                }
            }
        }
        return new ArrayList<>(partNos);
    }

    /**
     * 加载指定料号对应的衍生属性列表（按 sortOrder 排序）。
     *
     * <p>DerivedAttribute 通过 hostSheetId（BasicDataConfig）关联，无直接 partNo FK。
     * 当前通过 native query 查询 basic_data_config.sheet_name 包含 partNo 的关联（v5.1 简化策略）。
     * 若无关联数据，返回空列表（公式计算跳过，不阻塞报价保存）。
     */
    @SuppressWarnings("unchecked")
    private List<DerivedAttribute> loadDerivedAttributes(String partNo) {
        try {
            // v5.1 简化：通过 native query 关联 basic_data_config 找到与 partNo 相关的 sheet
            // 若 basic_data_config.description 包含 partNo，则认为相关；
            // 实际生产中此关联由 product_category → basic_data_config 确定（未来版本完善）
            List<UUID> hostSheetIds = em.createNativeQuery(
                    "SELECT id FROM basic_data_config WHERE description LIKE :partNo LIMIT 10")
                    .setParameter("partNo", "%" + partNo + "%")
                    .getResultList();

            if (hostSheetIds.isEmpty()) return List.of();

            return DerivedAttribute.<DerivedAttribute>find(
                    "hostSheetId IN ?1 AND status = 'ACTIVE' ORDER BY sortOrder ASC",
                    hostSheetIds).list();
        } catch (Exception e) {
            LOG.debugf("loadDerivedAttributes failed for partNo=%s: %s", partNo, e.getMessage());
            return List.of();
        }
    }

    /**
     * 将公式计算结果合并到现有的 productAttributeValues JSON 字符串。
     * FormulaError 值序列化为 "__error:<message>" 占位符（前端识别后展示红色单元格）。
     */
    private String mergeFormulaResults(String existing, Map<String, Object> calcResults) {
        Map<String, Object> merged = new LinkedHashMap<>();

        // 先解析现有值
        if (existing != null && !existing.isBlank()) {
            try {
                Map<String, Object> existingMap = MAPPER.readValue(existing,
                        new TypeReference<Map<String, Object>>() {});
                merged.putAll(existingMap);
            } catch (Exception e) {
                LOG.debugf("mergeFormulaResults: failed to parse existing productAttributeValues: %s", e.getMessage());
            }
        }

        // 合并计算结果
        for (Map.Entry<String, Object> entry : calcResults.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof FormulaError err) {
                merged.put(entry.getKey(), "__error:" + err.getMessage());
            } else {
                merged.put(entry.getKey(), val);
            }
        }

        try {
            return MAPPER.writeValueAsString(merged);
        } catch (Exception e) {
            LOG.warnf("mergeFormulaResults: serialization failed: %s", e.getMessage());
            return existing;
        }
    }

    /**
     * 记录公式计算中出现的 FormulaError（WARN 级别，不阻塞流程）。
     */
    private void logFormulaErrors(Map<String, Object> calcResults, UUID quotationId, String partNo) {
        for (Map.Entry<String, Object> entry : calcResults.entrySet()) {
            if (entry.getValue() instanceof FormulaError err) {
                LOG.warnf("FormulaError in quotation=%s partNo=%s attr=%s: %s",
                        quotationId, partNo, entry.getKey(), err.getMessage());
            }
        }
    }

    private String generateQuotationNumber() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Long seq = (Long) em.createNativeQuery("SELECT nextval('quotation_number_seq')").getSingleResult();
        return String.format("QT-%s-%04d", dateStr, seq);
    }

    private void deleteLineItems(UUID quotationId) {
        List<QuotationLineItem> items = QuotationLineItem.list("quotationId = ?1", quotationId);
        for (QuotationLineItem li : items) {
            QuotationLineProcess.delete("lineItemId = ?1", li.id);
            QuotationLineComponentData.delete("lineItemId = ?1", li.id);
            QuotationLineItemSnapshot.delete("lineItemId = ?1", li.id);
        }
        QuotationLineItem.delete("quotationId = ?1", quotationId);
    }

    private List<QuotationDTO.ApprovalDTO> loadApprovalHistory(UUID quotationId) {
        return QuotationApproval.<QuotationApproval>list("quotationId = ?1 ORDER BY actedAt ASC", quotationId)
                .stream().map(a -> {
                    QuotationDTO.ApprovalDTO approvalDto = QuotationDTO.ApprovalDTO.from(a);
                    User approverUser = User.findById(a.approverId);
                    if (approverUser != null) {
                        approvalDto.approverName = approverUser.fullName;
                    }
                    return approvalDto;
                }).collect(Collectors.toList());
    }

    /**
     * QIMP-V5-REIMPORT-15/16: 重新导入报价单的基础数据。
     *
     * <p>业务规则:
     * <ul>
     *   <li>仅 DRAFT 状态可重导；其他状态返回 400</li>
     *   <li>复用 BasicDataImportServiceV5 的 parse + 写入逻辑</li>
     *   <li>创建新 ImportRecord 并关联到 quotation_id</li>
     *   <li>line_items 全量替换（先删旧的）</li>
     *   <li>重置漂移检测（清空 referenced_versions，强制重新采集）</li>
     * </ul>
     *
     * @param id     报价单 ID
     * @param stream Excel 文件输入流
     * @param userId 当前操作用户 ID
     * @return 导入结果 DTO
     */
    @Transactional
    public ImportResultDTO reimportBasicData(UUID id, InputStream stream, UUID userId) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }
        if (!"DRAFT".equals(q.status)) {
            throw new BusinessException(400, "已提交报价单不可重新导入，请先撤回");
        }

        // Delete existing line items (cascade to child tables)
        deleteLineItems(id);

        // Reset referenced versions to force drift re-detection after reimport
        q.referencedVersions = null;
        em.flush();

        // Run V5 import against the quotation's customer
        ImportResultDTO result = basicDataImportServiceV5.importBasicDataV5(stream, q.customerId, userId);

        // Associate the newly created ImportRecord with this quotation
        if (result.importRecordId != null) {
            ImportRecord rec = ImportRecord.findById(result.importRecordId);
            if (rec != null) {
                rec.quotationId = id;
                // No explicit persist needed — Panache entity is already managed
            }
        }

        LOG.infof("reimportBasicData done for quotation=%s customerId=%s importRecord=%s status=%s",
                id, q.customerId, result.importRecordId, result.status);

        return result;
    }

    private List<QuotationDTO.LineItemDTO> loadLineItems(UUID quotationId) {
        List<QuotationLineItem> items = QuotationLineItem.list("quotationId = ?1 ORDER BY sortOrder ASC", quotationId);
        if (items.isEmpty()) return List.of();

        // 一次性按 (customerId, hf_part_no) 批量查 mat_customer_part_mapping，避免 N+1。
        // customerId 来自 quotation；hf_part_no 列表来自 lineItems 的 product_part_no_snapshot
        // 或 product 表反查。前端"客户视角"展示这两个字段（PRD：产品卡片显示客户料号名称 + 客户产品编号）
        UUID customerId = items.get(0).quotationId == null ? null : (Quotation.findById(quotationId) instanceof Quotation q ? q.customerId : null);
        Map<String, Object[]> customerMappingByHfPartNo = new HashMap<>();
        Map<String, Object[]> matPartByHfPartNo = new HashMap<>();
        List<String> hfPartNos = new ArrayList<>();
        for (QuotationLineItem li : items) {
            String hfpn = resolveHfPartNo(li);
            if (hfpn != null && !hfpn.isBlank() && !hfPartNos.contains(hfpn)) {
                hfPartNos.add(hfpn);
            }
        }
        if (customerId != null && !hfPartNos.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT hf_part_no, customer_part_name, customer_product_no, customer_drawing_no " +
                    "FROM mat_customer_part_mapping " +
                    "WHERE customer_id = :cid AND hf_part_no IN (:pns)")
                    .setParameter("cid", customerId)
                    .setParameter("pns", hfPartNos)
                    .getResultList();
            for (Object[] r : rows) {
                if (r != null && r[0] != null) {
                    customerMappingByHfPartNo.putIfAbsent(r[0].toString(), r);
                }
            }
        }
        // 同时一次性拉「生产料号管理」(internal_material) 数据；前端卡片右侧 popover 用。
        // 用 internal_material 而不是 mat_part：生产料号管理是用户在产品-生产料号管理页维护的，
        // 包含 name / specification / size / status_code，与 popover 字段一一对应。
        // 缺失时回退到 mat_part 主档，避免没维护过的料号 popover 全空。
        if (!hfPartNos.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT material_no, name, specification, size, status_code " +
                    "FROM internal_material WHERE material_no IN (:pns)")
                    .setParameter("pns", hfPartNos)
                    .getResultList();
            for (Object[] r : rows) {
                if (r != null && r[0] != null) {
                    matPartByHfPartNo.putIfAbsent(r[0].toString(), r);
                }
            }
            // 回退：internal_material 没维护到的，从 mat_part 主档兜底
            List<String> missing = new ArrayList<>();
            for (String pn : hfPartNos) if (!matPartByHfPartNo.containsKey(pn)) missing.add(pn);
            if (!missing.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Object[]> fbRows = em.createNativeQuery(
                        "SELECT part_no, part_name, specification, size_info, status_code " +
                        "FROM mat_part WHERE part_no IN (:pns)")
                        .setParameter("pns", missing)
                        .getResultList();
                for (Object[] r : fbRows) {
                    if (r != null && r[0] != null) {
                        matPartByHfPartNo.putIfAbsent(r[0].toString(), r);
                    }
                }
            }
        }

        return items.stream().map(li -> {
            QuotationDTO.LineItemDTO dto = QuotationDTO.LineItemDTO.from(li);

            // 注入客户视角字段（前端优先展示）
            String hfpn = resolveHfPartNo(li);
            Object[] mapping = hfpn != null ? customerMappingByHfPartNo.get(hfpn) : null;
            if (mapping != null) {
                dto.customerPartName = mapping[1] != null ? mapping[1].toString() : null;
                dto.customerProductNo = mapping[2] != null ? mapping[2].toString() : null;
                dto.customerDrawingNo = mapping[3] != null ? mapping[3].toString() : null;
            }
            // 注入生产料号详情
            Object[] mp = hfpn != null ? matPartByHfPartNo.get(hfpn) : null;
            if (mp != null) {
                QuotationDTO.HfPartInfo info = new QuotationDTO.HfPartInfo();
                info.partNo = mp[0] != null ? mp[0].toString() : null;
                info.partName = mp[1] != null ? mp[1].toString() : null;
                info.specification = mp[2] != null ? mp[2].toString() : null;
                info.sizeInfo = mp[3] != null ? mp[3].toString() : null;
                info.statusCode = mp[4] != null ? mp[4].toString() : null;
                dto.hfPartInfo = info;
            }

            dto.processes = QuotationLineProcess.<QuotationLineProcess>list("lineItemId = ?1", li.id)
                    .stream().map(QuotationDTO.ProcessDTO::from).collect(Collectors.toList());

            dto.componentData = QuotationLineComponentData.<QuotationLineComponentData>list("lineItemId = ?1 ORDER BY sortOrder ASC", li.id)
                    .stream().map(QuotationDTO.ComponentDataDTO::from).collect(Collectors.toList());

            QuotationLineItemSnapshot snapshot = QuotationLineItemSnapshot.find("lineItemId = ?1", li.id).firstResult();
            if (snapshot != null) {
                dto.snapshot = QuotationDTO.SnapshotDTO.from(snapshot);
            }

            return dto;
        }).collect(Collectors.toList());
    }

    private static String resolveHfPartNo(QuotationLineItem li) {
        if (li.productPartNoSnapshot != null && !li.productPartNoSnapshot.isBlank()) {
            return li.productPartNoSnapshot;
        }
        if (li.productId != null) {
            Product p = Product.findById(li.productId);
            if (p != null && p.partNo != null) return p.partNo;
        }
        return null;
    }

    /**
     * 料号版本管理 (新需求): 更改某 line_item 的 part_version_locked.
     * 仅 DRAFT 态可改; 目标版本必须在 mat_part_version_log 历史中存在 (v2000 是基线, 永远合法).
     */
    @Transactional
    public void updateLineItemPartVersion(UUID quotationId, UUID lineItemId, int newVersion) {
        Quotation q = Quotation.findById(quotationId);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + quotationId);
        }
        if (!"DRAFT".equals(q.status)) {
            throw new BusinessException(400, "只有草稿状态的报价单可以切换料号版本");
        }
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        if (li == null || !quotationId.equals(li.quotationId)) {
            throw new BusinessException(404, "Line item not found in this quotation: " + lineItemId);
        }
        if (li.customerPartNo == null || li.customerPartNo.isBlank()
                || li.productPartNoSnapshot == null || li.productPartNoSnapshot.isBlank()) {
            throw new BusinessException(400,
                    "该行缺少 customer_product_no 或 hf_part_no, 无法切换版本");
        }
        if (newVersion < 2000) {
            throw new BusinessException(400, "Invalid version: " + newVersion);
        }
        if (newVersion > 2000) {
            Object exists = em.createNativeQuery(
                    "SELECT 1 FROM mat_part_version_log " +
                    "WHERE customer_product_no = :cpn AND hf_part_no = :hf AND version = :v")
                    .setParameter("cpn", li.customerPartNo)
                    .setParameter("hf", li.productPartNoSnapshot)
                    .setParameter("v", newVersion)
                    .getResultList().stream().findFirst().orElse(null);
            if (exists == null) {
                throw new BusinessException(400, "版本 v" + newVersion + " 不在 ("
                        + li.customerPartNo + ", " + li.productPartNoSnapshot + ") 的历史中");
            }
        }
        li.partVersionLocked = newVersion;
        li.persist();
    }
}
