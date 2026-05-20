package com.cpq.configtemplate.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.configtemplate.dto.CategoryRequest;
import com.cpq.configtemplate.dto.ConfigCategoryDTO;
import com.cpq.configtemplate.dto.ConfigItemDTO;
import com.cpq.configtemplate.dto.ItemRequest;
import com.cpq.configtemplate.service.ConfigTemplateService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

@Path("/api/cpq/config-categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfigCategoryResource {

    @Inject
    ConfigTemplateService svc;

    @PUT
    @Path("/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ConfigCategoryDTO> update(@PathParam("id") UUID id, @Valid CategoryRequest req) {
        return ApiResponse.success(svc.updateCategory(id, req));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        svc.deleteCategory(id);
        return ApiResponse.success();
    }

    @POST
    @Path("/{categoryId}/items")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ConfigItemDTO> createItem(
            @PathParam("categoryId") UUID categoryId, @Valid ItemRequest req) {
        return ApiResponse.success(svc.createItem(categoryId, req));
    }
}
