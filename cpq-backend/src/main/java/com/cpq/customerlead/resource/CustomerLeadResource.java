package com.cpq.customerlead.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.customerlead.entity.CustomerLead;
import com.cpq.customerlead.service.CustomerLeadService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/customer-leads")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN", "SALES_MANAGER", "SALES_REP"})
public class CustomerLeadResource {

    @Inject
    CustomerLeadService service;

    @GET
    public ApiResponse<PageResult<CustomerLead>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("status") String status,
            @QueryParam("phone") String phone) {
        return ApiResponse.success(service.list(page, size, status, phone));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<CustomerLead> get(@PathParam("id") UUID id) {
        return ApiResponse.success(service.getById(id));
    }

    @POST
    public ApiResponse<CustomerLead> create(CustomerLead l) {
        return ApiResponse.success(service.create(l));
    }

    @POST
    @Path("/{id}/review")
    public ApiResponse<Map<String, Object>> review(@PathParam("id") UUID id, Map<String, Object> body) {
        String action = body == null ? null : (String) body.get("action");
        if (action == null) throw new IllegalArgumentException("action required");
        UUID boundCustomerId = null;
        Object bc = body.get("bound_customer_id");
        if (bc != null && !bc.toString().isBlank()) boundCustomerId = UUID.fromString(bc.toString());
        String note = body.get("review_note") == null ? null : body.get("review_note").toString();
        // TODO: get reviewedBy from SessionHelper after切片完成 auth integration
        return ApiResponse.success(service.review(id, action, boundCustomerId, note, null));
    }
}
