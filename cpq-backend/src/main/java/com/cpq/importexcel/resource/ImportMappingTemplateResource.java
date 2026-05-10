package com.cpq.importexcel.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.importexcel.dto.CreateImportMappingTemplateRequest;
import com.cpq.importexcel.dto.ImportMappingTemplateDTO;
import com.cpq.importexcel.service.ImportMappingTemplateService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/cpq/import-mappings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
public class ImportMappingTemplateResource {

    @Inject
    ImportMappingTemplateService service;

    @Inject
    SessionHelper sessionHelper;

    @GET
    public ApiResponse<List<ImportMappingTemplateDTO>> list(@QueryParam("excelTemplateId") UUID excelTemplateId) {
        return ApiResponse.success(service.listByExcelTemplate(excelTemplateId));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<ImportMappingTemplateDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(service.getById(id));
    }

    @POST
    public ApiResponse<ImportMappingTemplateDTO> create(CreateImportMappingTemplateRequest request,
                                                        @Context HttpServerRequest httpRequest) {
        UUID userId = sessionHelper.getCurrentUserId(httpRequest);
        return ApiResponse.success(service.create(request, userId));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<ImportMappingTemplateDTO> update(@PathParam("id") UUID id,
                                                        CreateImportMappingTemplateRequest request) {
        return ApiResponse.success(service.update(id, request));
    }

    @DELETE
    @Path("/{id}")
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        service.delete(id);
        return ApiResponse.success();
    }
}
