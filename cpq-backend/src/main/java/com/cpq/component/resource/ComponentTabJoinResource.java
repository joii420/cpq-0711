package com.cpq.component.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.component.service.ComponentTabDefService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 组件级 TAB_JOIN_FORMULA 配置支撑端点。
 *
 * <p>{@code GET /api/cpq/components/{id}/tab-defs} 返回同目录组件页签定义，供
 * {@code TabJoinFormulaDrawer}（公式编辑器）的「可引用页签矩阵」消费。
 * 返回 shape 与模板级 {@code /templates/{id}/excel-view-config/tab-defs} 一致。
 */
@Path("/api/cpq/components")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ComponentTabJoinResource {

    @Inject
    ComponentTabDefService componentTabDefService;

    @GET
    @Path("/{id}/tab-defs")
    public ApiResponse<List<Map<String, Object>>> tabDefs(@PathParam("id") UUID id) {
        return ApiResponse.success(componentTabDefService.tabDefsForComponent(id));
    }
}
