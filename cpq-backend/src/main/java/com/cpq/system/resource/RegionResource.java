package com.cpq.system.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.system.dto.RegionDTO;
import com.cpq.system.service.RegionService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

@Path("/api/cpq/regions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class RegionResource {

    @Inject
    RegionService regionService;

    @GET
    public ApiResponse<PageResult<RegionDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size) {
        return ApiResponse.success(regionService.list(page, size));
    }

    @POST
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<RegionDTO> create(@Valid RegionDTO dto) {
        return ApiResponse.success(regionService.create(dto));
    }

    @PUT
    @Path("/{id}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<RegionDTO> update(@PathParam("id") UUID id, RegionDTO dto) {
        return ApiResponse.success(regionService.update(id, dto));
    }

    @PATCH
    @Path("/{id}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<RegionDTO> updateStatus(@PathParam("id") UUID id, RegionDTO dto) {
        return ApiResponse.success(regionService.updateStatus(id, dto.status));
    }
}
