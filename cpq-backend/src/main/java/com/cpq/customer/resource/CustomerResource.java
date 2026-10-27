package com.cpq.customer.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.filecrypto.FileCryptoService;
import com.cpq.customer.dto.CreateCustomerRequest;
import com.cpq.customer.dto.CustomerDTO;
import com.cpq.customer.service.CustomerService;
import com.cpq.customer.dto.CustomerImportReportDTO;
import com.cpq.customer.service.CustomerImportService;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/customers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class CustomerResource {

    @Inject
    CustomerService customerService;

    @Inject
    CustomerImportService customerImportService;

    @Inject
    SessionHelper sessionHelper;

    @Inject
    FileCryptoService fileCryptoService;

    @GET
    public ApiResponse<PageResult<CustomerDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("level") String level,
            @QueryParam("status") String status,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(customerService.list(page, size, level, status, keyword));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<CustomerDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(customerService.getById(id));
    }

    @POST
    public ApiResponse<CustomerDTO> create(@Valid CreateCustomerRequest request,
                                           @Context HttpServerRequest httpRequest) {
        UUID operatorId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        return ApiResponse.success(customerService.create(request, operatorId));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<CustomerDTO> update(@PathParam("id") UUID id, CreateCustomerRequest request) {
        return ApiResponse.success(customerService.update(id, request));
    }

    @GET
    @Path("/import/template")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response downloadImportTemplate() {
        return Response.ok(customerImportService.generateTemplate())
                .header("Content-Disposition", "attachment; filename=\"customer_import_template.xlsx\"; filename*=UTF-8''%E5%AE%A2%E6%88%B7%E5%AF%BC%E5%85%A5%E6%A8%A1%E6%9D%BF.xlsx")
                .build();
    }

    @POST
    @Path("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public ApiResponse<CustomerImportReportDTO> importCustomers(@RestForm("file") FileUpload file) {
        return ApiResponse.success(customerImportService.importCustomers(
                fileCryptoService.process(file).bytes()));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        customerService.delete(id);
        return ApiResponse.success();
    }

    @POST
    @Path("/batch-delete")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> batchDelete(Map<String, List<UUID>> body) {
        List<UUID> ids = body.get("ids");
        if (ids != null && !ids.isEmpty()) {
            customerService.batchDelete(ids);
        }
        return ApiResponse.success();
    }
}
