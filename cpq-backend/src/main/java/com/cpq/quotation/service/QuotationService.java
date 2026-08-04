package com.cpq.quotation.service;

import com.cpq.common.PrecisionPolicy;
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
import com.cpq.product.entity.Product;
import com.cpq.quotation.dto.CreateQuotationRequest;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.entity.*;
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
    DerivedAttributeCalculatorV5 derivedAttributeCalculatorV5;

    @Inject
    SnapshotCollectorService snapshotCollectorService;

    @Inject
    com.cpq.quotation.service.rowkey.RowKeyUniquenessService rowKeyUniquenessService;

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

    /** task-0721 B8（2026-07-21 补录，树任务）：反向校验——已有子节点的料号禁止加入材质元素/外购件页签。 */
    @Inject
    QuotationTreeService quotationTreeService;

    /** task-0721 报价升版逻辑 B8（repair-0726 B3 迁移为带引用守卫的 pending 料号回收）：
     *  报价单删除时清理本单 pending 料号（{@link #cleanupPendingV6Data}）。 */
    @Inject
    com.cpq.basicdata.v6.repository.MaterialMasterRepository materialMasterRepository;

    /** task-0721 报价升版逻辑 B5/B6/B7：核价通过两段式回填（preview → approve）。 */
    @Inject
    com.cpq.quotation.service.backfill.QuoteBackfillService quoteBackfillService;

    @Inject
    com.cpq.quotation.service.backfill.QuoteBackfillPreviewService quoteBackfillPreviewService;

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
        java.util.List<com.cpq.quotation.entity.QuotationViewStructure> rows =
                com.cpq.quotation.entity.QuotationViewStructure.list("quotationId", quotationId);
        // 自愈(2026-07-16 QT-2024):选配加产品(configureProduct)时若报价单模板尚未绑定,ensureStructure
        // 建了空 → quotation_view_structure 4 份结构缺失 → 详情页 COSTING(依赖冻结 COSTING_CARD 结构)
        // 显示"暂无组件数据",而编辑页(走实时 componentData)正常。此处按需补建(ensureStructure 幂等,
        // 仅在完全缺失时触发一次;此时模板已绑),自愈存量脏单 + 未来选配单。
        if (rows.isEmpty()) {
            try { cardSnapshotService.ensureStructure(quotationId); } catch (Exception ignore) { /* best-effort,不阻断详情 */ }
            rows = com.cpq.quotation.entity.QuotationViewStructure.list("quotationId", quotationId);
        }
        for (com.cpq.quotation.entity.QuotationViewStructure s : rows) {
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
     * task-0729: 模板绑定的服务层不变量（存在 / 类型 / 状态），create / saveDraft / copy 三入口共用。
     * 语义（已裁定，逐字实现，不得改，见 dev-docs/task-0729-模板绑定状态校验/需求与实现计划.md §二）：
     *   1. templateId == null        → 直接返回（保持既有"null 不覆盖"语义，不是绑定动作）
     *   2. 模板不存在                 → 400
     *   3. 模板类型 != expectedKind   → 400
     *   4. templateId == currentValue → 放行（维持原绑定，不是新绑定 —— 归档模板的历史草稿单仍需能 saveDraft）
     *   5. 模板 status != PUBLISHED  → 400
     *
     * @param templateId   待绑定的模板 id（null 表示本次调用未传该字段，不触发校验）
     * @param expectedKind 期望的 template_kind（"QUOTATION" / "COSTING"）；模板本身 kind 为空按 QUOTATION 兜底
     *                     （对齐 TemplateService 新建默认值口径）
     * @param currentValue 该字段在库内/源单上的当前值，用于豁免"值未变化"的重复绑定（§2.2）
     */
    private void validateTemplateBinding(UUID templateId, String expectedKind, UUID currentValue) {
        if (templateId == null) return;
        com.cpq.template.entity.Template tpl = com.cpq.template.entity.Template.findById(templateId);
        if (tpl == null) {
            throw new BusinessException(400, "模板不存在：templateId=" + templateId);
        }
        String actualKind = (tpl.templateKind == null || tpl.templateKind.isBlank()) ? "QUOTATION" : tpl.templateKind;
        if (!expectedKind.equals(actualKind)) {
            throw new BusinessException(400,
                    "模板类型不匹配：templateId=" + templateId + "，期望 templateKind=" + expectedKind + "，实际=" + actualKind);
        }
        if (templateId.equals(currentValue)) {
            // 维持原绑定，不是新绑定 —— 豁免状态校验（防止模板归档后历史单据被锁死，§2.2）
            return;
        }
        if (!"PUBLISHED".equals(tpl.status)) {
            throw new BusinessException(400,
                    "模板未发布，无法绑定：templateId=" + templateId + "，期望 status=PUBLISHED，实际=" + tpl.status);
        }
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
        // task-0729: 新建无原值 currentValue=null ⇒ 一律严格校验 存在/类型=QUOTATION/status=PUBLISHED。
        if (request.customerTemplateId != null) {
            validateTemplateBinding(request.customerTemplateId, "QUOTATION", null);
            q.customerTemplateId = request.customerTemplateId;
        }
        // task-0729: 建单时的产品分类落库，前端传什么存什么，不做二次推导（守 D4）。
        q.productCategoryId = request.categoryId;
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
        // task-0729: 新建无原值 currentValue=null ⇒ 一律严格校验 存在/类型=COSTING/status=PUBLISHED；
        // 行为变更（§2.4）：查不到模板原为 warn+静默忽略，现改为 400（静默忽略会让用户误以为绑上了）。
        if (request.costingTemplateId != null) {
            validateTemplateBinding(request.costingTemplateId, "COSTING", null);
            q.costingCardTemplateId = request.costingTemplateId;
            q.persist();
            LOG.infof("Quotation %s bound costing card template %s", q.id, request.costingTemplateId);
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
        // task-0729: currentValue 取库内现值 —— 维持原绑定(值未变)豁免状态校验，防止模板归档后
        // 历史草稿单无法保存（§2.2 防回归核心；前端每次保存都会回传同一个 templateId）。
        if (request.customerTemplateId != null) {
            validateTemplateBinding(request.customerTemplateId, "QUOTATION", q.customerTemplateId);
            q.customerTemplateId = request.customerTemplateId;
        }
        if (request.costingCardTemplateId != null) {
            validateTemplateBinding(request.costingCardTemplateId, "COSTING", q.costingCardTemplateId);
            q.costingCardTemplateId = request.costingCardTemplateId;
        }
        // task-0729: 仅当非 null 才覆盖 — null 不清空已存分类（旧前端/异常 payload 兜底）。
        if (request.categoryId != null) q.productCategoryId = request.categoryId;

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
                    // D-1 失效(lazy-cardvalues):本行子表(snapshot_rows)被重建 → 旧卡片值过期,置 NULL,
                    // 使 ensureCardValues 的 IS NULL 谓词下次重新选中、用最新 snapshot_rows 重算。
                    li.quoteCardValues = null;
                    li.costingCardValues = null;
                    newIdsByIndex[i] = li.id;  // V169 二阶段父子关系重建用

                    // task-0723 B3: 料号版本族整族下线 — 原 S5 块拷贝 mat_customer_part_mapping.current_version
                    // → part_version_locked，该表 current_version 从未被真实升版（恒 2000），已随
                    // PartVersionService/import-session 一并退役。part_version_locked 列保留但不再写入。

                    // 通过 Product 查询 HF 料号，同步快照列
                    if (liDraft.productId != null) {
                        Product product = Product.findById(liDraft.productId);
                        if (product != null && product.partNo != null) {
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
                    // task-0712 缺口1 遗留涟漪修复: process_no 全链贯通(与 ConfigureProductService.
                    // insertQuotationLineProcesses 同口径), 取代旧 process_id(process V4 UUID) 写法。
                    if (liDraft.processNos != null) {
                        for (String processNo : liDraft.processNos) {
                            QuotationLineProcess lp = new QuotationLineProcess();
                            lp.lineItemId = li.id;
                            lp.processNo = processNo;
                            lp.persist();
                        }
                    }

                    // 导入来源行:无用户工序时,从该料号基础工序(material_bom_item.operation_no)
                    // seed 本行 quotation_line_process(operation_no 即 process_no),使 [选配-工序列表]
                    // 与选配产品渲染一致。仅 seedProcessesFromBase=true 的导入行触发(选配路径不设,保持"没选=空")。
                    boolean noProcs = (liDraft.processNos == null || liDraft.processNos.isEmpty());
                    if (noProcs && Boolean.TRUE.equals(liDraft.seedProcessesFromBase)
                            && li.productPartNoSnapshot != null && !li.productPartNoSnapshot.isBlank()) {
                        try {
                            Object ccObj = em.createNativeQuery("SELECT code FROM customer WHERE id = :cid")
                                    .setParameter("cid", q.customerId)
                                    .getResultStream().findFirst().orElse(null);
                            if (ccObj != null) {
                                // process_master 取代 process(V4, 冻结快照) 作 JOIN 目标: 选配落库的
                                // 孤儿工序(如 TP10)只进 process_master, 不进 process(V4), 若仍 JOIN 旧表
                                // 会漏 seed(F9, 见 ConfigureProductService#resolveProcessCodes 注释)。
                                em.createNativeQuery(
                                        "INSERT INTO quotation_line_process (id, line_item_id, process_no) " +
                                        "SELECT gen_random_uuid(), :lid, pm.process_no FROM (" +
                                        "  SELECT DISTINCT operation_no FROM material_bom_item " +
                                        "  WHERE system_type='QUOTE' AND customer_no=:cc AND material_no=:part " +
                                        "    AND characteristic='ASSEMBLY' AND operation_no IS NOT NULL AND is_current = true" +
                                        ") ops JOIN process_master pm ON pm.process_no = ops.operation_no")
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
                    // task-0721 B8（2026-07-21 补录）：收集本行"材质元素/外购件"类型页签的行数据，
                    // 待本行全部 componentData（含树页签自身）落库+flush 后再校验，避免同一 line 内
                    // 原生查询（buildHitContext）读到"部分 componentData 已 persist、部分还没"的中间态
                    // ——尤其树页签若排在数组靠后位置，此刻它的 snapshot_rows 还未回填。
                    List<Object[]> pendingRestrictedChecks = new ArrayList<>();
                    if (liDraft.componentData != null) {
                        for (int j = 0; j < liDraft.componentData.size(); j++) {
                            SaveDraftRequest.ComponentDataDraft cdDraft = liDraft.componentData.get(j);
                            QuotationLineComponentData cd = new QuotationLineComponentData();
                            cd.lineItemId = li.id;
                            cd.componentId = cdDraft.componentId;
                            cd.tabName = cdDraft.tabName;
                            if (cdDraft.rowData != null && cdDraft.componentId != null) {
                                pendingRestrictedChecks.add(new Object[]{ cdDraft.componentId, cdDraft.rowData });
                            }
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
                    // task-0721 B8：本行 componentData 全部落库后再校验（flush 保证 QuotationTreeService
                    // 的原生查询能读到刚 persist 的行，含树页签 snapshot_rows）。命中即 400 中止整次 saveDraft。
                    if (!pendingRestrictedChecks.isEmpty()) {
                        em.flush();
                        for (Object[] pending : pendingRestrictedChecks) {
                            quotationTreeService.assertCanAddRowsToRestrictedTab(
                                    (UUID) pending[0], (String) pending[1], li.id);
                        }
                    }
                }

                // 删除本次 payload 未保留的旧行(用户删除的产品行) + 其子表
                for (QuotationLineItem ex : existingLines) {
                    if (keptIds.contains(ex.id)) continue;
                    clearLineItemChildren(ex.id);
                    ex.delete();
                }

                // task-0801 B4/B5：除法中间精度 4→12（PrecisionPolicy.DIVISION_SCALE），
                // 落库边界（originalAmount/totalAmount）统一 PrecisionPolicy.round() 规整到 6 位。
                q.originalAmount = PrecisionPolicy.round(total);
                q.totalAmount = PrecisionPolicy.round(total.multiply(q.finalDiscountRate)
                        .divide(new BigDecimal("100"), PrecisionPolicy.DIVISION_SCALE, RoundingMode.HALF_UP));

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

            } // end per-row path
        }

        q.persist();
        LOG.infof("Saved draft for quotation id=%s", id);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);

        return dto;
    }

    @Transactional
    public QuotationDTO calculateDiscount(UUID id, BigDecimal originalAmount) {
        Quotation q = Quotation.findById(id);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + id);
        }

        DiscountResult result = discountCalculationService.calculate(q.customerId, originalAmount);
        // task-0801 B5：落库边界统一 PrecisionPolicy.round() 规整到 6 位。
        q.originalAmount = PrecisionPolicy.round(originalAmount);
        q.systemDiscountRate = result.discountRate;
        if (!Boolean.TRUE.equals(q.isManuallyAdjusted)) {
            q.finalDiscountRate = result.discountRate;
        }
        // task-0801 B4：除法中间精度 4→12（PrecisionPolicy.DIVISION_SCALE）。
        q.totalAmount = PrecisionPolicy.round(originalAmount.multiply(q.finalDiscountRate)
                .divide(new BigDecimal("100"), PrecisionPolicy.DIVISION_SCALE, RoundingMode.HALF_UP));

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
                // repair-0727 B5.1：补 deletedRowKeys（行墓碑），供 collectConflicts 判重前过滤已删行（P0）
                comps.add(new com.cpq.quotation.service.rowkey.RowKeyUniquenessService.CompRows(
                    cd.componentId.toString(), cd.snapshotRows, cd.rowData, cd.deletedRowKeys));
            }
            // repair-0727 B5.1：补 li.deletedTreeNodes（剪枝墓碑），供 collectConflicts 判重前过滤已剪枝节点
            rowsForCheck.add(new com.cpq.quotation.service.rowkey.RowKeyUniquenessService.LineItemComps(
                li.id.toString(), productName, li.productPartNoSnapshot, li.deletedTreeNodes, comps));
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
        // task-0801 B5：落库边界 —— PrecisionPolicy.round() 规整到 6 位（原 setScale(4)）。
        q.totalAmount = PrecisionPolicy.round(lineSum);

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

    /**
     * 兼容/内部入口：不校验 previewToken，直接回填（既有测试/内部调用点沿用此签名，零回归）。
     * <b>真实 API 表面</b>是下方 4 参重载（{@code previewToken} 必填，见 api.md §1.2），由
     * {@code QuotationResource} 独家调用，强制走"先预览再提交"两段式。
     */
    @Transactional
    public QuotationDTO costingApprove(UUID id, String comment, UUID currentUserId) {
        return doCostingApprove(id, comment, currentUserId);
    }

    /**
     * task-0721 报价升版逻辑 B5/B6：两段式核价通过（api.md §1.2）。
     * @param previewToken 必填；重算当前有效状态 hash 与之比对，不一致 → 409（预览后数据漂移）。
     */
    @Transactional
    public QuotationDTO costingApprove(UUID id, String comment, UUID currentUserId, String previewToken) {
        if (previewToken == null || previewToken.isBlank()) {
            throw new BusinessException(400, "previewToken 缺失，请先调用回填影响预览接口");
        }
        if (!quoteBackfillPreviewService.verifyToken(id, previewToken)) {
            throw new BusinessException(409, "报价数据在预览后发生变化，请重新预览");
        }
        return doCostingApprove(id, comment, currentUserId);
    }

    private QuotationDTO doCostingApprove(UUID id, String comment, UUID currentUserId) {
        Quotation q = Quotation.findById(id);
        if (q == null) throw new BusinessException(404, "Quotation not found: " + id);
        if (!"SUBMITTED".equals(q.status)) throw new BusinessException(400, "仅待核价(SUBMITTED)可核价通过");
        if (!isFinanceOrAdmin(currentUserId)) throw new BusinessException(403, "仅财务/管理员可核价");

        // task-0721 B5：回填 7 张表升版 + B9 主档促升 + B7 占号表闸门翻转 + 本单 pending 残留清理，
        // 与状态机翻转同一事务（失败整体回滚，报价单保持 SUBMITTED，pending 保留可重试，backtask B5.4）。
        com.cpq.quotation.service.backfill.QuoteBackfillService.Summary backfillSummary =
            quoteBackfillService.execute(id, currentUserId);

        q.status = "APPROVED";
        CostingOrder coApprove = CostingOrder.findActiveByQuotation(id);
        if (coApprove != null) {
            coApprove.status = "APPROVED";
            coApprove.reviewedBy = currentUserId;
            coApprove.reviewedAt = java.time.OffsetDateTime.now();
        }
        writeApproval(id, currentUserId, "COSTING_APPROVED", comment);
        LOG.infof("Costing approved quotation id=%s by=%s backfill(groups=%d,added=%d,deleted=%d,changed=%d)",
            id, currentUserId, backfillSummary.versionedGroups, backfillSummary.addedRows,
            backfillSummary.deletedRows, backfillSummary.changedRows);
        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        dto.backfill = backfillSummary;
        return dto;
    }

    /**
     * task-0721 B8 状态机：驳回<b>不清理</b>本单 pending 行/pending 料号——销售改完重交（再次导入）时
     * 由 {@code QuoteImportService}/各 Q*Handler 的"同 pending_quotation_id 先清后写"逻辑覆盖旧
     * pending（B2 已含），驳回本身只是状态流转，无需在此额外处理 V6 数据。
     */
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

    /**
     * task-0721 B8 状态机：撤回<b>不回滚</b>已回填的 V6 数据（需求说明 §4.3 规则七"撤回已通过：不回滚
     * （避免抽走下游已引用的新版本数据）"）。已 APPROVED 撤回时，B5 的回填早已在核价通过那一刻完成，
     * 7 张表 pending 行也已清理，此处除了状态流转（回 DRAFT）外，故意不做任何 V6 层面的补偿/逆操作。
     */
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
     * 复制报价单。templateId 非空且与源单模板不同 → 换模板：新单 customerTemplateId=templateId，
     * 行项目页签按新模板重建，仅迁移用户输入值(INPUT 类型，按字段名)，driver/公式由新模板重算，
     * 金额/值快照/结构快照占位待编辑或重算回填。
     * templateId 为空或等于源单模板 → 同模板复制（repair-0729）：值快照整份继承（单据头金额、
     * 行级 4 份值快照 + 折扣明细、组件数据 row_data/snapshot_rows/subtotal），显式跳过
     * refreshQuoteCardValues/refreshCostingCardValues 重算——新单 id 下查不到源单私有 pending
     * 数据，重算会把刚继承的正确值刷成空（本 bug 根因 A）。
     */
    @Transactional
    public QuotationDTO copy(UUID id, UUID templateId) {
        Quotation source = Quotation.findById(id);
        if (source == null) throw new BusinessException(404, "Quotation not found: " + id);

        UUID newTemplateId = (templateId != null) ? templateId : source.customerTemplateId;
        // task-0729: currentValue = source.customerTemplateId —— 同模板复制(含 templateId==null)天然
        // 相等 ⇒ 豁免状态校验(源模板已归档也能复制)；换模板 ⇒ 严格要求新模板 PUBLISHED。
        // costingCardTemplateId 在下方 :=source.costingCardTemplateId 是延续既有绑定而非新绑定，不加校验。
        validateTemplateBinding(newTemplateId, "QUOTATION", source.customerTemplateId);
        // repair-0729: 同模板复制 = 值快照整份继承（复制冻结结果而非换 id 重新取数）；
        // 换模板复制保持原「重建」路径不变。单据头 + 行级快照 + 组件数据均按此开关分流。
        boolean sameTemplate = newTemplateId != null && newTemplateId.equals(source.customerTemplateId);

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
        // repair-0729: 同模板复制继承源单金额（值快照整份继承，非重算）；换模板仍按原占位逻辑，
        // 由编辑/重算回填，避免列表显示源模板陈旧金额。
        copy.originalAmount = sameTemplate ? source.originalAmount : BigDecimal.ZERO;
        copy.systemDiscountRate = source.systemDiscountRate;
        copy.finalDiscountRate = source.finalDiscountRate;
        copy.totalAmount = sameTemplate ? source.totalAmount : BigDecimal.ZERO;
        if (sameTemplate) {
            // repair-0729 R2: 冻结结果整份继承，避免"头部金额已继承、税额/折扣理由缺位"的不一致态——
            // 用户不重跑 Step3 直接提交时，submit 权威重算会用缺位值覆盖头部金额。
            // 不继承 referencedVersions(漂移检测基线)/submissionSnapshot/importBatchId/
            // assignedApproverId（语义上本就不该复制，另见 BACKLOG）。
            copy.taxRate = source.taxRate;
            copy.taxAmount = source.taxAmount;
            copy.isManuallyAdjusted = source.isManuallyAdjusted;
            copy.discountAdjustmentReason = source.discountAdjustmentReason;
            copy.remarks = source.remarks;
        }
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
            newLi.systemDiscountRate = srcLi.systemDiscountRate;
            newLi.finalDiscountRate = srcLi.finalDiscountRate;
            newLi.sortOrder = srcLi.sortOrder;
            newLi.customerPartNo = srcLi.customerPartNo;
            newLi.partVersionLocked = srcLi.partVersionLocked;
            newLi.compositeType = srcLi.compositeType;
            // parentLineItemId 稍后重映射。
            // repair-0729: 同模板复制 = 值快照整份继承（含 BOM 树墓碑 deletedTreeNodes，
            // 否则源单已剪掉的枝会在新单复活；含 annualVolume 年用量——它是
            // CostingSubtotalUtil.lineCostingAmount / LineDiscountService 两条金额链路的
            // 乘数，漏继承会让核价总额与行折扣金额静默算成 0，是根因 E 的同族形态；
            // 含折扣明细列，避免"头部金额已继承、行级折扣明细全空"的不一致态）；
            // 换模板复制保持原「留空待重建」逻辑不变。
            if (sameTemplate) {
                newLi.subtotal = srcLi.subtotal;
                newLi.quoteCardValues = srcLi.quoteCardValues;
                newLi.quoteExcelValues = srcLi.quoteExcelValues;
                newLi.costingCardValues = srcLi.costingCardValues;
                newLi.costingExcelValues = srcLi.costingExcelValues;
                newLi.cardSnapshotAt = srcLi.cardSnapshotAt;
                newLi.quoteValuesAt = srcLi.quoteValuesAt;
                newLi.excelViewSnapshot = srcLi.excelViewSnapshot;
                newLi.deletedTreeNodes = srcLi.deletedTreeNodes;
                newLi.annualVolume = srcLi.annualVolume;
                newLi.discountSource = srcLi.discountSource;
                newLi.discountBaseAmount = srcLi.discountBaseAmount;
                newLi.discountRateApplied = srcLi.discountRateApplied;
                newLi.lineDiscountAmount = srcLi.lineDiscountAmount;
                newLi.lineUnitPrice = srcLi.lineUnitPrice;
                newLi.lineFinalPrice = srcLi.lineFinalPrice;
                newLi.lineTotalAmount = srcLi.lineTotalAmount;
                newLi.discountRuleCode = srcLi.discountRuleCode;
                newLi.isManuallyAdjusted = srcLi.isManuallyAdjusted;
                newLi.discountAdjustmentReason = srcLi.discountAdjustmentReason;
            } else {
                newLi.subtotal = BigDecimal.ZERO;
            }
            newLi.persist();
            lineIdMap.put(srcLi.id, newLi.id);
            newItems.add(newLi);

            for (QuotationLineProcess srcP : QuotationLineProcess.<QuotationLineProcess>list("lineItemId = ?1", srcLi.id)) {
                QuotationLineProcess newP = new QuotationLineProcess();
                newP.lineItemId = newLi.id;
                // task-0712 缺口1 遗留涟漪修复: 复制 process_no(权威列); process_id 是遗留列,
                // 新写路径统一不再填(与 ConfigureProductService/saveDraft 同口径)。
                newP.processNo = srcP.processNo;
                newP.persist();
            }

            migrateAndCreateComponentData(srcLi.id, newLi.id, newTabs, sameTemplate);
        }

        // 3. 重映射父子链
        for (int i = 0; i < sourceItems.size(); i++) {
            UUID srcParent = sourceItems.get(i).parentLineItemId;
            if (srcParent != null) newItems.get(i).parentLineItemId = lineIdMap.get(srcParent);
        }

        // 4. 报价侧 4 份快照：换模板 = 重建（driver 重展开 + 合并迁移 row_data 输入 + 重算公式）；
        //    同模板 = repair-0729 值快照整份继承路径，**显式跳过重算**——重算会在新单的 pending
        //    可见域（QuotePendingScope.open(copy.id)）下重查 SQL，把刚继承的正确值刷成空
        //    （本 bug 根因 A：本单私有 pending 数据不迁移，新单 id 下查不到源单 pending）。
        //    不依赖 refreshQuoteCardValues 内部 force=false + cardSnapshotAt!=null 的短路 no-op，
        //    显式跳过避免后人改动短路条件时静默回归。
        if (!sameTemplate) {
            for (QuotationLineItem newLi : newItems) {
                cardSnapshotService.refreshQuoteCardValues(newLi);
            }
            if (copy.costingCardTemplateId != null) {
                cardSnapshotService.refreshCostingCardValues(copy.id);
            }
        }

        // repair-0729 R3: 结构快照补建（quotation_view_structure 4 份）**不在这里做**。
        // ensureStructure 是 @Transactional(REQUIRED)，若在此调用会加入本方法的事务；copy()
        // 刚 persist 的 Quotation/QuotationLineItem 行此刻仍只在本事务内可见，把 ensureStructure
        // 挪到独立事务（哪怕是 REQUIRES_NEW）在 commit 前调用也读不到这些行——必须等本方法的
        // @Transactional 边界提交之后才能安全调用。按 QuotationResource#saveDraft 同一口径
        // （该 Resource 类无 @Transactional），由 QuotationResource#copy 在
        // quotationService.copy(...) 返回（即事务已提交）之后再调用 ensureStructure，
        // 避免 auto-flush 异常污染本方法的事务（污染会导致 copy 整体在 commit 处失败于一个
        // 与复制本身无关的、被吞掉细节的 500）。

        LOG.infof("Copied quotation id=%s -> id=%s number=%s template=%s sameTemplate=%s",
                id, copy.id, copy.quotationNumber, newTemplateId, sameTemplate);
        QuotationDTO dto = QuotationDTO.from(copy);
        dto.lineItems = loadLineItems(copy.id);
        return dto;
    }

    /**
     * 按新模板页签建 QuotationLineComponentData。
     * 换模板复制：row_data 仅迁移 INPUT 字段（先 componentId 后 tabName 配对），snapshot_rows 留空待重建。
     * repair-0729 同模板复制：row_data / snapshot_rows / subtotal 整份继承源页签（值快照继承，
     * 不走 mapInputRowData 的 INPUT 过滤——那会丢掉 __nodeId 等系统列和非 INPUT 值，
     * 也是 BOM 树 spine 数据的唯一来源，BOM 页签不参与实时展开，只从这里读冻结树）。
     */
    private void migrateAndCreateComponentData(UUID srcLineItemId, UUID newLineItemId,
                                               java.util.List<TabFields> newTabs, boolean sameTemplate) {
        List<QuotationLineComponentData> srcData =
                QuotationLineComponentData.list("lineItemId = ?1", srcLineItemId);
        java.util.Map<String, QuotationLineComponentData> byCompId = new java.util.HashMap<>();
        java.util.Map<String, QuotationLineComponentData> byTabName = new java.util.HashMap<>();
        java.util.Set<QuotationLineComponentData> matched = new java.util.HashSet<>();
        for (QuotationLineComponentData cd : srcData) {
            if (cd.componentId != null) {
                // R5 M-2（AP-40 族防御）：同 componentId 多页签是单键映射，后者会覆盖前者。
                // 当前库内 0 例，仅加可观测告警，不改匹配算法——本次把继承范围从"仅 INPUT 值"
                // 扩到整份 snapshot_rows，一旦出现重复 cid，污染面会从个别输入格升级为整页签串号。
                if (byCompId.containsKey(cd.componentId.toString())) {
                    LOG.warnf("copy: lineItemId=%s componentId=%s 对应多个源页签(tabName=%s 与已存在的一份重复)，" +
                                    "按 HashMap 覆盖语义只会继承最后一条，可能丢失页签数据",
                            srcLineItemId, cd.componentId, cd.tabName);
                }
                byCompId.put(cd.componentId.toString(), cd);
            }
            if (cd.tabName != null) byTabName.put(cd.tabName, cd);
        }
        int sort = 0;
        for (TabFields tab : newTabs) {
            QuotationLineComponentData match = byCompId.get(tab.componentId);
            if (match == null) match = byTabName.get(tab.tabName);
            if (match != null) matched.add(match);

            QuotationLineComponentData newCd = new QuotationLineComponentData();
            newCd.lineItemId = newLineItemId;
            newCd.componentId = (tab.componentId == null || tab.componentId.isEmpty())
                    ? null : UUID.fromString(tab.componentId);
            newCd.tabName = tab.tabName;
            if (sameTemplate) {
                // 值快照整份继承：match==null（源单缺该页签）时仍走兜底，不能空指针。
                // R5 M-1：quotation_line_component_data.snapshot_at 是 DB 列但实体未映射，
                // 继承后新行该列为 NULL 而 snapshot_rows 非空——已核实无消费者，审计断链已知，不阻断。
                newCd.rowData = (match == null) ? "[]" : match.rowData;
                newCd.snapshotRows = (match == null) ? null : match.snapshotRows;
                newCd.subtotal = (match == null) ? BigDecimal.ZERO : match.subtotal;
            } else {
                newCd.rowData = (match == null)
                        ? "[]"
                        : mapInputRowData(match.rowData, tab.inputFieldNames, MAPPER);
                newCd.snapshotRows = null;
                newCd.subtotal = BigDecimal.ZERO;
            }
            newCd.sortOrder = sort++;
            // driver 默认行墓碑：同模板复制按 componentId 原样拷贝（源集/effKey/fp 不变,墓碑仍匹配）；
            // 换模板复制清空（换模板后 driver/源集/effKey 全变,旧墓碑必失配 → 会误删新行）。
            newCd.deletedRowKeys = (sameTemplate && match != null && match.deletedRowKeys != null)
                    ? match.deletedRowKeys : "[]";
            newCd.persist();
        }
        // R5 M-3：newTabs（目标模板快照）是主轴，源单独有、目标模板未声明的页签会被静默丢弃。
        // 仅加可观测告警，不改变现有"以模板为准"的行为。
        int unmatchedCount = 0;
        for (QuotationLineComponentData cd : srcData) {
            if (!matched.contains(cd)) unmatchedCount++;
        }
        if (unmatchedCount > 0) {
            LOG.warnf("copy: lineItemId=%s 源单有 %d 个组件页签未被目标模板任何 tab 匹配到，将被静默丢弃",
                    srcLineItemId, unmatchedCount);
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

        try {
            deleteLineItems(id);
            QuotationApproval.delete("quotationId = ?1", id);
            // v4: also clean withdraw requests (no DB cascade) and detach import records (永久保留)
            QuotationWithdrawRequest.delete("quotationId = ?1", id);
            em.createNativeQuery("UPDATE import_record SET quotation_id = NULL WHERE quotation_id = :qid")
                    .setParameter("qid", id)
                    .executeUpdate();
            // task-0721 B8 状态机：报价单删除级联清理本单 pending 数据（未生效过，无保留价值）。
            // 只有 DRAFT 才能走到这（above guard），DRAFT 单可能已导入过、留有 pending 行/pending 料号。
            cleanupPendingV6Data(id);
            // repair-0803（BL-0108）：task-0729 B1 引入的 material_price_update_job_item 表，FK 是
            // NO ACTION 阻塞型，删除序列此前没有覆盖，导致任何被调价 job 扫过的 DRAFT 单裸报 500
            // （FK 违反）。见方法 javadoc。
            cleanupPriceAdjustJobItems(id);
            // costing_sheet has ON DELETE CASCADE (V30) — auto-deleted with quotation
            q.delete();
            // repair-0803（BL-0108 ②）：强制在本方法体内同步 flush，让任何遗留的阻塞型 FK（含上面
            // 未覆盖到的、以及未来任何新增的）在这里同步抛出，而不是被 @Transactional 拦截器
            // 延后到方法返回后的提交阶段才抛——那样 catch 块根本捕获不到，只会冒泡成裸 500。
            em.flush();
            LOG.infof("Deleted quotation id=%s number=%s", id, q.quotationNumber);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw translateDeleteFailure(id, e);
        }
    }

    /**
     * repair-0803（BL-0108 ①，我方责任）：清理 {@code material_price_update_job_item}
     * （task-0729 B1 引入，FK {@code material_price_update_job_item_quotation_id_fkey} 是
     * {@code NO ACTION} 阻塞型）。直接 DELETE——job 明细对已删除的报价单没有保留价值
     * （对应的报价单都没了，{@code retry} 也无处可试）。
     *
     * <p>🔒 顺序与判断：先删本单的 job_item，再检查每个受影响的 {@code job_id} 是否因此变成
     * 空批次（该 job 名下所有 item 都已被删光）——若是，**一并删除该 job**：job 的
     * {@code totalCount}/{@code successCount} 等汇总字段只服务于它名下的 item，item 清零后这些
     * 数字就变成"指向不存在明细的统计"，留着是孤儿审计记录，无查阅价值。若该 job 还有其它 item
     * （常见情况——一次批量升版通常同时覆盖同客户名下的多张报价单），则只删本单的 item，job 与
     * 其余 item 原样保留、不受影响。
     */
    private void cleanupPriceAdjustJobItems(UUID quotationId) {
        @SuppressWarnings("unchecked")
        List<Object> jobIdRows = em.createNativeQuery(
                "SELECT DISTINCT job_id FROM material_price_update_job_item WHERE quotation_id = :qid")
            .setParameter("qid", quotationId)
            .getResultList();
        if (jobIdRows.isEmpty()) return;

        int deletedItems = em.createNativeQuery(
                "DELETE FROM material_price_update_job_item WHERE quotation_id = :qid")
            .setParameter("qid", quotationId)
            .executeUpdate();
        LOG.infof("cleanupPriceAdjustJobItems: quotation=%s 清理 material_price_update_job_item %d 条",
            quotationId, deletedItems);

        for (Object jobIdObj : jobIdRows) {
            UUID jobId = (UUID) jobIdObj;
            long remaining = ((Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM material_price_update_job_item WHERE job_id = :jid")
                .setParameter("jid", jobId)
                .getSingleResult()).longValue();
            if (remaining == 0) {
                em.createNativeQuery("DELETE FROM material_price_update_job WHERE id = :jid")
                    .setParameter("jid", jobId)
                    .executeUpdate();
                LOG.infof("cleanupPriceAdjustJobItems: job=%s 名下 item 已因本次删除清空，一并删除该批次记录（避免孤儿审计行）",
                    jobId);
            }
        }
    }

    /**
     * repair-0803（BL-0108 ②，价值最大的一条）：把外键阻塞的裸异常翻译成可读业务错误。
     *
     * <p>🔒 不依赖具体异常包装类型——Hibernate 的 DELETE 语句何时真正发给数据库不确定
     * （可能在方法体内 flush 时同步执行，也可能被 {@code @Transactional} 拦截器延后到方法
     * 返回后的提交阶段），沿途包装类可能是 {@code ConstraintViolationException} /
     * {@code PersistenceException} / {@code RollbackException} 等，逐层猜类型必然漏。改为
     * 沿 {@code getCause()} 链一路找**根 {@link java.sql.SQLException}**，按 SQLState=23503
     * （{@code foreign_key_violation}，PostgreSQL 标准错误码，与具体 driver/ORM 包装方式无关）
     * 精确判定，再从错误信息里抠出约束名查友好名映射。
     *
     * <p>本方法 {@link #delete} 里额外调用 {@code em.flush()} 就是为了让 FK 检查同步发生在
     * try 块内（不外溢到方法体外的提交阶段），使这里的 catch 真正捕获得到。
     *
     * <p>映射表目前只登记本方法删除序列**已知未覆盖**的两个阻塞型 FK
     * （{@code costing_order} 是 task-0713 引入，本次按 coordinator 裁定不做级联删除，只排查
     * 报告，见 repair-0803 需求文档 §3）——未登记的约束名走通用兜底（报约束名本身），保证
     * **任何**未来新增的阻塞型 FK 都不会再裸 500，而是至少报出约束名可供排查。
     */
    private static final Map<String, String> FK_BLOCKER_FRIENDLY_NAMES = Map.of(
        "costing_order_quotation_id_fkey", "关联的核价单（costing_order）",
        "material_price_update_job_item_quotation_id_fkey", "关联的价格调整任务明细（material_price_update_job_item）"
    );

    private RuntimeException translateDeleteFailure(UUID quotationId, Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.sql.SQLException sqlEx && "23503".equals(sqlEx.getSQLState())) {
                String msg = sqlEx.getMessage();
                String constraint = extractConstraintName(msg);
                String friendly = constraint != null ? FK_BLOCKER_FRIENDLY_NAMES.get(constraint) : null;
                if (friendly == null) {
                    friendly = constraint != null ? ("数据表约束 " + constraint) : "未知的关联数据";
                }
                LOG.warnf("delete quotation=%s 被外键阻塞: constraint=%s msg=%s", quotationId, constraint, msg);
                return new BusinessException(409, "无法删除该报价单：存在" + friendly + "，请先处理后再删除");
            }
            t = t.getCause();
        }
        // 非外键冲突的其它异常：原样上抛，不吞掉（GlobalExceptionMapper 的既有兜底继续生效）
        LOG.errorf(e, "delete quotation=%s 失败（非已知外键阻塞）", quotationId);
        return (e instanceof RuntimeException re) ? re : new RuntimeException(e);
    }

    private static String extractConstraintName(String pgMessage) {
        if (pgMessage == null) return null;
        // Postgres 消息形如: ERROR: update or delete on table "quotation" violates foreign key
        // constraint "xxx_fkey" on table "yyy"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("constraint \"([^\"]+)\"").matcher(pgMessage);
        return m.find() ? m.group(1) : null;
    }

    /**
     * task-0721 B8：清理该报价单在 7 张版本化表 + 占号表 material_customer_map 的全部 pending 行，
     * 以及 material_master 的 pending 料号（repair-0726 B3，带引用守卫）。用于报价单删除（本单从未
     * 生效过，pending 无保留价值）。与 {@code QuoteImportService}/{@code V6QuotationCommitService}
     * 的 pending 表清单同源（8 表字面量重复，见 {@code V6QuotationCommitService.PENDING_TABLES}
     * 注释：分属不同包各自 private，重复的耦合成本低于抽共享工具类）。
     *
     * <p>repair-0726 B3：{@link com.cpq.basicdata.v6.repository.MaterialMasterRepository#deletePendingWithGuard}
     * 的引用守卫刻意排除本单自己的 pending 行（{@code <> :qid}），因此本方法内 8 表 DELETE 与料号回收
     * 的先后顺序<b>不影响正确性</b>——代码保持"先 8 表、后料号"仅为直观，非必需。⚠️ <b>不要移除守卫里的
     * {@code <> :qid}</b>：一旦移除，本单自己的 material_bom_item 就会顶住守卫，届时"先删 8 表、后删
     * 料号"才真正成为铁律。
     */
    private static final java.util.List<String> B8_PENDING_TABLES = java.util.List.of(
        "unit_price", "material_bom", "material_bom_item", "element_bom", "element_bom_item",
        "capacity", "plating_scheme", "material_customer_map");

    private void cleanupPendingV6Data(UUID quotationId) {
        for (String table : B8_PENDING_TABLES) {
            em.createNativeQuery("DELETE FROM " + table + " WHERE pending_quotation_id = :qid")
              .setParameter("qid", quotationId)
              .executeUpdate();
        }
        int deleted = materialMasterRepository.deletePendingWithGuard(quotationId);
        int survivors = materialMasterRepository.listPending(quotationId).size();
        if (survivors > 0) {
            LOG.warnf("cleanupPendingV6Data: material_master pending 引用守卫拦下 %d 条（本次已删 %d 条），"
                + "quotationId=%s（报价单即将被删除）——这些行仍被其它 pending/正式数据引用，"
                + "pending_quotation_id 标记会指向一个已不存在的报价单，需人工核查引用方", survivors, deleted, quotationId);
        }
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
        // task-0801 B4/B5：除法中间精度 4→12（PrecisionPolicy.DIVISION_SCALE），落库边界统一
        // PrecisionPolicy.round() 规整到 6 位。
        q.originalAmount = PrecisionPolicy.round(total);
        if (q.finalDiscountRate != null) {
            q.totalAmount = PrecisionPolicy.round(total.multiply(q.finalDiscountRate)
                    .divide(new BigDecimal("100"), PrecisionPolicy.DIVISION_SCALE, RoundingMode.HALF_UP));
        } else {
            q.totalAmount = PrecisionPolicy.round(total);
        }
        em.flush();

        LOG.infof("recalculate done for quotation=%s lineItems=%d totalAmount=%s",
                id, lineItems.size(), q.totalAmount);

        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = loadLineItems(id);
        return dto;
    }

    // --- Private helpers ---

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
        // E3 收集：需要 seed 工序的 (lineItemId → partNo) 对
        java.util.Map<java.util.UUID, String> seedProcLines = new java.util.LinkedHashMap<>();

        // E4 收集：需要 derivedAttr 计算的行 (lineItem, partNo)
        // 先用对象持有引用，计算后直接写 li.productAttributeValues，循环结束统一 flush
        java.util.List<QuotationLineItem> derivedAttrLines = new java.util.ArrayList<>();
        java.util.List<String> derivedAttrPartNos = new java.util.ArrayList<>();

        BigDecimal total = BigDecimal.ZERO;
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
            // D-1 失效(lazy-cardvalues):本行子表(snapshot_rows)被重建 → 旧卡片值过期,置 NULL,
            // 使 ensureCardValues 的 IS NULL 谓词下次重新选中、用最新 snapshot_rows 重算。
            li.quoteCardValues = null;
            li.costingCardValues = null;
            newIdsByIndex[i] = li.id;

            // Product 查询：填充 productPartNoSnapshot / productNameSnapshot，收集 partNo
            if (liDraft.productId != null) {
                Product product = Product.findById(liDraft.productId);
                if (product != null && product.partNo != null) {
                    li.productPartNoSnapshot = product.partNo;
                    li.productNameSnapshot = product.name;
                    // E4 收集：有 derivedAttrs 的行
                    // 注意：这里只收集，实际计算在循环结束后批量处理
                    derivedAttrLines.add(li);
                    derivedAttrPartNos.add(product.partNo);
                }
            }

            if (liDraft.subtotal != null) total = total.add(liDraft.subtotal);

            // processNos（低频，逐行 persist，无性能收益集合化）
            // task-0712 缺口1 遗留涟漪修复: process_no 全链贯通, 取代旧 process_id(process V4 UUID)。
            if (liDraft.processNos != null) {
                for (String processNo : liDraft.processNos) {
                    QuotationLineProcess lp = new QuotationLineProcess();
                    lp.lineItemId = li.id;
                    lp.processNo = processNo;
                    lp.persist();
                }
            }

            // E3 收集：seedProcessesFromBase 行
            boolean noProcs = (liDraft.processNos == null || liDraft.processNos.isEmpty());
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
                // task-0721 B8（2026-07-21 补录）：同款"先收集、本行落库+flush 后再校验"纪律（见 §2.0 段落
                // 逐行路径同名注释）——避免树页签 snapshot_rows 尚未回填时原生查询读到中间态。
                List<Object[]> pendingRestrictedChecks = new ArrayList<>();
                for (int j = 0; j < liDraft.componentData.size(); j++) {
                    SaveDraftRequest.ComponentDataDraft cdDraft = liDraft.componentData.get(j);
                    QuotationLineComponentData cd = new QuotationLineComponentData();
                    cd.lineItemId = li.id;
                    cd.componentId = cdDraft.componentId;
                    cd.tabName = cdDraft.tabName;
                    if (cdDraft.rowData != null && cdDraft.componentId != null) {
                        pendingRestrictedChecks.add(new Object[]{ cdDraft.componentId, cdDraft.rowData });
                    }
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
                if (!pendingRestrictedChecks.isEmpty()) {
                    em.flush();
                    for (Object[] pending : pendingRestrictedChecks) {
                        quotationTreeService.assertCanAddRowsToRestrictedTab(
                                (UUID) pending[0], (String) pending[1], li.id);
                    }
                }
            }
        } // end main loop

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

                    // process_master 取代 process(V4, 冻结快照) 作 JOIN 目标: 选配落库的孤儿工序
                    // (如 TP10)只进 process_master, 不进 process(V4)(F9)。
                    em.createNativeQuery(
                            "INSERT INTO quotation_line_process (id, line_item_id, process_no) " +
                            "SELECT gen_random_uuid(), kv.lid::uuid, pm.process_no " +
                            "FROM ( " +
                            "  SELECT unnest(CAST(:lids AS text[]))::uuid AS lid, unnest(CAST(:parts AS text[])) AS part_no " +
                            ") kv " +
                            "JOIN ( " +
                            "  SELECT DISTINCT material_no, operation_no FROM material_bom_item " +
                            "  WHERE system_type='QUOTE' AND customer_no=:cc " +
                            "    AND characteristic='ASSEMBLY' AND operation_no IS NOT NULL AND is_current = true " +
                            "    AND material_no = ANY(CAST(:parts_arr AS text[])) " +
                            ") bom ON bom.material_no = kv.part_no " +
                            "JOIN process_master pm ON pm.process_no = bom.operation_no")
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
        // task-0801 B4/B5：除法中间精度 4→12（PrecisionPolicy.DIVISION_SCALE），
        // 落库边界（originalAmount/totalAmount）统一 PrecisionPolicy.round() 规整到 6 位。
        q.originalAmount = PrecisionPolicy.round(total);
        q.totalAmount = PrecisionPolicy.round(total.multiply(q.finalDiscountRate)
                .divide(new BigDecimal("100"), PrecisionPolicy.DIVISION_SCALE, RoundingMode.HALF_UP));

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

    // task-0723 B5: V5/import-session 死链路退役 — reimportBasicData 已删除
    // (依赖的 BasicDataImportServiceV5 已整体退役, basicdata.v6 是唯一正式导入路径)。

    private List<QuotationDTO.LineItemDTO> loadLineItems(UUID quotationId) {
        List<QuotationLineItem> items = QuotationLineItem.list("quotationId = ?1 ORDER BY sortOrder ASC", quotationId);
        if (items.isEmpty()) return List.of();

        // task-0723 B2: 一次性按 (customer.code, material_no) 批量查 V6 material_customer_map，避免 N+1。
        // customerId 来自 quotation；hf_part_no 列表来自 lineItems 的 product_part_no_snapshot
        // 或 product 表反查。前端"客户视角"展示这两个字段（PRD：产品卡片显示客户料号名称 + 客户产品编号）
        // 全键严格匹配 (customer.code, material_no)，不做仅料号降级（防跨客户串号，见需求说明 Q5）。
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
                    "SELECT v.material_no, v.customer_material_name, v.customer_product_no, v.customer_drawing_no " +
                    "FROM material_customer_map v JOIN customer c ON c.code = v.customer_no " +
                    "WHERE c.id = :cid AND v.material_no IN (:pns)")
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

    // task-0723 B3: 料号版本族整族下线 — updateLineItemPartVersion 已删除
    // (part_version_locked 138/138=2000 从未生效, mat_part_version_log 随
    // PartVersionService 一并退役; part_version_locked 列保留但不再可写)。

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

    // Phase 1(2026-07-14 删错行修复)：本方法只在独立事务里写墓碑并提交（tx1）；重算+物化+投影由 Resource
    // 随后单独调 cardSnapshotService.refreshQuoteProjection(tx2)，读已提交墓碑。拆两事务是因为：同事务里
    // 「先写托管 cd 墓碑 → 再 em.clear() 重读」会把未 flush 的墓碑写丢/或 flush 失败标记 tx rollback-only → 整单回滚。
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
        // 关键：flush 把墓碑写库。refreshQuoteProjection 的 loadTombstonesByComp 用原生 SQL 读 deleted_row_keys,
        // 原生查询不触发 Hibernate auto-flush → 不 flush 会读到空墓碑 → assemble/materialize 都不过滤(删不掉行)。
        cd.persistAndFlush();
        // 只写墓碑。重算+物化+投影交给 Resource 调 refreshQuoteProjection。
    }

    @Transactional
    public void restoreAllDriverRows(UUID lineItemId, UUID componentId) {
        QuotationLineComponentData cd = QuotationLineComponentData
            .find("lineItemId = ?1 and componentId = ?2", lineItemId, componentId).firstResult();
        if (cd == null) return;
        cd.deletedRowKeys = "[]";
        cd.persistAndFlush();  // 同 deleteDriverRow：flush 墓碑写库,使后续原生查询读到清空后的墓碑。
        // 只清墓碑。投影交给 Resource 调 refreshQuoteProjection。
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

        // ── task-0713 B5：核价侧渲染缓存 + 单据总价 + override 列表 + editable ──────────
        // 打开永远读缓存，不 on-open 重算（守 BL-0010）：costing_render/costing_total_amount
        // 直接读列，不触发任何 V6 查询/expand。
        if (co.costingRender != null && !co.costingRender.isBlank()) {
            try {
                d.costingRender = MAPPER.readTree(co.costingRender);
            } catch (Exception e) {
                LOG.warnf("[costing-order] costingRender 解析失败 coid=%s: %s", co.id, e.getMessage());
            }
        }
        d.costingTotalAmount = co.costingTotalAmount;
        java.util.List<com.cpq.quotation.entity.CostingOrderVersionOverride> overrides =
                com.cpq.quotation.entity.CostingOrderVersionOverride.findByCostingOrder(co.id);
        d.versionOverrides = new java.util.ArrayList<>();
        for (com.cpq.quotation.entity.CostingOrderVersionOverride ov : overrides) {
            d.versionOverrides.add(new com.cpq.quotation.dto.CostingOrderDetailDTO.VersionOverrideItem(
                    ov.componentId.toString(), ov.partNo, ov.viewVersion));
        }
        // 角色已由 CostingOrderResource 的 @RoleAllowed({"PRICING_MANAGER","SYSTEM_ADMIN"}) 端点门禁保证，
        // 此处只需判断状态。
        d.editable = "PENDING".equals(co.status);
        return d;
    }
}
