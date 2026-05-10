package com.cpq.costing.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.costing.dto.CostingTemplateDTO;
import com.cpq.costing.dto.CreateCostingTemplateRequest;
import com.cpq.costing.service.CostingTemplateService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/cpq/costing-templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CostingTemplateResource {

    @Inject
    CostingTemplateService service;

    @GET
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<CostingTemplateDTO>> list(
            @QueryParam("status") String status,
            // V73 起：报价单/核价单 Excel 视图按 linkedTemplateId 反查关联 Excel 模板。
            // V74 起：移除 categoryId 入参 —— Excel 模板不再按产品分类组织，按 linked_template_id 调用。
            @QueryParam("linkedTemplateId") UUID linkedTemplateId) {
        return ApiResponse.success(service.list(status, linkedTemplateId));
    }

    @GET
    @Path("/{id}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingTemplateDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(service.getById(id));
    }

    @POST
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingTemplateDTO> create(@Valid CreateCostingTemplateRequest req) {
        return ApiResponse.success(service.create(req));
    }

    @PUT
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingTemplateDTO> update(@PathParam("id") UUID id, CreateCostingTemplateRequest req) {
        return ApiResponse.success(service.update(id, req));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        service.delete(id);
        return ApiResponse.success();
    }

    /**
     * 已归档 / 已发布 → 派生新草稿。前端「创建新草稿」按钮调用，便于在原模板基础上修改。
     */
    @POST
    @Path("/{id}/new-draft")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingTemplateDTO> createNewDraft(@PathParam("id") UUID id) {
        return ApiResponse.success(service.createNewDraft(id));
    }

    /**
     * V73：单独的关联 setter。
     * PUT /costing-templates/{id}/linked-template?templateId=xxx
     *   templateId 缺省/空 → 解除关联
     *   有值 → 校验 template 存在并设置
     */
    @PUT
    @Path("/{id}/linked-template")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingTemplateDTO> setLinkedTemplate(
            @PathParam("id") UUID id,
            @QueryParam("templateId") UUID templateId) {
        return ApiResponse.success(service.setLinkedTemplate(id, templateId));
    }

    @POST
    @Path("/{id}/publish")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingTemplateDTO> publish(@PathParam("id") UUID id) {
        return ApiResponse.success(service.publish(id));
    }

    @POST
    @Path("/{id}/archive")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingTemplateDTO> archive(@PathParam("id") UUID id) {
        return ApiResponse.success(service.archive(id));
    }
}
