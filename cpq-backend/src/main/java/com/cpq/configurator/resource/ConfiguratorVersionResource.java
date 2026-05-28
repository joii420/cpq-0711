package com.cpq.configurator.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.configurator.entity.ConfiguratorTemplateVersion;
import com.cpq.configurator.service.ConfiguratorVersionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/configurator-templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN", "PRICING_MANAGER"})
public class ConfiguratorVersionResource {

    @Inject
    ConfiguratorVersionService service;

    @GET
    @Path("/{templateId}/versions")
    public ApiResponse<List<ConfiguratorTemplateVersion>> listVersions(@PathParam("templateId") UUID templateId) {
        return ApiResponse.success(service.listVersions(templateId));
    }

    @POST
    @Path("/{templateId}/versions/snapshot")
    public ApiResponse<ConfiguratorTemplateVersion> createSnapshot(
            @PathParam("templateId") UUID templateId, Map<String, Object> body) {
        String label = body != null && body.get("label") != null ? body.get("label").toString() : null;
        String summary = body != null && body.get("changeSummary") != null ? body.get("changeSummary").toString() : null;
        return ApiResponse.success(service.createSnapshot(templateId, label, summary));
    }

    @GET
    @Path("/versions/diff")
    public ApiResponse<Map<String, Object>> diff(@QueryParam("v1") UUID v1, @QueryParam("v2") UUID v2) {
        return ApiResponse.success(service.diffVersions(v1, v2));
    }

    @POST
    @Path("/{templateId}/versions/{versionId}/rollback")
    public ApiResponse<Map<String, Object>> rollback(
            @PathParam("templateId") UUID templateId, @PathParam("versionId") UUID versionId) {
        return ApiResponse.success(service.rollback(templateId, versionId));
    }
}
