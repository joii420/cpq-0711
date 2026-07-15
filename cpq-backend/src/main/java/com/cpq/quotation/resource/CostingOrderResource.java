package com.cpq.quotation.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.quotation.dto.CostingOrderDetailDTO;
import com.cpq.quotation.dto.CostingOrderListItemDTO;
import com.cpq.quotation.dto.VersionOptionsResponseDTO;
import com.cpq.quotation.dto.VersionSwitchRequest;
import com.cpq.quotation.dto.VersionSwitchResponseDTO;
import com.cpq.quotation.service.CostingVersionService;
import com.cpq.quotation.service.QuotationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * 核价管理 REST 端点（第一期）。
 *
 * <p>GET /api/cpq/costing-orders             — 核价工作台列表（多状态过滤 + 关键字搜索）
 * <p>GET /api/cpq/costing-orders/{coid}      — 单条核价单详情（含冻结副本）
 *
 * <p>仅 PRICING_MANAGER / SYSTEM_ADMIN 可访问。
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

    @Inject
    CostingVersionService costingVersionService;

    /**
     * 核价管理列表。
     *
     * @param statuses 英文码状态过滤（可多值）：PENDING / APPROVED / REJECTED / WITHDRAWN；
     *                 null 或不传表示返回全部
     * @param keyword  按报价单号模糊搜索（不区分大小写），不传则不过滤
     * @param sort     排序字段："status" / "updatedAt"；默认按 entered_costing_at DESC
     */
    @GET
    public ApiResponse<List<CostingOrderListItemDTO>> list(
            @QueryParam("status") List<String> statuses,
            @QueryParam("keyword") String keyword,
            @QueryParam("sort") String sort) {
        return ApiResponse.success(quotationService.listCostingOrders(statuses, keyword, sort));
    }

    /**
     * 单条核价单详情（含冻结副本 frozenDto）。
     *
     * @param coid 核价单 ID
     */
    @GET
    @Path("/{coid}")
    public ApiResponse<CostingOrderDetailDTO> getOne(@PathParam("coid") UUID coid) {
        return ApiResponse.success(quotationService.getCostingOrderById(coid));
    }

    /**
     * task-0713 B6：查询某料号在某页签的可选版本（下拉数据源，api.md §2）。
     */
    @GET
    @Path("/{coid}/version-options")
    public ApiResponse<VersionOptionsResponseDTO> versionOptions(
            @PathParam("coid") UUID coid,
            @QueryParam("lineItemId") UUID lineItemId,
            @QueryParam("componentId") UUID componentId,
            @QueryParam("partNo") String partNo) {
        return ApiResponse.success(costingVersionService.listVersionOptions(coid, lineItemId, componentId, partNo));
    }

    /**
     * task-0713 B7：切换版本（核心写操作，api.md §3）。仅 PENDING 核价单可切
     * （角色已由本类 {@code @RoleAllowed} 门禁保证）。
     */
    @POST
    @Path("/{coid}/version-switch")
    public ApiResponse<VersionSwitchResponseDTO> versionSwitch(
            @PathParam("coid") UUID coid, VersionSwitchRequest body) {
        return ApiResponse.success(costingVersionService.switchVersion(coid, body));
    }
}
