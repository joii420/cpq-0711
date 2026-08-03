package com.cpq.priceadjust.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.dto.ApproveRejectRequest;
import com.cpq.priceadjust.dto.ImpactResultDTO;
import com.cpq.priceadjust.dto.ReviewDetailDTO;
import com.cpq.priceadjust.dto.ReviewListItemDTO;
import com.cpq.priceadjust.service.PriceAdjustReviewService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

/**
 * task-0729 B5 · 审核端点（api.md §2）。
 */
@Path("/api/cpq/price-adjust/reviews")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PriceAdjustReviewResource {

    @Inject PriceAdjustReviewService reviewService;
    @Inject SessionHelper sessionHelper;
    @Context HttpServerRequest httpRequest;

    @GET
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<PageResult<ReviewListItemDTO>> list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("customerNo") String customerNo,
            @QueryParam("status") String status,
            @QueryParam("breachedOnly") @DefaultValue("false") boolean breachedOnly,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(reviewService.list(customerNo, status, breachedOnly, keyword, page, size));
    }

    @GET
    @Path("/{reviewId}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ReviewDetailDTO> detail(@PathParam("reviewId") UUID reviewId) {
        return ApiResponse.success(reviewService.detail(reviewId));
    }

    @POST
    @Path("/impact")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ImpactResultDTO> impact(ApproveRejectRequest req) {
        return ApiResponse.success(reviewService.impact(req != null ? req.reviewIds : null));
    }

    @POST
    @Path("/approve")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response approve(ApproveRejectRequest req) {
        UUID actorId = sessionHelper.getCurrentUserId(httpRequest);
        PriceAdjustReviewService.ApproveResult result = reviewService.approve(req, actorId);
        return Response.status(202).entity(ApiResponse.success(result)).build();
    }

    @POST
    @Path("/reject")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> reject(ApproveRejectRequest req) {
        UUID actorId = sessionHelper.getCurrentUserId(httpRequest);
        reviewService.reject(req, actorId);
        return ApiResponse.success(null);
    }

    @POST
    @Path("/{reviewId}/recompute-budget")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response recomputeBudget(@PathParam("reviewId") UUID reviewId) {
        reviewService.recomputeBudget(reviewId);
        return Response.status(202).entity(ApiResponse.success(null)).build();
    }
}
