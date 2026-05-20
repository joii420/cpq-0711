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

    /**
     * V204: 编辑模板组件 override (fields / dataDriverPath). body 内任一键缺省 = 不动该字段;
     * 显式 null = 清空 override (走 component 默认); 非空 = 设置 override.
     * 仅 DRAFT 可改.
     *
     * <p>示例:
     * <pre>
     *   PATCH .../components/{tcId}/overrides
     *   { "fieldsOverride": "[...JSON 数组...]", "dataDriverPathOverride": "mat_process" }
     *   PATCH ... { "fieldsOverride": null }                            // 仅清空 fields override
     *   PATCH ... { "dataDriverPathOverride": "v_composite_child_*" }   // 仅设 driver override
     * </pre>
     */
    @PATCH
    @Path("/{tcId}/overrides")
    public ApiResponse<TemplateComponentDTO> updateOverrides(
            @PathParam("templateId") UUID templateId,
            @PathParam("tcId") UUID tcId,
            Map<String, Object> body) {
        boolean fieldsProvided = body != null && body.containsKey("fieldsOverride");
        boolean driverProvided = body != null && body.containsKey("dataDriverPathOverride");
        String fieldsJson = null;
        if (fieldsProvided) {
            Object v = body.get("fieldsOverride");
            if (v == null) {
                fieldsJson = null;
            } else if (v instanceof String) {
                fieldsJson = (String) v;
            } else {
                // List/Map: 序列化成 JSON
                try {
                    fieldsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(v);
                } catch (Exception e) {
                    throw new jakarta.ws.rs.BadRequestException("fieldsOverride 不是合法的 JSON 数组: " + e.getMessage());
                }
            }
        }
        String driverPath = null;
        if (driverProvided) {
            Object v = body.get("dataDriverPathOverride");
            driverPath = v == null ? null : v.toString();
        }
        return ApiResponse.success(templateComponentService.updateOverrides(
                templateId, tcId, fieldsJson, fieldsProvided, driverPath, driverProvided));
    }
}
