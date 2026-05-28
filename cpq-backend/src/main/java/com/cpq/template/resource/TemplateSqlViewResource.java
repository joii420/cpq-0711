package com.cpq.template.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.component.dto.DryRunSqlViewResponse;
import com.cpq.template.dto.CreateTemplateSqlViewRequest;
import com.cpq.template.dto.DryRunTemplateSqlViewRequest;
import com.cpq.template.dto.TemplateSqlViewDTO;
import com.cpq.template.dto.UpdateTemplateSqlViewRequest;
import com.cpq.template.service.TemplateSqlViewService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * 产品卡片模板 SQL 视图 REST 端点（Phase 2 迁移自 costing 包）。
 *
 * <p>路由（与 ComponentSqlViewResource 路由风格对齐）：
 * <pre>
 *   GET    /api/cpq/templates/{templateId}/sql-views
 *   GET    /api/cpq/templates/{templateId}/sql-views/{id}
 *   POST   /api/cpq/templates/{templateId}/sql-views
 *   PUT    /api/cpq/templates/sql-views/{id}
 *   DELETE /api/cpq/templates/sql-views/{id}
 *   POST   /api/cpq/templates/sql-views/dry-run
 * </pre>
 *
 * <p>隔离约束在 Service 层强制（非 DRAFT 模板禁止 CUD）。
 * 角色：PRICING_MANAGER / SYSTEM_ADMIN（CUD）；读取操作加 SALES_MANAGER。
 * V249 起替代 Phase 1 的 CostingTemplateSqlViewResource。
 */
@Path("/api/cpq/templates/{templateId}/sql-views")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TemplateSqlViewResource {

    private static final Logger LOG = Logger.getLogger(TemplateSqlViewResource.class);

    @Inject
    TemplateSqlViewService service;

    /**
     * 列出指定模板下所有 ACTIVE SQL 视图。
     * GET /api/cpq/templates/{templateId}/sql-views
     */
    @GET
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN", "SALES_MANAGER"})
    public ApiResponse<List<TemplateSqlViewDTO>> list(
            @PathParam("templateId") UUID templateId) {
        return ApiResponse.success(service.list(templateId));
    }

    /**
     * 获取单条 SQL 视图。
     * GET /api/cpq/templates/{templateId}/sql-views/{id}
     */
    @GET
    @Path("/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN", "SALES_MANAGER"})
    public ApiResponse<TemplateSqlViewDTO> getById(
            @PathParam("templateId") UUID templateId,
            @PathParam("id") UUID id) {
        return ApiResponse.success(service.getForTemplate(templateId, id));
    }

    /**
     * 新建 SQL 视图（同时 dry-run 校验，校验失败抛 400；模板非 DRAFT 抛 400）。
     * POST /api/cpq/templates/{templateId}/sql-views
     */
    @POST
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<TemplateSqlViewDTO> create(
            @PathParam("templateId") UUID templateId,
            CreateTemplateSqlViewRequest req) {
        LOG.infof("[TemplateSqlView] create templateId=%s name=%s", templateId, req.sqlViewName);
        return ApiResponse.success(service.create(templateId, req, null));
    }

    /**
     * 更新 SQL 视图（改 SQL 时重跑 dry-run；模板非 DRAFT 抛 400）。
     * PUT /api/cpq/templates/{templateId}/sql-views/{id}
     */
    @PUT
    @Path("/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<TemplateSqlViewDTO> update(
            @PathParam("templateId") UUID templateId,
            @PathParam("id") UUID id,
            UpdateTemplateSqlViewRequest req) {
        LOG.infof("[TemplateSqlView] update templateId=%s id=%s", templateId, id);
        return ApiResponse.success(service.update(id, req));
    }

    /**
     * 软删除（status → INACTIVE；模板非 DRAFT 抛 400）。
     * DELETE /api/cpq/templates/{templateId}/sql-views/{id}
     */
    @DELETE
    @Path("/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(
            @PathParam("templateId") UUID templateId,
            @PathParam("id") UUID id) {
        LOG.infof("[TemplateSqlView] delete templateId=%s id=%s", templateId, id);
        service.delete(id);
        return ApiResponse.success(null);
    }

    /**
     * Dry-run 校验：仅校验 SQL，不落库。返回列签名 + 占位符清单。
     * POST /api/cpq/templates/{templateId}/sql-views/dry-run
     *
     * <p>注意：JAX-RS 路由 dry-run 在 /{id} 之前匹配（"dry-run" 是字面量，优先于 UUID 模板）。
     */
    @POST
    @Path("/dry-run")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<DryRunSqlViewResponse> dryRun(
            @PathParam("templateId") UUID templateId,
            DryRunTemplateSqlViewRequest req) {
        return ApiResponse.success(service.dryRun(req.sqlTemplate));
    }
}
