package com.cpq.customer.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.customer.dto.ContactDTO;
import com.cpq.customer.service.CustomerContactService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/cpq/customers/{customerId}/contacts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class CustomerContactResource {

    @Inject
    CustomerContactService contactService;

    @GET
    public ApiResponse<List<ContactDTO>> list(@PathParam("customerId") UUID customerId) {
        return ApiResponse.success(contactService.listByCustomer(customerId));
    }

    @POST
    public ApiResponse<ContactDTO> create(@PathParam("customerId") UUID customerId, ContactDTO dto) {
        return ApiResponse.success(contactService.create(customerId, dto));
    }

    @PUT
    @Path("/{contactId}")
    public ApiResponse<ContactDTO> update(
            @PathParam("customerId") UUID customerId,
            @PathParam("contactId") UUID contactId,
            ContactDTO dto) {
        return ApiResponse.success(contactService.update(contactId, dto));
    }

    @DELETE
    @Path("/{contactId}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(
            @PathParam("customerId") UUID customerId,
            @PathParam("contactId") UUID contactId) {
        contactService.delete(contactId);
        return ApiResponse.success();
    }

    @PUT
    @Path("/{contactId}/set-primary")
    public ApiResponse<ContactDTO> setPrimary(
            @PathParam("customerId") UUID customerId,
            @PathParam("contactId") UUID contactId) {
        return ApiResponse.success(contactService.setPrimary(customerId, contactId));
    }
}
