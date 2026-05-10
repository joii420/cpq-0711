package com.cpq.template.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.template.dto.CompareTemplatesRequest;
import com.cpq.template.dto.CreateTemplateRequest;
import com.cpq.template.dto.PublishRequest;
import com.cpq.template.dto.TemplateComparisonResult;
import com.cpq.template.dto.TemplateDTO;
import com.cpq.template.service.TemplateComparisonService;
import com.cpq.template.service.TemplateService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/cpq/templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class TemplateResource {

    @Inject
    TemplateService templateService;

    @Inject
    TemplateComparisonService templateComparisonService;

    @GET
    public ApiResponse<List<TemplateDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("category") String category,
            @QueryParam("customerId") UUID customerId,
            @QueryParam("categoryId") UUID categoryId,
            @QueryParam("status") String status,
            @QueryParam("keyword") String keyword,
            // V71：按模板类型过滤（QUOTATION / COSTING），不传则返全部
            @QueryParam("templateKind") String templateKind) {
        return ApiResponse.success(templateService.list(page, size, category, customerId, categoryId, status, keyword, templateKind));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<TemplateDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(templateService.getById(id));
    }

    /**
     * 客户报价模板匹配 — 客户专属优先 → 通用兜底。
     * 对应 docs/API.md L100/L643 设计。
     */
    @GET
    @Path("/match-customer-quote")
    public ApiResponse<com.cpq.template.dto.TemplateMatchResult> matchCustomerQuote(
            @QueryParam("customerId") UUID customerId,
            @QueryParam("categoryId") UUID categoryId) {
        return ApiResponse.success(templateService.matchCustomerQuoteTemplate(customerId, categoryId));
    }

    @POST
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<TemplateDTO> create(CreateTemplateRequest request) {
        return ApiResponse.success(templateService.create(request));
    }

    @PUT
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<TemplateDTO> update(
            @PathParam("id") UUID id,
            CreateTemplateRequest request) {
        return ApiResponse.success(templateService.update(id, request));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        templateService.delete(id);
        return ApiResponse.success();
    }

    @POST
    @Path("/{id}/publish")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<TemplateDTO> publish(
            @PathParam("id") UUID id,
            PublishRequest request) {
        return ApiResponse.success(templateService.publish(id, request));
    }

    @POST
    @Path("/{id}/archive")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<TemplateDTO> archive(
            @PathParam("id") UUID id,
            @QueryParam("force") @DefaultValue("false") boolean force) {
        return ApiResponse.success(templateService.archive(id, force));
    }

    @POST
    @Path("/{id}/new-draft")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<TemplateDTO> createNewDraft(@PathParam("id") UUID id) {
        return ApiResponse.success(templateService.createNewDraft(id));
    }

    @GET
    @Path("/series/{seriesId}/versions")
    public ApiResponse<List<TemplateDTO>> getVersionHistory(@PathParam("seriesId") UUID seriesId) {
        return ApiResponse.success(templateService.getVersionHistory(seriesId));
    }

    @POST
    @Path("/compare")
    public ApiResponse<TemplateComparisonResult> compare(CompareTemplatesRequest request) {
        return ApiResponse.success(templateComparisonService.compare(request.templateAId, request.templateBId));
    }
}
