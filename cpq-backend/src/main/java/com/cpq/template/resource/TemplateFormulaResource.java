package com.cpq.template.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.template.dto.FormulaCompletionDTO;
import com.cpq.template.dto.TemplateFormulaDTO;
import com.cpq.template.service.TemplateFormulaService;
import com.cpq.template.service.TemplateFormulaService.ValidationResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V145 (Stage 1) — 模板公式 CRUD + 试算端点.
 *
 * <pre>
 *   GET    /api/cpq/templates/{templateId}/formulas
 *   POST   /api/cpq/templates/{templateId}/formulas               (新增, 仅 DRAFT)
 *   PUT    /api/cpq/templates/{templateId}/formulas/{name}        (更新, 仅 DRAFT)
 *   DELETE /api/cpq/templates/{templateId}/formulas/{name}        (删除, 仅 DRAFT)
 *   POST   /api/cpq/templates/{templateId}/formulas/{name}/evaluate (试算)
 *   POST   /api/cpq/templates/{templateId}/formulas/validate      (保存前校验)
 * </pre>
 */
@Path("/api/cpq/templates/{templateId}/formulas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN", "PRICING_MANAGER"})
public class TemplateFormulaResource {

    @Inject
    TemplateFormulaService service;

    @GET
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN", "PRICING_MANAGER"})
    public ApiResponse<List<TemplateFormulaDTO>> list(@PathParam("templateId") UUID templateId) {
        return ApiResponse.success(service.listByTemplate(templateId));
    }

    @POST
    public ApiResponse<TemplateFormulaDTO> add(@PathParam("templateId") UUID templateId,
                                                TemplateFormulaDTO dto) {
        return ApiResponse.success(service.addFormula(templateId, dto));
    }

    @PUT
    @Path("/{name}")
    public ApiResponse<TemplateFormulaDTO> update(@PathParam("templateId") UUID templateId,
                                                   @PathParam("name") String name,
                                                   TemplateFormulaDTO dto) {
        return ApiResponse.success(service.updateFormula(templateId, name, dto));
    }

    @DELETE
    @Path("/{name}")
    public ApiResponse<Void> delete(@PathParam("templateId") UUID templateId,
                                     @PathParam("name") String name) {
        service.deleteFormula(templateId, name);
        return ApiResponse.success();
    }

    /**
     * 试算: 给 partNo + customerId, 返回该公式的求值结果.
     * Body: {partNo, customerId, trace}
     *   trace=true 时返回 {value, trace:{依赖公式: 求值结果}}
     */
    @POST
    @Path("/{name}/evaluate")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN", "PRICING_MANAGER"})
    public ApiResponse<Object> evaluate(@PathParam("templateId") UUID templateId,
                                         @PathParam("name") String name,
                                         Map<String, Object> body) {
        String partNo = body != null && body.get("partNo") != null ? body.get("partNo").toString() : null;
        UUID customerId = null;
        if (body != null && body.get("customerId") != null) {
            try { customerId = UUID.fromString(body.get("customerId").toString()); }
            catch (Exception ignored) {}
        }
        boolean trace = body != null && Boolean.TRUE.equals(body.get("trace"));
        return ApiResponse.success(service.evaluateFormula(templateId, name, customerId, partNo, trace));
    }

    /** 保存前校验: 表达式合法 + 依赖完整 + 不引入循环 */
    @POST
    @Path("/validate")
    public ApiResponse<ValidationResult> validate(@PathParam("templateId") UUID templateId,
                                                   TemplateFormulaDTO dto) {
        return ApiResponse.success(service.validateFormula(templateId, dto));
    }

    /**
     * Stage4 Debug: 直接测试 SUM_OVER 内部逻辑，返回中间步骤。
     * Body: {partNo, expression}
     */
    @POST
    @Path("/debug-sum-over")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<Map<String, Object>> debugSumOver(@PathParam("templateId") UUID templateId,
                                                          Map<String, Object> body) {
        String partNo = body != null && body.get("partNo") != null ? body.get("partNo").toString() : null;
        String expression = body != null && body.get("expression") != null ? body.get("expression").toString() : null;
        return ApiResponse.success(service.debugSumOver(expression, partNo));
    }

    /**
     * P0 自动补全 API: 返回当前模板的公式 / 组件字段 / 全局变量三类候选数据.
     *
     * <pre>
     *   GET /api/cpq/templates/{templateId}/formula-completions
     * </pre>
     *
     * <p>响应结构：
     * <pre>{
     *   "templateFormulas": [{name, dataType, description}],
     *   "components":       [{code, name, fields:[{name, label, dataType}]}],
     *   "globalVariables":  [{name, code, dataType, currentValue, description, unit, varType}]
     * }</pre>
     *
     * <p>供前端公式 textarea 触发自动补全时调用（每次打开公式编辑器时预加载一次）。
     */
    @GET
    @Path("/completions")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN", "PRICING_MANAGER"})
    public ApiResponse<FormulaCompletionDTO> getCompletions(@PathParam("templateId") UUID templateId) {
        return ApiResponse.success(service.getFormulaCompletions(templateId));
    }
}
