package com.cpq.quotation.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.importexcel.dto.ImportResultDTO;
import com.cpq.quotation.dto.CreateQuotationRequest;
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
        QuotationDTO dto = quotationService.saveDraft(id, request);
        // saveDraft 已提交,按新行重快照(降级:失败不影响保存)
        try {
            snapshotService.snapshotQuotation(id);
        } catch (Exception ignore) {
            // 快照尽力而为
        }
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
        try {
            cardSnapshotService.ensureStructure(id);
            var lines = snapshotService.loadQuotationLines(id);
            for (var liMap : lines) {
                UUID lineItemId = asUuid(liMap.get("id"));
                if (lineItemId != null) {
                    com.cpq.quotation.entity.QuotationLineItem li =
                        com.cpq.quotation.entity.QuotationLineItem.findById(lineItemId);
                    boolean hasSnapshot = li != null
                        && li.quoteCardValues != null && !li.quoteCardValues.isBlank();
                    if (li != null && !hasSnapshot) {
                        cardSnapshotService.snapshotLineValues(li); // 仅新行首次初始化, 已有行保留 editQuoteCardValue 的增量
                    }
                }
            }
        } catch (Exception ignore) {
            // 尽力而为
        }
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
     * 前端 Step2 进入编辑态时调用，再 getById 拿最新快照渲染。
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
    @Path("/{id}/withdraw")
    public ApiResponse<QuotationDTO> withdraw(@PathParam("id") UUID id, @Context HttpServerRequest request) {
        UUID currentUserId = sessionHelper.getCurrentUserIdOrFallback(request);
        return ApiResponse.success(quotationService.withdraw(id, currentUserId));
    }

    @POST
    @Path("/{id}/copy")
    public ApiResponse<QuotationDTO> copy(@PathParam("id") UUID id) {
        return ApiResponse.success(quotationService.copy(id));
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

    private static UUID asUuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }
}
