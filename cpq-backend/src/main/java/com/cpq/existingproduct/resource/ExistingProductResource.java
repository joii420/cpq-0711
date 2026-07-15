package com.cpq.existingproduct.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.existingproduct.dto.ExistingProductDTO;
import com.cpq.existingproduct.service.ExistingProductService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * 报价单「从已有产品添加」端点（task-0712 B3，api.md §2.1）。
 *
 * <p>{@code GET /api/cpq/quotations/{quotationId}/existing-products}：服务端从 quotation 派生
 * customer_no（前端不传客户），查 {@code material_customer_map} 该客户产品，4 过滤（全可选、AND、模糊）+
 * 分页。响应 {@link PageResult}（content/totalElements/page/size/totalPages）。
 */
@Path("/api/cpq/quotations/{quotationId}/existing-products")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ExistingProductResource {

    @Inject
    ExistingProductService service;

    @GET
    public ApiResponse<PageResult<ExistingProductDTO>> list(
            @PathParam("quotationId") UUID quotationId,
            @QueryParam("customerProductNo") String customerProductNo,
            @QueryParam("salesPartNo") String salesPartNo,
            @QueryParam("productName") String productName,
            @QueryParam("spec") String spec,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return ApiResponse.success(
                service.list(quotationId, customerProductNo, salesPartNo, productName, spec, page, size));
    }
}
