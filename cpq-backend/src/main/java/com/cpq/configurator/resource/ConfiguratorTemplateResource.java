package com.cpq.configurator.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.configurator.entity.Configurator3DRule;
import com.cpq.configurator.entity.ConfiguratorOption;
import com.cpq.configurator.entity.ConfiguratorOptionValue;
import com.cpq.configurator.entity.ConfiguratorTemplate;
import com.cpq.configurator.entity.ConfiguratorValueReference;
import com.cpq.configurator.service.ConfiguratorTemplateService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/configurator-templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN", "PRICING_MANAGER", "SALES_MANAGER", "SALES_REP"})
public class ConfiguratorTemplateResource {

    @Inject
    ConfiguratorTemplateService service;

    @GET
    public ApiResponse<PageResult<ConfiguratorTemplate>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("status") String status,
            @QueryParam("category") String category,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(service.list(page, size, status, category, keyword));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<ConfiguratorTemplate> get(@PathParam("id") UUID id) {
        return ApiResponse.success(service.getById(id));
    }

    @POST
    public ApiResponse<ConfiguratorTemplate> create(ConfiguratorTemplate t) {
        return ApiResponse.success(service.create(t));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<ConfiguratorTemplate> update(@PathParam("id") UUID id, Map<String, Object> patch) {
        return ApiResponse.success(service.update(id, patch));
    }

    @POST
    @Path("/{id}/publish")
    public ApiResponse<ConfiguratorTemplate> publish(@PathParam("id") UUID id) {
        return ApiResponse.success(service.publish(id));
    }

    @POST
    @Path("/{id}/archive")
    public ApiResponse<Void> archive(@PathParam("id") UUID id) {
        service.archive(id);
        return ApiResponse.success(null);
    }

    // ===== 选项 + 值 =====

    @GET
    @Path("/{id}/options")
    public ApiResponse<List<ConfiguratorOption>> listOptions(@PathParam("id") UUID templateId) {
        return ApiResponse.success(service.listOptions(templateId));
    }

    @POST
    @Path("/{id}/options")
    public ApiResponse<ConfiguratorOption> addOption(@PathParam("id") UUID templateId, ConfiguratorOption opt) {
        return ApiResponse.success(service.addOption(templateId, opt));
    }

    @GET
    @Path("/options/{optionId}/values")
    public ApiResponse<List<ConfiguratorOptionValue>> listValues(@PathParam("optionId") UUID optionId) {
        return ApiResponse.success(service.listValues(optionId));
    }

    @POST
    @Path("/options/{optionId}/values")
    public ApiResponse<ConfiguratorOptionValue> addValue(@PathParam("optionId") UUID optionId, ConfiguratorOptionValue v) {
        return ApiResponse.success(service.addValue(optionId, v));
    }

    // Option update / delete
    @PUT
    @Path("/options/{optionId}")
    public ApiResponse<ConfiguratorOption> updateOption(@PathParam("optionId") UUID optionId, Map<String, Object> patch) {
        return ApiResponse.success(service.updateOption(optionId, patch));
    }

    @DELETE
    @Path("/options/{optionId}")
    public ApiResponse<Void> deleteOption(@PathParam("optionId") UUID optionId) {
        service.deleteOption(optionId);
        return ApiResponse.success(null);
    }

    // OptionValue update / delete
    @PUT
    @Path("/values/{valueId}")
    public ApiResponse<ConfiguratorOptionValue> updateValue(@PathParam("valueId") UUID valueId, Map<String, Object> patch) {
        return ApiResponse.success(service.updateOptionValue(valueId, patch));
    }

    @DELETE
    @Path("/values/{valueId}")
    public ApiResponse<Void> deleteValue(@PathParam("valueId") UUID valueId) {
        service.deleteOptionValue(valueId);
        return ApiResponse.success(null);
    }

    // 3D 规则 CRUD
    @GET
    @Path("/values/{valueId}/3d-rules")
    public ApiResponse<List<Configurator3DRule>> list3DRules(@PathParam("valueId") UUID valueId) {
        return ApiResponse.success(service.list3DRules(valueId));
    }

    @POST
    @Path("/values/{valueId}/3d-rules")
    public ApiResponse<Configurator3DRule> add3DRule(@PathParam("valueId") UUID valueId, Configurator3DRule rule) {
        return ApiResponse.success(service.add3DRule(valueId, rule));
    }

    @PUT
    @Path("/3d-rules/{ruleId}")
    public ApiResponse<Configurator3DRule> update3DRule(@PathParam("ruleId") UUID ruleId, Map<String, Object> patch) {
        return ApiResponse.success(service.update3DRule(ruleId, patch));
    }

    @DELETE
    @Path("/3d-rules/{ruleId}")
    public ApiResponse<Void> delete3DRule(@PathParam("ruleId") UUID ruleId) {
        service.delete3DRule(ruleId);
        return ApiResponse.success(null);
    }

    // 业务实体引用 CRUD（§18A 收敛后替代 mat_feature_reference）
    @GET
    @Path("/values/{valueId}/refs")
    public ApiResponse<List<ConfiguratorValueReference>> listRefs(@PathParam("valueId") UUID valueId) {
        return ApiResponse.success(service.listRefs(valueId));
    }

    @POST
    @Path("/values/{valueId}/refs")
    public ApiResponse<ConfiguratorValueReference> addRef(@PathParam("valueId") UUID valueId, ConfiguratorValueReference r) {
        return ApiResponse.success(service.addRef(valueId, r));
    }

    @PUT
    @Path("/refs/{refId}")
    public ApiResponse<ConfiguratorValueReference> updateRef(@PathParam("refId") UUID refId, Map<String, Object> patch) {
        return ApiResponse.success(service.updateRef(refId, patch));
    }

    @DELETE
    @Path("/refs/{refId}")
    public ApiResponse<Void> deleteRef(@PathParam("refId") UUID refId) {
        service.deleteRef(refId);
        return ApiResponse.success(null);
    }

    @POST
    @Path("/{id}/import-features")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> importFeatures(@PathParam("id") UUID templateId, Map<String, Object> body) {
        Object ids = body.get("feature_field_ids");
        if (!(ids instanceof java.util.List)) {
            throw new IllegalArgumentException("feature_field_ids must be a list of Long");
        }
        java.util.List<Long> fieldIds = new java.util.ArrayList<>();
        for (Object o : (java.util.List<Object>) ids) {
            fieldIds.add(o instanceof Number ? ((Number) o).longValue() : Long.parseLong(o.toString()));
        }
        return ApiResponse.success(service.importFeatures(templateId, fieldIds));
    }

    @POST
    @Path("/{id}/base-model")
    public ApiResponse<com.cpq.configurator.entity.ConfiguratorTemplate> setBaseModel(
            @PathParam("id") UUID templateId, Map<String, Object> body) {
        Object mid = body.get("model_id");
        if (mid == null) throw new IllegalArgumentException("model_id required");
        UUID modelId = UUID.fromString(mid.toString());
        return ApiResponse.success(service.setBaseModel(templateId, modelId));
    }
}
