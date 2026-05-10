package com.cpq.importexcel.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.importexcel.dto.CustomerMaterialMappingDTO;
import com.cpq.importexcel.dto.InternalMaterialDTO;
import com.cpq.importexcel.service.CustomerMaterialMappingService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.RestForm;

import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/customers/{customerId}/material-mappings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CustomerMaterialMappingResource {

    @Inject
    CustomerMaterialMappingService service;

    @GET
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<PageResult<CustomerMaterialMappingDTO>> list(
            @PathParam("customerId") UUID customerId,
            @QueryParam("keyword") String keyword,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return ApiResponse.success(service.listByCustomer(customerId, keyword, page, size));
    }

    @POST
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<CustomerMaterialMappingDTO> create(
            @PathParam("customerId") UUID customerId,
            Map<String, String> body) {
        if (body == null) {
            throw new com.cpq.common.exception.BusinessException(400, "Request body is required");
        }
        String customerPartNo = body.get("customerPartNo");
        if (customerPartNo == null || customerPartNo.isBlank()) {
            throw new com.cpq.common.exception.BusinessException(400, "customerPartNo is required");
        }
        String materialIdStr = body.get("materialId");
        if (materialIdStr == null || materialIdStr.isBlank()) {
            throw new com.cpq.common.exception.BusinessException(400, "materialId is required");
        }
        UUID materialId;
        try {
            materialId = UUID.fromString(materialIdStr);
        } catch (IllegalArgumentException e) {
            throw new com.cpq.common.exception.BusinessException(400, "materialId must be a valid UUID");
        }
        return ApiResponse.success(service.create(customerId, customerPartNo, materialId));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("customerId") UUID customerId, @PathParam("id") UUID id) {
        service.delete(id);
        return ApiResponse.success();
    }

    @POST
    @Path("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Map<String, Integer>> batchImport(
            @PathParam("customerId") UUID customerId,
            @RestForm("file") FileUpload file) {
        try (java.io.InputStream fis = file.uploadedFile().toFile().toURI().toURL().openStream()) {
            int count = service.batchImport(customerId, fis);
            return ApiResponse.success(Map.of("imported", count));
        } catch (Exception e) {
            throw new com.cpq.common.exception.BusinessException(400, "Import failed: " + e.getMessage());
        }
    }

    @GET
    @Path("/match")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<InternalMaterialDTO> match(
            @PathParam("customerId") UUID customerId,
            @QueryParam("partNo") String partNo) {
        InternalMaterialDTO result = service.matchPartNo(customerId, partNo);
        return ApiResponse.success(result);
    }
}
