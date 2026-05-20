package com.cpq.configtemplate.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.configtemplate.dto.ConfigItemDTO;
import com.cpq.configtemplate.dto.ItemRequest;
import com.cpq.configtemplate.service.ConfigTemplateService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

@Path("/api/cpq/config-items")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfigItemResource {

    @Inject
    ConfigTemplateService svc;

    @PUT
    @Path("/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ConfigItemDTO> update(@PathParam("id") UUID id, @Valid ItemRequest req) {
        return ApiResponse.success(svc.updateItem(id, req));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        svc.deleteItem(id);
        return ApiResponse.success();
    }
}
