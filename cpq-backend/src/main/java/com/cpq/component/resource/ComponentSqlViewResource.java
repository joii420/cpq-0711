package com.cpq.component.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.component.dto.ComponentSqlViewDTO;
import com.cpq.component.dto.CreateComponentSqlViewRequest;
import com.cpq.component.dto.DryRunSqlViewRequest;
import com.cpq.component.dto.DryRunSqlViewResponse;
import com.cpq.component.service.ComponentSqlViewService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * 组件 SQL 视图 REST 端点（方案 §5.4 / §7）。
 *
 * <p>路径：{@code /api/cpq/components/{cid}/sql-views}
 */
@Path("/api/cpq/components/{cid}/sql-views")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
public class ComponentSqlViewResource {

    private static final Logger LOG = Logger.getLogger(ComponentSqlViewResource.class);

    @Inject
    ComponentSqlViewService service;

    /** 列表：本组件全部 SQL 视图。 */
    @GET
    public ApiResponse<List<ComponentSqlViewDTO>> list(@PathParam("cid") UUID componentId) {
        return ApiResponse.success(service.listByComponent(componentId));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<ComponentSqlViewDTO> getById(
            @PathParam("cid") UUID componentId,
            @PathParam("id") UUID id) {
        return ApiResponse.success(service.get(componentId, id));
    }

    /** 新建（同时 dry-run 校验，校验失败抛 400）。 */
    @POST
    public ApiResponse<ComponentSqlViewDTO> create(
            @PathParam("cid") UUID componentId,
            CreateComponentSqlViewRequest req) {
        LOG.infof("[ComponentSqlView] create componentId=%s name=%s", componentId, req.sqlViewName);
        // TODO: 接通 SecurityContext 拿 userId 写入 createdBy
        return ApiResponse.success(service.create(componentId, req, null));
    }

    /** 更新（同时 dry-run 校验，校验失败抛 400）。 */
    @PUT
    @Path("/{id}")
    public ApiResponse<ComponentSqlViewDTO> update(
            @PathParam("cid") UUID componentId,
            @PathParam("id") UUID id,
            CreateComponentSqlViewRequest req) {
        LOG.infof("[ComponentSqlView] update componentId=%s id=%s", componentId, id);
        return ApiResponse.success(service.update(componentId, id, req));
    }

    /** 软删除（status → INACTIVE）。 */
    @DELETE
    @Path("/{id}")
    public ApiResponse<Void> delete(
            @PathParam("cid") UUID componentId,
            @PathParam("id") UUID id) {
        LOG.infof("[ComponentSqlView] delete componentId=%s id=%s", componentId, id);
        service.delete(componentId, id);
        return ApiResponse.success(null);
    }

    /** Dry-run 校验：仅校验 SQL，不落库。返回列签名 + 占位符清单。 */
    @POST
    @Path("/dry-run")
    public ApiResponse<DryRunSqlViewResponse> dryRun(
            @PathParam("cid") UUID componentId,
            DryRunSqlViewRequest req) {
        return ApiResponse.success(service.dryRun(req.sqlTemplate));
    }
}
