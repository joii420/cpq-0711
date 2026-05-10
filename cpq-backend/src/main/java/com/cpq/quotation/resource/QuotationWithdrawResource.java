package com.cpq.quotation.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.quotation.dto.QuotationWithdrawRequestDTO;
import com.cpq.quotation.service.QuotationWithdrawService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/quotations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QuotationWithdrawResource {

    @Inject
    QuotationWithdrawService service;

    @Inject
    SessionHelper sessionHelper;

    @Context
    HttpServerRequest httpRequest;

    @GET
    @Path("/{id}/withdraw-requests")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<QuotationWithdrawRequestDTO>> list(@PathParam("id") UUID quotationId) {
        return ApiResponse.success(service.listForQuotation(quotationId));
    }

    @GET
    @Path("/{id}/withdraw-requests/pending")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<QuotationWithdrawRequestDTO> getPending(@PathParam("id") UUID quotationId) {
        return ApiResponse.success(service.findPending(quotationId));
    }

    @POST
    @Path("/{id}/withdraw-request")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<QuotationWithdrawRequestDTO> request(@PathParam("id") UUID quotationId,
                                                             Map<String, Object> body) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        String reason = body == null ? null : (String) body.get("reason");
        return ApiResponse.success(service.requestWithdraw(quotationId, reason, userId));
    }

    @POST
    @Path("/{id}/withdraw/approve")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<QuotationWithdrawRequestDTO> approve(@PathParam("id") UUID quotationId,
                                                             Map<String, Object> body) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        String note = body == null ? null : (String) body.get("note");
        return ApiResponse.success(service.approveWithdraw(quotationId, userId, note));
    }

    @POST
    @Path("/{id}/withdraw/reject")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<QuotationWithdrawRequestDTO> reject(@PathParam("id") UUID quotationId,
                                                            Map<String, Object> body) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        String note = body == null ? null : (String) body.get("note");
        return ApiResponse.success(service.rejectWithdraw(quotationId, userId, note));
    }
}
