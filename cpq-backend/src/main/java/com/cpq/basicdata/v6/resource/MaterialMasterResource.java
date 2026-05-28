package com.cpq.basicdata.v6.resource;

import com.cpq.basicdata.v6.dto.CreateMaterialMasterRequest;
import com.cpq.basicdata.v6.dto.MaterialMasterDTO;
import com.cpq.basicdata.v6.service.MaterialMasterCrudService;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * V6 料号主数据 REST 资源（替代旧 InternalMaterialResource,服务于「产品管理 → 产品主数据」UI）。
 *
 * <p>路由 {@code /api/cpq/material-masters},与 V6 导入服务共表 {@code material_master}。
 */
@Path("/api/cpq/material-masters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MaterialMasterResource {

    @Inject
    MaterialMasterCrudService service;

    @GET
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<PageResult<MaterialMasterDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(service.list(page, size, keyword));
    }

    @GET
    @Path("/{id}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<MaterialMasterDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(service.getById(id));
    }

    @POST
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<MaterialMasterDTO> create(@Valid CreateMaterialMasterRequest request) {
        return ApiResponse.success(service.create(request));
    }

    @PUT
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<MaterialMasterDTO> update(@PathParam("id") UUID id,
                                                  CreateMaterialMasterRequest request) {
        return ApiResponse.success(service.update(id, request));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        service.delete(id);
        return ApiResponse.success();
    }
}
