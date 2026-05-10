package com.cpq.system.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.system.dto.DepartmentDTO;
import com.cpq.system.service.DepartmentService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

@Path("/api/cpq/departments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class DepartmentResource {

    @Inject
    DepartmentService departmentService;

    @GET
    public ApiResponse<PageResult<DepartmentDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size) {
        return ApiResponse.success(departmentService.list(page, size));
    }

    @POST
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<DepartmentDTO> create(@Valid DepartmentDTO dto) {
        return ApiResponse.success(departmentService.create(dto));
    }

    @PUT
    @Path("/{id}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<DepartmentDTO> update(@PathParam("id") UUID id, DepartmentDTO dto) {
        return ApiResponse.success(departmentService.update(id, dto));
    }

    @PATCH
    @Path("/{id}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<DepartmentDTO> updateStatus(@PathParam("id") UUID id, DepartmentDTO dto) {
        return ApiResponse.success(departmentService.updateStatus(id, dto.status));
    }
}
