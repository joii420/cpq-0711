package com.cpq.costing.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.costing.dto.ComparisonDTO;
import com.cpq.costing.dto.CostingSheetDTO;
import com.cpq.costing.service.CostingSheetService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

@Path("/api/cpq/quotations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CostingSheetResource {

    @Inject
    CostingSheetService service;

    @GET
    @Path("/{id}/costing-sheet")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingSheetDTO> getCostingSheet(@PathParam("id") UUID quotationId) {
        return ApiResponse.success(service.getByQuotation(quotationId));
    }

    @GET
    @Path("/{id}/comparison")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ComparisonDTO> getComparison(@PathParam("id") UUID quotationId) {
        return ApiResponse.success(service.buildComparison(quotationId));
    }
}
