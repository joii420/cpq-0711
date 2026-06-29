package com.cpq.quotation.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.quotation.dto.CostingOrderListItemDTO;
import com.cpq.quotation.service.QuotationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * 核价管理 REST 端点（第一期）。
 *
 * <p>GET /api/cpq/costing-orders — 核价工作台列表，仅 PRICING_MANAGER / SYSTEM_ADMIN 可访问。
 *
 * <p>核价通过 / 驳回动作由 QuotationResource 提供：
 * POST /api/cpq/quotations/{id}/costing-approve
 * POST /api/cpq/quotations/{id}/costing-reject
 */
@Path("/api/cpq/costing-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
public class CostingOrderResource {

    @Inject
    QuotationService quotationService;

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
}
