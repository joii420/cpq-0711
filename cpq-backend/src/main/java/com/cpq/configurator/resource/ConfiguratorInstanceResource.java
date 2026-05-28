package com.cpq.configurator.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.configurator.entity.ConfiguratorInstance;
import com.cpq.configurator.service.ConfiguratorInstanceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/configurator/instances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN", "PRICING_MANAGER", "SALES_MANAGER", "SALES_REP"})
public class ConfiguratorInstanceResource {

    @Inject
    ConfiguratorInstanceService service;

    @GET
    public ApiResponse<PageResult<ConfiguratorInstance>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("status") String status,
            @QueryParam("customerId") UUID customerId,
            @QueryParam("templateId") UUID templateId) {
        return ApiResponse.success(service.list(page, size, status, customerId, templateId));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<ConfiguratorInstance> get(@PathParam("id") UUID id) {
        return ApiResponse.success(service.getById(id));
    }

    @POST
    public ApiResponse<ConfiguratorInstance> create(ConfiguratorInstance i) {
        return ApiResponse.success(service.create(i));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<ConfiguratorInstance> update(@PathParam("id") UUID id, ConfiguratorInstance patch) {
        return ApiResponse.success(service.update(id, patch));
    }

    @DELETE
    @Path("/{id}")
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        service.delete(id);
        return ApiResponse.success(null);
    }

    @POST
    @Path("/evaluate-by-template/{templateId}")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> evaluateByTemplate(
            @PathParam("templateId") UUID templateId, Map<String, Object> body) {
        Object selObj = body == null ? null : body.get("selectedValues");
        Map<String, Object> selected = (selObj instanceof Map)
            ? (Map<String, Object>) selObj
            : new java.util.HashMap<>();
        return ApiResponse.success(service.evaluate(templateId, selected));
    }

    @POST
    @Path("/{id}/link-action")
    public ApiResponse<Map<String, Object>> linkAction(@PathParam("id") UUID id, Map<String, Object> body) {
        String action = body == null ? null : (String) body.get("action");
        if (action == null) throw new IllegalArgumentException("action required");
        UUID qid = null;
        Object q = body.get("quotation_id");
        if (q != null && !q.toString().isBlank()) qid = UUID.fromString(q.toString());
        return ApiResponse.success(service.linkAction(id, action, qid));
    }

    @POST
    @Path("/{id}/unlink")
    public ApiResponse<Map<String, Object>> unlink(@PathParam("id") UUID id) {
        return ApiResponse.success(service.unlink(id));
    }
}
