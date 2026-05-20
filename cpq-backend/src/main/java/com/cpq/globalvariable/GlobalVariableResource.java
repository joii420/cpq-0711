package com.cpq.globalvariable;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * V104 全局变量 REST 端点.
 *
 * <ul>
 *   <li>GET  /api/cpq/global-variables                        — 列出所有已注册全局变量 (公式选择器拉取)</li>
 *   <li>GET  /api/cpq/global-variables/{code}                 — 单个变量定义</li>
 *   <li>GET  /api/cpq/global-variables/{code}/keys?limit=     — 候选 key 列表 (UI 选 key 用)</li>
 *   <li>GET  /api/cpq/global-variables/{code}/value?...       — 直接取值 (后端 evaluate 调试用)</li>
 * </ul>
 */
@Path("/api/cpq/global-variables")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class GlobalVariableResource {

    @Inject
    GlobalVariableService service;

    @Inject
    SessionHelper sessionHelper;

    @GET
    public ApiResponse<List<GlobalVariableDefinition>> list() {
        return ApiResponse.success(service.listAll());
    }

    /**
     * G1: 新建全局变量定义. 仅 PRICING_MANAGER+ 可写, 形态强制 KV_TABLE + PUBLIC.
     * 核价类 (COSTING_VIEW) 仅 Flyway 初始化, 不接受 UI 新建.
     */
    @POST
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN", "SALES_MANAGER"})
    public ApiResponse<GlobalVariableDefinition> createDefinition(GlobalVariableDefinition req) {
        return ApiResponse.success(service.createDefinition(req));
    }

    /**
     * G1: 删除全局变量定义. 核价变量不可删. 级联清 global_variable_value (FK CASCADE).
     */
    @DELETE
    @Path("/{code}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN", "SALES_MANAGER"})
    public ApiResponse<String> deleteDefinition(@PathParam("code") String code) {
        service.deleteDefinition(code);
        return ApiResponse.success("ok");
    }

    @GET
    @Path("/{code}")
    public ApiResponse<GlobalVariableDefinition> getOne(@PathParam("code") String code) {
        return service.getByCode(code)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "全局变量未注册: " + code));
    }

    @GET
    @Path("/{code}/keys")
    public ApiResponse<List<Map<String, Object>>> listKeys(
            @PathParam("code") String code,
            @QueryParam("limit") @DefaultValue("1000") int limit) {
        return ApiResponse.success(service.listKeys(code, Math.min(limit, 5000)));
    }

    /**
     * 取值. 复合键时 query 参数按列名传, 例如 ?from_currency=CNY&to_currency=USD.
     */
    @GET
    @Path("/{code}/value")
    public ApiResponse<BigDecimal> getValue(
            @PathParam("code") String code,
            @Context UriInfo uriInfo) {
        var qp = uriInfo.getQueryParameters();
        var def = service.getByCode(code)
                .orElseThrow(() -> new com.cpq.common.exception.BusinessException(404, "未注册: " + code));
        java.util.Map<String, Object> keyValues = new java.util.LinkedHashMap<>();
        for (String col : def.keyColumns) {
            String v = qp.getFirst(col);
            if (v != null) keyValues.put(col, v);
        }
        return ApiResponse.success(service.resolveValue(code, keyValues));
    }

    // ───────── V106: P2 CRUD + 变更日志 ─────────

    /**
     * Upsert 一条明细行. 静默 no-op 当值未变.
     * 决策 #2: 仅 PRICING_MANAGER+ 可写.
     */
    @POST
    @Path("/{code}/entries")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN", "SALES_MANAGER"})
    public ApiResponse<Map<String, Object>> upsertEntry(
            @PathParam("code") String code,
            UpsertEntryRequest req,
            @Context io.vertx.core.http.HttpServerRequest httpReq) {
        if (req == null || req.keyValues == null || req.value == null) {
            throw new com.cpq.common.exception.BusinessException(400, "keyValues 与 value 必填");
        }
        java.util.UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpReq);
        String userName = lookupUserName(userId);
        BigDecimal v = service.upsertEntry(code, req.keyValues, req.value, req.note, userId, userName);
        return ApiResponse.success(Map.of("value", v));
    }

    /**
     * 删除一条明细行. 幂等.
     */
    @DELETE
    @Path("/{code}/entries")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN", "SALES_MANAGER"})
    public ApiResponse<String> deleteEntry(
            @PathParam("code") String code,
            DeleteEntryRequest req,
            @Context io.vertx.core.http.HttpServerRequest httpReq) {
        if (req == null || req.keyValues == null) {
            throw new com.cpq.common.exception.BusinessException(400, "keyValues 必填");
        }
        java.util.UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpReq);
        String userName = lookupUserName(userId);
        service.deleteEntry(code, req.keyValues, req.note, userId, userName);
        return ApiResponse.success("ok");
    }

    /**
     * 变更日志. code 留空 = 全部.
     */
    @GET
    @Path("/change-log")
    public ApiResponse<List<Map<String, Object>>> listChangeLog(
            @QueryParam("code") String code,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        return ApiResponse.success(service.listChangeLog(code, limit));
    }

    private String lookupUserName(java.util.UUID userId) {
        if (userId == null) return null;
        var u = com.cpq.system.entity.User.findById(userId);
        if (u == null) return null;
        return ((com.cpq.system.entity.User) u).fullName;
    }

    public static class UpsertEntryRequest {
        public Map<String, Object> keyValues;
        public BigDecimal value;
        public String note;
    }

    public static class DeleteEntryRequest {
        public Map<String, Object> keyValues;
        public String note;
    }
}
