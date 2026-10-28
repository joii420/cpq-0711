package com.cpq.costing.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.costing.dto.ComparisonDTO;
import com.cpq.costing.dto.ComparisonExportRequest;
import com.cpq.costing.service.ComparisonExportService;
import com.cpq.costing.service.CostingSheetService;
import com.cpq.quotation.service.QuotationDisplayPrecisionPolicy;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

@Path("/api/cpq/quotations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CostingSheetResource {

    @Inject
    CostingSheetService service;

    @Inject
    ComparisonExportService comparisonExportService;

    @Inject
    SessionHelper sessionHelper;

    // task-0723 B6: 旧核价引擎退役 — getCostingSheet() 端点已删除 (GET /{id}/costing-sheet, 421 死代码)。

    @GET
    @Path("/{id}/comparison")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ComparisonDTO> getComparison(@PathParam("id") UUID quotationId) {
        return ApiResponse.success(service.buildComparison(quotationId));
    }

    @POST
    @Path("/{id}/comparison/export")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response exportComparison(@PathParam("id") UUID quotationId, ComparisonExportRequest req,
                                     @Context HttpServerRequest request) {
        int maxDisplayDecimals = QuotationDisplayPrecisionPolicy.maxDecimalsForRole(
                sessionHelper.getCurrentUserRoleOrFallback(request));
        byte[] xlsx = comparisonExportService.export(req, maxDisplayDecimals);
        return Response.ok(xlsx)
                .header("Content-Disposition", "attachment; filename=\"comparison-" + quotationId + ".xlsx\"")
                .build();
    }
}
