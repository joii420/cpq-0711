package com.cpq.template.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.template.dto.CreateBindingRequest;
import com.cpq.template.dto.ProductTemplateBindingDTO;
import com.cpq.template.service.ProductTemplateBindingService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/cpq/products/{productId}/template-bindings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ProductTemplateBindingResource {

    @Inject
    ProductTemplateBindingService bindingService;

    @GET
    public ApiResponse<List<ProductTemplateBindingDTO>> list(@PathParam("productId") UUID productId) {
        return ApiResponse.success(bindingService.listByProduct(productId));
    }

    @POST
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ProductTemplateBindingDTO> create(
            @PathParam("productId") UUID productId,
            CreateBindingRequest request) {
        request.productId = productId;
        return ApiResponse.success(bindingService.create(request));
    }

    @DELETE
    @Path("/{bindingId}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(
            @PathParam("productId") UUID productId,
            @PathParam("bindingId") UUID bindingId) {
        bindingService.delete(bindingId);
        return ApiResponse.success();
    }

    @PUT
    @Path("/{bindingId}/set-default")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ProductTemplateBindingDTO> setDefault(
            @PathParam("productId") UUID productId,
            @PathParam("bindingId") UUID bindingId) {
        return ApiResponse.success(bindingService.setDefault(bindingId));
    }

    @GET
    @Path("/match")
    public ApiResponse<List<ProductTemplateBindingDTO>> matchTemplates(
            @PathParam("productId") UUID productId,
            @QueryParam("processIds") @DefaultValue("") String processIdsParam) {
        List<UUID> processIds;
        if (processIdsParam == null || processIdsParam.isBlank()) {
            processIds = List.of();
        } else {
            processIds = Arrays.stream(processIdsParam.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .collect(Collectors.toList());
        }
        return ApiResponse.success(bindingService.matchTemplates(productId, processIds));
    }
}
