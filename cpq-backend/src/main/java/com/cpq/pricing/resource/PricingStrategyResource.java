package com.cpq.pricing.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.pricing.dto.CreatePricingStrategyRequest;
import com.cpq.pricing.dto.PricingStrategyDTO;
import com.cpq.pricing.service.PricingStrategyService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/pricing-strategies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class PricingStrategyResource {

    @Inject
    PricingStrategyService pricingStrategyService;

    @GET
    public ApiResponse<PageResult<PricingStrategyDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("customerId") UUID customerId) {
        return ApiResponse.success(pricingStrategyService.list(page, size, customerId));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<PricingStrategyDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(pricingStrategyService.getById(id));
    }

    @POST
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<PricingStrategyDTO> create(@Valid CreatePricingStrategyRequest request) {
        return ApiResponse.success(pricingStrategyService.create(request));
    }

    @PUT
    @Path("/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<PricingStrategyDTO> update(@PathParam("id") UUID id, CreatePricingStrategyRequest request) {
        return ApiResponse.success(pricingStrategyService.update(id, request));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        pricingStrategyService.delete(id);
        return ApiResponse.success();
    }

    @PATCH
    @Path("/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<PricingStrategyDTO> updateStatus(@PathParam("id") UUID id, Map<String, String> body) {
        String status = body.get("status");
        if (status == null || status.isBlank()) {
            throw new WebApplicationException("status field is required", 400);
        }
        return ApiResponse.success(pricingStrategyService.updateStatus(id, status));
    }

    @GET
    @Path("/export")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response exportStrategies(@QueryParam("customerId") UUID customerId) {
        byte[] excel = pricingStrategyService.exportToExcel(customerId);
        return Response.ok(excel)
                .header("Content-Disposition", "attachment; filename=pricing-strategies.xlsx")
                .build();
    }

    /**
     * GET /api/cpq/pricing-strategies/{strategyId}/rules
     * List rules for a strategy (T4 P2: API.md documented endpoint that was missing).
     */
    @GET
    @Path("/{strategyId}/rules")
    public ApiResponse<java.util.List<com.cpq.pricing.dto.PricingRuleDTO>> listRules(
            @PathParam("strategyId") UUID strategyId) {
        // Validate strategy exists (404 instead of empty list when id is invalid)
        pricingStrategyService.getById(strategyId);
        java.util.List<com.cpq.pricing.entity.PricingRule> rules =
                com.cpq.pricing.entity.PricingRule.list("strategy.id = ?1 ORDER BY sortOrder ASC", strategyId);
        return ApiResponse.success(rules.stream()
                .map(com.cpq.pricing.dto.PricingRuleDTO::from)
                .collect(java.util.stream.Collectors.toList()));
    }
}
