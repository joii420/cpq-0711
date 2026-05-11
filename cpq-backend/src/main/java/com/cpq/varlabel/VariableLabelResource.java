package com.cpq.varlabel;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * V149 变量标签 REST 端点.
 *
 * <ul>
 *   <li>GET  /api/cpq/variable-labels                      — 全部 ACTIVE 行 (前端选择器一次拉取)</li>
 *   <li>GET  /api/cpq/variable-labels/grouped              — 按 category 分组 (前端"业务域树")</li>
 *   <li>GET  /api/cpq/variable-labels/by-path?path=...     — 按路径精确查 (回退判定用)</li>
 *   <li>POST /api/cpq/variable-labels                      — 渐进式起名 (用户编辑器里弹窗补名)</li>
 * </ul>
 *
 * <p>读权限放给所有业务角色 (公式编辑器/Excel 列编辑器都要用).
 * 写权限默认 SALES_MANAGER+ — 起名是配置层动作, 非任意人可改.
 */
@Path("/api/cpq/variable-labels")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class VariableLabelResource {

    @Inject
    VariableLabelService service;

    @GET
    public ApiResponse<List<VariableLabel>> list() {
        return ApiResponse.success(service.listAll());
    }

    @GET
    @Path("/grouped")
    public ApiResponse<LinkedHashMap<String, List<VariableLabel>>> grouped() {
        return ApiResponse.success(service.groupByCategory());
    }

    @GET
    @Path("/by-path")
    public ApiResponse<VariableLabel> getByPath(@QueryParam("path") String path) {
        return service.findByPath(path)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "未注册的变量路径: " + path));
    }

    @POST
    @RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<VariableLabel> upsert(QuickNameRequest req) {
        if (req == null) {
            return ApiResponse.error(400, "请求体不能为空");
        }
        VariableLabel v = service.quickName(
                req.variablePath, req.displayName, req.category,
                req.dataType, req.unit, req.description);
        return ApiResponse.success(v);
    }

    public static class QuickNameRequest {
        public String variablePath;
        public String displayName;
        public String category;
        public String dataType;
        public String unit;
        public String description;
    }

    /**
     * V149 Phase 2: 样本求值. 用户在模板配置时点 ▶ 试算按钮触发.
     * 仅注册过的 path 才能查 (服务层白名单校验).
     */
    @POST
    @Path("/eval")
    public ApiResponse<java.util.Map<String, Object>> eval(EvalRequest req) {
        if (req == null || req.path == null) {
            return ApiResponse.error(400, "path 必填");
        }
        try {
            Object v = service.evalAt(req.path, req.hfPartNo);
            return ApiResponse.success(java.util.Map.of("value", v == null ? "" : v));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "求值失败: " + e.getMessage());
        }
    }

    public static class EvalRequest {
        public String path;
        public String hfPartNo;
    }
}
