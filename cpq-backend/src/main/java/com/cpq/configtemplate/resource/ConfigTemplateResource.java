package com.cpq.configtemplate.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.configtemplate.dto.CategoryRequest;
import com.cpq.configtemplate.dto.ConfigCategoryDTO;
import com.cpq.configtemplate.dto.ConfigTemplateDTO;
import com.cpq.configtemplate.dto.TemplateRequest;
import com.cpq.configtemplate.service.ConfigTemplateService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * V203 / Phase B1: 配置模板 (LIST_FORMULA 数据源) 主资源端点.
 */
@Path("/api/cpq/config-templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfigTemplateResource {

    @Inject
    ConfigTemplateService svc;

    @GET
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<ConfigTemplateDTO>> list(@QueryParam("status") String status) {
        return ApiResponse.success(svc.listTemplates(status));
    }

    @GET
    @Path("/{id}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ConfigTemplateDTO> get(@PathParam("id") UUID id) {
        return ApiResponse.success(svc.getTemplate(id));
    }

    @POST
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ConfigTemplateDTO> create(@Valid TemplateRequest req) {
        return ApiResponse.success(svc.createTemplate(req));
    }

    @PUT
    @Path("/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ConfigTemplateDTO> update(@PathParam("id") UUID id, @Valid TemplateRequest req) {
        return ApiResponse.success(svc.updateTemplate(id, req));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        svc.deleteTemplate(id);
        return ApiResponse.success();
    }

    @POST
    @Path("/{id}/publish")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ConfigTemplateDTO> publish(@PathParam("id") UUID id) {
        return ApiResponse.success(svc.publishTemplate(id));
    }

    @POST
    @Path("/{id}/archive")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ConfigTemplateDTO> archive(@PathParam("id") UUID id) {
        return ApiResponse.success(svc.archiveTemplate(id));
    }

    @POST
    @Path("/{templateId}/categories")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ConfigCategoryDTO> createCategory(
            @PathParam("templateId") UUID templateId, @Valid CategoryRequest req) {
        return ApiResponse.success(svc.createCategory(templateId, req));
    }
}
