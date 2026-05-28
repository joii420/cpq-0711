package com.cpq.partmodel.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.partmodel.entity.PartModel;
import com.cpq.partmodel.service.PartModelService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/part-models")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN", "PRICING_MANAGER", "SALES_MANAGER"})
public class PartModelResource {

    @Inject
    PartModelService service;

    @GET
    public ApiResponse<PageResult<PartModel>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("partNo") String partNo,
            @QueryParam("isCurrent") Boolean isCurrent) {
        return ApiResponse.success(service.list(page, size, partNo, isCurrent));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<PartModel> get(@PathParam("id") UUID id) {
        return ApiResponse.success(service.getById(id));
    }

    @POST
    public ApiResponse<PartModel> register(PartModel m) {
        return ApiResponse.success(service.register(m));
    }

    @POST
    @Path("/{id}/set-current")
    public ApiResponse<PartModel> setCurrent(@PathParam("id") UUID id) {
        return ApiResponse.success(service.setCurrent(id));
    }

    // ===== 后续切片实现 =====

    @POST
    @Path("/upload")
    public ApiResponse<Map<String, Object>> upload(Map<String, Object> body) {
        // TODO §6: UG NX .prt + .stp 上传 + 触发转换流水线
        return ApiResponse.success(Map.of("status", "not_implemented", "todo", "P3 CAD 转换"));
    }
}
