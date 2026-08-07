package com.cpq.template.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.template.dto.CompareTemplatesRequest;
import com.cpq.template.dto.CreateTemplateRequest;
import com.cpq.template.dto.PublishRequest;
import com.cpq.template.dto.TemplateComparisonResult;
import com.cpq.template.dto.TemplateDTO;
import com.cpq.template.service.TemplateComparisonService;
import com.cpq.template.service.TemplateFreezeDriftService;
import com.cpq.template.service.TemplateService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Path("/api/cpq/templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class TemplateResource {

    @Inject
    TemplateService templateService;

    @Inject
    TemplateComparisonService templateComparisonService;

    /** task-0806 B11：模板发布全量冻结体检 + 差异服务（A2/A3/A4）。 */
    @Inject
    TemplateFreezeDriftService templateFreezeDriftService;

    @Inject
    SessionHelper sessionHelper;

    @GET
    public ApiResponse<List<TemplateDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("category") String category,
            @QueryParam("customerId") UUID customerId,
            @QueryParam("categoryId") UUID categoryId,
            @QueryParam("status") String status,
            @QueryParam("keyword") String keyword,
            // V71：按模板类型过滤（QUOTATION / COSTING），不传则返全部
            @QueryParam("templateKind") String templateKind) {
        return ApiResponse.success(templateService.list(page, size, category, customerId, categoryId, status, keyword, templateKind));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<TemplateDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(templateService.getById(id));
    }

    /**
     * 客户报价模板匹配 — 客户专属优先 → 通用兜底。
     * 对应 docs/API.md L100/L643 设计。
     */
    @GET
    @Path("/match-customer-quote")
    public ApiResponse<com.cpq.template.dto.TemplateMatchResult> matchCustomerQuote(
            @QueryParam("customerId") UUID customerId,
            @QueryParam("categoryId") UUID categoryId) {
        return ApiResponse.success(templateService.matchCustomerQuoteTemplate(customerId, categoryId));
    }

    @GET
    @Path("/auto-defaults")
    public ApiResponse<com.cpq.template.dto.QuoteImportAutoDefaults> autoDefaults(
            @QueryParam("customerId") UUID customerId) {
        return ApiResponse.success(templateService.computeAutoDefaults(customerId));
    }

    @POST
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<TemplateDTO> create(CreateTemplateRequest request) {
        return ApiResponse.success(templateService.create(request));
    }

    @PUT
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<TemplateDTO> update(
            @PathParam("id") UUID id,
            CreateTemplateRequest request) {
        return ApiResponse.success(templateService.update(id, request));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        templateService.delete(id);
        return ApiResponse.success();
    }

    @POST
    @Path("/{id}/publish")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<TemplateDTO> publish(
            @PathParam("id") UUID id,
            PublishRequest request) {
        return ApiResponse.success(templateService.publish(id, request));
    }

    @POST
    @Path("/{id}/archive")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<TemplateDTO> archive(
            @PathParam("id") UUID id,
            @QueryParam("force") @DefaultValue("false") boolean force) {
        return ApiResponse.success(templateService.archive(id, force));
    }

    @POST
    @Path("/{id}/new-draft")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<TemplateDTO> createNewDraft(@PathParam("id") UUID id) {
        return ApiResponse.success(templateService.createNewDraft(id));
    }

    @GET
    @Path("/series/{seriesId}/versions")
    public ApiResponse<List<TemplateDTO>> getVersionHistory(@PathParam("seriesId") UUID seriesId) {
        return ApiResponse.success(templateService.getVersionHistory(seriesId));
    }

    @POST
    @Path("/compare")
    public ApiResponse<TemplateComparisonResult> compare(CompareTemplatesRequest request) {
        return ApiResponse.success(templateComparisonService.compare(request.templateAId, request.templateBId));
    }

    // task-0806 D11：migrate-to-unified-view 端点整体删除（AC-4）——一次性历史迁移，
    // 基线 §3.5 标注「已跑过」，其唯一实现 TemplateService.migrateToUnifiedView 已随本任务删除。
    // 路由消失 → 404。

    /**
     * task-0806 B11 / api.md §3 · A2：单模板「冻结快照 vs 当前活配置」逐字段差异。
     *
     * <p>语义：迁移刚完成时应全部 {@code hasDrift=false}；随组件迭代差异自然增长——
     * 这是严格版本化下的正常状态，不是故障。回答的是「这个已发布模板比当前组件配置落后
     * 多少，值不值得发新版」，同时兼任 admin 后门的安全网。
     */
    @GET
    @Path("/{id}/frozen-drift")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<java.util.Map<String, Object>> frozenDrift(@PathParam("id") UUID id) {
        return ApiResponse.success(templateFreezeDriftService.driftOf(id));
    }

    /**
     * task-0806 B11 / api.md §4 · A3：A2 的批量版，兼迁移前体检 A。
     *
     * @param status    默认 PUBLISHED,ARCHIVED；逗号分隔
     * @param onlyDrift 默认 true，只返回有差异的模板
     */
    @GET
    @Path("/admin/frozen-drift")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<java.util.Map<String, Object>> adminFrozenDrift(
            @QueryParam("status") @DefaultValue("PUBLISHED,ARCHIVED") String status,
            @QueryParam("onlyDrift") @DefaultValue("true") boolean onlyDrift) {
        List<String> statuses = Arrays.asList(status.split(","));
        return ApiResponse.success(templateFreezeDriftService.driftOfMany(statuses, onlyDrift));
    }

    /**
     * task-0806 B11 / api.md §5 · A4：迁移前体检 B——验证每个已发布模板引用到的 SQL 视图
     * 都在 {@code sql_views_snapshot} 闭包内。是 FR-6「SQL 视图 fallback 切报错」的前置门槛
     * （D13，结果须交用户按三档拍板，不由实现者自决）。
     */
    @GET
    @Path("/admin/sqlview-closure-check")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<java.util.Map<String, Object>> sqlviewClosureCheck(
            @QueryParam("status") @DefaultValue("PUBLISHED,ARCHIVED") String status) {
        List<String> statuses = Arrays.asList(status.split(","));
        return ApiResponse.success(templateFreezeDriftService.sqlviewClosureCheck(statuses));
    }

    /**
     * 2026-05-20 admin endpoint: 按 sortOrder 删除 PUBLISHED 模板的 tc 记录.
     *
     * <p>task-0806 B7 / api.md §7 A6：加 {@code confirm} 预览门槛（缺省 false=仅预览零写入）+
     * 执行时写 {@code operation_log} 审计 + {@code LOG.warn} 告警。
     *
     * <p>Body 示例:
     * <pre>POST /api/cpq/templates/admin/{templateId}/delete-tcs
     * { "sortOrders": [0, 1, 2, 4], "confirm": false }</pre>
     */
    @POST
    @Path("/admin/{templateId}/delete-tcs")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<java.util.Map<String, Object>> adminDeleteTcs(
            @PathParam("templateId") UUID templateId,
            java.util.Map<String, Object> body,
            @Context HttpServerRequest httpRequest) {
        @SuppressWarnings("unchecked")
        List<Integer> sortOrders = body != null && body.get("sortOrders") instanceof List
                ? ((List<Object>) body.get("sortOrders")).stream()
                        .map(v -> v instanceof Number ? ((Number) v).intValue() : Integer.parseInt(v.toString()))
                        .collect(java.util.stream.Collectors.toList())
                : java.util.Collections.emptyList();
        boolean confirm = body != null && Boolean.TRUE.equals(body.get("confirm"));
        UUID operatorId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        return ApiResponse.success(
                templateService.deleteTemplateComponentsBySortOrder(templateId, sortOrders, confirm, operatorId));
    }

    /**
     * 2026-05-21: 将 template_component.fields_override 上升为 component.fields（单一来源）.
     *
     * <p>task-0806 B7 / api.md §7 A7：加 {@code confirm} 预览门槛（缺省 false=仅预览零写入）+
     * 执行时写 {@code operation_log} 审计（按受影响模板各写一行）+ {@code LOG.warn} 告警。
     *
     * <p>Body（可选）：
     * <pre>{ "componentIds": ["e42185ec-...", "dae85db8-...", "0a436b6c-..."], "confirm": false }</pre>
     * 不传或 componentIds 为空 → 默认处理所有名称以"选配-"开头的 ACTIVE 组件。
     */
    @POST
    @Path("/admin/promote-override-to-component")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<java.util.Map<String, Object>> adminPromoteOverrideToComponent(
            java.util.Map<String, Object> body,
            @Context HttpServerRequest httpRequest) {
        List<UUID> componentIds = null;
        if (body != null && body.get("componentIds") instanceof List<?> cidList) {
            componentIds = cidList.stream()
                    .filter(o -> o != null)
                    .map(o -> UUID.fromString(o.toString()))
                    .collect(java.util.stream.Collectors.toList());
        }
        boolean confirm = body != null && Boolean.TRUE.equals(body.get("confirm"));
        UUID operatorId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        return ApiResponse.success(templateService.promoteOverrideToComponent(componentIds, confirm, operatorId));
    }
}
