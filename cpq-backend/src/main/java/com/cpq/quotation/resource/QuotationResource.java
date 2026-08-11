package com.cpq.quotation.resource;

import com.cpq.common.DecimalRequestValidator;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.quotation.dto.CreateQuotationRequest;
import com.cpq.quotation.dto.ExcelDryRunRequest;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.service.ExcelViewService;
import com.cpq.quotation.service.QuotationEmailService;
import com.cpq.quotation.service.QuotationExportService;
import com.cpq.quotation.service.CustomerPartCandidateService;
import com.cpq.quotation.service.QuotationService;
import com.cpq.quotation.dto.CustomerPartCandidateDTO;
import java.util.HashMap;
import java.util.List;
import com.cpq.quotation.snapshot.FieldTraceDTO;
import com.cpq.system.entity.User;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/quotations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class QuotationResource {

    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(QuotationResource.class);

    @Inject
    QuotationService quotationService;

    @Inject
    QuotationExportService exportService;

    @Inject
    QuotationEmailService emailService;

    @Inject
    ExcelViewService excelViewService;

    @Inject
    SessionHelper sessionHelper;

    @Inject
    CustomerPartCandidateService candidateService;

    /** task-0806 阶段① B1-2/B1-3：对账差异埋点落点（API-5 写入、submit 闸门 B1-1 读取）。 */
    @Inject
    com.cpq.quotation.service.reconcile.ReconcileDiffStore reconcileDiffStore;

    // 加产品整份快照 Phase 2:saveDraft 全量重建后按新行重快照(UPSERT 保留编辑层 row_data)
    @Inject
    com.cpq.configure.service.ConfigureSnapshotService snapshotService;

    /** task-0729 B10：价格列归位 + 初版 R 占位行钩子，与 saveDraft 同一条链路。 */
    @Inject
    com.cpq.priceadjust.service.PriceReconciler priceReconciler;

    // 报价单整份快照 Phase 1: 4 份结构 + 行级 4 份值
    @Inject
    com.cpq.quotation.service.CardSnapshotService cardSnapshotService;

    @Inject
    jakarta.persistence.EntityManager em;

    /** task-0721 报价升版逻辑 B6：核价通过两段式——预览。 */
    @Inject
    com.cpq.quotation.service.backfill.QuoteBackfillPreviewService quoteBackfillPreviewService;

    @GET
    public ApiResponse<PageResult<QuotationDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("status") String status,
            @QueryParam("salesRepId") UUID salesRepId,
            @QueryParam("assignedApproverId") UUID assignedApproverId,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(quotationService.list(page, size, status, salesRepId, assignedApproverId, keyword));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<QuotationDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(quotationService.getById(id));
    }

    @POST
    public ApiResponse<QuotationDTO> create(@Valid CreateQuotationRequest request, @Context HttpServerRequest httpRequest) {
        UUID salesRepId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        return ApiResponse.success(quotationService.create(request, salesRepId));
    }

    /**
     * Step2 "批量从基础数据导入产品" 候选列表 — 列出该客户可加入报价单的所有料号
     * (客户专属 mapping + 全局 mat_part)
     */
    @GET
    @Path("/customer-part-candidates")
    public ApiResponse<List<CustomerPartCandidateDTO>> listCustomerPartCandidates(
            @QueryParam("customerId") UUID customerId,
            @QueryParam("importRecordId") UUID importRecordId) {
        if (customerId == null) {
            return ApiResponse.error(400, "customerId 不能为空");
        }
        return ApiResponse.success(candidateService.listCandidates(customerId, importRecordId));
    }

    @PUT
    @Path("/{id}/draft")
    public ApiResponse<QuotationDTO> saveDraft(@PathParam("id") UUID id, SaveDraftRequest request) {
        validateDraftDecimals(request);
        // [draft-profile] 分段埋点(2026-06-26):S1 saveDraft(全删全建+落库) / S2 snapshotQuotation(snapshot_rows)。
        //   卡片值不再在保存路径计算(已迁至 lazy ensureCardValues);重建行的旧卡片值由 saveDraft 内置 D-1 失效置 NULL,
        //   下次 ensureCardValues 的 IS NULL 谓词会重新选中并用最新 snapshot_rows 重算。日志前缀 [draft-profile] 便于过滤。
        long _p0 = System.nanoTime();
        QuotationDTO dto = quotationService.saveDraft(id, request);
        long _s1 = (System.nanoTime() - _p0) / 1_000_000;
        // 自愈根治(2026-07-16 QT-2024):saveDraft 已绑定报价单模板(customer/costing_card_template_id)并提交。
        // 选配加产品(configureProduct)时其 ensureStructure 可能早于模板绑定(三模板按产品分类轴自动匹配) → 建了空;
        // 此处模板已 flush,幂等补建缺失的 quotation_view_structure(4 份结构),否则详情页 COSTING 依赖冻结
        // COSTING_CARD 结构 → "暂无组件数据"(编辑页走实时 componentData 不受影响)。best-effort,不阻断保存。
        try { cardSnapshotService.ensureStructure(id); } catch (Exception ignore) { /* 结构快照尽力而为 */ }
        // saveDraft 已提交,按新行重快照(降级:失败不影响保存)
        long _p1 = System.nanoTime();
        try {
            snapshotService.snapshotQuotation(id, true);  // 增量: 复用行已回写 snapshot_rows → 跳过全量重 expand
        } catch (Exception ignore) {
            // 快照尽力而为
        }
        long _s2 = (System.nanoTime() - _p1) / 1_000_000;

        // task-0729 B10：价格列归位。🔒 插入位置严格不可变通——必须在 row_data 落库/snapshot_rows
        // 重建【之后】、quoteCardValues 的懒重算【之前】：在前会被前端提交值覆盖，在后卡片值算的
        // 是旧价。quoteCardValues 已在 quotationService.saveDraft 内部置 NULL（D-1 失效），本步只
        // 需保证归位发生在 ensureCardValues 真正读取 snapshot_rows 之前——两者天然满足（ensureCardValues
        // 是后续独立请求才触发的懒计算），故归位放在此处（snapshotQuotation 之后）即满足时序要求。
        long _p2 = System.nanoTime();
        try {
            priceReconciler.ensureInitialRevisionPlaceholder(id); // §11.10.6：建单(首次保存且已有产品行)懒建未定型初版
            priceReconciler.reconcileQuotation(id);
        } catch (Exception e) {
            // 归位尽力而为，不阻断保存（同 snapshotService 的既定容错纪律）；失败下次 saveDraft 仍会重试，幂等。
            LOG.warnf("[price-reconcile] saveDraft id=%s 归位失败（不阻断保存）: %s", id, e.getMessage());
        }
        long _s3 = (System.nanoTime() - _p2) / 1_000_000;

        // 卡片值(quote/costing card values)不再在保存路径计算 —— 原 Phase1 块含 jsonb 上的 btrim(quote_card_values)
        // 原生查询会在解析期抛异常, 被 catch (Exception ignore) 吞掉 → 卡片值从未落库 → 前端打开时风暴。
        // 现已迁至 lazy ensureCardValues(IS NULL 谓词选中、用最新 snapshot_rows 重算)。saveDraft 重建行时
        // 已对被重建行的旧卡片值置 NULL(D-1 失效, 见 QuotationService), 故此处无需任何卡片值计算。
        LOG.debugf("[draft-profile] id=%s total=%dms | S1.saveDraft=%dms S2.snapshotRows=%dms S3.priceReconcile=%dms",
                id, _s1 + _s2 + _s3, _s1, _s2, _s3);
        return ApiResponse.success(dto);
    }

    /**
     * 草稿态重刷报价侧卡片值快照（报价单整份快照 Phase 2 §5）。
     * 仅 DRAFT 执行（遍历报价行重 expand + 按行键保编辑 + 重算）；非 DRAFT no-op 返 refreshed=0。
     * 前端 Step2「刷新基础数据」按钮显式触发（2026-06-18 草稿默认冻结后不再 on-open 自动调用），再 getById 拿最新快照渲染。
     */
    @POST
    @Path("/{id}/refresh-card-snapshot")
    public ApiResponse<Map<String, Object>> refreshCardSnapshot(@PathParam("id") UUID id) {
        int refreshed = cardSnapshotService.refreshDraftQuoteCards(id);
        Map<String, Object> resp = new HashMap<>();
        resp.put("quotationId", id);
        resp.put("refreshed", refreshed);
        return ApiResponse.success(resp);
    }

    /**
     * P3 lazy-excel:懒算并落库整单 Excel 值(quoteExcelValues/costingExcelValues)。
     * 首存只算卡片值、Excel 值留 NULL;前端开「Excel 视图」/导出前调本端点补算(幂等,已算的零开销)。
     * 返回补算后的最新 DTO(含 Excel 值),前端据此渲染 Excel 视图。
     */
    @POST
    @Path("/{id}/ensure-excel-values")
    public ApiResponse<QuotationDTO> ensureExcelValues(@PathParam("id") UUID id) {
        int computed = cardSnapshotService.ensureExcelValues(id);
        // 落库后清 L1 取最新(同 saveDraft 路径的一级缓存纪律)
        if (computed > 0) {
            try { em.clear(); } catch (Exception ignore) { /* 尽力 */ }
        }
        return ApiResponse.success(quotationService.getById(id));
    }

    /** P3 lazy-cardvalues：懒算并落库整单卡片值（quote/costing card values）。warm 与打开兜底复用。 */
    @POST
    @Path("/{id}/ensure-card-values")
    public ApiResponse<QuotationDTO> ensureCardValues(@PathParam("id") UUID id) {
        cardSnapshotService.ensureStructure(id);
        int r = cardSnapshotService.ensureCardValues(id);
        if (r == com.cpq.quotation.service.CardSnapshotService.WARMING_IN_PROGRESS) {
            // warm 在飞（未取到单飞锁）：返回轻量 warming 状态，不阻塞、不重算
            QuotationDTO dto = new QuotationDTO();
            dto.cardValuesWarming = true;
            return ApiResponse.success(dto);
        }
        em.clear();                          // 驱逐陈旧 L1，让 getById 读新值
        return ApiResponse.success(quotationService.getById(id));
    }

    /**
     * 编辑回写报价卡片单元格（报价单整份快照 Phase 2 §6，替代旧 autosave 写 row_data）。
     * body: {componentId, rowKey, fieldName, value}。写 editRows + 重算 formulaResults/报价 Excel；核价不动。
     * 仅 DRAFT 可编辑；非 DRAFT → 400。返回更新后的 quoteCardValues/quoteExcelValues 供前端就地刷新（AP-50）。
     */
    @PUT
    @Path("/line-items/{lineItemId}/quote-card-edit")
    public ApiResponse<Map<String, Object>> editQuoteCardValue(
            @PathParam("lineItemId") UUID lineItemId, Map<String, Object> body) {
        if (body == null) throw new com.cpq.common.exception.BusinessException(400, "请求体不能为空");
        DecimalRequestValidator.rejectNumericTokens(body.get("value"), "value");
        DecimalRequestValidator.rejectNumericTokens(body.get("rowData"), "rowData");
        Object componentId = body.get("componentId");
        Object rowKey = body.get("rowKey");
        Object fieldName = body.get("fieldName");
        if (componentId == null || rowKey == null || fieldName == null) {
            throw new com.cpq.common.exception.BusinessException(400, "componentId/rowKey/fieldName 不能为空");
        }
        Map<String, Object> result = cardSnapshotService.editCardValue(
            lineItemId, componentId.toString(), rowKey.toString(), fieldName.toString(), body.get("value"));
        if (result == null) {
            throw new com.cpq.common.exception.BusinessException(400, "编辑失败：非草稿态或数据缺失");
        }
        return ApiResponse.success(result);
    }

    /**
     * task-0806 阶段① API-5：对账差异埋点（fire-and-forget，D1 只记录不改数据）。
     * 前端把本轮对账发现的差异上报；本端点只落 WARN 日志 + 写进程内差异 Map（B1-3，
     * 供 {@code submit} 的提交闸门 B1-1 查询），不做任何业务判定、不改任何数据。
     * {@code diffs} 为空数组 = 本轮对账无差异（消解条件①，见 需求文档.md §4.1）。
     */
    @POST
    @Path("/line-items/{lineItemId}/reconcile-report")
    public Response reportReconcileDiff(
            @PathParam("lineItemId") UUID lineItemId,
            com.cpq.quotation.dto.ReconcileReportRequest body) {
        List<com.cpq.quotation.dto.ReconcileDiffEntry> diffs =
                (body != null && body.diffs != null) ? body.diffs : List.of();
        java.time.Instant reconciledAt = (body != null && body.reconciledAt != null)
                ? body.reconciledAt : java.time.Instant.now();
        for (com.cpq.quotation.dto.ReconcileDiffEntry d : diffs) {
            DecimalRequestValidator.rejectNumericTokens(d.frontendValue, "diffs.frontendValue");
            DecimalRequestValidator.rejectNumericTokens(d.backendValue, "diffs.backendValue");
            DecimalRequestValidator.rejectNumericTokens(d.frontendInputs, "diffs.frontendInputs");
            DecimalRequestValidator.rejectNumericTokens(d.backendInputs, "diffs.backendInputs");
            LOG.warnf("[reconcile-diff] lineItemId=%s componentId=%s tabName=%s rowKey=%s fieldName=%s "
                    + "frontendValue=%s backendValue=%s frontendInputs=%s backendInputs=%s reconciledAt=%s",
                    lineItemId, d.componentId, d.tabName, d.rowKey, d.fieldName,
                    d.frontendValue, d.backendValue, d.frontendInputs, d.backendInputs, reconciledAt);
        }
        reconcileDiffStore.report(lineItemId, diffs, reconciledAt);
        Map<String, Object> data = new HashMap<>();
        data.put("recorded", diffs.size());
        // 契约要求 message="accepted"（非 "success"），复用 ApiResponse.error(code,message,data) 的通用三元工厂
        // ——该方法名虽叫 error 但语义只是「code+message+data 任意组合」，2xx 场景沿用无问题。
        return Response.status(202).entity(ApiResponse.error(202, "accepted", data)).build();
    }

    @POST
    @Path("/{id}/calculate-discount")
    public ApiResponse<QuotationDTO> calculateDiscount(@PathParam("id") UUID id, Map<String, Object> body) {
        if (body == null || body.get("originalAmount") == null) {
            throw new com.cpq.common.exception.BusinessException(400, "originalAmount is required");
        }
        Object rawOriginalAmount = body.get("originalAmount");
        if (!(rawOriginalAmount instanceof String)) {
            DecimalRequestValidator.rejectNumericTokens(rawOriginalAmount, "originalAmount");
            throw new com.cpq.common.exception.BusinessException(400,
                    "originalAmount must be a decimal string; received " + rawOriginalAmount);
        }
        BigDecimal originalAmount;
        try {
            String raw = (String) rawOriginalAmount;
            if (!raw.matches("[+-]?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) {
                throw new NumberFormatException("non-plain decimal");
            }
            originalAmount = new BigDecimal(raw);
            if (Math.max(originalAmount.scale(), 0) > com.cpq.common.PrecisionPolicy.CALCULATION_SCALE) {
                throw new NumberFormatException("scale exceeds 12");
            }
        } catch (NumberFormatException e) {
            throw new com.cpq.common.exception.BusinessException(400,
                    "originalAmount must be a plain decimal string with at most 12 fractional digits");
        }
        return ApiResponse.success(quotationService.calculateDiscount(id, originalAmount));
    }

    /**
     * v5.1 §10 提交报价单：DRAFT→SUBMITTED + 写入提交快照。
     * 权限：仅 SALES_REP 可提交（Resource 层注解 + Service 层守卫双重保护）。
     */
    @POST
    @Path("/{id}/submit")
    @RoleAllowed({"SALES_REP", "SYSTEM_ADMIN"})
    public ApiResponse<QuotationDTO> submit(@PathParam("id") UUID id,
                                             @Context HttpServerRequest request) {
        UUID currentUserId = sessionHelper.getCurrentUserIdOrFallback(request);
        // P3 lazy-excel:提交冻结前确保 Excel 值已补算(首存懒算留 NULL),否则冻结/导出会缺 Excel 快照。
        try { cardSnapshotService.ensureExcelValues(id); em.clear(); } catch (Exception ignore) { /* 尽力,不阻断提交 */ }
        awaitWarmBeforeSubmit(id);
        return ApiResponse.success(quotationService.submit(id, currentUserId));
    }

    /** 提交前等 warm 让锁的预算（ms）。见 {@link #awaitWarmBeforeSubmit} 的取值依据。 */
    private static final long SUBMIT_WARM_WAIT_BUDGET_MS = 8000L;
    /** 两次 probe 之间的休眠（ms）。休眠期间【不持有】DB 连接，见 awaitWarmBeforeSubmit 不变量②。 */
    private static final long SUBMIT_WARM_PROBE_INTERVAL_MS = 150L;

    /**
     * 提交前有界等待整单卡片值 warm 让出单飞锁。
     *
     * <p><b>要解决的现象</b>：用户改一格 → autoSave 防抖 → {@code saveDraft} 无条件把全单卡片值置 NULL
     * → warm（{@code ensure-card-values}）fire-and-forget 起飞并占住单飞锁 0.5~1.7s；用户此刻点提交，
     * {@code ensureCardValues} 取不到锁返回 {@code WARMING_IN_PROGRESS} → 409。<b>单击就会中</b>，
     * 不需要双击。前端在飞守卫拦不住它 —— 守卫管的是 save，而 warm 是 save <b>完成之后</b>才起飞的，活得比守卫长。
     *
     * <hr>
     * <h4>🔒 不变量①：等待必须在【事务外】—— 放进事务内会造成死锁，不是性能问题而是正确性问题</h4>
     *
     * {@code CardSnapshotService#ensureCardValues} 是 {@code @Transactional(REQUIRED)}，
     * 若在 {@code QuotationService#submit} 内部等锁，就是<b>加入 submit 的事务</b>等。而此时：
     * <pre>
     *   T_submit: 已持有 quotation 行写锁（submit 前段改过 q 的客户快照字段/assignedApproverId/
     *             submissionSnapshot，中途查询触发 auto-flush 已把 UPDATE 发出去）
     *             →  等 advisory 锁
     *   T_warm  : 已持有 advisory 锁
     *             →  等 quotation 行锁（CardSnapshotService#recomputeDraftHeaderTotals 会
     *                UPDATE quotation.original_amount/total_amount）
     * </pre>
     * 成环。PG 的死锁检测器能发现（advisory 等待也在锁管理器里），所以不会挂死，
     * 但会变成<b>随机杀掉一方的 deadlock 错误</b> —— 比现在这个必现且干净的 409 更糟。
     *
     * <p>⚠️ 这个环是<b>方向3 Q2 引入的</b>：在那之前 warm 只写 {@code quotation_line_item}，
     * 不碰 {@code quotation} 行，没有环。也就是说<b>「取不到锁就立刻失败」这个现状不是疏漏，是保护</b>。
     * 本方法把等待放在 Resource 层（本类无 {@code @Transactional}，且上一行 {@code ensureExcelValues}
     * 已是同款事务外前置调用），{@code quotationService.submit(...)} 的事务此刻<b>尚未开启</b> → 环不成立。
     *
     * <hr>
     * <h4>🔒 不变量②：必须用【非阻塞 probe + sleep】，不能改成 SQL 阻塞等待</h4>
     *
     * 把 {@code pg_try_advisory_xact_lock} 换成阻塞版 {@code pg_advisory_xact_lock}（+{@code lock_timeout}）
     * 代码更短、看起来更「正统」，但<b>等待期间会一直占着一条 DB 连接</b>：
     * <pre>
     *   连接池 quarkus.datasource.jdbc.max-size = 20   ← 20 个并发等待就把【整个应用】打死
     *   工作线程池 default max-threads       = 200
     * </pre>
     * 现写法每次 probe 是一次独立短事务，<b>sleep 期间连接已归还池</b>，等待只占线程不占连接
     * —— 把瓶颈从 20 挪到 200。这是本方案能成立的关键，改动前请先想清楚这 20 与 200 的差别。
     *
     * <hr>
     * <h4>🔒 不变量③：本方法只治【可用性】，不治【金额正确性】—— 且顺序是硬约束</h4>
     *
     * 三件事各治一面，<b>缺一不可，且本方法绝不能单独存在</b>：
     * <table border="1">
     *   <tr><th>做什么</th><th>治什么</th></tr>
     *   <tr><td>{@code CardSnapshotService#ensureCardValues(id, force=true)}（修法①）</td>
     *       <td>提交金额可信 —— 无视 {@code IS NULL} 强制重算</td></tr>
     *   <tr><td>{@code QuotationLineItem} 上的 {@code @DynamicUpdate}（修法②）</td>
     *       <td>{@code annual_volume}/{@code discount_*} 等<b>其它列</b>不被全列 UPDATE 用旧值覆盖</td></tr>
     *   <tr><td><b>本方法</b>（修法③）</td><td><b>只治 409 这个可用性问题</b></td></tr>
     * </table>
     *
     * 🔴 <b>单独落本方法会把「可见失败」变成「静默错值」</b>：
     * <pre>
     *   只有 409（改造前）：submit 退场 → 用户重试 → saveDraft 又置 NULL → 从最新 row_data 重算 → 金额正确
     *   只加等锁（危险）  ：submit 等到 warm 算完 —— 而【warm 算完正是陈旧值写回的那一刻】
     *                      → IS NULL 选不中 → 200 但提交的是编辑前的金额
     * </pre>
     * 即：<b>等锁的终点恰好是污染发生的时刻</b>。所以修法① 必须先于或同时于本方法落地，
     * 任何人想单独回退①而保留本方法，等于亲手制造静默错价。
     *
     * <hr>
     * <h4>预算取值依据（实测，非估算）</h4>
     *
     * 锁是事务级（{@code pg_try_advisory_xact_lock}），持锁时长 == {@code ensureCardValues} 整个事务时长。
     * 实测（每档 5 次）：1 行 median 525ms / 3 行 595ms / 5 行 689ms（max 1442ms）/ <b>10 行 1708ms</b>，
     * 近似线性；与测试侧独立拟合的 {@code T(N) ≈ 0.44 + 0.065N} 吻合。
     * 反推：<b>3s ≈ 39 行 / 8s ≈ 116 行</b>。RECORD 有 <b>77 行</b>首存记载 —— 落在 3s 之外、8s 之内，
     * 故取 <b>8000ms</b>。
     *
     * <p>为什么 8s 不会拖垮系统：sleep 期间<b>不持有 DB 连接</b>（见不变量②），只占工作线程；
     * 200 线程 ÷ 8s ⇒ 需持续 <b>25 次提交/秒</b>才可能耗尽，本系统不可能到该量级。
     * JTA 60s 侧：8s 等待 + 大单自算 ~10s ≈ 18s，余量充足。
     *
     * <p>🔴 <b>仍不覆盖超大单</b>（130~180 行，前端 {@code QuotationWizard} 有「大单 ensure 可阻塞 ~9-12s」
     * 的现场记载）。本方法是<b>概率性改善</b>，不是根治。根治见 BACKLOG「saveDraft 增量失效」：
     * 现在每次 autoSave 都触发<b>整单</b>重算，只失效真正变动的行才能让 warm 变快、碰撞窗口按比例缩小。
     *
     * <p>大单超时后重试仍可成功（有界）：点提交时用户已停止编辑，且前端 {@code handleSubmit} 里那次
     * {@code saveDraft} 带 {@code skipWarm} 不再发新 warm → 在飞的 warm 飞完即可提交。
     */
    private void awaitWarmBeforeSubmit(UUID id) {
        long deadline = System.nanoTime() + SUBMIT_WARM_WAIT_BUDGET_MS * 1_000_000L;
        long t0 = System.nanoTime();
        int probes = 0;
        while (true) {
            probes++;
            int r;
            try {
                r = cardSnapshotService.ensureCardValues(id);
            } catch (Exception e) {
                // 补算本身失败不在这里判定 —— 交给 submit 内部那次（它会以同样方式再试一次并如实报错）
                LOG.warnf("[submit-warm-wait] quotation=%s probe#%d 异常（不阻断，交由 submit 内部处理）: %s",
                        id, probes, e.getMessage());
                return;
            }
            if (r != com.cpq.quotation.service.CardSnapshotService.WARMING_IN_PROGRESS) {
                long waitedMs = (System.nanoTime() - t0) / 1_000_000L;
                // 🔒 防假绿判据：本行只在【真的等过】时打印。验收要证明「确实等了」而不是「碰巧没撞上」，
                //    就看这行是否出现且 probes>1 —— 若把整个重试逻辑删掉，本行永不出现。
                if (probes > 1) {
                    LOG.infof("[submit-warm-wait] quotation=%s 等待 %dms（probe %d 次）后取得单飞锁，继续提交",
                            id, waitedMs, probes);
                }
                em.clear();   // 驱逐 probe 期间读进来的陈旧 L1，让 submit 读到 warm 落库的新值
                return;
            }
            if (System.nanoTime() >= deadline) {
                long waitedMs = (System.nanoTime() - t0) / 1_000_000L;
                LOG.warnf("[submit-warm-wait] quotation=%s 等待 %dms（probe %d 次）仍未取得单飞锁，拒绝提交",
                        id, waitedMs, probes);
                // 文案：已经等过了，语义不再是「稍等就好」，而是「这张单确实大 / 系统繁忙」。
                throw new BusinessException(409, String.format(
                        "系统正在处理该报价单的金额（已等待 %d 毫秒），请稍后重新提交", waitedMs));
            }
            try {
                Thread.sleep(SUBMIT_WARM_PROBE_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;   // 被中断则不再等，交给 submit 内部按原逻辑处理
            }
        }
    }

    /**
     * v5.1 §10 获取报价单提交快照。
     */
    @GET
    @Path("/{id}/snapshot")
    public ApiResponse<Object> getSnapshot(@PathParam("id") UUID id) {
        String snapshotJson = quotationService.getSnapshot(id);
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return ApiResponse.success(null);
        }
        // 将原始 JSON 字符串反序列化后返回，避免双重序列化
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(snapshotJson, Object.class);
            return ApiResponse.success(parsed);
        } catch (Exception e) {
            return ApiResponse.success(snapshotJson);
        }
    }

    /**
     * v5.1 §4.9 字段级追溯 API。
     *
     * <p>示例：GET /api/cpq/quotations/{id}/field-trace?fieldPath=mat_fee.xxx|yyy.unit_price
     */
    @GET
    @Path("/{id}/field-trace")
    public ApiResponse<FieldTraceDTO> getFieldTrace(
            @PathParam("id") UUID id,
            @QueryParam("fieldPath") String fieldPath) {
        return ApiResponse.success(quotationService.getFieldTrace(id, fieldPath));
    }

    /**
     * PERF-FULL-RECALC-10: 全表重算 DRAFT 报价单的所有公式字段。
     * 仅 DRAFT 状态可用；其他状态返回 400"已提交报价单不可重算"。
     */
    @POST
    @Path("/{id}/recalculate")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<QuotationDTO> recalculate(@PathParam("id") UUID id) {
        return ApiResponse.success(quotationService.recalculate(id));
    }

    @POST
    @Path("/{id}/approve")
    public ApiResponse<QuotationDTO> approve(@PathParam("id") UUID id, Map<String, String> body, @Context HttpServerRequest request) {
        UUID currentUserId = sessionHelper.getCurrentUserIdOrFallback(request);
        String comment = body != null ? body.get("comment") : null;
        return ApiResponse.success(quotationService.approve(id, comment, currentUserId));
    }

    @POST
    @Path("/{id}/reject")
    public ApiResponse<QuotationDTO> reject(@PathParam("id") UUID id, Map<String, String> body, @Context HttpServerRequest request) {
        UUID currentUserId = sessionHelper.getCurrentUserIdOrFallback(request);
        String comment = body != null ? body.get("comment") : null;
        return ApiResponse.success(quotationService.reject(id, comment, currentUserId));
    }

    /**
     * task-0721 报价升版逻辑 B6：核价通过前的回填影响预览（api.md §1.1）。只读、无副作用、幂等。
     */
    @GET
    @Path("/{id}/costing-approve/preview")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<com.cpq.quotation.dto.backfill.BackfillPreviewDTO> costingApprovePreview(
            @PathParam("id") UUID id) {
        return ApiResponse.success(quoteBackfillPreviewService.preview(id));
    }

    /**
     * task-0721 报价升版逻辑 B5/B6：核价通过（两段式，api.md §1.2）。{@code previewToken} 必填——
     * 老调用方（不带 token）直接 400，强制先调预览接口；token 与提交时重算不一致 → 409。
     */
    @POST
    @Path("/{id}/costing-approve")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<QuotationDTO> costingApprove(@PathParam("id") UUID id, Map<String, String> body, @Context HttpServerRequest request) {
        UUID uid = sessionHelper.getCurrentUserIdOrFallback(request);
        String comment = body != null ? body.get("comment") : null;
        String previewToken = body != null ? body.get("previewToken") : null;
        return ApiResponse.success(quotationService.costingApprove(id, comment, uid, previewToken));
    }

    @POST
    @Path("/{id}/costing-reject")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<QuotationDTO> costingReject(@PathParam("id") UUID id, Map<String, String> body, @Context HttpServerRequest request) {
        UUID uid = sessionHelper.getCurrentUserIdOrFallback(request);
        String reason = body != null ? body.get("comment") : null;
        return ApiResponse.success(quotationService.costingReject(id, reason, uid));
    }

    @POST
    @Path("/{id}/withdraw")
    public ApiResponse<QuotationDTO> withdraw(@PathParam("id") UUID id, @Context HttpServerRequest request) {
        UUID currentUserId = sessionHelper.getCurrentUserIdOrFallback(request);
        return ApiResponse.success(quotationService.withdraw(id, currentUserId));
    }

    @POST
    @Path("/{id}/begin-edit")
    public ApiResponse<QuotationDTO> beginEdit(@PathParam("id") UUID id, @Context HttpServerRequest request) {
        UUID uid = sessionHelper.getCurrentUserIdOrFallback(request);
        return ApiResponse.success(quotationService.beginEdit(id, uid));
    }

    @POST
    @Path("/{id}/copy")
    public ApiResponse<QuotationDTO> copy(@PathParam("id") UUID id, java.util.Map<String, Object> body) {
        UUID templateId = null;
        if (body != null && body.get("templateId") != null && !body.get("templateId").toString().isBlank()) {
            templateId = UUID.fromString(body.get("templateId").toString());
        }
        QuotationDTO dto = quotationService.copy(id, templateId);
        // repair-0729 R3: 结构快照补建须在 quotationService.copy() 的事务提交之后独立调用——
        // 口径与上面 saveDraft(:128) 一致（本 Resource 类无 @Transactional）；提前到 copy()
        // 内部会加入其事务，读不到刚 persist 尚未提交的行，且 auto-flush 异常会污染整个复制事务。
        try { cardSnapshotService.ensureStructure(dto.id); } catch (Exception ignore) { /* 结构快照尽力而为 */ }
        return ApiResponse.success(dto);
    }

    @DELETE
    @Path("/{id}")
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        quotationService.delete(id);
        return ApiResponse.success();
    }

    /**
     * Admin: 一次性洗 quotation_line_component_data.tab_name —— 把 AP-37 根因 5 的脏数据
     * (saved-driven enrich 误把标准 Tab 写成"选配-*") 修回模板权威值. dry-run 默认.
     * 必须传 ?apply=true 才真改库.
     */
    @POST
    @Path("/admin/heal-componentdata-tabnames")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<Map<String, Object>> healComponentDataTabNames(
            @QueryParam("apply") @DefaultValue("false") boolean apply) {
        return ApiResponse.success(quotationService.healComponentDataTabNames(apply));
    }

    // ---- M5: Quotation Output ----

    @POST
    @Path("/{id}/export/html")
    @Produces(MediaType.TEXT_HTML)
    public Response exportHtml(
            @PathParam("id") UUID id,
            @QueryParam("showDiscount") @DefaultValue("true") boolean showDiscount,
            @QueryParam("showProcesses") @DefaultValue("true") boolean showProcesses,
            @QueryParam("showTabDetails") @DefaultValue("false") boolean showTabDetails) {
        byte[] html = exportService.exportHtml(id, showDiscount, showProcesses, showTabDetails);
        return Response.ok(html, MediaType.TEXT_HTML)
                .header("Content-Disposition", "inline; filename=\"quotation.html\"")
                .build();
    }

    @POST
    @Path("/{id}/export/pdf")
    @Produces(MediaType.TEXT_HTML)
    public Response exportPdf(
            @PathParam("id") UUID id,
            Map<String, Object> body) {
        boolean showDiscount = body != null && Boolean.TRUE.equals(body.get("showDiscount"));
        boolean showProcesses = body == null || !Boolean.FALSE.equals(body.get("showProcesses"));
        boolean showTabDetails = body != null && Boolean.TRUE.equals(body.get("showTabDetails"));
        // Return HTML for browser print-to-PDF (pragmatic approach)
        byte[] html = exportService.exportHtml(id, showDiscount, showProcesses, showTabDetails);
        return Response.ok(html, MediaType.TEXT_HTML)
                .header("Content-Disposition", "inline; filename=\"quotation.html\"")
                .build();
    }

    @POST
    @Path("/{id}/export/excel")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response exportExcel(
            @PathParam("id") UUID id,
            Map<String, Object> body) {
        boolean showDiscount = body == null || !Boolean.FALSE.equals(body.get("showDiscount"));
        boolean includeRawData = body != null && Boolean.TRUE.equals(body.get("includeRawData"));
        byte[] excel = exportService.exportExcel(id, showDiscount, includeRawData);
        QuotationDTO q = quotationService.getById(id);
        String filename = (q.quotationNumber != null ? q.quotationNumber : "quotation") + ".xlsx";
        return Response.ok(excel)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    @POST
    @Path("/{id}/send")
    public ApiResponse<QuotationDTO> sendQuotation(@PathParam("id") UUID id, Map<String, Object> body) {
        String to = body != null ? (String) body.get("to") : null;
        String cc = body != null ? (String) body.get("cc") : null;
        String subject = body != null ? (String) body.get("subject") : null;
        String emailBody = body != null ? (String) body.get("body") : null;
        boolean attachExcel = body != null && Boolean.TRUE.equals(body.get("attachExcel"));
        return ApiResponse.success(emailService.send(id, to, cc, subject, emailBody, attachExcel));
    }

    // ---- Excel View v2 ----

    @GET
    @Path("/{id}/excel-view")
    public ApiResponse<Map<String, Object>> getExcelView(@PathParam("id") UUID id,
                                                          @QueryParam("templateId") UUID templateId) {
        return ApiResponse.success(excelViewService.getExcelView(id, templateId));
    }

    @POST
    @Path("/{id}/excel-view/dry-run")
    public ApiResponse<Map<String, Object>> dryRunExcelView(@PathParam("id") UUID id,
                                                             ExcelDryRunRequest req) {
        DecimalRequestValidator.rejectNumericTokens(req != null ? req.columns : null, "columns");
        return ApiResponse.success(excelViewService.dryRun(id,
                req != null ? req.columns : null,
                req != null ? req.templateId : null));
    }

    @PUT
    @Path("/{id}/excel-view")
    public ApiResponse<Void> updateExcelViewCell(
            @PathParam("id") UUID id,
            Map<String, Object> body) {
        if (body == null) throw new WebApplicationException("Request body is required", 400);
        Object lineItemIdObj = body.get("lineItemId");
        Object colKeyObj = body.get("colKey");
        Object value = body.get("value");
        if (lineItemIdObj == null || colKeyObj == null) {
            throw new WebApplicationException("lineItemId and colKey are required", 400);
        }
        UUID lineItemId = UUID.fromString(lineItemIdObj.toString());
        String colKey = colKeyObj.toString();
        DecimalRequestValidator.rejectNumericTokens(value, "value");
        excelViewService.updateExcelViewCell(id, lineItemId, colKey, value);
        return ApiResponse.success();
    }

    private static void validateDraftDecimals(SaveDraftRequest request) {
        if (request == null || request.lineItems == null) {
            return;
        }
        for (int lineIndex = 0; lineIndex < request.lineItems.size(); lineIndex++) {
            SaveDraftRequest.LineItemDraft line = request.lineItems.get(lineIndex);
            if (line == null) {
                continue;
            }
            String linePath = "lineItems[" + lineIndex + "]";
            DecimalRequestValidator.rejectNumericJsonTokens(
                    line.productAttributeValues, linePath + ".productAttributeValues");
            DecimalRequestValidator.rejectNumericJsonTokens(
                    line.quoteExcelValues, linePath + ".quoteExcelValues");
            if (line.componentData != null) {
                for (int componentIndex = 0; componentIndex < line.componentData.size(); componentIndex++) {
                    SaveDraftRequest.ComponentDataDraft component = line.componentData.get(componentIndex);
                    if (component != null) {
                        DecimalRequestValidator.rejectNumericJsonTokens(component.rowData,
                                linePath + ".componentData[" + componentIndex + "].rowData");
                    }
                }
            }
            if (line.compositeProcesses != null) {
                for (int processIndex = 0; processIndex < line.compositeProcesses.size(); processIndex++) {
                    SaveDraftRequest.CompositeProcessDraft process = line.compositeProcesses.get(processIndex);
                    if (process != null) {
                        DecimalRequestValidator.rejectNumericTokens(process.paramValues,
                                linePath + ".compositeProcesses[" + processIndex + "].paramValues");
                    }
                }
            }
        }
    }

    @GET
    @Path("/{id}/export-excel-view")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response exportExcelView(@PathParam("id") UUID id) {
        byte[] excel = excelViewService.exportExcelView(id);
        QuotationDTO q = quotationService.getById(id);
        String filename = (q.quotationNumber != null ? q.quotationNumber : "quotation") + "-view.xlsx";
        return Response.ok(excel)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    @PUT
    @Path("/{id}/extend")
    public ApiResponse<QuotationDTO> extend(@PathParam("id") UUID id, Map<String, String> body) {
        if (body == null) {
            throw new com.cpq.common.exception.BusinessException(400, "Request body is required");
        }
        // Accept both "newExpiryDate" (canonical) and "expiryDate" (alias) for ergonomics
        String dateStr = body.get("newExpiryDate");
        if (dateStr == null || dateStr.isBlank()) dateStr = body.get("expiryDate");
        if (dateStr == null || dateStr.isBlank()) {
            throw new com.cpq.common.exception.BusinessException(400,
                    "newExpiryDate is required (ISO date format yyyy-MM-dd)");
        }
        LocalDate newExpiryDate;
        try {
            newExpiryDate = LocalDate.parse(dateStr);
        } catch (java.time.format.DateTimeParseException e) {
            throw new com.cpq.common.exception.BusinessException(400,
                    "Invalid date format, expected yyyy-MM-dd: " + dateStr);
        }
        return ApiResponse.success(quotationService.extend(id, newExpiryDate));
    }

    @POST
    @Path("/{id}/accept")
    public ApiResponse<QuotationDTO> accept(@PathParam("id") UUID id, @Context HttpServerRequest request) {
        UUID currentUserId = sessionHelper.getCurrentUserIdOrFallback(request);
        return ApiResponse.success(quotationService.accept(id, currentUserId));
    }

    @POST
    @Path("/{id}/reject-by-customer")
    public ApiResponse<QuotationDTO> rejectByCustomer(@PathParam("id") UUID id, Map<String, String> body,
                                                       @Context HttpServerRequest request) {
        String comment = body != null ? body.get("comment") : null;
        UUID currentUserId = sessionHelper.getCurrentUserIdOrFallback(request);
        return ApiResponse.success(quotationService.rejectByCustomer(id, comment, currentUserId));
    }

    // task-0723 B5: V5/import-session 死链路退役 — reimportBasicData 端点已删除
    // (依赖的 BasicDataImportServiceV5 已整体退役, basicdata.v6 是唯一正式导入路径)。

    // ──────────────────────────────────────────────
    // driver 默认行墓碑端点（deletable-driver-rows）
    // ──────────────────────────────────────────────

    /**
     * POST /api/cpq/quotations/{qid}/line-items/{lid}/delete-driver-row
     * body: { componentId, effKey, fp? }
     * 将指定行追加到 deletedRowKeys 墓碑列表并立即重刷报价快照。
     */
    @POST
    @Path("/{qid}/line-items/{lid}/delete-driver-row")
    public ApiResponse<Map<String, Object>> deleteDriverRow(@PathParam("qid") UUID qid, @PathParam("lid") UUID lid,
            Map<String, Object> body) {
        if (body == null || body.get("componentId") == null || body.get("effKey") == null) {
            throw new BusinessException(400, "componentId 和 effKey 不能为空");
        }
        UUID componentId = UUID.fromString(body.get("componentId").toString());
        String effKey = String.valueOf(body.get("effKey"));
        String fp = String.valueOf(body.getOrDefault("fp", ""));
        // Phase 1：tx1 写墓碑提交 → tx2 读已提交墓碑重算+物化 row_data(N-1)+返回整单投影，
        // 供前端原子重灌 comp.rows/deletedRowKeys/quoteCardValues，消除「快照过滤但 row_data 未过滤」的删错行。
        quotationService.deleteDriverRow(lid, componentId, effKey, fp);
        return ApiResponse.success(cardSnapshotService.refreshQuoteProjection(lid));
    }

    /**
     * POST /api/cpq/quotations/{qid}/line-items/{lid}/restore-driver-rows
     * body: { componentId }
     * 清空 deletedRowKeys 墓碑列表并立即重刷报价快照。
     */
    @POST
    @Path("/{qid}/line-items/{lid}/restore-driver-rows")
    public ApiResponse<Map<String, Object>> restoreDriverRows(@PathParam("qid") UUID qid, @PathParam("lid") UUID lid,
            Map<String, Object> body) {
        if (body == null || body.get("componentId") == null) {
            throw new BusinessException(400, "componentId 不能为空");
        }
        UUID componentId = UUID.fromString(body.get("componentId").toString());
        // Phase 1：tx1 清墓碑 → tx2 重算+物化+投影，同 deleteDriverRow。
        quotationService.restoreAllDriverRows(lid, componentId);
        return ApiResponse.success(cardSnapshotService.refreshQuoteProjection(lid));
    }
}
