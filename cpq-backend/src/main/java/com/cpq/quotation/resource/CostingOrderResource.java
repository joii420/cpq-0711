package com.cpq.quotation.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.quotation.dto.CostingOrderListItemDTO;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.service.QuotationService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 核价管理 REST 端点（第一期）。
 *
 * <p>GET  /api/cpq/costing-orders         — 核价工作台列表
 * <p>POST /api/cpq/costing-orders/{id}/approve — 核价通过
 * <p>POST /api/cpq/costing-orders/{id}/reject  — 核价驳回
 *
 * <p>访问控制：仅 PRICING_MANAGER / SYSTEM_ADMIN。
 */
@Path("/api/cpq/costing-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
public class CostingOrderResource {

    @Inject
    QuotationService quotationService;

    @Inject
    SessionHelper sessionHelper;

    /**
     * 核价管理列表。
     *
     * @param status 状态过滤：待核价 / 核价驳回 / 核价通过；null 表示全部
     * @param sort   排序字段（预留，当前不生效）
     */
    @GET
    public ApiResponse<List<CostingOrderListItemDTO>> list(
            @QueryParam("status") String status,
            @QueryParam("sort") String sort) {
        return ApiResponse.success(quotationService.listCostingOrders(status, sort));
    }

    /**
     * 核价通过：将 quotation.status 置为 APPROVED，写 COSTING_APPROVED 审批记录。
     *
     * <p>请求体：{"comment": "..."} （可选）
     */
    @POST
    @Path("/{id}/approve")
    public ApiResponse<QuotationDTO> approve(
            @PathParam("id") UUID id,
            Map<String, String> body,
            @Context HttpServerRequest request) {
        UUID currentUserId = sessionHelper.getCurrentUserIdOrFallback(request);
        String comment = body != null ? body.get("comment") : null;
        return ApiResponse.success(quotationService.costingApprove(id, comment, currentUserId));
    }

    /**
     * 核价驳回：将 quotation.status 置为 COSTING_REJECTED，写 COSTING_REJECTED 审批记录。
     *
     * <p>请求体：{"reason": "..."} （必填）
     */
    @POST
    @Path("/{id}/reject")
    public ApiResponse<QuotationDTO> reject(
            @PathParam("id") UUID id,
            Map<String, String> body,
            @Context HttpServerRequest request) {
        UUID currentUserId = sessionHelper.getCurrentUserIdOrFallback(request);
        String reason = body != null ? body.get("reason") : null;
        return ApiResponse.success(quotationService.costingReject(id, reason, currentUserId));
    }
}
