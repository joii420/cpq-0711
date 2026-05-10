package com.cpq.costingsummary.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.costingsummary.entity.*;
import com.cpq.costingsummary.service.CostingSummaryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/cpq/costing-summary")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class CostingSummaryResource {

    @Inject CostingSummaryService svc;

    @GET
    public ApiResponse<List<CostingSummary>> list(
            @QueryParam("hfPartNo") String hfPartNo,
            @QueryParam("status") String status) {
        return ApiResponse.success(svc.listSummaries(hfPartNo, status));
    }

    @GET @Path("/{id}")
    public ApiResponse<CostingSummary> get(@PathParam("id") UUID id) {
        return ApiResponse.success(svc.getSummary(id));
    }

    @POST
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingSummary> create(CostingSummary req) {
        return ApiResponse.success(svc.createSummary(req));
    }

    @DELETE @Path("/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        svc.deleteSummary(id); return ApiResponse.success();
    }

    @POST @Path("/{id}/compute")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<CostingSummaryResult>> compute(@PathParam("id") UUID id) {
        return ApiResponse.success(svc.compute(id));
    }

    @POST @Path("/{id}/publish")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingSummary> publish(@PathParam("id") UUID id) {
        return ApiResponse.success(svc.publish(id));
    }

    @POST @Path("/{id}/archive")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingSummary> archive(@PathParam("id") UUID id) {
        return ApiResponse.success(svc.archiveSummary(id));
    }

    @GET @Path("/{id}/results")
    public ApiResponse<List<CostingSummaryResult>> listResults(@PathParam("id") UUID id) {
        return ApiResponse.success(svc.listResults(id));
    }

    // ─── Override CRUD ───────────────────────────────────────────────
    @GET @Path("/{id}/overrides")
    public ApiResponse<List<CostingSummaryOverride>> listOverrides(@PathParam("id") UUID id) {
        return ApiResponse.success(svc.listOverrides(id));
    }

    @POST @Path("/{id}/overrides")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingSummaryOverride> saveOverride(
            @PathParam("id") UUID summaryId, CostingSummaryOverride req) {
        req.summaryId = summaryId;
        return ApiResponse.success(svc.saveOverride(req));
    }

    @DELETE @Path("/overrides/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> deleteOverride(@PathParam("id") UUID id) {
        svc.deleteOverride(id); return ApiResponse.success();
    }
}
