package com.cpq.basicdata.resource;

import com.cpq.basicdata.dto.CreateProductCategoryRequest;
import com.cpq.basicdata.dto.ProductCategoryDTO;
import com.cpq.basicdata.service.ProductCategoryService;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/cpq/product-categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProductCategoryResource {

    @Inject
    ProductCategoryService service;

    @GET
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<ProductCategoryDTO>> list(@QueryParam("status") String status) {
        return ApiResponse.success(service.list(status));
    }

    @GET
    @Path("/{id}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ProductCategoryDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(service.getById(id));
    }

    @POST
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ProductCategoryDTO> create(@Valid CreateProductCategoryRequest req) {
        return ApiResponse.success(service.create(req));
    }

    @PUT
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ProductCategoryDTO> update(@PathParam("id") UUID id,
                                                  CreateProductCategoryRequest req) {
        return ApiResponse.success(service.update(id, req));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        service.delete(id);
        return ApiResponse.success();
    }
}
