package com.cpq.basicdata.resource;

import com.cpq.basicdata.dto.ComparisonTagDTO;
import com.cpq.basicdata.dto.CreateComparisonTagRequest;
import com.cpq.basicdata.service.ComparisonTagService;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/cpq/comparison-tags")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ComparisonTagResource {

    @Inject
    ComparisonTagService service;

    @GET
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<ComparisonTagDTO>> list(@QueryParam("status") String status) {
        return ApiResponse.success(service.list(status));
    }

    @GET
    @Path("/{id}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ComparisonTagDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(service.getById(id));
    }

    @POST
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ComparisonTagDTO> create(@Valid CreateComparisonTagRequest req) {
        return ApiResponse.success(service.create(req));
    }

    @PUT
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ComparisonTagDTO> update(@PathParam("id") UUID id,
                                                CreateComparisonTagRequest req) {
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
