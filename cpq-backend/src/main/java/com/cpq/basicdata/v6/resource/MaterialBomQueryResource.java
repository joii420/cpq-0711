package com.cpq.basicdata.v6.resource;

import com.cpq.basicdata.v6.dto.MaterialBomItemDTO;
import com.cpq.basicdata.v6.service.MaterialBomQueryService;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * V6 物料 BOM 查询端点（只读，3 个 GET）。
 *
 * <ul>
 *   <li>{@code GET /api/cpq/v6/material-bom-items}            — 分页列表
 *   <li>{@code GET /api/cpq/v6/material-bom-items/customer-nos} — DISTINCT 客户编号
 *   <li>{@code GET /api/cpq/v6/material-bom-items/material-nos} — DISTINCT 物料编号（customerNo 必填）
 * </ul>
 */
@Path("/api/cpq/v6/material-bom-items")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MaterialBomQueryResource {

    @Inject
    MaterialBomQueryService service;

    /**
     * 分页查询 BOM 子表记录。
     *
     * @param customerNo 必填；缺失返回 400 MISSING_CUSTOMER_NO
     * @param materialNo 可选
     * @param systemType 可选；合法值 QUOTE / PRICING / BOTH（大小写不敏感）；
     *                   非法值返回 400 INVALID_SYSTEM_TYPE
     * @param page       页码，从 0 开始（默认 0）
     * @param size       每页条数（默认 20，最大 200）
     */
    @GET
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<PageResult<MaterialBomItemDTO>> list(
            @QueryParam("customerNo") String customerNo,
            @QueryParam("materialNo") String materialNo,
            @QueryParam("systemType") String systemType,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return ApiResponse.success(service.queryItems(customerNo, materialNo, systemType, page, size));
    }

    /**
     * 返回 material_bom_item 中所有不重复的 customer_no（缓存 5 分钟）。
     */
    @GET
    @Path("/customer-nos")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<String>> customerNos() {
        return ApiResponse.success(service.findDistinctCustomerNos());
    }

    /**
     * 返回指定客户下所有不重复的 material_no，支持模糊搜索。
     *
     * @param customerNo 必填；缺失返回 400 MISSING_CUSTOMER_NO
     * @param q          可选；模糊匹配 material_no（LIKE %q%）
     * @param limit      最多返回条数（默认 500，最大 1000）
     */
    @GET
    @Path("/material-nos")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<String>> materialNos(
            @QueryParam("customerNo") String customerNo,
            @QueryParam("q") String q,
            @QueryParam("limit") @DefaultValue("500") int limit) {
        return ApiResponse.success(service.findDistinctMaterialNos(customerNo, q, limit));
    }
}
