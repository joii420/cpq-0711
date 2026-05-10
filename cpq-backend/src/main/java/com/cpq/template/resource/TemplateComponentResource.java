package com.cpq.template.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.template.dto.TemplateComponentDTO;
import com.cpq.template.service.TemplateComponentService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/templates/{templateId}/components")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
public class TemplateComponentResource {

    @Inject
    TemplateComponentService templateComponentService;

    @GET
    public ApiResponse<List<TemplateComponentDTO>> list(@PathParam("templateId") UUID templateId) {
        return ApiResponse.success(templateComponentService.listByTemplate(templateId));
    }

    @POST
    public ApiResponse<TemplateComponentDTO> add(
            @PathParam("templateId") UUID templateId,
            Map<String, Object> body) {
        String componentIdStr = body.get("componentId") != null ? body.get("componentId").toString() : null;
        if (componentIdStr == null) {
            throw new jakarta.ws.rs.BadRequestException("componentId is required");
        }
        UUID componentId = UUID.fromString(componentIdStr);
        String tabName = body.get("tabName") != null ? body.get("tabName").toString() : null;
        return ApiResponse.success(templateComponentService.addComponent(templateId, componentId, tabName));
    }

    @DELETE
    @Path("/{tcId}")
    public ApiResponse<Void> remove(
            @PathParam("templateId") UUID templateId,
            @PathParam("tcId") UUID tcId) {
        templateComponentService.removeComponent(templateId, tcId);
        return ApiResponse.success();
    }

    @PATCH
    @Path("/{tcId}/preset-rows")
    public ApiResponse<TemplateComponentDTO> updatePresetRows(
            @PathParam("templateId") UUID templateId,
            @PathParam("tcId") UUID tcId,
            Map<String, Object> body) {
        Object rows = body.get("presetRows");
        String json = rows != null ? rows.toString() : "[]";
        // If it's a list, serialize it properly
        if (rows instanceof List) {
            try {
                json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rows);
            } catch (Exception e) {
                json = "[]";
            }
        }
        return ApiResponse.success(templateComponentService.updatePresetRows(templateId, tcId, json));
    }

    @PATCH
    @Path("/{tcId}/formula-assignments")
    public ApiResponse<TemplateComponentDTO> updateFormulaAssignments(
            @PathParam("templateId") UUID templateId,
            @PathParam("tcId") UUID tcId,
            Map<String, Object> body) {
        Object assignments = body.get("formulaAssignments");
        String json = "{}";
        if (assignments instanceof Map) {
            try {
                json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(assignments);
            } catch (Exception e) {
                json = "{}";
            }
        } else if (assignments != null) {
            json = assignments.toString();
        }
        return ApiResponse.success(templateComponentService.updateFormulaAssignments(templateId, tcId, json));
    }

    @PUT
    @Path("/reorder")
    public ApiResponse<List<TemplateComponentDTO>> reorder(
            @PathParam("templateId") UUID templateId,
            Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        if (ids == null) {
            throw new jakarta.ws.rs.BadRequestException("ids is required");
        }
        List<UUID> uuids = ids.stream().map(UUID::fromString).collect(java.util.stream.Collectors.toList());
        return ApiResponse.success(templateComponentService.reorder(templateId, uuids));
    }
}
