package com.cpq.quotation.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.customer.entity.Customer;
import com.cpq.component.entity.Component;
import com.cpq.component.service.ComponentSqlViewService;
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
import jakarta.persistence.LockModeType;
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
    com.cpq.quotation.service.rowkey.RowKeyUniquenessService rowKeyUniquenessService;

    @Inject
    BasicDataImportServiceV5 basicDataImportServiceV5;

    /** 阶段 2: 组件 SQL 视图冻结服务（SUBMITTED 时调 snapshotForComponents 写 quotation_component_sql_snapshot） */
    @Inject
    ComponentSqlViewService componentSqlViewService;

    @Inject
    EntityManager em;

    @Inject
    ExcelViewService excelViewService;

    @Inject
    com.cpq.quotation.service.CardSnapshotService cardSnapshotService;

    @Inject
    LineDiscountService lineDiscountService;

    @Inject
    CostingFreezeService costingFreezeService;

    private static final java.util.Set<String> VALID_QUOTATION_STATUSES = java.util.Set.of(
            "DRAFT", "SUBMITTED", "APPROVED", "SENT", "ACCEPTED", "REJECTED", "EXPIRED", "CANCELLED", "COSTING_REJECTED"
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

        // Phase 2 渲染脱钩: 报价单级 4 份结构快照(从 quotation_view_structure 读填充)
        populateViewStructures(dto, id);

        // 详情页只读 Excel/比对：把带 display_format 的有效列定义捎回（零值计算）
        populateEffectiveExcelColumns(dto, q);

        return dto;
    }

    /**
     * 填充报价/核价有效 Excel 列定义到 DTO（详情页只读 Excel/比对视图渲染用）。
     * 调 ExcelViewService.getEffectiveColumns 仅读列结构，不做任何值计算。
     * 任何异常（模板不存在/配置损坏）静默降级为 null，不阻断 getById。
     */
    private void populateEffectiveExcelColumns(QuotationDTO dto, Quotation q) {
        try {
            if (q.customerTemplateId != null) {
                com.cpq.template.entity.Template qt =
                        com.cpq.template.entity.Template.findById(q.customerTemplateId);
                if (qt != null) {
                    dto.quoteExcelColumns = excelViewService.getEffectiveColumns(qt);
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to load quoteExcelColumns for quotation=%s: %s", q.id, e.getMessage());
        }
        try {
            if (q.costingCardTemplateId != null) {
                com.cpq.template.entity.Template ct =
                        com.cpq.template.entity.Template.findById(q.costingCardTemplateId);
                if (ct != null) {
                    dto.costingExcelColumns = excelViewService.getEffectiveColumns(ct);
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to load costingExcelColumns for quotation=%s: %s", q.id, e.getMessage());
        }
    }

    /** Phase 2: 把 quotation_view_structure 的四份结构填进 DTO(渲染脱钩, 创建即冻)。 */
    private void populateViewStructures(QuotationDTO dto, UUID quotationId) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (com.cpq.quotation.entity.QuotationViewStructure s :
                com.cpq.quotation.entity.QuotationViewStructure
                    .<com.cpq.quotation.entity.QuotationViewStructure>list("quotationId", quotationId)) {
            if (s.structure == null) continue;
            try {
                var node = mapper.readTree(s.structure);
                switch (s.viewKind) {
                    case "QUOTE_CARD" -> dto.quoteCardStructure = node;
                    case "QUOTE_EXCEL" -> dto.quoteExcelStructure = node;
                    case "COSTING_CARD" -> dto.costingCardStructure = node;
                    case "COSTING_EXCEL" -> dto.costingExcelStructure = node;
                }
            } catch (Exception ignore) { /* 结构缺失/损坏 → 该份为 null, 不阻断 */ }
        }
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
        // Phase 2-0 数据安全闸: 对 quotation 行加悲观写锁，串行化同单并发 saveDraft。
        //
        // 背景: saveDraft 对每个复用行执行 clearLineItemChildren(全删子表) + persist(重建)。
        // 当同一报价单的两个 saveDraft 请求并发时，事务交错 → A 的 DELETE 提交后 B 已读完旧行
        // 并在 A 提交的空表上插入，或 A 的 INSERT 被 B 的 DELETE 抹掉 → 行数归零（939e072e 案例）。
        //
        // 修复方案: 在 findById 之后立即用 PESSIMISTIC_WRITE 行锁（SELECT ... FOR UPDATE）持锁
        // 到事务结束。第二个同单 saveDraft 事务在此等待，直到第一个事务提交（释放锁）后再继续，
        // 消除"全删"与"重建"的交错窗口。
        //
        // 死锁风险: 悲观锁只锁单条 quotation 行，且所有 saveDraft 请求按同一固定顺序（先锁 quotation
        // 再操作子表）获取锁，不存在两个事务互相等待对方的情况，死锁概率极低。
        // 不同 quotation 的 saveDraft 互不干扰（各自锁不同行）。
        //
        // 性能影响: 同单并发 saveDraft 将排队（每次 ~8s），但这是数据安全的必要代价。
        // 在前端 Plan A（止住 autosave 风暴）已落地后，同单并发的实际频率极低（多 tab/多用户场景），
        // 排队概率可接受。
        //
        // Kill switch: cpq.savedraft-serialize-lock（默认 true，因为这是数据安全修复而非有等价
        // 风险的性能优化——与 firstsave-batch-write 等性能 kill switch 不同，性能 kill switch
        // 默认 false 灰度；数据安全 kill switch 默认 true，仅在极端锁争用场景下才关）。
        // 关闭: -Dcpq.savedraft-serialize-lock=false 或 export CPQ_SAVEDRAFT_SERIALIZE_LOCK=false
        boolean serializeLockEnabled = "true".equalsIgnoreCase(
                System.getProperty("cpq.savedraft-serialize-lock",
                    System.getenv().getOrDefault("CPQ_SAVEDRAFT_SERIALIZE_LOCK", "true")));

        Quotation q;
        if (serializeLockEnabled) {
            // PESSIMISTIC_WRITE → SELECT ... FOR UPDATE，锁到事务提交/回滚
            q = Quotation.findById(id, LockModeType.PESSIMISTIC_WRITE);
        } else {
            q = Quotation.findById(id);
        }
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

        // 2026-05-18: 报价模板 / 核价模板 — 透传到 quotation header, 让刷新页面后 Step1 能带出.
        // 仅在 quotation 未已生成态下允许写入(对应前端 readOnly 锁定逻辑); DRAFT 阶段允许覆盖以兼容
        // "用户先选 → next 触发 saveDraft → 后续再调整"链路.
        if (request.customerTemplateId != null) q.customerTemplateId = request.customerTemplateId;
        if (request.costingCardTemplateId != null) q.costingCardTemplateId = request.costingCardTemplateId;

        // Pricing overrides
        if (request.finalDiscountRate != null) {
            q.finalDiscountRate = request.finalDiscountRate;
            q.isManuallyAdjusted = true;
            q.discountAdjustmentReason = request.discountAdjustmentReason;
        }

        // 2026-06-01: 按 id UPSERT 报价行(替代"全删全建 last-write-wins")。
        //   draft.id 命中现有行 → 复用同一实体(就地 UPDATE, id 不变); 未命中 → 新建; 末尾删除本次未保留的旧行。
        //   动机: 原"全删全建"每次换新 UUID, 导致 editQuoteCardValue 撞已删 id(400) + driver 缓存 churn。
        //   子表(process/componentData/snapshot/composite_process)仍按 draft 全量重建(行为不变), 仅 line 实体 id 稳定。
        //
        // Phase 2-1 kill switch: cpq.savedraft-batch-stage1（2026-06-26 转默认 true——
        //   等价铁证 BatchStage1PersistEquivTest 2/2「170行/77行 OFF/ON 持久化逐位等价」已过,
        //   且 Phase 2-0 悲观锁(默认 ON)护住并发删数据 → 满足注释「等价铁证后再转 true」条件)。
        //   true  → 集合化路径（E2/E3/E4/E5/§2.1 批量子表 DELETE/INSERT,消删建行逐行往返）
        //   false → 原逐行路径（逃生回落: -Dcpq.savedraft-batch-stage1=false）
        boolean batchStage1Enabled = "true".equalsIgnoreCase(
                System.getProperty("cpq.savedraft-batch-stage1",
                    System.getenv().getOrDefault("CPQ_SAVEDRAFT_BATCH_STAGE1", "true")));

        LOG.infof("[saveDraft-diag] id=%s received lineItems=%s batchStage1=%b", id,
            request.lineItems == null ? "null" : String.valueOf(request.lineItems.size()),
            batchStage1Enabled);
        if (request.lineItems != null) {
            if (batchStage1Enabled) {
                // ── Phase 2-1 批量集合化路径 ──────────────────────────────────────────────
                // E2/E3/E4/E5/§2.1：把阶段①里的 per-row SQL 合成整单集合 SQL，单线程批量。
                // 产出与逐行路径逐位等价（详见 docs/superpowers/plans/2026-06-25-savedraft-setbased-rearchitecture.md §3 表）。
                processBatchStage1(id, q, request);
            } else {
                // ── 原逐行路径（Phase 2-0 基线，默认） ────────────────────────────────────
                java.util.List<QuotationLineItem> existingLines = QuotationLineItem.list("quotationId = ?1", id);
                java.util.Map<java.util.UUID, QuotationLineItem> existingById = new java.util.HashMap<>();
                for (QuotationLineItem ex : existingLines) existingById.put(ex.id, ex);
                java.util.Set<java.util.UUID> keptIds = new java.util.HashSet<>();
                BigDecimal total = BigDecimal.ZERO;
                Set<String> collectedPartNos = new LinkedHashSet<>();
                // V169 二阶段 parent_line_item_id 重建用: index → 行 UUID 的映射(复用行=原 id, 新行=新 id)
                java.util.UUID[] newIdsByIndex = new java.util.UUID[request.lineItems.size()];

                // FixC1: 复用行 clearLineItemChildren 前先保存各 component 的 deletedRowKeys,
                // 重建时按 componentId 回填; saveDraft 请求不携带 deletedRowKeys(由专用端点管)
                java.util.Map<java.util.UUID, String> preservedTombstones = new java.util.HashMap<>();
                // Part A: 复用行 snapshot_rows 保留 —— 全量重建会清子表, 重建时回写避免 snapshotQuotation 全量重 expand
                java.util.Map<java.util.UUID, String> preservedSnapshots = new java.util.HashMap<>();

                for (int i = 0; i < request.lineItems.size(); i++) {
                    SaveDraftRequest.LineItemDraft liDraft = request.lineItems.get(i);
                    QuotationLineItem li;
                    if (liDraft.id != null && existingById.containsKey(liDraft.id)) {
                        li = existingById.get(liDraft.id);   // 复用 → 就地 UPDATE, id 不变
                        keptIds.add(li.id);
                        // FixC1: clear 前先存现有墓碑,重建时按 componentId 回填(saveDraft 请求不带 deletedRowKeys)
                        preservedTombstones.clear();
                        preservedSnapshots.clear();          // Part A
                        for (QuotationLineComponentData old :
                                QuotationLineComponentData.<QuotationLineComponentData>list("lineItemId = ?1", li.id)) {
                            if (old.componentId != null && old.deletedRowKeys != null)
                                preservedTombstones.put(old.componentId, old.deletedRowKeys);
                            if (old.componentId != null && old.snapshotRows != null)   // Part A
                                preservedSnapshots.put(old.componentId, old.snapshotRows);
                        }
                        clearLineItemChildren(li.id);        // 旧子表清掉, 下面按 draft 重建
                        li.parentLineItemId = null;          // 父子关系清空, 待二阶段重链
                    } else {
                        li = new QuotationLineItem();
                        preservedTombstones.clear();         // 新行无墓碑
                        preservedSnapshots.clear();          // Part A: 新行无快照
                    }
                    li.quotationId = id;
                    li.productId = liDraft.productId;
                    // 选配产品行的 templateId 偶发为空(前端 onConfigureConfirm 读 customerTemplateId 有竞态),
                    // 持久化成 NULL → 刷新时 enrichComponentData 在 if(!templateId) 处跳过 → 所有页签拿不到
                    // dataDriverPath → 全空。兜底为报价单模板,保证每行都有模板 id、刷新必能 enrich。
                    li.templateId = liDraft.templateId != null ? liDraft.templateId : q.customerTemplateId;
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
                    // V6 修复: 兼容前端 BulkImportPartsDrawer.buildLineItemFromTemplate 写的 customerProductNo 字段
                    // 若 customerPartNo 为空 fallback 到 customerProductNo，避免 part_version_locked 漏查
                    String effectiveCpn = (liDraft.customerPartNo != null && !liDraft.customerPartNo.isBlank())
                            ? liDraft.customerPartNo
                            : ((liDraft.customerProductNo != null && !liDraft.customerProductNo.isBlank())
                                    ? liDraft.customerProductNo : null);
                    if (effectiveCpn != null) {
                        li.customerPartNo = effectiveCpn;
                    }
                    // V169 选配组合关系 — saveDraft 全量重建后所有 line_items 是新 UUID,
                    // 不能直接写 liDraft.parentLineItemId (旧 UUID 已被 CASCADE 删除会触发 FK 违反 409).
                    // compositeType 直接写; parentLineItemId 留 null, 循环结束后按 tempParentIndex 二阶段 UPDATE.
                    if (liDraft.compositeType != null && !liDraft.compositeType.isBlank()) {
                        li.compositeType = liDraft.compositeType;
                    }
                    // Step3 行级折扣（V302）：原样落库前端送来的值；submit 时再权威重算覆盖。
                    li.annualVolume = liDraft.annualVolume;
                    li.discountSource = liDraft.discountSource;
                    li.discountBaseAmount = liDraft.discountBaseAmount;
                    li.discountRateApplied = liDraft.discountRateApplied;
                    li.lineDiscountAmount = liDraft.lineDiscountAmount;
                    li.lineUnitPrice = liDraft.lineUnitPrice;
                    li.lineFinalPrice = liDraft.lineFinalPrice;
                    li.lineTotalAmount = liDraft.lineTotalAmount;
                    li.discountRuleCode = liDraft.discountRuleCode;
                    // Phase 3（2026-06-21）：前端单引擎算好的报价 Excel 快照原样落库。
                    // 后端 snapshotLineValues 守卫：仅当 li.quoteExcelValues==null 时才 buildExcelValues 兜底。
                    if (liDraft.quoteExcelValues != null) li.quoteExcelValues = liDraft.quoteExcelValues;
                    li.persist();
                    newIdsByIndex[i] = li.id;  // V169 二阶段父子关系重建用

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

                    // 导入来源行:无用户工序时,从该料号基础工序(material_bom_item.operation_no)
                    // seed 本行 quotation_line_process(operation_no → process.id),使 [选配-工序列表]
                    // 与选配产品渲染一致。仅 seedProcessesFromBase=true 的导入行触发(选配路径不设,保持"没选=空")。
                    boolean noProcs = (liDraft.processIds == null || liDraft.processIds.isEmpty());
                    if (noProcs && Boolean.TRUE.equals(liDraft.seedProcessesFromBase)
                            && li.productPartNoSnapshot != null && !li.productPartNoSnapshot.isBlank()) {
                        try {
                            Object ccObj = em.createNativeQuery("SELECT code FROM customer WHERE id = :cid")
                                    .setParameter("cid", q.customerId)
                                    .getResultStream().findFirst().orElse(null);
                            if (ccObj != null) {
                                em.createNativeQuery(
                                        "INSERT INTO quotation_line_process (id, line_item_id, process_id) " +
                                        "SELECT gen_random_uuid(), :lid, p.id FROM (" +
                                        "  SELECT DISTINCT operation_no FROM material_bom_item " +
                                        "  WHERE system_type='QUOTE' AND customer_no=:cc AND material_no=:part " +
                                        "    AND characteristic='ASSEMBLY' AND operation_no IS NOT NULL AND is_current = true" +
                                        ") ops JOIN process p ON p.code = ops.operation_no")
                                    .setParameter("lid", li.id)
                                    .setParameter("cc", ccObj.toString())
                                    .setParameter("part", li.productPartNoSnapshot)
                                    .executeUpdate();
                            }
                        } catch (Exception e) {
                            LOG.warnf("[seed-import-process] line=%s 从基础工序 seed 失败(降级): %s", li.id, e.getMessage());
                        }
                    }

                    // 选配-组合工艺 per-quote:从 draft 重写本行(全量重建换 line id 后跨保存存活)。
                    // 上层 deleteLineItems 已级联删旧行的 quotation_line_composite_process,这里按新 li.id 重写。
                    if (liDraft.compositeProcesses != null && !liDraft.compositeProcesses.isEmpty()) {
                        com.fasterxml.jackson.databind.ObjectMapper cpOm = new com.fasterxml.jackson.databind.ObjectMapper();
                        for (SaveDraftRequest.CompositeProcessDraft cpd : liDraft.compositeProcesses) {
                            if (cpd.defCode == null || cpd.defCode.isBlank()) continue;
                            try {
                                em.createNativeQuery(
                                        "INSERT INTO quotation_line_composite_process " +
                                        "(line_item_id, def_code, seq_no, participating_parts, param_values) " +
                                        "VALUES (:lid, :d, :sq, CAST(:pp AS jsonb), CAST(:pv AS jsonb))")
                                    .setParameter("lid", li.id)
                                    .setParameter("d", cpd.defCode)
                                    .setParameter("sq", cpd.seqNo)
                                    .setParameter("pp", cpOm.writeValueAsString(cpd.participatingParts == null ? java.util.List.of() : cpd.participatingParts))
                                    .setParameter("pv", cpOm.writeValueAsString(cpd.paramValues == null ? java.util.Map.of() : cpd.paramValues))
                                    .executeUpdate();
                            } catch (Exception e) {
                                LOG.warnf("[composite-proc-save] line=%s 写组合工艺失败(降级): %s", li.id, e.getMessage());
                            }
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
                            // FixC1: 回填墓碑(同模板复用行,源集/effKey 不变,墓碑仍匹配);新行/无记录 → "[]"
                            String preserved = (cdDraft.componentId != null)
                                    ? preservedTombstones.get(cdDraft.componentId) : null;
                            cd.deletedRowKeys = (preserved != null) ? preserved : "[]";
                            // Part A: 复用行回写旧 snapshot_rows(新行 = null, 由 snapshotQuotation 重 expand 填充)
                            String preservedSr = (cdDraft.componentId != null)
                                    ? preservedSnapshots.get(cdDraft.componentId) : null;
                            if (preservedSr != null) cd.snapshotRows = preservedSr;
                            cd.persist();
                        }
                    }
                }

                // 删除本次 payload 未保留的旧行(用户删除的产品行) + 其子表
                for (QuotationLineItem ex : existingLines) {
                    if (keptIds.contains(ex.id)) continue;
                    clearLineItemChildren(ex.id);
                    ex.delete();
                }

                q.originalAmount = total;
                q.totalAmount = total.multiply(q.finalDiscountRate).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);

                // V169 二阶段父子关系重建: 按 tempParentIndex 把 PART 子件 UPDATE 指向新父 UUID
                for (int i = 0; i < request.lineItems.size(); i++) {
                    SaveDraftRequest.LineItemDraft draft = request.lineItems.get(i);
                    if (draft.tempParentIndex == null) continue;
                    int parentIdx = draft.tempParentIndex;
                    if (parentIdx < 0 || parentIdx >= newIdsByIndex.length) continue;
                    java.util.UUID childId = newIdsByIndex[i];
                    java.util.UUID parentId = newIdsByIndex[parentIdx];
                    if (childId == null || parentId == null) continue;
                    em.createNativeQuery(
                            "UPDATE quotation_line_item SET parent_line_item_id = :pid WHERE id = :cid")
                        .setParameter("pid", parentId)
                        .setParameter("cid", childId)
                        .executeUpdate();
                }

                // v5.1 §6.6 收集版本快照
                if (!collectedPartNos.isEmpty()) {
                    String versionsJson = driftDetectionService.collectReferencedVersions(
                            q.customerId, new ArrayList<>(collectedPartNos));
                    q.referencedVersions = versionsJson;
                    LOG.debugf("Recorded referencedVersions for quotation=%s partNos=%s", q.id, collectedPartNos);
                }
            } // end per-row path
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

        // 行键唯一性校验（设计 E）：组合行键不可重复，含 driver 展开行。冲突即拒绝提交。
        // 结构快照（含 AP-39 冻入的 rowKeyFields）从 quotation_view_structure 的 QUOTE_CARD 份取。
        String quoteCardStructureJson = null;
        for (com.cpq.quotation.entity.QuotationViewStructure s :
                com.cpq.quotation.entity.QuotationViewStructure
                    .<com.cpq.quotation.entity.QuotationViewStructure>list("quotationId", id)) {
            if ("QUOTE_CARD".equals(s.viewKind)) { quoteCardStructureJson = s.structure; break; }
        }
        java.util.List<com.cpq.quotation.service.rowkey.RowKeyUniquenessService.LineItemComps> rowsForCheck =
            new java.util.ArrayList<>();
        for (QuotationLineItem li : lineItems) {
            String productName = li.productNameSnapshot != null ? li.productNameSnapshot
                         : (li.productPartNoSnapshot != null ? li.productPartNoSnapshot : "明细");
            java.util.List<com.cpq.quotation.service.rowkey.RowKeyUniquenessService.CompRows> comps =
                new java.util.ArrayList<>();
            java.util.List<com.cpq.quotation.entity.QuotationLineComponentData> cds =
                com.cpq.quotation.entity.QuotationLineComponentData.list("lineItemId", li.id);
            for (com.cpq.quotation.entity.QuotationLineComponentData cd : cds) {
                if (cd.componentId == null) continue;
                comps.add(new com.cpq.quotation.service.rowkey.RowKeyUniquenessService.CompRows(
                    cd.componentId.toString(), cd.snapshotRows, cd.rowData));
            }
            rowsForCheck.add(new com.cpq.quotation.service.rowkey.RowKeyUniquenessService.LineItemComps(
                li.id.toString(), productName, li.productPartNoSnapshot, comps));
        }
        java.util.List<com.cpq.quotation.service.rowkey.RowKeyConflictDTO> conflicts =
            rowKeyUniquenessService.collectConflicts(quoteCardStructureJson, rowsForCheck);
        if (!conflicts.isEmpty()) {
            StringBuilder sb = new StringBuilder("行键重复，无法提交：");
            for (com.cpq.quotation.service.rowkey.RowKeyConflictDTO c : conflicts) {
                sb.append("\n· ").append(c.describe());
            }
            throw new com.cpq.common.exception.RowKeyConflictException(sb.toString(), conflicts);
        }

        for (QuotationLineItem li : lineItems) {
            // Delete existing snapshot if any
            QuotationLineItemSnapshot.delete("lineItemId = ?1", li.id);
            // product_id 已废弃（V6：报价改用 material_master 料号，不再绑定 product 表客户料号）。
            // 仅历史上绑定了 Product 的明细才建快照；productId 为 null 时直接跳过，
            // 否则 Product.findById(null) 会抛 IllegalArgumentException("Identifier may not be null") → 400。
            if (li.productId == null) {
                continue;
            }
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

        // 阶段 2: 冻结组件 SQL 视图闭包到 quotation_component_sql_snapshot
        //   - 找该报价单 line_items 关联的所有 componentId（从模板 snapshot 反推 / 或从 line_item_component_data）
        //   - 调 snapshotForComponents 拿闭包 map
        //   - 逐 entry 落库
        try {
            freezeSqlViewsForQuotation(id, lineItems);
        } catch (Exception e) {
            LOG.warnf("[QuotationService] freezeSqlViewsForQuotation failed (non-blocking): %s", e.getMessage());
        }

        // Step3：提交时权威重算每行折后小计（防前端篡改），整单总额 = Σ行合计。
        BigDecimal lineSum = BigDecimal.ZERO;
        for (QuotationLineItem li : lineItems) {
            if ("PART".equals(li.compositeType)) continue;   // 选配子件不单独计入整单
            lineDiscountService.recompute(li);
            if (li.lineTotalAmount != null) lineSum = lineSum.add(li.lineTotalAmount);
        }
        q.totalAmount = lineSum.setScale(4, java.math.RoundingMode.HALF_UP);

        // 进入财务核价: 每次提交都建新核价单（累积模式），冻结 DTO+gvDefs，并发时 409。
        costingFreezeService.createForSubmission(id, userId);

        q.status = "SUBMITTED";
        LOG.infof("Submitted quotation id=%s number=%s approver=%s", id, q.quotationNumber, q.assignedApproverId);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        return dto;
    }

    /**
     * 阶段 2: 报价单 SUBMITTED 时冻结组件 SQL 视图闭包。
     *
     * <p>策略：
     * <ol>
     *   <li>从 quotation_line_component_data 反推 line_items 引用的 componentId 集合
     *       （兜底：直接扫所有 GLOBAL scope 视图保证跨组件回放）</li>
     *   <li>调 {@link ComponentSqlViewService#snapshotForComponents}</li>
     *   <li>序列化为 quotation_component_sql_snapshot 行（key = componentId::sql_view_name）</li>
     * </ol>
     */
    private void freezeSqlViewsForQuotation(UUID quotationId, List<QuotationLineItem> lineItems) {
        // 1. 收集 componentId（从 quotation_line_component_data 表 JSONB componentData 推不便，
        //    退而求其次：从 line_items.templateId → template.components_snapshot 提取所有 componentId）
        java.util.Set<UUID> componentIds = new java.util.HashSet<>();
        for (QuotationLineItem li : lineItems) {
            if (li.templateId != null) {
                com.cpq.template.entity.Template t = com.cpq.template.entity.Template.findById(li.templateId);
                if (t != null && t.componentsSnapshot != null) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode arr = MAPPER.readTree(t.componentsSnapshot);
                        if (arr.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode entry : arr) {
                                com.fasterxml.jackson.databind.JsonNode cid = entry.get("componentId");
                                if (cid != null && !cid.isNull()) {
                                    try { componentIds.add(UUID.fromString(cid.asText())); } catch (Exception ignored) {}
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOG.debugf("[freezeSqlViews] parse components_snapshot failed: %s", e.getMessage());
                    }
                }
            }
        }

        Map<String, Map<String, Object>> closure =
                componentSqlViewService.snapshotForComponents(new ArrayList<>(componentIds));
        if (closure.isEmpty()) {
            LOG.debugf("[freezeSqlViews] no sql views to freeze for quotation=%s", quotationId);
            return;
        }

        // 2. 清旧 + 写新（重复提交允许覆盖）
        em.createNativeQuery("DELETE FROM quotation_component_sql_snapshot WHERE quotation_id = ?")
                .setParameter(1, quotationId).executeUpdate();

        int inserted = 0;
        for (Map.Entry<String, Map<String, Object>> e : closure.entrySet()) {
            String key = e.getKey();
            Map<String, Object> v = e.getValue();
            String declaredCols = jsonOrEmpty(v.get("declared_columns"));
            String requiredVarsJson = jsonOrEmpty(v.get("required_variables"));
            em.createNativeQuery(
                    "INSERT INTO quotation_component_sql_snapshot " +
                    "(quotation_id, sql_view_key, sql_template, declared_columns, required_variables, frozen_at) " +
                    "VALUES (?, ?, ?, ?::jsonb, " +
                    // 不能写 array_agg(jsonb_array_elements_text(...)) —— PostgreSQL 禁止聚合函数直接套
                    // set-returning function。须把 SRF 放进 FROM 子句（PG 报错原文给的 LATERAL 提示）。
                    // 2026-06-02 修复 submit 500: required_variables 为空数组 [] 时 jsonb_array_elements_text
                    //   返 0 行 → array_agg 对空集返 NULL → 违反 required_variables NOT NULL → 事务 abort →
                    //   后续 loadLineItems 在坏事务里炸 500。COALESCE 兜成空 text[] '{}'。
                    "  COALESCE((SELECT array_agg(x)::text[] FROM jsonb_array_elements_text(?::jsonb) AS x), '{}'::text[]), " +
                    "  now())")
                    .setParameter(1, quotationId)
                    .setParameter(2, key)
                    .setParameter(3, String.valueOf(v.get("sql_template")))
                    .setParameter(4, declaredCols)
                    .setParameter(5, requiredVarsJson)
                    .executeUpdate();
            inserted++;
        }
        LOG.infof("[freezeSqlViews] frozen %d sql_view entries for quotation=%s", inserted, quotationId);
    }

    private String jsonOrEmpty(Object o) {
        if (o == null) return "[]";
        if (o instanceof String s) return s.isBlank() ? "[]" : s;
        try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return "[]"; }
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

    // ── 核价通过/驳回（role-based 队列，任一 PRICING_MANAGER/SYSTEM_ADMIN 均可操作）────────────

    private boolean isFinanceOrAdmin(UUID userId) {
        User u = User.findById(userId);
        return u != null && ("PRICING_MANAGER".equals(u.role) || "SYSTEM_ADMIN".equals(u.role));
    }

    @Transactional
    public QuotationDTO costingApprove(UUID id, String comment, UUID currentUserId) {
        Quotation q = Quotation.findById(id);
        if (q == null) throw new BusinessException(404, "Quotation not found: " + id);
        if (!"SUBMITTED".equals(q.status)) throw new BusinessException(400, "仅待核价(SUBMITTED)可核价通过");
        if (!isFinanceOrAdmin(currentUserId)) throw new BusinessException(403, "仅财务/管理员可核价");
        q.status = "APPROVED";
        CostingOrder coApprove = CostingOrder.findActiveByQuotation(id);
        if (coApprove != null) {
            coApprove.status = "APPROVED";
            coApprove.reviewedBy = currentUserId;
            coApprove.reviewedAt = java.time.OffsetDateTime.now();
        }
        writeApproval(id, currentUserId, "COSTING_APPROVED", comment);
        LOG.infof("Costing approved quotation id=%s by=%s", id, currentUserId);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        return dto;
    }

    @Transactional
    public QuotationDTO costingReject(UUID id, String reason, UUID currentUserId) {
        Quotation q = Quotation.findById(id);
        if (q == null) throw new BusinessException(404, "Quotation not found: " + id);
        if (!"SUBMITTED".equals(q.status)) throw new BusinessException(400, "仅待核价(SUBMITTED)可驳回");
        if (!isFinanceOrAdmin(currentUserId)) throw new BusinessException(403, "仅财务/管理员可核价");
        if (reason == null || reason.isBlank()) throw new BusinessException(400, "驳回原因必填");
        q.status = "COSTING_REJECTED";
        CostingOrder coReject = CostingOrder.findActiveByQuotation(id);
        if (coReject != null) {
            coReject.status = "REJECTED";
            coReject.rejectReason = reason;
            coReject.reviewedBy = currentUserId;
            coReject.reviewedAt = java.time.OffsetDateTime.now();
        }
        writeApproval(id, currentUserId, "COSTING_REJECTED", reason);
        LOG.infof("Costing rejected quotation id=%s reason=%s by=%s", id, reason, currentUserId);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        return dto;
    }

    private void writeApproval(UUID quotationId, UUID approverId, String action, String comment) {
        QuotationApproval a = new QuotationApproval();
        a.quotationId = quotationId;
        a.approverId = approverId;
        a.action = action;
        a.comment = comment;
        a.actedAt = OffsetDateTime.now();
        a.persist();
    }

    @Transactional
    public QuotationDTO withdraw(UUID id, UUID currentUserId) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }

        java.util.Set<String> withdrawable = java.util.Set.of("SUBMITTED", "COSTING_REJECTED", "APPROVED");
        if (!withdrawable.contains(q.status)) {
            throw new BusinessException(400, "仅待核价/核价驳回/核价通过的单可撤回(SENT/ACCEPTED 不可)");
        }
        if (!q.salesRepId.equals(currentUserId) && !isFinanceOrAdmin(currentUserId)) {
            throw new BusinessException(403, "仅创建人或管理员可撤回");
        }

        unfreezeToDraft(q);
        q.status = "DRAFT";
        q.assignedApproverId = null;
        // 用 findLatest：已驳回(REJECTED)的核价单是终态，findActive 取不到；findLatest 兼容所有场景
        CostingOrder coWithdraw = CostingOrder.findLatestByQuotation(id);
        if (coWithdraw != null) {
            coWithdraw.status = "WITHDRAWN";
        }
        writeApproval(id, currentUserId, "WITHDRAWN", "撤回到草稿");

        LOG.infof("Withdrawn quotation id=%s number=%s by user=%s", id, q.quotationNumber, currentUserId);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        return dto;
    }

    /**
     * 回 DRAFT 统一解冻: 清提交快照 + SQL 视图闭包; total 留待下次重算。
     */
    private void unfreezeToDraft(Quotation q) {
        q.submissionSnapshot = null;
        em.createNativeQuery(
                "DELETE FROM quotation_component_sql_snapshot WHERE quotation_id = :qid")
                .setParameter("qid", q.id)
                .executeUpdate();
    }

    /**
     * 驳回编辑入口：仅对 COSTING_REJECTED 状态的报价单有效，将报价单退回 DRAFT。
     * 不触碰 costing_order（驳回条留 REJECTED）；不写 quotation_approval（无合法 action 值）。
     */
    @Transactional
    public QuotationDTO beginEdit(UUID id, UUID currentUserId) {
        Quotation q = Quotation.findById(id);
        if (q == null) throw new BusinessException(404, "Quotation not found: " + id);
        if (!"COSTING_REJECTED".equals(q.status)) {
            throw new BusinessException(400, "仅已驳回的报价单可进入编辑转草稿");
        }
        if (!q.salesRepId.equals(currentUserId) && !isFinanceOrAdmin(currentUserId)) {
            throw new BusinessException(403, "仅创建人或管理员可编辑驳回单");
        }
        unfreezeToDraft(q);
        q.status = "DRAFT";
        q.assignedApproverId = null;
        LOG.infof("BeginEdit quotation id=%s number=%s by user=%s", id, q.quotationNumber, currentUserId);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        return dto;
    }

    @Transactional
    public QuotationDTO copy(UUID id) {
        return copy(id, null);
    }

    /**
     * 复制报价单。templateId 非空 → 换模板：新单 customerTemplateId=templateId，
     * 行项目页签按新模板重建，仅迁移用户输入值(INPUT 类型，按字段名)，driver/公式由新模板重算。
     * templateId 为空 → 沿用源 customerTemplateId（同模板复制，同样走重建流程修正历史缺陷）。
     */
    @Transactional
    public QuotationDTO copy(UUID id, UUID templateId) {
        Quotation source = Quotation.findById(id);
        if (source == null) throw new BusinessException(404, "Quotation not found: " + id);

        UUID newTemplateId = (templateId != null) ? templateId : source.customerTemplateId;

        // 读新模板页签输入字段（用于 row_data 迁移）
        java.util.List<TabFields> newTabs;
        {
            Object snap = null;
            if (newTemplateId != null) {
                var rows = em.createNativeQuery(
                        "SELECT components_snapshot FROM template WHERE id = :tid")
                        .setParameter("tid", newTemplateId).getResultList();
                if (!rows.isEmpty() && rows.get(0) != null) snap = rows.get(0);
            }
            newTabs = parseTemplateTabFields(snap == null ? null : snap.toString(), MAPPER);
        }

        // 1. 单据头（保留原 copy 的全部字段赋值；customerTemplateId 改为新模板）
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
        copy.originalAmount = BigDecimal.ZERO;   // 占位：换模板后金额由编辑/重算回填，避免列表显示源模板陈旧金额
        copy.systemDiscountRate = source.systemDiscountRate;
        copy.finalDiscountRate = source.finalDiscountRate;
        copy.totalAmount = BigDecimal.ZERO;       // 同上
        copy.sourceQuotationId = source.id;
        copy.snapshotCustomerName = source.snapshotCustomerName;
        copy.snapshotCustomerLevel = source.snapshotCustomerLevel;
        copy.snapshotCustomerRegion = source.snapshotCustomerRegion;
        copy.snapshotCustomerIndustry = source.snapshotCustomerIndustry;
        copy.snapshotCustomerAddress = source.snapshotCustomerAddress;
        copy.customerTemplateId = newTemplateId;
        copy.costingCardTemplateId = source.costingCardTemplateId;
        copy.persist();

        // 2. 行项目（先建，记录 源id→新id 映射；父子链稍后重映射）
        java.util.Map<UUID, UUID> lineIdMap = new java.util.LinkedHashMap<>();
        List<QuotationLineItem> sourceItems =
                QuotationLineItem.list("quotationId = ?1 ORDER BY sortOrder ASC", id);
        java.util.List<QuotationLineItem> newItems = new java.util.ArrayList<>();
        for (QuotationLineItem srcLi : sourceItems) {
            QuotationLineItem newLi = new QuotationLineItem();
            newLi.quotationId = copy.id;
            newLi.productId = srcLi.productId;
            newLi.templateId = newTemplateId;
            newLi.productNameSnapshot = srcLi.productNameSnapshot;
            newLi.productPartNoSnapshot = srcLi.productPartNoSnapshot;
            newLi.productAttributeValues = srcLi.productAttributeValues;
            newLi.subtotal = java.math.BigDecimal.ZERO;
            newLi.systemDiscountRate = srcLi.systemDiscountRate;
            newLi.finalDiscountRate = srcLi.finalDiscountRate;
            newLi.sortOrder = srcLi.sortOrder;
            newLi.customerPartNo = srcLi.customerPartNo;
            newLi.partVersionLocked = srcLi.partVersionLocked;
            newLi.compositeType = srcLi.compositeType;
            // parentLineItemId 稍后重映射；4 份值快照列留空（重建）
            newLi.persist();
            lineIdMap.put(srcLi.id, newLi.id);
            newItems.add(newLi);

            for (QuotationLineProcess srcP : QuotationLineProcess.<QuotationLineProcess>list("lineItemId = ?1", srcLi.id)) {
                QuotationLineProcess newP = new QuotationLineProcess();
                newP.lineItemId = newLi.id;
                newP.processId = srcP.processId;
                newP.persist();
            }

            boolean sameTemplate = newTemplateId != null && newTemplateId.equals(source.customerTemplateId);
            migrateAndCreateComponentData(srcLi.id, newLi.id, newTabs, sameTemplate);
        }

        // 3. 重映射父子链
        for (int i = 0; i < sourceItems.size(); i++) {
            UUID srcParent = sourceItems.get(i).parentLineItemId;
            if (srcParent != null) newItems.get(i).parentLineItemId = lineIdMap.get(srcParent);
        }

        // 4. 用新模板重建报价侧 4 份快照（driver 重展开 + 合并迁移 row_data 输入 + 重算公式）
        for (QuotationLineItem newLi : newItems) {
            cardSnapshotService.refreshQuoteCardValues(newLi);
        }
        if (copy.costingCardTemplateId != null) {
            cardSnapshotService.refreshCostingCardValues(copy.id);
        }

        LOG.infof("Copied quotation id=%s -> id=%s number=%s template=%s",
                id, copy.id, copy.quotationNumber, newTemplateId);
        QuotationDTO dto = QuotationDTO.from(copy);
        dto.lineItems = loadLineItems(copy.id);
        return dto;
    }

    /** 按新模板页签建 QuotationLineComponentData，row_data 仅迁移 INPUT 字段（先 componentId 后 tabName 配对）。 */
    private void migrateAndCreateComponentData(UUID srcLineItemId, UUID newLineItemId,
                                               java.util.List<TabFields> newTabs, boolean sameTemplate) {
        List<QuotationLineComponentData> srcData =
                QuotationLineComponentData.list("lineItemId = ?1", srcLineItemId);
        java.util.Map<String, QuotationLineComponentData> byCompId = new java.util.HashMap<>();
        java.util.Map<String, QuotationLineComponentData> byTabName = new java.util.HashMap<>();
        for (QuotationLineComponentData cd : srcData) {
            if (cd.componentId != null) byCompId.put(cd.componentId.toString(), cd);
            if (cd.tabName != null) byTabName.put(cd.tabName, cd);
        }
        int sort = 0;
        for (TabFields tab : newTabs) {
            QuotationLineComponentData match = byCompId.get(tab.componentId);
            if (match == null) match = byTabName.get(tab.tabName);
            String migratedRowData = (match == null)
                    ? "[]"
                    : mapInputRowData(match.rowData, tab.inputFieldNames, MAPPER);

            QuotationLineComponentData newCd = new QuotationLineComponentData();
            newCd.lineItemId = newLineItemId;
            newCd.componentId = (tab.componentId == null || tab.componentId.isEmpty())
                    ? null : UUID.fromString(tab.componentId);
            newCd.tabName = tab.tabName;
            newCd.rowData = migratedRowData;
            newCd.snapshotRows = null;
            newCd.subtotal = java.math.BigDecimal.ZERO;
            newCd.sortOrder = sort++;
            // driver 默认行墓碑：同模板复制按 componentId 原样拷贝（源集/effKey/fp 不变,墓碑仍匹配）；
            // 换模板复制清空（换模板后 driver/源集/effKey 全变,旧墓碑必失配 → 会误删新行）。
            newCd.deletedRowKeys = (sameTemplate && match != null && match.deletedRowKeys != null)
                    ? match.deletedRowKeys : "[]";
            newCd.persist();
        }
    }

    /**
     * 跨模板复制：从源页签 row_data 只迁移「目标页签输入型字段」的值（按字段名匹配）。
     * 非输入字段(FORMULA/BASIC_DATA/DATA_SOURCE/FIXED_VALUE/LIST_FORMULA)不迁移，由新模板重算。
     */
    static String mapInputRowData(String sourceRowDataJson, java.util.Set<String> targetInputFieldNames,
                                  com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (sourceRowDataJson == null || sourceRowDataJson.isBlank()) return "[]";
        try {
            com.fasterxml.jackson.databind.JsonNode rows = mapper.readTree(sourceRowDataJson);
            if (!rows.isArray() || rows.isEmpty()) return "[]";
            com.fasterxml.jackson.databind.node.ArrayNode out = mapper.createArrayNode();
            for (com.fasterxml.jackson.databind.JsonNode row : rows) {
                com.fasterxml.jackson.databind.node.ObjectNode newRow = mapper.createObjectNode();
                if (row.has("row_index")) newRow.set("row_index", row.get("row_index"));
                for (String fieldName : targetInputFieldNames) {
                    if (row.has(fieldName)) newRow.set(fieldName, row.get(fieldName));
                }
                out.add(newRow);
            }
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 复制迁移用：模板某页签的标识 + 输入型字段名集合。 */
    static final class TabFields {
        final String componentId;
        final String tabName;
        final java.util.Set<String> inputFieldNames;
        TabFields(String componentId, String tabName, java.util.Set<String> inputFieldNames) {
            this.componentId = componentId; this.tabName = tabName; this.inputFieldNames = inputFieldNames;
        }
    }

    private static final java.util.Set<String> INPUT_FIELD_TYPES =
            java.util.Set.of("INPUT_TEXT", "INPUT_NUMBER");

    /** 解析 components_snapshot → 每页签的输入字段名集合。 */
    static java.util.List<TabFields> parseTemplateTabFields(String componentsSnapshotJson,
                                                            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        java.util.List<TabFields> result = new java.util.ArrayList<>();
        if (componentsSnapshotJson == null || componentsSnapshotJson.isBlank()) return result;
        try {
            com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(componentsSnapshotJson);
            if (!arr.isArray()) return result;
            for (com.fasterxml.jackson.databind.JsonNode tab : arr) {
                java.util.Set<String> inputs = new java.util.LinkedHashSet<>();
                com.fasterxml.jackson.databind.JsonNode fields = tab.path("fields");
                if (fields.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode f : fields) {
                        String type = f.path("field_type").asText("");
                        String name = f.path("name").asText("");
                        if (!name.isEmpty() && INPUT_FIELD_TYPES.contains(type)) inputs.add(name);
                    }
                }
                result.add(new TabFields(
                    tab.path("componentId").asText(""),
                    tab.path("tabName").asText(""),
                    inputs));
            }
        } catch (Exception ignore) { }
        return result;
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

    /**
     * 2026-06-01: 清掉单个 line item 的全部子表(供 saveDraft UPSERT 复用行重建子表用)。
     * 复用行(就地 UPDATE 不删 line 实体)不会触发 quotation_line_composite_process 的 FK CASCADE,
     * 故 composite_process 需显式 native DELETE。
     */
    private void clearLineItemChildren(UUID lineItemId) {
        QuotationLineProcess.delete("lineItemId = ?1", lineItemId);
        QuotationLineComponentData.delete("lineItemId = ?1", lineItemId);
        QuotationLineItemSnapshot.delete("lineItemId = ?1", lineItemId);
        em.createNativeQuery("DELETE FROM quotation_line_composite_process WHERE line_item_id = :lid")
            .setParameter("lid", lineItemId).executeUpdate();
    }

    /**
     * Phase 2-1 §2.1 辅助：一次批量删除多个 line_item_id 的所有子表记录。
     * 用 unnest(CAST(:ids AS text[]))::uuid 规避 Hibernate native query 不能直接传 uuid[] 的限制。
     * ids 是 UUID.toString() 字符串数组。
     */
    private void batchDeleteChildrenByIds(String[] idsAsText) {
        if (idsAsText == null || idsAsText.length == 0) return;
        // PostgreSQL: unnest(CAST(:ids AS text[]))::uuid 展开文本数组并转型 uuid
        em.createNativeQuery(
            "DELETE FROM quotation_line_process " +
            "WHERE line_item_id IN (SELECT unnest(CAST(:ids AS text[]))::uuid)")
            .setParameter("ids", idsAsText).executeUpdate();
        em.createNativeQuery(
            "DELETE FROM quotation_line_component_data " +
            "WHERE line_item_id IN (SELECT unnest(CAST(:ids AS text[]))::uuid)")
            .setParameter("ids", idsAsText).executeUpdate();
        em.createNativeQuery(
            "DELETE FROM quotation_line_item_snapshot " +
            "WHERE line_item_id IN (SELECT unnest(CAST(:ids AS text[]))::uuid)")
            .setParameter("ids", idsAsText).executeUpdate();
        em.createNativeQuery(
            "DELETE FROM quotation_line_composite_process " +
            "WHERE line_item_id IN (SELECT unnest(CAST(:ids AS text[]))::uuid)")
            .setParameter("ids", idsAsText).executeUpdate();
    }

    /**
     * Phase 2-1 集合化路径：把阶段①里的 per-row SQL 合成整单集合 SQL。
     *
     * <p>等价论证（§3 表 E1~E5）：
     * <ul>
     *   <li>§2.1/E1：子表全删全建，只是把逐行 DELETE/INSERT 合批为 DELETE ANY + 批量 INSERT。
     *       同 draft payload → 同 INSERT 集合；sortOrder 落库序保持 componentData 顺序。</li>
     *   <li>E2：mat_customer_part_mapping (cpn,hf) 有唯一约束 uq_mat_cust_part_per_hf，
     *       LIMIT 1 确定 → IN 一次等价。</li>
     *   <li>E3：seedProcessesFromBase INSERT 集合相同，customer code 一次查等价逐行查。</li>
     *   <li>E4：derivedAttr 公式纯函数，flush 时机不影响结果。</li>
     *   <li>E5：V169 父子 UPDATE，(childId,parentId) 对不变，批量等价逐行。</li>
     * </ul>
     *
     * <p>纪律：单线程批量 SQL，严禁并行（[[cpq-expand-layer-not-threadsafe]]）。
     */
    @SuppressWarnings("unchecked")
    private void processBatchStage1(UUID quotationId, Quotation q, SaveDraftRequest request) {
        java.util.List<QuotationLineItem> existingLines = QuotationLineItem.list("quotationId = ?1", quotationId);
        java.util.Map<java.util.UUID, QuotationLineItem> existingById = new java.util.HashMap<>();
        for (QuotationLineItem ex : existingLines) existingById.put(ex.id, ex);

        java.util.Set<java.util.UUID> keptIds = new java.util.HashSet<>();
        java.util.Set<java.util.UUID> removedIds = new java.util.HashSet<>();

        // ── §2.1 预处理：批量读旧 componentData（tombstones + snapshotRows），然后整单一次 DELETE ──
        // 先确定复用行集合 & 被删行集合
        for (int i = 0; i < request.lineItems.size(); i++) {
            SaveDraftRequest.LineItemDraft d = request.lineItems.get(i);
            if (d.id != null && existingById.containsKey(d.id)) {
                keptIds.add(d.id);
            }
        }
        for (QuotationLineItem ex : existingLines) {
            if (!keptIds.contains(ex.id)) removedIds.add(ex.id);
        }

        // 整单一次读取所有复用行的旧 componentData（FixC1 + Part A）
        // Map: lineItemId → (componentId → tombstoneJson)
        java.util.Map<java.util.UUID, java.util.Map<java.util.UUID, String>> allTombstones = new java.util.HashMap<>();
        java.util.Map<java.util.UUID, java.util.Map<java.util.UUID, String>> allSnapshots = new java.util.HashMap<>();
        if (!keptIds.isEmpty()) {
            // 批量查所有复用行的 component data（一次 IN）
            List<QuotationLineComponentData> oldCds = QuotationLineComponentData.list(
                "lineItemId IN ?1", new ArrayList<>(keptIds));
            for (QuotationLineComponentData old : oldCds) {
                if (old.componentId == null) continue;
                if (old.deletedRowKeys != null) {
                    allTombstones.computeIfAbsent(old.lineItemId, k -> new java.util.HashMap<>())
                            .put(old.componentId, old.deletedRowKeys);
                }
                if (old.snapshotRows != null) {
                    allSnapshots.computeIfAbsent(old.lineItemId, k -> new java.util.HashMap<>())
                            .put(old.componentId, old.snapshotRows);
                }
            }
        }

        // §2.1 整单一次 DELETE ANY：复用行子表（4 个子表）
        // 用 unnest(CAST(:ids AS text[]))::uuid 方式传 UUID 集合（Hibernate native query 无法直接传 uuid[]）
        if (!keptIds.isEmpty()) {
            String[] keptStrArr = keptIds.stream().map(UUID::toString).toArray(String[]::new);
            batchDeleteChildrenByIds(keptStrArr);
        }

        // §2.1 被删行子表 + 行实体
        if (!removedIds.isEmpty()) {
            String[] removedStrArr = removedIds.stream().map(UUID::toString).toArray(String[]::new);
            batchDeleteChildrenByIds(removedStrArr);
            for (QuotationLineItem ex : existingLines) {
                if (removedIds.contains(ex.id)) ex.delete();
            }
        }

        // ── 主循环：persist 行实体 + 子表 ────────────────────────────────────────────────────
        // E2 收集：需要版本查询的 (cpn, hf) 对 → lineItem 回填
        // key = cpn + " " + hf（零字节分隔，两字段均不含此字符）
        java.util.Map<String, List<QuotationLineItem>> cpnHfToLines = new java.util.LinkedHashMap<>();

        // E3 收集：需要 seed 工序的 (lineItemId → partNo) 对
        java.util.Map<java.util.UUID, String> seedProcLines = new java.util.LinkedHashMap<>();

        // E4 收集：需要 derivedAttr 计算的行 (lineItem, partNo)
        // 先用对象持有引用，计算后直接写 li.productAttributeValues，循环结束统一 flush
        java.util.List<QuotationLineItem> derivedAttrLines = new java.util.ArrayList<>();
        java.util.List<String> derivedAttrPartNos = new java.util.ArrayList<>();

        BigDecimal total = BigDecimal.ZERO;
        Set<String> collectedPartNos = new LinkedHashSet<>();
        java.util.UUID[] newIdsByIndex = new java.util.UUID[request.lineItems.size()];
        com.fasterxml.jackson.databind.ObjectMapper cpOm = new com.fasterxml.jackson.databind.ObjectMapper();

        for (int i = 0; i < request.lineItems.size(); i++) {
            SaveDraftRequest.LineItemDraft liDraft = request.lineItems.get(i);
            QuotationLineItem li;
            if (liDraft.id != null && existingById.containsKey(liDraft.id)) {
                li = existingById.get(liDraft.id);
                li.parentLineItemId = null;  // 父子关系清空，待二阶段重链
            } else {
                li = new QuotationLineItem();
            }
            li.quotationId = quotationId;
            li.productId = liDraft.productId;
            li.templateId = liDraft.templateId != null ? liDraft.templateId : q.customerTemplateId;
            if (liDraft.productAttributeValues != null) li.productAttributeValues = liDraft.productAttributeValues;
            if (liDraft.subtotal != null) li.subtotal = liDraft.subtotal;
            li.sortOrder = liDraft.sortOrder != null ? liDraft.sortOrder : i;
            if (liDraft.productPartNo != null && !liDraft.productPartNo.isBlank()) {
                li.productPartNoSnapshot = liDraft.productPartNo;
            }
            if (liDraft.productName != null && !liDraft.productName.isBlank()) {
                li.productNameSnapshot = liDraft.productName;
            }
            String effectiveCpn = (liDraft.customerPartNo != null && !liDraft.customerPartNo.isBlank())
                    ? liDraft.customerPartNo
                    : ((liDraft.customerProductNo != null && !liDraft.customerProductNo.isBlank())
                            ? liDraft.customerProductNo : null);
            if (effectiveCpn != null) li.customerPartNo = effectiveCpn;
            if (liDraft.compositeType != null && !liDraft.compositeType.isBlank()) {
                li.compositeType = liDraft.compositeType;
            }
            li.annualVolume = liDraft.annualVolume;
            li.discountSource = liDraft.discountSource;
            li.discountBaseAmount = liDraft.discountBaseAmount;
            li.discountRateApplied = liDraft.discountRateApplied;
            li.lineDiscountAmount = liDraft.lineDiscountAmount;
            li.lineUnitPrice = liDraft.lineUnitPrice;
            li.lineFinalPrice = liDraft.lineFinalPrice;
            li.lineTotalAmount = liDraft.lineTotalAmount;
            li.discountRuleCode = liDraft.discountRuleCode;
            if (liDraft.quoteExcelValues != null) li.quoteExcelValues = liDraft.quoteExcelValues;
            li.persist();
            newIdsByIndex[i] = li.id;

            // E2 收集：有 cpn + hf 才加入批量版本查
            if (li.customerPartNo != null && !li.customerPartNo.isBlank()
                    && li.productPartNoSnapshot != null && !li.productPartNoSnapshot.isBlank()) {
                String key = li.customerPartNo + " " + li.productPartNoSnapshot;
                cpnHfToLines.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(li);
            }

            // Product 查询：填充 productPartNoSnapshot / productNameSnapshot，收集 partNo
            if (liDraft.productId != null) {
                Product product = Product.findById(liDraft.productId);
                if (product != null && product.partNo != null) {
                    collectedPartNos.add(product.partNo);
                    li.productPartNoSnapshot = product.partNo;
                    li.productNameSnapshot = product.name;
                    // E2：product 覆盖了 productPartNoSnapshot，需要重新更新 E2 收集 key
                    // 先移除原来按旧 snapshot 加的条目，再按新 snapshot 重新加
                    if (li.customerPartNo != null && !li.customerPartNo.isBlank()) {
                        // 移除旧 key（如果存在且 hf 字段刚被 product 覆盖）
                        String oldHf = liDraft.productPartNo != null && !liDraft.productPartNo.isBlank()
                                ? liDraft.productPartNo : null;
                        if (oldHf != null && !oldHf.equals(product.partNo)) {
                            String oldKey = li.customerPartNo + " " + oldHf;
                            List<QuotationLineItem> oldList = cpnHfToLines.get(oldKey);
                            if (oldList != null) {
                                oldList.remove(li);
                                if (oldList.isEmpty()) cpnHfToLines.remove(oldKey);
                            }
                        }
                        // 加入新 key
                        String newKey = li.customerPartNo + " " + product.partNo;
                        cpnHfToLines.computeIfAbsent(newKey, k -> new java.util.ArrayList<>()).add(li);
                    }

                    // E4 收集：有 derivedAttrs 的行
                    // 注意：这里只收集，实际计算在循环结束后批量处理
                    derivedAttrLines.add(li);
                    derivedAttrPartNos.add(product.partNo);
                }
            }

            if (liDraft.subtotal != null) total = total.add(liDraft.subtotal);

            // processIds（低频，逐行 persist，无性能收益集合化）
            if (liDraft.processIds != null) {
                for (UUID processId : liDraft.processIds) {
                    QuotationLineProcess lp = new QuotationLineProcess();
                    lp.lineItemId = li.id;
                    lp.processId = processId;
                    lp.persist();
                }
            }

            // E3 收集：seedProcessesFromBase 行
            boolean noProcs = (liDraft.processIds == null || liDraft.processIds.isEmpty());
            if (noProcs && Boolean.TRUE.equals(liDraft.seedProcessesFromBase)
                    && li.productPartNoSnapshot != null && !li.productPartNoSnapshot.isBlank()) {
                seedProcLines.put(li.id, li.productPartNoSnapshot);
            }

            // compositeProcesses（低频，逐行 INSERT，不做集合化）
            if (liDraft.compositeProcesses != null && !liDraft.compositeProcesses.isEmpty()) {
                for (SaveDraftRequest.CompositeProcessDraft cpd : liDraft.compositeProcesses) {
                    if (cpd.defCode == null || cpd.defCode.isBlank()) continue;
                    try {
                        em.createNativeQuery(
                                "INSERT INTO quotation_line_composite_process " +
                                "(line_item_id, def_code, seq_no, participating_parts, param_values) " +
                                "VALUES (:lid, :d, :sq, CAST(:pp AS jsonb), CAST(:pv AS jsonb))")
                            .setParameter("lid", li.id)
                            .setParameter("d", cpd.defCode)
                            .setParameter("sq", cpd.seqNo)
                            .setParameter("pp", cpOm.writeValueAsString(cpd.participatingParts == null ? java.util.List.of() : cpd.participatingParts))
                            .setParameter("pv", cpOm.writeValueAsString(cpd.paramValues == null ? java.util.Map.of() : cpd.paramValues))
                            .executeUpdate();
                    } catch (Exception e) {
                        LOG.warnf("[batch-composite-proc-save] line=%s 写组合工艺失败(降级): %s", li.id, e.getMessage());
                    }
                }
            }

            // componentData：逐行 persist（批量 INSERT 收益低，且需要正确回填 tombstones/snapshots）
            if (liDraft.componentData != null) {
                java.util.Map<java.util.UUID, String> tombstonesForLine =
                        allTombstones.getOrDefault(li.id, java.util.Collections.emptyMap());
                java.util.Map<java.util.UUID, String> snapshotsForLine =
                        allSnapshots.getOrDefault(li.id, java.util.Collections.emptyMap());
                for (int j = 0; j < liDraft.componentData.size(); j++) {
                    SaveDraftRequest.ComponentDataDraft cdDraft = liDraft.componentData.get(j);
                    QuotationLineComponentData cd = new QuotationLineComponentData();
                    cd.lineItemId = li.id;
                    cd.componentId = cdDraft.componentId;
                    cd.tabName = cdDraft.tabName;
                    if (cdDraft.rowData != null) cd.rowData = cdDraft.rowData;
                    if (cdDraft.subtotal != null) cd.subtotal = cdDraft.subtotal;
                    cd.sortOrder = cdDraft.sortOrder != null ? cdDraft.sortOrder : j;
                    String preserved = (cdDraft.componentId != null)
                            ? tombstonesForLine.get(cdDraft.componentId) : null;
                    cd.deletedRowKeys = (preserved != null) ? preserved : "[]";
                    String preservedSr = (cdDraft.componentId != null)
                            ? snapshotsForLine.get(cdDraft.componentId) : null;
                    if (preservedSr != null) cd.snapshotRows = preservedSr;
                    cd.persist();
                }
            }
        } // end main loop

        // ── E2 批量版本查询 ────────────────────────────────────────────────────────────────────
        // 一次 IN 查询，按 (cpn, hf) 回填 partVersionLocked
        if (!cpnHfToLines.isEmpty()) {
            try {
                // 构造 (cpn, hf) 对列表
                java.util.List<String> cpns = new java.util.ArrayList<>();
                java.util.List<String> hfs = new java.util.ArrayList<>();
                for (String key : cpnHfToLines.keySet()) {
                    int sep = key.indexOf(' ');
                    cpns.add(key.substring(0, sep));
                    hfs.add(key.substring(sep + 1));
                }
                // PostgreSQL：(cpn, hf) IN (...) 写法；用 unnest 两个数组 JOIN 方式最稳健
                List<Object[]> versionRows = em.createNativeQuery(
                        "SELECT m.customer_product_no, m.hf_part_no, m.current_version " +
                        "FROM mat_customer_part_mapping m " +
                        "JOIN (SELECT unnest(CAST(:cpns AS text[])) AS cpn, unnest(CAST(:hfs AS text[])) AS hf) pairs " +
                        "  ON m.customer_product_no = pairs.cpn AND m.hf_part_no = pairs.hf")
                    .setParameter("cpns", cpns.toArray(new String[0]))
                    .setParameter("hfs", hfs.toArray(new String[0]))
                    .getResultList();
                for (Object[] row : versionRows) {
                    String cpn = (String) row[0];
                    String hf  = (String) row[1];
                    int ver = ((Number) row[2]).intValue();
                    String key = cpn + " " + hf;
                    List<QuotationLineItem> lis = cpnHfToLines.get(key);
                    if (lis != null) {
                        for (QuotationLineItem lx : lis) lx.partVersionLocked = ver;
                    }
                }
            } catch (Exception e) {
                LOG.warnf("[batch-E2] mat_customer_part_mapping 批量版本查询失败(降级): %s", e.getMessage());
            }
        }

        // ── E4 derivedAttr 批量计算 + 末尾统一 flush ─────────────────────────────────────────
        // 公式纯函数；去掉 per-row flush，循环结束后统一一次 flush。
        boolean anyDerivedChanged = false;
        for (int k = 0; k < derivedAttrLines.size(); k++) {
            QuotationLineItem li = derivedAttrLines.get(k);
            String partNo = derivedAttrPartNos.get(k);
            try {
                List<DerivedAttribute> derivedAttrs = loadDerivedAttributes(partNo);
                if (!derivedAttrs.isEmpty()) {
                    Map<String, Object> calcResults = derivedAttributeCalculatorV5.calculate(
                            q.customerId, partNo, derivedAttrs);
                    if (!calcResults.isEmpty()) {
                        li.productAttributeValues = mergeFormulaResults(li.productAttributeValues, calcResults);
                        anyDerivedChanged = true;
                    }
                    logFormulaErrors(calcResults, quotationId, partNo);
                }
            } catch (Exception e) {
                LOG.warnf("FormulaEngine calculation failed for quotation=%s partNo=%s: %s",
                        quotationId, partNo, e.getMessage());
            }
        }
        if (anyDerivedChanged) {
            em.flush();  // 统一一次 flush，等价于逐行 flush（公式纯函数，顺序无关）
        }

        // ── E3 seedProcessesFromBase 整单批量 INSERT ───────────────────────────────────────────
        // 原逐行：每行各自按 partNo 查 material_bom_item + INSERT quotation_line_process。
        // 集合化：一次查客户 code，再用 (lineItemId, partNo) 对一次 INSERT…SELECT。
        // 等价论证：INSERT 集合 = ∪_{per-row} INSERT，因为 (lineItemId, partNo) 对独立，操作集合相同。
        if (!seedProcLines.isEmpty()) {
            try {
                Object ccObj = em.createNativeQuery("SELECT code FROM customer WHERE id = :cid")
                        .setParameter("cid", q.customerId)
                        .getResultStream().findFirst().orElse(null);
                if (ccObj != null) {
                    String customerCode = ccObj.toString();
                    // 构造 (lineItemId, partNo) VALUES 表用于 JOIN
                    // 用 unnest 两个数组展开成行，再 JOIN material_bom_item 按 partNo 匹配
                    // lid 用 text[] + ::uuid 转型，规避 Hibernate native query 传 uuid[] 的兼容性问题
                    java.util.List<java.util.UUID> lidList = new java.util.ArrayList<>(seedProcLines.keySet());
                    java.util.List<String> partList = new java.util.ArrayList<>();
                    String[] lidStrArr = new String[lidList.size()];
                    for (int si = 0; si < lidList.size(); si++) {
                        lidStrArr[si] = lidList.get(si).toString();
                        partList.add(seedProcLines.get(lidList.get(si)));
                    }
                    String[] partArr = partList.toArray(new String[0]);

                    em.createNativeQuery(
                            "INSERT INTO quotation_line_process (id, line_item_id, process_id) " +
                            "SELECT gen_random_uuid(), kv.lid::uuid, p.id " +
                            "FROM ( " +
                            "  SELECT unnest(CAST(:lids AS text[]))::uuid AS lid, unnest(CAST(:parts AS text[])) AS part_no " +
                            ") kv " +
                            "JOIN ( " +
                            "  SELECT DISTINCT material_no, operation_no FROM material_bom_item " +
                            "  WHERE system_type='QUOTE' AND customer_no=:cc " +
                            "    AND characteristic='ASSEMBLY' AND operation_no IS NOT NULL AND is_current = true " +
                            "    AND material_no = ANY(CAST(:parts_arr AS text[])) " +
                            ") bom ON bom.material_no = kv.part_no " +
                            "JOIN process p ON p.code = bom.operation_no")
                        .setParameter("lids", lidStrArr)
                        .setParameter("parts", partArr)
                        .setParameter("cc", customerCode)
                        .setParameter("parts_arr", partArr)
                        .executeUpdate();
                }
            } catch (Exception e) {
                LOG.warnf("[batch-E3] seedProcessesFromBase 批量 seed 失败(降级): %s", e.getMessage());
            }
        }

        // ── 更新总额 ───────────────────────────────────────────────────────────────────────────
        q.originalAmount = total;
        q.totalAmount = total.multiply(q.finalDiscountRate).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);

        // ── E5 V169 父子关系批量 UPDATE ────────────────────────────────────────────────────────
        // 原逐行：per-child UPDATE quotation_line_item SET parent_line_item_id = :pid WHERE id = :cid。
        // 集合化：批量 UPDATE...FROM (VALUES (...)) AS v(cid, pid)。
        // 等价论证：同 (childId, parentId) 对，UPDATE 结果逐行相同。
        java.util.List<java.util.UUID[]> parentChildPairs = new java.util.ArrayList<>();
        for (int i = 0; i < request.lineItems.size(); i++) {
            SaveDraftRequest.LineItemDraft draft = request.lineItems.get(i);
            if (draft.tempParentIndex == null) continue;
            int parentIdx = draft.tempParentIndex;
            if (parentIdx < 0 || parentIdx >= newIdsByIndex.length) continue;
            java.util.UUID childId = newIdsByIndex[i];
            java.util.UUID parentId = newIdsByIndex[parentIdx];
            if (childId == null || parentId == null) continue;
            parentChildPairs.add(new java.util.UUID[]{childId, parentId});
        }
        if (!parentChildPairs.isEmpty()) {
            if (parentChildPairs.size() == 1) {
                // 单对：直接 UPDATE（避免构造 VALUES 列表的复杂度）
                em.createNativeQuery(
                        "UPDATE quotation_line_item SET parent_line_item_id = :pid WHERE id = :cid")
                    .setParameter("pid", parentChildPairs.get(0)[1])
                    .setParameter("cid", parentChildPairs.get(0)[0])
                    .executeUpdate();
            } else {
                // 多对：批量 UPDATE...FROM (VALUES ...) AS v(cid, pid)
                StringBuilder values = new StringBuilder();
                for (int k = 0; k < parentChildPairs.size(); k++) {
                    if (k > 0) values.append(',');
                    values.append("(CAST(:c").append(k).append(" AS uuid), CAST(:p").append(k).append(" AS uuid))");
                }
                StringBuilder sql = new StringBuilder(
                        "UPDATE quotation_line_item qli SET parent_line_item_id = v.pid " +
                        "FROM (VALUES ").append(values).append(") AS v(cid, pid) WHERE qli.id = v.cid");
                jakarta.persistence.Query upd = em.createNativeQuery(sql.toString());
                for (int k = 0; k < parentChildPairs.size(); k++) {
                    upd.setParameter("c" + k, parentChildPairs.get(k)[0]);
                    upd.setParameter("p" + k, parentChildPairs.get(k)[1]);
                }
                upd.executeUpdate();
            }
        }

        // ── 收集版本快照（v5.1 §6.6） ─────────────────────────────────────────────────────────
        if (!collectedPartNos.isEmpty()) {
            String versionsJson = driftDetectionService.collectReferencedVersions(
                    q.customerId, new ArrayList<>(collectedPartNos));
            q.referencedVersions = versionsJson;
            LOG.debugf("Recorded referencedVersions for quotation=%s partNos=%s", quotationId, collectedPartNos);
        }
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
        // 批量查 product_type — 供前端 ProductCard 按产品类型条件渲染 Tab
        // (COMPOSITE 专属 Tab 在 SIMPLE 产品下隐藏). V6 替代 mat_part：
        // COMPOSITE 判定：material_bom_item 含 component_usage_type='ASSEMBLY' 的当前行。
        Map<String, String> productTypeByHfPartNo = new HashMap<>();
        if (!hfPartNos.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT mm.material_no AS part_no, " +
                    "  CASE WHEN EXISTS(SELECT 1 FROM material_bom_item mb WHERE mb.material_no=mm.material_no " +
                    "     AND mb.component_usage_type='ASSEMBLY' AND mb.is_current=true) THEN 'COMPOSITE' ELSE 'SIMPLE' END AS product_type " +
                    "FROM material_master mm WHERE mm.material_no IN (:pns)")
                    .setParameter("pns", hfPartNos)
                    .getResultList();
            for (Object[] r : rows) {
                if (r != null && r[0] != null && r[1] != null) {
                    productTypeByHfPartNo.put(r[0].toString(), r[1].toString());
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
            // 回退：internal_material 没维护到的，从 material_master（V6 替代 mat_part）兜底
            List<String> missing = new ArrayList<>();
            for (String pn : hfPartNos) if (!matPartByHfPartNo.containsKey(pn)) missing.add(pn);
            if (!missing.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Object[]> fbRows = em.createNativeQuery(
                        "SELECT material_no AS part_no, material_name AS part_name, specification, " +
                        "  dimension AS size_info, NULL AS status_code FROM material_master WHERE material_no IN (:pns)")
                        .setParameter("pns", missing)
                        .getResultList();
                for (Object[] r : fbRows) {
                    if (r != null && r[0] != null) {
                        matPartByHfPartNo.putIfAbsent(r[0].toString(), r);
                    }
                }
            }
        }

        // getById N+1 融合(kill switch cpq.getbyid-batch,默认 ON):4 类子表整单一次 IN 查 + 内存按
        // lineItemId 分组,替代 stream 内每行 4 条 WHERE line_item_id=? (680→4)。OFF=逐行(原行为)。
        boolean getByIdBatch = "true".equalsIgnoreCase(
                System.getProperty("cpq.getbyid-batch",
                    System.getenv().getOrDefault("CPQ_GETBYID_BATCH", "true")));
        final List<UUID> lineIds = items.stream().map(i -> i.id).collect(Collectors.toList());
        final Map<UUID, List<QuotationLineProcess>> procByLine = !getByIdBatch ? Map.of()
                : QuotationLineProcess.<QuotationLineProcess>list("lineItemId IN ?1 ORDER BY lineItemId, id", lineIds)
                    .stream().collect(Collectors.groupingBy(p -> p.lineItemId));
        final Map<UUID, List<QuotationLineComponentData>> cdByLine = !getByIdBatch ? Map.of()
                : QuotationLineComponentData.<QuotationLineComponentData>list("lineItemId IN ?1 ORDER BY lineItemId, sortOrder, id", lineIds)
                    .stream().collect(Collectors.groupingBy(c -> c.lineItemId));
        final Map<UUID, QuotationLineItemSnapshot> snapByLine = new HashMap<>();
        final Map<UUID, List<Map<String, Object>>> cpByLine = new HashMap<>();
        if (getByIdBatch && !lineIds.isEmpty()) {
            for (QuotationLineItemSnapshot s : QuotationLineItemSnapshot
                    .<QuotationLineItemSnapshot>list("lineItemId IN ?1 ORDER BY lineItemId, id", lineIds)) {
                snapByLine.putIfAbsent(s.lineItemId, s);   // firstResult 语义:每行第一条
            }
            @SuppressWarnings("unchecked")
            List<Object[]> cpAll = em.createNativeQuery(
                    "SELECT line_item_id, def_code, seq_no, participating_parts::text, param_values::text " +
                    "FROM quotation_line_composite_process WHERE line_item_id IN (:ids) ORDER BY line_item_id, seq_no")
                .setParameter("ids", lineIds).getResultList();
            com.fasterxml.jackson.databind.ObjectMapper cpOm0 = new com.fasterxml.jackson.databind.ObjectMapper();
            for (Object[] r : cpAll) {
                UUID lid = (r[0] instanceof UUID u) ? u : UUID.fromString(r[0].toString());
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("defCode", r[1]);
                m.put("seqNo", r[2]);
                try {
                    m.put("participatingParts", r[3] == null ? java.util.List.of() : cpOm0.readValue(r[3].toString(), java.util.List.class));
                    m.put("paramValues", r[4] == null ? java.util.Map.of() : cpOm0.readValue(r[4].toString(), java.util.Map.class));
                } catch (Exception ex) {
                    m.put("participatingParts", java.util.List.of());
                    m.put("paramValues", java.util.Map.of());
                }
                cpByLine.computeIfAbsent(lid, k -> new java.util.ArrayList<>()).add(m);
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
            // 注入 productType (用于前端 ProductCard 条件渲染 COMPOSITE 专属 Tab)
            if (hfpn != null) {
                dto.productType = productTypeByHfPartNo.get(hfpn);
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

            if (getByIdBatch) {
                // 融合路径:从整单一次 IN 查的内存分组取(0 往返)
                dto.processes = procByLine.getOrDefault(li.id, java.util.List.of())
                        .stream().map(QuotationDTO.ProcessDTO::from).collect(Collectors.toList());
                dto.compositeProcesses = new java.util.ArrayList<>(cpByLine.getOrDefault(li.id, java.util.List.of()));
                dto.componentData = cdByLine.getOrDefault(li.id, java.util.List.of())
                        .stream().map(QuotationDTO.ComponentDataDTO::from).collect(Collectors.toList());
                QuotationLineItemSnapshot snapshot = snapByLine.get(li.id);
                if (snapshot != null) dto.snapshot = QuotationDTO.SnapshotDTO.from(snapshot);
                return dto;
            }

            dto.processes = QuotationLineProcess.<QuotationLineProcess>list("lineItemId = ?1", li.id)
                    .stream().map(QuotationDTO.ProcessDTO::from).collect(Collectors.toList());

            // 选配-组合工艺 per-quote:读本行步骤回传,使刷新/saveDraft 透传后跨保存存活
            dto.compositeProcesses = new java.util.ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Object[]> cprows = em.createNativeQuery(
                    "SELECT def_code, seq_no, participating_parts::text, param_values::text " +
                    "FROM quotation_line_composite_process WHERE line_item_id = :lid ORDER BY seq_no")
                .setParameter("lid", li.id).getResultList();
            com.fasterxml.jackson.databind.ObjectMapper cpOm = new com.fasterxml.jackson.databind.ObjectMapper();
            for (Object[] r : cprows) {
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("defCode", r[0]);
                m.put("seqNo", r[1]);
                try {
                    m.put("participatingParts", r[2] == null ? java.util.List.of() : cpOm.readValue(r[2].toString(), java.util.List.class));
                    m.put("paramValues", r[3] == null ? java.util.Map.of() : cpOm.readValue(r[3].toString(), java.util.Map.class));
                } catch (Exception ex) {
                    m.put("participatingParts", java.util.List.of());
                    m.put("paramValues", java.util.Map.of());
                }
                dto.compositeProcesses.add(m);
            }

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
    public String updateLineItemPartVersion(UUID quotationId, UUID lineItemId, int newVersion) {
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
        // V6: 版本切换后重算整单所有 line_item 的 excel_view_snapshot
        excelViewService.regenerateAllSnapshots(quotationId);
        // 重读切换的 line_item 拿最新 snapshot 返给前端立即渲染
        QuotationLineItem refreshed = QuotationLineItem.findById(lineItemId);
        return refreshed != null ? refreshed.excelViewSnapshot : null;
    }

    /**
     * Admin heal: 把所有 quotation_line_component_data 的 tab_name 重写为模板 snapshot 权威值,
     * 一次性洗历史 AP-37 根因 5 污染的脏数据 (saved-driven enrich 误用 cid 反查塌缩,
     * 把同 cid 多 Tab 的标准 Tab 名错写成"选配-*"等情况).
     *
     * <p>对每个 line_item: 拉模板 components_snapshot → 按 cid 分组成队列 →
     * 同 cid 多条按 (cid, tabName) 精确匹配优先, 否则同 cid 第一条 → 重写 tab_name + sort_order
     * 与 snapshot 对齐. SUBTOTAL 行同样处理.
     *
     * <p>dryRun=true 只统计不写库; apply=true 才持久化.
     *
     * @return Map with keys: scannedLineItems, scannedRows, plannedUpdates, applied
     */
    @Transactional
    public Map<String, Object> healComponentDataTabNames(boolean apply) {
        int scannedLineItems = 0;
        int scannedRows = 0;
        int plannedUpdates = 0;
        int applied = 0;
        List<Map<String, Object>> samples = new ArrayList<>();

        List<QuotationLineItem> allLineItems = QuotationLineItem.listAll();
        // 模板 snapshot 缓存避免重复反序列化
        Map<UUID, List<Map<String, Object>>> snapshotByTemplateId = new HashMap<>();

        for (QuotationLineItem li : allLineItems) {
            scannedLineItems++;
            if (li.templateId == null) continue;

            List<Map<String, Object>> snapshot = snapshotByTemplateId.computeIfAbsent(li.templateId, tid -> {
                com.cpq.template.entity.Template tpl = com.cpq.template.entity.Template.findById(tid);
                if (tpl == null || tpl.componentsSnapshot == null) return Collections.emptyList();
                try {
                    return MAPPER.readValue(tpl.componentsSnapshot, new TypeReference<List<Map<String, Object>>>() {});
                } catch (Exception e) {
                    LOG.warnf("healComponentDataTabNames: failed to parse template %s snapshot: %s", tid, e.getMessage());
                    return Collections.emptyList();
                }
            });
            if (snapshot.isEmpty()) continue;

            List<QuotationLineComponentData> savedList = QuotationLineComponentData.list(
                    "lineItemId = ?1 ORDER BY sortOrder ASC", li.id);
            scannedRows += savedList.size();

            // 按 cid 分组队列
            Map<UUID, Deque<QuotationLineComponentData>> queueByCid = new HashMap<>();
            for (QuotationLineComponentData s : savedList) {
                if (s.componentId == null) continue;
                queueByCid.computeIfAbsent(s.componentId, k -> new ArrayDeque<>()).add(s);
            }

            // 按 snapshot 顺序遍历, 给每个 snapshot entry 配一个 saved
            for (int i = 0; i < snapshot.size(); i++) {
                Map<String, Object> sc = snapshot.get(i);
                Object cidObj = sc.get("componentId");
                if (cidObj == null) cidObj = sc.get("component_id");
                if (cidObj == null) continue;
                UUID cid;
                try { cid = UUID.fromString(cidObj.toString()); } catch (Exception e) { continue; }
                String snapTab = strVal(sc.get("tabName"), sc.get("tab_name"));

                Deque<QuotationLineComponentData> q = queueByCid.get(cid);
                if (q == null || q.isEmpty()) continue;

                // (cid, tabName) 精确匹配优先
                QuotationLineComponentData picked = null;
                Iterator<QuotationLineComponentData> it = q.iterator();
                while (it.hasNext()) {
                    QuotationLineComponentData s = it.next();
                    if (snapTab != null && snapTab.equals(s.tabName)) {
                        it.remove();
                        picked = s;
                        break;
                    }
                }
                if (picked == null) picked = q.pollFirst();
                if (picked == null) continue;

                boolean needsUpdate = false;
                String oldTab = picked.tabName;
                Integer oldOrder = picked.sortOrder;
                if (snapTab != null && !snapTab.equals(picked.tabName)) {
                    needsUpdate = true;
                }
                if (picked.sortOrder == null || picked.sortOrder != i) {
                    needsUpdate = true;
                }
                if (needsUpdate) {
                    plannedUpdates++;
                    if (samples.size() < 20) {
                        Map<String, Object> sample = new LinkedHashMap<>();
                        sample.put("lineItemId", li.id);
                        sample.put("componentId", cid);
                        sample.put("oldTabName", oldTab);
                        sample.put("newTabName", snapTab);
                        sample.put("oldSortOrder", oldOrder);
                        sample.put("newSortOrder", i);
                        samples.add(sample);
                    }
                    if (apply) {
                        picked.tabName = snapTab;
                        picked.sortOrder = i;
                        picked.persist();
                        applied++;
                    }
                }
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("apply", apply);
        report.put("scannedLineItems", scannedLineItems);
        report.put("scannedRows", scannedRows);
        report.put("plannedUpdates", plannedUpdates);
        report.put("applied", applied);
        report.put("samples", samples);
        return report;
    }

    private static String strVal(Object... candidates) {
        for (Object c : candidates) {
            if (c != null) {
                String s = c.toString();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    // ──────────────────────────────────────────────
    // driver 默认行墓碑管理（deletable-driver-rows）
    // ──────────────────────────────────────────────

    @Transactional
    public void deleteDriverRow(UUID lineItemId, UUID componentId, String effKey, String fp) {
        QuotationLineComponentData cd = QuotationLineComponentData
            .find("lineItemId = ?1 and componentId = ?2", lineItemId, componentId).firstResult();
        if (cd == null) throw new BusinessException(404, "component data not found");
        try {
            var raw = (cd.deletedRowKeys == null || cd.deletedRowKeys.isBlank()) ? "[]" : cd.deletedRowKeys;
            var arr = MAPPER.readTree(raw);
            com.fasterxml.jackson.databind.node.ArrayNode out = arr.isArray()
                ? (com.fasterxml.jackson.databind.node.ArrayNode) arr : MAPPER.createArrayNode();
            boolean exists = false;
            for (var n : out) {
                if (effKey.equals(n.path("effKey").asText()) && fp.equals(n.path("fp").asText())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                if (out.size() >= 500) {
                    LOG.warnf("[row-delete] tombstones >=500 lineItem=%s comp=%s", lineItemId, componentId);
                }
                var t = MAPPER.createObjectNode();
                t.put("effKey", effKey);
                t.put("fp", fp);
                out.add(t);
                cd.deletedRowKeys = MAPPER.writeValueAsString(out);
            }
        } catch (Exception e) {
            throw new BusinessException(500, "deleted_row_keys 更新失败: " + e.getMessage());
        }
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        if (li != null) cardSnapshotService.refreshQuoteCardValues(li, true);
    }

    @Transactional
    public void restoreAllDriverRows(UUID lineItemId, UUID componentId) {
        QuotationLineComponentData cd = QuotationLineComponentData
            .find("lineItemId = ?1 and componentId = ?2", lineItemId, componentId).firstResult();
        if (cd == null) return;
        cd.deletedRowKeys = "[]";
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        if (li != null) cardSnapshotService.refreshQuoteCardValues(li, true);
    }

    // ── 核价管理列表 ──────────────────────────────────────────────────────────────

    /**
     * 查核价管理列表（核价工作台用）。
     *
     * <p>标量投影排除 frozen_dto 大字段（N5 防护）；直接使用 costing_order.status 英文码，
     * 不再从 quotation.status 派生中文标签。
     *
     * @param statuses 英文码状态过滤列表（PENDING/APPROVED/REJECTED/WITHDRAWN），
     *                 null 或空列表表示返回全部
     * @param keyword  按报价单号模糊过滤（不区分大小写），null 或空串不过滤
     * @param sort     排序字段："status" 按状态升序；"updatedAt" 按更新时间降序；
     *                 null/其它 按 entered_costing_at 降序（默认）
     */
    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.SUPPORTS)
    public java.util.List<com.cpq.quotation.dto.CostingOrderListItemDTO> listCostingOrders(
            java.util.List<String> statuses, String keyword, String sort) {

        // Step 1: 标量投影取 CostingOrder（排除 frozen_dto），默认按 enteredCostingAt DESC
        java.util.List<Object[]> rows = em.createQuery(
                "SELECT co.id, co.quotationId, co.costingOrderNumber, co.status, co.rejectReason, " +
                "co.submittedBy, co.enteredCostingAt, co.updatedAt " +
                "FROM CostingOrder co ORDER BY co.enteredCostingAt DESC", Object[].class)
                .getResultList();

        if (rows.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        // Step 2: 批量取 Quotation（按 quotationId IN）
        java.util.Set<UUID> quotationIds = rows.stream()
                .map(r -> (UUID) r[1])
                .collect(java.util.stream.Collectors.toSet());
        java.util.List<Quotation> quotations = em.createQuery(
                "FROM Quotation q WHERE q.id IN :ids", Quotation.class)
                .setParameter("ids", quotationIds)
                .getResultList();
        java.util.Map<UUID, Quotation> quotationMap = quotations.stream()
                .collect(java.util.stream.Collectors.toMap(q -> q.id, q -> q));

        // Step 3: 批量取 User 姓名（排除 null submittedBy）
        java.util.Set<UUID> userIds = rows.stream()
                .filter(r -> r[5] != null)
                .map(r -> (UUID) r[5])
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<UUID, String> userNameMap = new java.util.HashMap<>();
        if (!userIds.isEmpty()) {
            java.util.List<User> users = em.createQuery(
                    "FROM User u WHERE u.id IN :ids", User.class)
                    .setParameter("ids", userIds)
                    .getResultList();
            users.forEach(u -> userNameMap.put(u.id, u.fullName));
        }

        // Step 4: 组装 DTO，执行状态 + 关键字过滤
        String kw = (keyword != null && !keyword.isBlank()) ? keyword.toLowerCase() : null;
        java.util.List<com.cpq.quotation.dto.CostingOrderListItemDTO> out = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            UUID costingOrderId       = (UUID) r[0];
            UUID quotationId          = (UUID) r[1];
            String costingOrderNumber = (String) r[2];
            String status             = (String) r[3];
            String rejectReason       = (String) r[4];
            UUID submittedBy          = (UUID) r[5];
            java.time.OffsetDateTime enteredCostingAt = (java.time.OffsetDateTime) r[6];
            java.time.OffsetDateTime updatedAt        = (java.time.OffsetDateTime) r[7];

            // 孤儿核价单（quotation 已不存在）跳过
            Quotation q = quotationMap.get(quotationId);
            if (q == null) continue;

            // 多值英文码状态过滤
            if (statuses != null && !statuses.isEmpty() && !statuses.contains(status)) {
                continue;
            }

            // 关键字过滤（按报价单号，不区分大小写）
            if (kw != null && (q.quotationNumber == null || !q.quotationNumber.toLowerCase().contains(kw))) {
                continue;
            }

            com.cpq.quotation.dto.CostingOrderListItemDTO d = new com.cpq.quotation.dto.CostingOrderListItemDTO();
            d.costingOrderId     = costingOrderId;
            d.quotationId        = quotationId;
            d.costingOrderNumber = costingOrderNumber;
            d.quotationNumber    = q.quotationNumber;
            d.customerName       = q.snapshotCustomerName;
            d.submittedByName    = submittedBy != null ? userNameMap.get(submittedBy) : null;
            d.currency           = "CNY"; // 当前系统统一人民币，per-part 多币种待后续演进
            d.status             = status;
            d.rejectReason       = rejectReason;
            d.createdAt          = enteredCostingAt;
            d.updatedAt          = updatedAt;
            out.add(d);
        }

        // Step 5: 内存排序（status/updatedAt；默认 enteredCostingAt DESC 已由投影保证）
        if ("status".equals(sort)) {
            out.sort(java.util.Comparator.comparing(d -> d.status == null ? "" : d.status));
        } else if ("updatedAt".equals(sort)) {
            out.sort((a, b) -> {
                if (a.updatedAt == null && b.updatedAt == null) return 0;
                if (a.updatedAt == null) return 1;
                if (b.updatedAt == null) return -1;
                return b.updatedAt.compareTo(a.updatedAt);
            });
        }

        return out;
    }

    /**
     * 查单条核价单详情（含冻结副本），供核价工作台详情页使用。
     *
     * @param coid 核价单 ID
     * @return 含 frozenDto 的详情 DTO
     * @throws BusinessException 404 若核价单不存在
     */
    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.SUPPORTS)
    public com.cpq.quotation.dto.CostingOrderDetailDTO getCostingOrderById(UUID coid) {
        CostingOrder co = CostingOrder.findById(coid);
        if (co == null) {
            throw new BusinessException(404, "核价单不存在");
        }
        com.cpq.quotation.dto.CostingOrderDetailDTO d = new com.cpq.quotation.dto.CostingOrderDetailDTO();
        d.costingOrderId     = co.id;
        d.quotationId        = co.quotationId;
        d.costingOrderNumber = co.costingOrderNumber;
        d.status             = co.status;
        d.rejectReason       = co.rejectReason;
        d.totalAmount        = co.totalAmount;
        d.frozenDto          = co.frozenDto;
        d.createdAt          = co.enteredCostingAt;
        d.reviewedAt         = co.reviewedAt;
        return d;
    }
}
