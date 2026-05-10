package com.cpq.costingbasic.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.costingbasic.dto.*;
import com.cpq.costingbasic.service.CostingBasicDataService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * 核价基础数据 API。
 *
 * 路由设计：版本主表用 /costing-basic/versions；明细按 kind 分三个子路径以减少前端误用。
 *   - GET    /versions?kind=&status=
 *   - POST   /versions
 *   - PUT    /versions/{id}
 *   - POST   /versions/{id}/publish | /archive | /set-default | /new-draft
 *   - DELETE /versions/{id}
 *   - GET/POST/PUT/DELETE  /versions/{id}/elements | /materials | /rates
 */
@Path("/api/cpq/costing-basic")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class CostingBasicDataResource {

    @Inject CostingBasicDataService svc;

    // ── 版本主表 ──────────────────────────────────────────────────────

    @GET
    @Path("/versions")
    public ApiResponse<List<CostingPriceVersionDTO>> listVersions(
            @QueryParam("kind") String kind,
            @QueryParam("status") String status) {
        return ApiResponse.success(svc.listVersions(kind, status));
    }

    @GET
    @Path("/versions/{id}")
    public ApiResponse<CostingPriceVersionDTO> getVersion(@PathParam("id") UUID id) {
        return ApiResponse.success(svc.getVersion(id));
    }

    @POST
    @Path("/versions")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingPriceVersionDTO> createVersion(@Valid CreateVersionRequest req) {
        return ApiResponse.success(svc.createVersion(req));
    }

    @PUT
    @Path("/versions/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingPriceVersionDTO> updateVersion(
            @PathParam("id") UUID id, CreateVersionRequest req) {
        return ApiResponse.success(svc.updateVersion(id, req));
    }

    @POST
    @Path("/versions/{id}/publish")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingPriceVersionDTO> publish(@PathParam("id") UUID id) {
        return ApiResponse.success(svc.publishVersion(id));
    }

    @POST
    @Path("/versions/{id}/archive")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingPriceVersionDTO> archive(@PathParam("id") UUID id) {
        return ApiResponse.success(svc.archiveVersion(id));
    }

    @POST
    @Path("/versions/{id}/set-default")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingPriceVersionDTO> setDefault(@PathParam("id") UUID id) {
        return ApiResponse.success(svc.setAsDefault(id));
    }

    @POST
    @Path("/versions/{id}/new-draft")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CostingPriceVersionDTO> newDraft(
            @PathParam("id") UUID id,
            @QueryParam("versionNumber") String versionNumber) {
        return ApiResponse.success(svc.createDraftFrom(id, versionNumber));
    }

    @DELETE
    @Path("/versions/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> deleteVersion(@PathParam("id") UUID id) {
        svc.deleteVersion(id);
        return ApiResponse.success();
    }

    // ── 元素价格 ──────────────────────────────────────────────────────

    @GET
    @Path("/versions/{versionId}/elements")
    public ApiResponse<List<ElementPriceDTO>> listElement(@PathParam("versionId") UUID versionId) {
        return ApiResponse.success(svc.listElementPrices(versionId));
    }

    @POST
    @Path("/versions/{versionId}/elements")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ElementPriceDTO> createElement(
            @PathParam("versionId") UUID versionId, ElementPriceDTO req) {
        return ApiResponse.success(svc.createElementPrice(versionId, req));
    }

    @PUT
    @Path("/elements/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ElementPriceDTO> updateElement(@PathParam("id") UUID id, ElementPriceDTO req) {
        return ApiResponse.success(svc.updateElementPrice(id, req));
    }

    @DELETE
    @Path("/elements/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> deleteElement(@PathParam("id") UUID id) {
        svc.deleteElementPrice(id);
        return ApiResponse.success();
    }

    // ── 材料价格 ──────────────────────────────────────────────────────

    @GET
    @Path("/versions/{versionId}/materials")
    public ApiResponse<List<MaterialPriceDTO>> listMaterial(@PathParam("versionId") UUID versionId) {
        return ApiResponse.success(svc.listMaterialPrices(versionId));
    }

    @POST
    @Path("/versions/{versionId}/materials")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<MaterialPriceDTO> createMaterial(
            @PathParam("versionId") UUID versionId, MaterialPriceDTO req) {
        return ApiResponse.success(svc.createMaterialPrice(versionId, req));
    }

    @PUT
    @Path("/materials/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<MaterialPriceDTO> updateMaterial(@PathParam("id") UUID id, MaterialPriceDTO req) {
        return ApiResponse.success(svc.updateMaterialPrice(id, req));
    }

    @DELETE
    @Path("/materials/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> deleteMaterial(@PathParam("id") UUID id) {
        svc.deleteMaterialPrice(id);
        return ApiResponse.success();
    }

    // ── 汇率 ────────────────────────────────────────────────────────

    @GET
    @Path("/versions/{versionId}/rates")
    public ApiResponse<List<ExchangeRateDTO>> listRate(@PathParam("versionId") UUID versionId) {
        return ApiResponse.success(svc.listExchangeRates(versionId));
    }

    @POST
    @Path("/versions/{versionId}/rates")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ExchangeRateDTO> createRate(
            @PathParam("versionId") UUID versionId, ExchangeRateDTO req) {
        return ApiResponse.success(svc.createExchangeRate(versionId, req));
    }

    @PUT
    @Path("/rates/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ExchangeRateDTO> updateRate(@PathParam("id") UUID id, ExchangeRateDTO req) {
        return ApiResponse.success(svc.updateExchangeRate(id, req));
    }

    @DELETE
    @Path("/rates/{id}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> deleteRate(@PathParam("id") UUID id) {
        svc.deleteExchangeRate(id);
        return ApiResponse.success();
    }
}
