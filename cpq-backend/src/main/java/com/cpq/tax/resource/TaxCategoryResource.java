package com.cpq.tax.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.tax.dto.*;
import com.cpq.tax.service.TaxCategoryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Path("/api/cpq/tax-categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class TaxCategoryResource {
    @Inject TaxCategoryService service;
    @GET public ApiResponse<List<TaxCategoryDTO>> list(@QueryParam("keyword") String keyword, @QueryParam("status") String status, @QueryParam("effectiveOn") LocalDate effectiveOn) { return ApiResponse.success(service.list(keyword, status, effectiveOn)); }
    @POST public ApiResponse<TaxCategoryDTO> create(TaxCategorySaveRequest r) { return ApiResponse.success(service.create(r)); }
    @PUT @Path("/{id}") public ApiResponse<TaxCategoryDTO> update(@PathParam("id") UUID id, TaxCategorySaveRequest r) { return ApiResponse.success(service.update(id, r)); }
    @PUT @Path("/default") public ApiResponse<Void> setDefault(TaxCategoryDefaultRequest r) { service.setDefault(r.taxCategoryId); return ApiResponse.success(); }
    @GET @Path("/{id}/vat-versions") public ApiResponse<List<TaxCategoryVatVersionDTO>> versions(@PathParam("id") UUID id) { return ApiResponse.success(service.versions(id)); }
    @POST @Path("/{id}/vat-versions") public ApiResponse<TaxCategoryVatVersionDTO> addVersion(@PathParam("id") UUID id, TaxCategoryVatVersionSaveRequest r) { return ApiResponse.success(service.addVersion(id, r)); }
    @PUT @Path("/{id}/vat-versions/{versionId}") public ApiResponse<TaxCategoryVatVersionDTO> updateVersion(@PathParam("id") UUID id, @PathParam("versionId") UUID versionId, TaxCategoryVatVersionSaveRequest r) { return ApiResponse.success(service.updateVersion(id, versionId, r)); }
}
