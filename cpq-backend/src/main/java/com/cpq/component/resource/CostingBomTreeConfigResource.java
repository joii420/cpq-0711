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
 * 全局可配置「核价/报价树递归 SQL」配置的 CRUD + 设为生效端点。
 *
 * <p>🔒 <b>task-260909 B-8（AC-18，用户裁决 D-5）：类级角色由
 * {@code {SALES_MANAGER, SYSTEM_ADMIN}} 收紧为 {@code {SYSTEM_ADMIN}}</b>。
 * 本资源改的是<b>全库唯一一份</b>的树递归骨架 SQL —— 它决定所有报价单/核价单的树长什么样，
 * 属系统级配置而非业务操作。
 * <p>类级注解覆盖类下<b>全部</b>端点（{@code GET}/{@code POST}/{@code PUT}/{@code activate}/{@code DELETE}），
 * 🚫 <b>不新增方法级注解</b>：方法级会覆盖类级，加一处就等于给"哪些端点归谁"开了第二个事实来源，
 * 漏一个方法就是一个静默的权限缺口。
 * <p>影响面（2026-09-09 实测）：{@code SALES_MANAGER} 仅 3 个账号且全为测试号。
 */
@Path("/api/cpq/costing-bom-tree-config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN"})
public class CostingBomTreeConfigResource {

    @Inject
    CostingBomTreeConfigService service;

    /**
     * usage 查询参数（{@code QUOTE} / {@code COST_BASIC} / {@code COST_DETAIL}，
     * 另接受只读兼容别名 {@code COSTING} → 按 {@code COST_BASIC} 处理）；
     * 不传 = 返回全部（api.md §1.1 向后兼容）。
     */
    @GET
    public ApiResponse<List<CostingBomTreeConfig>> list(@QueryParam("usage") String usage) {
        return ApiResponse.success(service.list(usage));
    }

    /**
     * 请求体 {@code usage} 字段（api.md §1.1）。
     * <p>🚫 task-260909 B-2：<b>未传 = 400</b>，原「兜底 COSTING」的默认行为已取消 ——
     * 三套数据集并存后静默兜底就是「用户选了详细核价、系统写成基础核价」那类静默故障。
     * <p>🚫 写入 {@code usage=COSTING} 同样 400（只读兼容别名）。
     */
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
