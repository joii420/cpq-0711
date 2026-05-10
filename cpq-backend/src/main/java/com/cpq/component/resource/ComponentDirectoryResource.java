package com.cpq.component.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.component.dto.ComponentDirectoryDTO;
import com.cpq.component.dto.CreateComponentDirectoryRequest;
import com.cpq.component.service.ComponentDirectoryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/cpq/component-directories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
public class ComponentDirectoryResource {

    @Inject
    ComponentDirectoryService directoryService;

    @GET
    public ApiResponse<List<ComponentDirectoryDTO>> tree(@QueryParam("keyword") String keyword) {
        return ApiResponse.success(directoryService.listTree(keyword));
    }

    @POST
    public ApiResponse<ComponentDirectoryDTO> create(CreateComponentDirectoryRequest request) {
        return ApiResponse.success(directoryService.create(request.name, request.parentId, request.sortOrder));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<ComponentDirectoryDTO> update(
            @PathParam("id") UUID id,
            CreateComponentDirectoryRequest request) {
        return ApiResponse.success(directoryService.update(id, request.name, request.sortOrder));
    }

    @DELETE
    @Path("/{id}")
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        directoryService.delete(id);
        return ApiResponse.success();
    }
}
