package com.cpq.component.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.component.service.ComponentSampleCardService;
import com.cpq.component.service.ComponentTabDefService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ComponentTabJoinResource {

    @Inject
    ComponentTabDefService componentTabDefService;

    @Inject
    ComponentSampleCardService componentSampleCardService;

    @GET
    @Path("/{id}/tab-defs")
    public ApiResponse<List<Map<String, Object>>> tabDefs(@PathParam("id") UUID id) {
        return ApiResponse.success(componentTabDefService.tabDefsForComponent(id));
    }

    /**
     * GET /api/cpq/components/{id}/sample-cards
     * 组件级样本卡片：反查引用本组件的报价行（最多 50 条），供抽屉选样本试算。
     * 无引用 → 返回空列表（抽屉据此禁用试算，仅允许保存表达式）。
     * 响应：[{quotationId, quotationNo, lineItemId, cardName}]，shape 与模板级一致。
     */
    @GET
    @Path("/{id}/sample-cards")
    public ApiResponse<List<Map<String, Object>>> sampleCards(@PathParam("id") UUID id) {
        return ApiResponse.success(componentSampleCardService.sampleCardsForComponent(id));
    }

    /**
     * POST /api/cpq/components/{id}/dry-run
     * 组件级试算：给样本 lineItem + TAB_JOIN_FORMULA 列配置 → 返回单值。
     * 复用模板级试算内核（ExcelViewService#dryRunTabFormula），仅上下文来源换成组件。
     * 请求体：{"lineItemId": "uuid"(可空), "column": {...col定义...}, "cardValuesJson": "可选"}
     * 无 lineItemId / 无样本 → 返回 {"value":null,"errors":["试算不可用(无样本卡)..."]}（非 500）。
     */
    @POST
    @Path("/{id}/dry-run")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> dryRun(
            @PathParam("id") UUID id,
            Map<String, Object> body) {
        if (body == null) throw new BusinessException(400, "请求体不能为空");
        Object colObj = body.get("column");
        if (colObj == null) throw new BusinessException(400, "column is required");
        if (!(colObj instanceof Map)) throw new BusinessException(400, "column 必须是对象");
        Map<String, Object> column = (Map<String, Object>) colObj;
        String cardValuesJson = body.get("cardValuesJson") != null
            ? body.get("cardValuesJson").toString() : null;
        UUID lineItemId = null;
        Object liIdObj = body.get("lineItemId");
        if (liIdObj != null && !liIdObj.toString().isBlank()) {
            try {
                lineItemId = UUID.fromString(liIdObj.toString());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "lineItemId 格式非法: " + liIdObj);
            }
        }
        return ApiResponse.success(
            componentSampleCardService.dryRunForComponent(id, lineItemId, column, cardValuesJson));
    }
}
