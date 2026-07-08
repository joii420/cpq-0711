package com.cpq.industry.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.industry.dto.IndustryDTO;
import com.cpq.industry.dto.IndustryRequest;
import com.cpq.industry.service.IndustryService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/industries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class IndustryResource {

    @Inject
    IndustryService industryService;

    @GET
    public ApiResponse<PageResult<IndustryDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("status") String status,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(industryService.list(page, size, status, keyword));
    }

    @GET
    @Path("/active")
    public ApiResponse<List<IndustryDTO>> listActive() {
        return ApiResponse.success(industryService.listActive());
    }

    @GET
    @Path("/{id}")
    public ApiResponse<IndustryDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(industryService.getById(id));
    }

    @POST
    public ApiResponse<IndustryDTO> create(@Valid IndustryRequest request) {
        return ApiResponse.success(industryService.create(request));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<IndustryDTO> update(@PathParam("id") UUID id, @Valid IndustryRequest request) {
        return ApiResponse.success(industryService.update(id, request));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        industryService.delete(id);
        return ApiResponse.success();
    }

    @POST
    @Path("/batch-delete")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> batchDelete(Map<String, List<UUID>> body) {
        List<UUID> ids = body.get("ids");
        if (ids != null && !ids.isEmpty()) industryService.batchDelete(ids);
        return ApiResponse.success();
    }
}
