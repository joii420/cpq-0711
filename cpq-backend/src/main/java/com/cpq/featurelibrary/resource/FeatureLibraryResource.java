package com.cpq.featurelibrary.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.featurelibrary.entity.FeatureField;
import com.cpq.featurelibrary.entity.FeatureGroup;
import com.cpq.featurelibrary.entity.FeatureValue;
import com.cpq.featurelibrary.service.FeatureLibraryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/**
 * 特征库 REST API（§18A）。
 *
 * <p>骨架阶段：暴露 CRUD 端点。重新拉取差异 / ERP 同步等业务后续切片实现。
 */
@Path("/api/cpq/feature-library")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN", "PRICING_MANAGER", "SALES_MANAGER"})
public class FeatureLibraryResource {

    @Inject
    FeatureLibraryService service;

    // ===== Group =====

    @GET
    @Path("/groups")
    public ApiResponse<PageResult<FeatureGroup>> listGroups(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("status") String status,
            @QueryParam("category") String category,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(service.listGroups(page, size, status, category, keyword));
    }

    @GET
    @Path("/groups/{id}")
    public ApiResponse<FeatureGroup> getGroup(@PathParam("id") Long id) {
        return ApiResponse.success(service.getGroup(id));
    }

    @POST
    @Path("/groups")
    public ApiResponse<FeatureGroup> createGroup(FeatureGroup group) {
        return ApiResponse.success(service.createGroup(group));
    }

    @PUT
    @Path("/groups/{id}")
    public ApiResponse<FeatureGroup> updateGroup(@PathParam("id") Long id, FeatureGroup patch) {
        return ApiResponse.success(service.updateGroup(id, patch));
    }

    @POST
    @Path("/groups/{id}/archive")
    public ApiResponse<Void> archiveGroup(@PathParam("id") Long id) {
        service.archiveGroup(id);
        return ApiResponse.success(null);
    }

    // ===== Field =====

    @GET
    @Path("/groups/{groupId}/fields")
    public ApiResponse<List<FeatureField>> listFields(@PathParam("groupId") Long groupId) {
        return ApiResponse.success(service.listFields(groupId));
    }

    @POST
    @Path("/groups/{groupId}/fields")
    public ApiResponse<FeatureField> createField(@PathParam("groupId") Long groupId, FeatureField field) {
        return ApiResponse.success(service.createField(groupId, field));
    }

    @PUT
    @Path("/fields/{fieldId}")
    public ApiResponse<FeatureField> updateField(@PathParam("fieldId") Long fieldId, FeatureField patch) {
        return ApiResponse.success(service.updateField(fieldId, patch));
    }

    @DELETE
    @Path("/fields/{fieldId}")
    public ApiResponse<Void> deleteField(@PathParam("fieldId") Long fieldId) {
        service.deleteField(fieldId);
        return ApiResponse.success(null);
    }

    // ===== Value =====

    @GET
    @Path("/fields/{fieldId}/values")
    public ApiResponse<List<FeatureValue>> listValues(@PathParam("fieldId") Long fieldId) {
        return ApiResponse.success(service.listValues(fieldId));
    }

    @POST
    @Path("/fields/{fieldId}/values")
    public ApiResponse<FeatureValue> createValue(@PathParam("fieldId") Long fieldId, FeatureValue v) {
        return ApiResponse.success(service.createValue(fieldId, v));
    }

    @PUT
    @Path("/values/{valueId}")
    public ApiResponse<FeatureValue> updateValue(@PathParam("valueId") Long valueId, FeatureValue patch) {
        return ApiResponse.success(service.updateValue(valueId, patch));
    }

    @DELETE
    @Path("/values/{valueId}")
    public ApiResponse<Void> deleteValue(@PathParam("valueId") Long valueId) {
        service.deleteValue(valueId);
        return ApiResponse.success(null);
    }

    @GET
    @Path("/groups/template-refs")
    public ApiResponse<Map<Long, Integer>> templateRefsByGroup() {
        return ApiResponse.success(service.countTemplateRefsByGroup());
    }

    // ===== 后续切片实现 =====

    @GET
    @Path("/refresh-diff/{templateId}")
    public ApiResponse<List<Map<String, Object>>> refreshDiff(@PathParam("templateId") java.util.UUID templateId) {
        return ApiResponse.success(service.computeRefreshDiff(templateId));
    }
}
