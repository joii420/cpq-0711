package com.cpq.priceadjust.resource;

import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.dto.ApproveRejectRequest;
import com.cpq.priceadjust.dto.ImpactResultDTO;
import com.cpq.priceadjust.dto.ReviewDetailDTO;
import com.cpq.priceadjust.dto.ReviewListItemDTO;
import com.cpq.priceadjust.dto.ReviewPageResult;
import com.cpq.priceadjust.dto.ComputeNowResponse;
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
 *
 * <p>🔒 2026-08-03 修正：响应体裸 DTO，不套 {@code ApiResponse} 信封（详见
 * {@code PriceAdjustStrategyResource} 类注释；同批一并修正 §3 {@code PriceAdjustJobResource}）。
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
    public ReviewPageResult<ReviewListItemDTO> list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("customerNo") String customerNo,
            @QueryParam("status") String status,
            @QueryParam("breachedOnly") @DefaultValue("false") boolean breachedOnly,
            @QueryParam("keyword") String keyword) {
        return reviewService.list(customerNo, status, breachedOnly, keyword, page, size);
    }

    @GET
    @Path("/{reviewId}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ReviewDetailDTO detail(@PathParam("reviewId") UUID reviewId) {
        return reviewService.detail(reviewId);
    }

    /**
     * task-260920 api.md §2.1 · 点击即算：一律 202（受理 / 已在算 / 已就绪都一样），结果经 {@code GET /{id}/row} 轮询。
     * 🔒 必须带 {@code @RoleAllowed}：RoleFilter 对无注解端点连登录检查都跳过（AC-29）。
     */
    @POST
    @Path("/{reviewId}/compute-now")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response computeNow(@PathParam("reviewId") UUID reviewId) {
        ComputeNowResponse body = reviewService.computeNow(reviewId);
        return Response.status(202).entity(body).build();
    }

    /** task-260920 api.md §2.2 · 单行状态（供轮询），字段与列表行一致；不触发任何试算。🔒 权限同上（AC-29）。 */
    @GET
    @Path("/{reviewId}/row")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ReviewListItemDTO row(@PathParam("reviewId") UUID reviewId) {
        return reviewService.row(reviewId);
    }

    @POST
    @Path("/impact")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ImpactResultDTO impact(ApproveRejectRequest req) {
        return reviewService.impact(req != null ? req.reviewIds : null);
    }

    @POST
    @Path("/approve")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response approve(ApproveRejectRequest req) {
        UUID actorId = sessionHelper.getCurrentUserId(httpRequest);
        PriceAdjustReviewService.ApproveResult result = reviewService.approve(req, actorId);
        return Response.status(202).entity(result).build();
    }

    @POST
    @Path("/reject")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response reject(ApproveRejectRequest req) {
        UUID actorId = sessionHelper.getCurrentUserId(httpRequest);
        reviewService.reject(req, actorId);
        return Response.ok().build();
    }

    @POST
    @Path("/{reviewId}/recompute-budget")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response recomputeBudget(@PathParam("reviewId") UUID reviewId) {
        reviewService.recomputeBudget(reviewId);
        return Response.status(202).build();
    }
}
