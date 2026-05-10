package com.cpq.datasource.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.datasource.dto.CreateDataSourceRequest;
import com.cpq.datasource.dto.DataSourceDTO;
import com.cpq.datasource.dto.DataSourceTestRequest;
import com.cpq.datasource.dto.DataSourceTestResult;
import com.cpq.datasource.service.DataSourceService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/datasources")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN"})
public class DataSourceResource {

    @Inject
    DataSourceService dataSourceService;

    @GET
    public ApiResponse<PageResult<DataSourceDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("type") String type,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(dataSourceService.list(page, size, type, keyword));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<DataSourceDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(dataSourceService.getById(id));
    }

    @POST
    public ApiResponse<DataSourceDTO> create(@Valid CreateDataSourceRequest request) {
        return ApiResponse.success(dataSourceService.create(request));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<DataSourceDTO> update(@PathParam("id") UUID id, CreateDataSourceRequest request) {
        return ApiResponse.success(dataSourceService.update(id, request));
    }

    @DELETE
    @Path("/{id}")
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        dataSourceService.delete(id);
        return ApiResponse.success();
    }

    @POST
    @Path("/{id}/test")
    public ApiResponse<DataSourceTestResult> test(@PathParam("id") UUID id, DataSourceTestRequest request) {
        Map<String, String> params = request != null ? request.testParams : null;
        return ApiResponse.success(dataSourceService.test(id, params));
    }

    @POST
    @Path("/{id}/execute")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<DataSourceTestResult> execute(@PathParam("id") UUID id, DataSourceTestRequest request) {
        Map<String, String> params = request != null ? request.testParams : null;
        return ApiResponse.success(dataSourceService.execute(id, params));
    }
}
