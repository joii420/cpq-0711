package com.cpq.system.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.system.dto.CreateUserRequest;
import com.cpq.system.dto.UpdateUserRequest;
import com.cpq.system.dto.UserDTO;
import com.cpq.system.service.UserService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN"})
public class UserResource {

    @Inject
    UserService userService;

    @GET
    public ApiResponse<PageResult<UserDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size,
            @QueryParam("role") String role,
            @QueryParam("status") String status,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(userService.list(page, size, role, status, keyword));
    }

    @POST
    public ApiResponse<UserDTO> create(@Valid CreateUserRequest request) {
        return ApiResponse.success(userService.create(request));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<UserDTO> update(@PathParam("id") UUID id, UpdateUserRequest request) {
        return ApiResponse.success(userService.update(id, request));
    }

    @PATCH
    @Path("/{id}")
    public ApiResponse<UserDTO> updateStatus(@PathParam("id") UUID id, UpdateUserRequest request) {
        return ApiResponse.success(userService.updateStatus(id, request.status));
    }

    @POST
    @Path("/{id}/reset-password")
    public ApiResponse<Map<String, String>> resetPassword(@PathParam("id") UUID id) {
        UserDTO dto = userService.resetPassword(id);
        return ApiResponse.success(Map.of("initialPassword", dto.initialPassword));
    }
}
