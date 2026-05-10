package com.cpq.system.config.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.SessionHelper;
import com.cpq.system.config.dto.CreateSystemConfigRequest;
import com.cpq.system.config.dto.SystemConfigDTO;
import com.cpq.system.config.dto.UpdateSystemConfigRequest;
import com.cpq.system.config.service.SystemConfigService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

@Path("/api/system/configs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SystemConfigResource {

    @Inject
    SystemConfigService configService;

    @Inject
    SessionHelper sessionHelper;

    @GET
    public Response list(@QueryParam("category") String category,
                         @Context HttpServerRequest request) {
        requireSystemAdmin(request);
        List<SystemConfigDTO> list = configService.list(category);
        return Response.ok(ApiResponse.success(list)).build();
    }

    @GET
    @Path("/{key}")
    public Response get(@PathParam("key") String key,
                        @Context HttpServerRequest request) {
        requireSystemAdmin(request);
        SystemConfigDTO dto = configService.get(key);
        return Response.ok(ApiResponse.success(dto)).build();
    }

    @POST
    public Response create(@Valid CreateSystemConfigRequest req,
                           @Context HttpServerRequest request) {
        requireSystemAdmin(request);
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(request);
        SystemConfigDTO dto = configService.create(req, userId);
        return Response.status(201).entity(ApiResponse.success(dto, 201)).build();
    }

    @PUT
    @Path("/{key}")
    public Response update(@PathParam("key") String key,
                           @Valid UpdateSystemConfigRequest req,
                           @Context HttpServerRequest request) {
        requireSystemAdmin(request);
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(request);
        String role = sessionHelper.getCurrentUserRoleOrFallback(request);
        SystemConfigDTO dto = configService.update(key, req, role, userId);
        return Response.ok(ApiResponse.success(dto)).build();
    }

    @DELETE
    @Path("/{key}")
    public Response delete(@PathParam("key") String key,
                           @Context HttpServerRequest request) {
        requireSystemAdmin(request);
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(request);
        configService.delete(key, userId);
        return Response.ok(ApiResponse.success()).build();
    }

    private void requireSystemAdmin(HttpServerRequest request) {
        sessionHelper.requireSystemAdmin(request);
    }
}
