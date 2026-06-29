package com.cpq.quotation.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.importexcel.dto.ImportResultDTO;
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
import java.io.InputStream;
import java.nio.file.Files;
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
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

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

    // 加产品整份快照 Phase 2:saveDraft 全量重建后按新行重快照(UPSERT 保留编辑层 row_data)
    @Inject
    com.cpq.configure.service.ConfigureSnapshotService snapshotService;

    // 报价单整份快照 Phase 1: 4 份结构 + 行级 4 份值
    @Inject
    com.cpq.quotation.service.CardSnapshotService cardSnapshotService;

    @Inject
    jakarta.persistence.EntityManager em;

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
        // [draft-profile] 分段埋点(2026-06-26):S1 saveDraft(全删全建+落库) / S2 snapshotQuotation(snapshot_rows) /
        //   S3 整份快照 Phase1(新行 card values) / S4 getById 重建。日志前缀 [draft-profile] 便于过滤。
        long _p0 = System.nanoTime();
        QuotationDTO dto = quotationService.saveDraft(id, request);
        long _s1 = (System.nanoTime() - _p0) / 1_000_000;
        // saveDraft 已提交,按新行重快照(降级:失败不影响保存)
        long _p1 = System.nanoTime();
        try {
            snapshotService.snapshotQuotation(id, true);  // 增量: 复用行已回写 snapshot_rows → 跳过全量重 expand
        } catch (Exception ignore) {
            // 快照尽力而为
        }
        long _s2 = (System.nanoTime() - _p1) / 1_000_000;
        long _p2 = System.nanoTime();
        int _newLines = 0;
        // 报价单整份快照 Phase 1: 固定 4 份结构 + 仅对新行初始化 4 份值
        // 2026-06-01 修复(单价小计清零 + 并发400 + 保存502): 保存时**只对没有 quote_card_values 的新行**
        //   调 snapshotLineValues 初始化; **已有快照的行一律跳过, 不在保存路径重建**。原因:
        //   1) 旧码无条件 snapshotLineValues → buildCardValues(editRows=null) 把用户单价编辑连同重算小计
        //      全部抹掉(事件驱动防抖保存每次触发)→ 小计归0; 且与 editQuoteCardValue 并发重建同一行→400。
        //   2) editQuoteCardValue(失焦)已把 editRows + 重算 formulaResults + quote_excel_values 增量落库,
        //      保存时无需对已有行再做任何重建。
        //   3) **严禁**在此高频防抖保存路径对已有行做 driver 全量重 expand(refreshQuoteCardValues)——
        //      会占满 worker 线程池 → 503/502(代理层 502 Bad Gateway)。全量重 expand 只在草稿**打开**时
        //      的 refresh-card-snapshot 触发一次。详见 docs/RECORD.md。
        boolean snapshotsCreated = false;
        try {
            cardSnapshotService.ensureStructure(id);
            var lines = snapshotService.loadQuotationLines(id);
            // P2-C4: 核价 driver 整单 union 预取 —— 懒触发(仅遇到首个新行才算一次),
            // 把 N 新行的核价 driver 远程查从 N×M_rec 压到 M_rec;无新行的高频防抖空存零开销(不进 if)。
            java.util.Map<java.util.UUID, java.util.Map<String, com.cpq.component.dto.ExpandDriverResponse>> union = null;
            boolean unionDone = false;
            // B2: 整单全部行 id（compdata IN 预取用；含已有快照行也无妨，多取的桶不被读）
            java.util.List<UUID> allLineIds = new java.util.ArrayList<>();
            for (var liMap : lines) {
                UUID lid = asUuid(liMap.get("id"));
                if (lid != null) allLineIds.add(lid);
            }
            // B2: 首存 card values 批量预取（懒触发，仅遇首个新行才建一次；无新行的高频防抖空存零开销）
            com.cpq.quotation.service.CardSnapshotService.CardValuesPrefetch prefetch = null;
            boolean prefetchDone = false;
            boolean excelCdDone = false;   // Excel compData 整单预取上下文(懒触发,与 prefetch 同时机)
            long _s3setup = 0, _s3cards = 0;   // [s3-detail] 埋点:setup(union/prefetch/compData) vs 逐行卡片值

            // FIX 2(2026-06-26):卡片值集合化落库。默认 ON(golden + CardValuesBatchPersistEquivTest 证逐位等价);
            //   -Dcpq.firstsave-cardvalues-batch=false / CPQ_FIRSTSAVE_CARDVALUES_BATCH=false 回退逐行老路。
            boolean cardValuesBatch = "true".equalsIgnoreCase(
                System.getProperty("cpq.firstsave-cardvalues-batch",
                    System.getenv().getOrDefault("CPQ_FIRSTSAVE_CARDVALUES_BATCH", "true")));

            if (cardValuesBatch) {
                // 一次查"无快照"新行 id(blank-inclusive,与逐行 hasSnapshot=!=null&&!isBlank() 逐位对齐)
                @SuppressWarnings("unchecked")
                java.util.List<Object> rawIds = em.createNativeQuery(
                        "SELECT id FROM quotation_line_item WHERE quotation_id = :q " +
                        "AND (quote_card_values IS NULL OR btrim(quote_card_values) = '')")
                    .setParameter("q", id).getResultList();
                java.util.List<UUID> newLineIds = new java.util.ArrayList<>();
                for (Object o : rawIds) { UUID u = asUuid(o); if (u != null) newLineIds.add(u); }
                if (!newLineIds.isEmpty()) {
                    long _su = System.nanoTime();
                    union = cardSnapshotService.precomputeCostingDriverUnion(id);
                    prefetch = cardSnapshotService.precomputeCardValuesPrefetch(id, allLineIds);
                    _s3setup = (System.nanoTime() - _su) / 1_000_000;
                    long _cd = System.nanoTime();
                    cardSnapshotService.snapshotNewLinesCardValues(id, newLineIds, union, prefetch);  // ★ 单事务集合化落库
                    _s3cards = (System.nanoTime() - _cd) / 1_000_000;
                    snapshotsCreated = true;
                    _newLines = newLineIds.size();
                    LOG.debugf("[s3-detail] id=%s newLines=%d(batch) | setup(union/prefetch,一次)=%dms 集合化卡片值落库=%dms",
                            id, _newLines, _s3setup, _s3cards);
                }
            } else {
                // ── kill switch 回退:逐行老路(每行一次 @Transactional snapshotLineValuesWithUnion = 独立事务)──
                for (var liMap : lines) {
                    UUID lineItemId = asUuid(liMap.get("id"));
                    if (lineItemId != null) {
                        com.cpq.quotation.entity.QuotationLineItem li =
                            com.cpq.quotation.entity.QuotationLineItem.findById(lineItemId);
                        boolean hasSnapshot = li != null
                            && li.quoteCardValues != null && !li.quoteCardValues.isBlank();
                        if (li != null && !hasSnapshot) {
                            long _su = System.nanoTime();
                            if (!unionDone) { union = cardSnapshotService.precomputeCostingDriverUnion(id); unionDone = true; }
                            if (!prefetchDone) { prefetch = cardSnapshotService.precomputeCardValuesPrefetch(id, allLineIds); prefetchDone = true; }
                            if (!excelCdDone) {
                                java.util.Map<UUID, java.util.List<com.cpq.quotation.entity.QuotationLineComponentData>> cdByLine =
                                    com.cpq.quotation.entity.QuotationLineComponentData
                                        .<com.cpq.quotation.entity.QuotationLineComponentData>list(
                                            "lineItemId IN ?1 ORDER BY lineItemId, sortOrder, id", allLineIds)
                                        .stream().collect(java.util.stream.Collectors.groupingBy(cd -> cd.lineItemId));
                                com.cpq.formula.dataloader.ExcelCompDataContext.set(cdByLine);
                                excelCdDone = true;
                            }
                            _s3setup += (System.nanoTime() - _su) / 1_000_000;
                            long _cd = System.nanoTime();
                            cardSnapshotService.snapshotLineValuesWithUnion(li, union, prefetch, false);
                            _s3cards += (System.nanoTime() - _cd) / 1_000_000;
                            snapshotsCreated = true;
                            _newLines++;
                        }
                    }
                }
                if (_newLines > 0)
                    LOG.debugf("[s3-detail] id=%s newLines=%d(per-line) | setup=%dms 逐行卡片值=%dms",
                            id, _newLines, _s3setup, _s3cards);
            }
        } catch (Exception ignore) {
            // 尽力而为
        } finally {
            com.cpq.formula.dataloader.ExcelCompDataContext.clear();   // ThreadLocal 卫生:务必清
        }
        // 行 113 的 dto 在算快照"之前"构建, 不含本次新行刚生成的 quoteCardValues/costingCardValues。
        // 导入流程首存(新行)时, 前端 syncLineItemsFromResponse 依赖响应里的 4 份卡片值翻入"快照模式"
        // (useSnapQuote=true), 否则报价卡走实时展开路径——该路径不读 deletedRowKeys 墓碑, 导致
        // driver 行删除"点了无反应"。故仅在确有新行落快照时, 重建一份新鲜 DTO 返回(读路径, 无副作用);
        // 高频防抖空存(无新行)不触发, 不增加额外开销。
        //
        // ⚠ 一级缓存陷阱: snapshotLineValues(@Transactional)已把值落库提交, 但本请求会话里在
        // 行 137 findById 时已缓存了"无快照"的 line 实体; 直接 getById 会命中陈旧 L1 缓存 → 仍读到
        // quoteCardValues=null。必须先 em.clear() 驱逐, 让 getById 重新从库读已提交的新值。
        long _s3 = (System.nanoTime() - _p2) / 1_000_000;
        long _p3 = System.nanoTime();
        if (snapshotsCreated) {
            try {
                em.clear();
                dto = quotationService.getById(id);
            } catch (Exception ignore) {
                // 取不到新鲜 DTO 时退回原 dto(不影响保存本身)
            }
        }
        long _s4 = (System.nanoTime() - _p3) / 1_000_000;
        LOG.debugf("[draft-profile] id=%s newLines=%d total=%dms | S1.saveDraft=%dms S2.snapshotRows=%dms S3.cardValues=%dms S4.getById=%dms",
                id, _newLines, _s1 + _s2 + _s3 + _s4, _s1, _s2, _s3, _s4);
        return ApiResponse.success(dto);
    }

    /**
     * 料号版本管理: 切换某 line_item 的 part_version_locked. 仅 DRAFT 态可改.
     */
    @PUT
    @Path("/{id}/line-items/{lineItemId}/part-version")
    public ApiResponse<Map<String, Object>> updateLineItemPartVersion(
            @PathParam("id") UUID id,
            @PathParam("lineItemId") UUID lineItemId,
            Map<String, Object> body) {
        if (body == null || body.get("version") == null) {
            throw new com.cpq.common.exception.BusinessException(400, "version 不能为空");
        }
        int version;
        try {
            version = Integer.parseInt(body.get("version").toString());
        } catch (NumberFormatException e) {
            throw new com.cpq.common.exception.BusinessException(400, "version must be an integer");
        }
        String snapshot = quotationService.updateLineItemPartVersion(id, lineItemId, version);
        Map<String, Object> resp = new HashMap<>();
        resp.put("quotationId", id);
        resp.put("lineItemId", lineItemId);
        resp.put("partVersionLocked", version);
        resp.put("excelViewSnapshot", snapshot);  // V6: 让前端立即按新版本数据渲染卡片
        return ApiResponse.success(resp);
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

    @POST
    @Path("/{id}/calculate-discount")
    public ApiResponse<QuotationDTO> calculateDiscount(@PathParam("id") UUID id, Map<String, Object> body) {
        if (body == null || body.get("originalAmount") == null) {
            throw new com.cpq.common.exception.BusinessException(400, "originalAmount is required");
        }
        BigDecimal originalAmount;
        try {
            originalAmount = new BigDecimal(body.get("originalAmount").toString());
        } catch (NumberFormatException e) {
            throw new com.cpq.common.exception.BusinessException(400, "originalAmount must be a number");
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
        return ApiResponse.success(quotationService.submit(id, currentUserId));
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

    @POST
    @Path("/{id}/costing-approve")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<QuotationDTO> costingApprove(@PathParam("id") UUID id, Map<String, String> body, @Context HttpServerRequest request) {
        UUID uid = sessionHelper.getCurrentUserIdOrFallback(request);
        String comment = body != null ? body.get("comment") : null;
        return ApiResponse.success(quotationService.costingApprove(id, comment, uid));
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
        return ApiResponse.success(quotationService.copy(id, templateId));
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
        excelViewService.updateExcelViewCell(id, lineItemId, colKey, value);
        return ApiResponse.success();
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

    /**
     * v5.1 §6.6 DRAFT 漂移检测：用户接受漂移后重新计算公式 + 更新 referenced_versions。
     *
     * <p>权限：仅 SALES_REP（或 SYSTEM_ADMIN）可调用；SALES_MANAGER 无操作权限。
     */
    @POST
    @Path("/{id}/refresh-versions")
    public ApiResponse<QuotationDTO> refreshVersions(@PathParam("id") UUID id,
                                                      @Context HttpServerRequest request) {
        UUID currentUserId = sessionHelper.getCurrentUserIdOrFallback(request);
        return ApiResponse.success(quotationService.refreshVersions(id, currentUserId));
    }

    @POST
    @Path("/{id}/reject-by-customer")
    public ApiResponse<QuotationDTO> rejectByCustomer(@PathParam("id") UUID id, Map<String, String> body,
                                                       @Context HttpServerRequest request) {
        String comment = body != null ? body.get("comment") : null;
        UUID currentUserId = sessionHelper.getCurrentUserIdOrFallback(request);
        return ApiResponse.success(quotationService.rejectByCustomer(id, comment, currentUserId));
    }

    /**
     * QIMP-V5-REIMPORT-15/16: 重新导入报价单基础数据（仅 DRAFT 状态可用）。
     *
     * <p>Request (multipart/form-data):
     *   - file: 新的 Excel 文件（.xlsx）
     *
     * <p>Response: ImportResultDTO（含 importRecordId、status、totalRows 等）
     */
    @POST
    @Path("/{id}/reimport-basic-data")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ImportResultDTO> reimportBasicData(
            @PathParam("id") UUID id,
            @RestForm("file") FileUpload file,
            @Context HttpServerRequest request) {
        if (file == null) {
            throw new BusinessException(400, "file 不能为空");
        }
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(request);
        try (InputStream is = Files.newInputStream(file.uploadedFile())) {
            ImportResultDTO result = quotationService.reimportBasicData(id, is, userId);
            return ApiResponse.success(result);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(400, "重新导入基础数据失败: " + e.getMessage());
        }
    }

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
    public ApiResponse<Void> deleteDriverRow(@PathParam("qid") UUID qid, @PathParam("lid") UUID lid,
            Map<String, Object> body) {
        if (body == null || body.get("componentId") == null || body.get("effKey") == null) {
            throw new BusinessException(400, "componentId 和 effKey 不能为空");
        }
        UUID componentId = UUID.fromString(body.get("componentId").toString());
        String effKey = String.valueOf(body.get("effKey"));
        String fp = String.valueOf(body.getOrDefault("fp", ""));
        quotationService.deleteDriverRow(lid, componentId, effKey, fp);
        return ApiResponse.success(null);
    }

    /**
     * POST /api/cpq/quotations/{qid}/line-items/{lid}/restore-driver-rows
     * body: { componentId }
     * 清空 deletedRowKeys 墓碑列表并立即重刷报价快照。
     */
    @POST
    @Path("/{qid}/line-items/{lid}/restore-driver-rows")
    public ApiResponse<Void> restoreDriverRows(@PathParam("qid") UUID qid, @PathParam("lid") UUID lid,
            Map<String, Object> body) {
        if (body == null || body.get("componentId") == null) {
            throw new BusinessException(400, "componentId 不能为空");
        }
        UUID componentId = UUID.fromString(body.get("componentId").toString());
        quotationService.restoreAllDriverRows(lid, componentId);
        return ApiResponse.success(null);
    }

    private static UUID asUuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }
}
