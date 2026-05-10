package com.cpq.importexcel.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.importexcel.dto.CreateCustomerExcelTemplateRequest;
import com.cpq.importexcel.dto.CustomerExcelTemplateDTO;
import com.cpq.importexcel.service.CustomerExcelTemplateService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.RestForm;

import java.util.List;
import java.util.UUID;

@Path("/api/cpq/excel-templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
public class CustomerExcelTemplateResource {

    @Inject
    CustomerExcelTemplateService service;

    @Inject
    SessionHelper sessionHelper;

    @GET
    public ApiResponse<List<CustomerExcelTemplateDTO>> list(@QueryParam("customerId") UUID customerId) {
        return ApiResponse.success(service.listByCustomer(customerId));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<CustomerExcelTemplateDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(service.getById(id));
    }

    @POST
    public ApiResponse<CustomerExcelTemplateDTO> create(CreateCustomerExcelTemplateRequest request,
                                                        @Context HttpServerRequest httpRequest) {
        UUID userId = sessionHelper.getCurrentUserId(httpRequest);
        return ApiResponse.success(service.create(request, userId));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<CustomerExcelTemplateDTO> update(@PathParam("id") UUID id,
                                                        CreateCustomerExcelTemplateRequest request) {
        return ApiResponse.success(service.update(id, request));
    }

    @DELETE
    @Path("/{id}")
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        service.delete(id);
        return ApiResponse.success();
    }

    @POST
    @Path("/parse-headers")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public ApiResponse<List<String>> parseHeaders(
            @RestForm("file") FileUpload file,
            @RestForm("sheetIndex") @DefaultValue("0") int sheetIndex,
            @RestForm("headerRowIndex") @DefaultValue("0") int headerRowIndex) {
        try (java.io.InputStream fis = file.uploadedFile().toFile().toURI().toURL().openStream()) {
            return ApiResponse.success(service.parseExcelHeaders(fis, sheetIndex, headerRowIndex));
        } catch (Exception e) {
            throw new com.cpq.common.exception.BusinessException(400, "Parse failed: " + e.getMessage());
        }
    }
}
