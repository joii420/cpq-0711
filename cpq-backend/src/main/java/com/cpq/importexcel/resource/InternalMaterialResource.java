package com.cpq.importexcel.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.importexcel.dto.CreateInternalMaterialRequest;
import com.cpq.importexcel.dto.InternalMaterialDTO;
import com.cpq.importexcel.service.InternalMaterialService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.RestForm;

import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/internal-materials")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InternalMaterialResource {

    @Inject
    InternalMaterialService service;

    @GET
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<PageResult<InternalMaterialDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("keyword") String keyword,
            @QueryParam("statusCode") String statusCode) {
        return ApiResponse.success(service.list(page, size, keyword, statusCode));
    }

    @GET
    @Path("/{id}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<InternalMaterialDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(service.getById(id));
    }

    @POST
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<InternalMaterialDTO> create(@Valid CreateInternalMaterialRequest request) {
        return ApiResponse.success(service.create(request));
    }

    @PUT
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<InternalMaterialDTO> update(@PathParam("id") UUID id,
                                                   CreateInternalMaterialRequest request) {
        return ApiResponse.success(service.update(id, request));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        service.delete(id);
        return ApiResponse.success();
    }

    @POST
    @Path("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Map<String, Integer>> importFromExcel(@RestForm("file") FileUpload file) {
        try (java.io.InputStream is = file.uploadedFile().toFile().toURI().toURL().openStream()) {
            int count = service.importFromExcel(is);
            return ApiResponse.success(Map.of("imported", count));
        } catch (Exception e) {
            throw new com.cpq.common.exception.BusinessException(400, "Import failed: " + e.getMessage());
        }
    }
}
