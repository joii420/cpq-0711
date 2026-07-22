package com.cpq.component.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.component.entity.CostingBomTreeConfig;
import com.cpq.component.service.CostingBomTreeConfigService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 全局可配置「核价树递归 SQL」配置的 CRUD + 设为生效端点。
 */
@Path("/api/cpq/costing-bom-tree-config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
public class CostingBomTreeConfigResource {

    @Inject
    CostingBomTreeConfigService service;

    /** task-0721 B2：usage 查询参数（QUOTE/COSTING），不传 = 返回全部（api.md §2.1 向后兼容）。 */
    @GET
    public ApiResponse<List<CostingBomTreeConfig>> list(@QueryParam("usage") String usage) {
        return ApiResponse.success(service.list(usage));
    }

    /** task-0721 B2：请求体新增 usage 字段（api.md §2.2，未传 = 兜底 COSTING，与列默认值一致）。 */
    @POST
    public ApiResponse<CostingBomTreeConfig> create(Map<String, String> body) {
        return ApiResponse.success(service.create(body.get("name"), body.get("sqlTemplate"), body.get("usage")));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<CostingBomTreeConfig> update(@PathParam("id") UUID id, Map<String, String> body) {
        return ApiResponse.success(service.update(id, body.get("name"), body.get("sqlTemplate"), body.get("usage")));
    }

    @POST
    @Path("/{id}/activate")
    public ApiResponse<Void> activate(@PathParam("id") UUID id) {
        service.setActive(id);
        return ApiResponse.success();
    }

    @DELETE
    @Path("/{id}")
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        service.delete(id);
        return ApiResponse.success();
    }
}
