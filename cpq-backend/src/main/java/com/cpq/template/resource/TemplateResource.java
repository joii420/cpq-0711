package com.cpq.template.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.template.dto.CompareTemplatesRequest;
import com.cpq.template.dto.CreateTemplateRequest;
import com.cpq.template.dto.PublishRequest;
import com.cpq.template.dto.TemplateComparisonResult;
import com.cpq.template.dto.TemplateDTO;
import com.cpq.template.service.TemplateComparisonService;
import com.cpq.template.service.TemplateService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

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

    /**
     * 2026-05-21: 统一智能视图路径方案 — 一次性模板数据迁移.
     *
     * <p>对每个 PUBLISHED 模板的每个 tc:
     * <ul>
     *   <li>fields_override 内 basic_data_path_composite 值 → 覆盖 basic_data_path，删除 _composite 键</li>
     *   <li>tc.dataDriverPathComposite 值 → 覆盖 dataDriverPathOverride，列写 null</li>
     *   <li>snapshot 同步重建</li>
     * </ul>
     *
     * <p>Body (可选): {@code { "templateIds": ["uuid1", "uuid2"] }} — 不传或空 → 处理全部 PUBLISHED 模板.
     *
     * <p>返回: { totalTemplates, totalTcMigrated, totalFieldsMigrated, details[] }
     */
    @POST
    @Path("/admin/migrate-to-unified-view")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<java.util.Map<String, Object>> adminMigrateToUnifiedView(
            java.util.Map<String, Object> body) {
        List<UUID> templateIds = null;
        if (body != null && body.get("templateIds") instanceof List<?> tidList) {
            templateIds = tidList.stream()
                    .filter(o -> o != null)
                    .map(o -> UUID.fromString(o.toString()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return ApiResponse.success(templateService.migrateToUnifiedView(templateIds));
    }

    /**
     * 2026-05-20: SYSTEM_ADMIN 一次性数据修复 — 删 PUBLISHED 模板的某些 Tab.
     *
     * <p>用途: V195 等 Flyway SQL 自动写入的 template_component (绕过组件管理 UI),
     * 用户想删但 PUBLISHED 模板不能走 update 端点 → 走这个 admin endpoint.
     *
     * <p>Body 示例:
     * <pre>POST /api/cpq/templates/admin/{templateId}/delete-tcs
     * { "sortOrders": [0, 1, 2, 4] }</pre>
     *
     * <p>返回: { deletedTcs, snapshotBefore, snapshotAfter }
     *
     * 2026-05-20: SYSTEM_ADMIN 数据修复 — 给某 tc 的 fields_override 注入 _composite 双轨字段.
     * @deprecated 统一智能视图方案后不再推荐使用，使用 migrate-to-unified-view 替代
     *
     * <p>Body:
     * <pre>
     * {
     *   "tcId": "...",
     *   "dataDriverPathComposite": "v_composite_child_processes",
     *   "fieldComposites": [
     *     {"name": "工序代码", "basicDataPathComposite": "v_composite_child_processes.process_code"}
     *   ]
     * }
     * </pre>
     */
    @POST
    @Path("/admin/{templateId}/patch-composite")
    @RoleAllowed({"SYSTEM_ADMIN"})
    @SuppressWarnings("deprecation")
    public ApiResponse<java.util.Map<String, Object>> adminPatchComposite(
            @PathParam("templateId") UUID templateId,
            java.util.Map<String, Object> body) {
        if (body == null) throw new com.cpq.common.exception.BusinessException(400, "body required");
        UUID tcId = body.get("tcId") != null ? UUID.fromString(body.get("tcId").toString()) : null;
        if (tcId == null) throw new com.cpq.common.exception.BusinessException(400, "tcId required");
        String dataDriverPathComposite = body.get("dataDriverPathComposite") != null
                ? body.get("dataDriverPathComposite").toString() : null;
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, String>> fieldComposites = body.get("fieldComposites") instanceof List
                ? ((List<Object>) body.get("fieldComposites")).stream()
                        .map(o -> {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
                            java.util.Map<String, String> r = new java.util.HashMap<>();
                            if (m.get("name") != null) r.put("name", m.get("name").toString());
                            if (m.get("basicDataPathComposite") != null) r.put("basicDataPathComposite", m.get("basicDataPathComposite").toString());
                            return r;
                        })
                        .collect(java.util.stream.Collectors.toList())
                : java.util.Collections.emptyList();
        // 2026-05-20: replaceFieldsOverride — 提供时整体替换 tc.fieldsOverride (问题 1: 加缺失字段)
        @SuppressWarnings("unchecked")
        List<Object> replaceFieldsOverride = body.get("fieldsOverride") instanceof List
                ? (List<Object>) body.get("fieldsOverride")
                : null;
        return ApiResponse.success(templateService.patchTemplateComponentCompositeOverrides(
                templateId, tcId, dataDriverPathComposite, fieldComposites, replaceFieldsOverride));
    }

    @POST
    @Path("/admin/{templateId}/delete-tcs")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<java.util.Map<String, Object>> adminDeleteTcs(
            @PathParam("templateId") UUID templateId,
            java.util.Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Integer> sortOrders = body != null && body.get("sortOrders") instanceof List
                ? ((List<Object>) body.get("sortOrders")).stream()
                        .map(v -> v instanceof Number ? ((Number) v).intValue() : Integer.parseInt(v.toString()))
                        .collect(java.util.stream.Collectors.toList())
                : java.util.Collections.emptyList();
        return ApiResponse.success(templateService.deleteTemplateComponentsBySortOrder(templateId, sortOrders));
    }

    /**
     * 2026-05-21: 将 template_component.fields_override 上升为 component.fields（单一来源）.
     *
     * <p>解决问题：组件管理 UI 看到的字段（component.fields）与实际渲染字段（fields_override 覆盖）
     * 不一致，用户无法在 UI 中维护"子件"等字段。
     *
     * <p>执行后：
     * <ul>
     *   <li>component.fields = 最完整的 fields_override（含"子件"字段）</li>
     *   <li>component.dataDriverPath = 从 tc.dataDriverPathOverride 推断（如有）</li>
     *   <li>所有 tc.fields_override = NULL，tc.dataDriverPathOverride = NULL</li>
     *   <li>所有模板 snapshot 同步刷新</li>
     * </ul>
     *
     * <p>Body（可选）：
     * <pre>{ "componentIds": ["e42185ec-...", "dae85db8-...", "0a436b6c-..."] }</pre>
     * 不传或 componentIds 为空 → 默认处理所有名称以"选配-"开头的 ACTIVE 组件。
     *
     * <p>返回：{ targetComponents, componentsUpdated, tcCleared, snapshotTouched, details[] }
     */
    @POST
    @Path("/admin/promote-override-to-component")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<java.util.Map<String, Object>> adminPromoteOverrideToComponent(
            java.util.Map<String, Object> body) {
        List<UUID> componentIds = null;
        if (body != null && body.get("componentIds") instanceof List<?> cidList) {
            componentIds = cidList.stream()
                    .filter(o -> o != null)
                    .map(o -> UUID.fromString(o.toString()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return ApiResponse.success(templateService.promoteOverrideToComponent(componentIds));
    }
}
