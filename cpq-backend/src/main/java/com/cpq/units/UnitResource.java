package com.cpq.units;

import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import com.cpq.common.dto.ApiResponse;
import static com.cpq.units.UnitDtos.*;

@Path("/api/cpq/units")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})
public class UnitResource {
    @Inject UnitService service;
    @GET public ApiResponse<UnitList> list(@QueryParam("category") String category, @QueryParam("enabled") Boolean enabled,
                                           @QueryParam("page") @DefaultValue("0") int page, @QueryParam("size") @DefaultValue("50") int size) { return ApiResponse.success(service.list(category,enabled,page,size)); }
    @POST @RoleAllowed({"SYSTEM_ADMIN"}) public ApiResponse<Unit> create(UnitUpsert req) { return ApiResponse.success(service.create(req)); }
    @PATCH @Path("/{id}") @RoleAllowed({"SYSTEM_ADMIN"}) public ApiResponse<Unit> update(@PathParam("id") long id, UnitUpdate req) { return ApiResponse.success(service.update(id,req)); }
    @POST @Path("/{id}/disable") @RoleAllowed({"SYSTEM_ADMIN"}) public ApiResponse<Unit> disable(@PathParam("id") long id, VersionRequest req) { service.disable(id,req == null ? null : req.version); return ApiResponse.success(service.get(id)); }
    @POST @Path("/{id}/enable") @RoleAllowed({"SYSTEM_ADMIN"}) public ApiResponse<Unit> enable(@PathParam("id") long id, VersionRequest req) { return ApiResponse.success(service.enable(id,req == null ? null : req.version)); }
    @GET @Path("/{id}/conversions") public ApiResponse<ConversionGroup> conversions(@PathParam("id") long id) { return ApiResponse.success(service.conversions(id)); }
    @PUT @Path("/{id}/conversions") @RoleAllowed({"SYSTEM_ADMIN"}) public ApiResponse<ConversionGroup> saveConversions(@PathParam("id") long id, ConversionSave req) { return ApiResponse.success(service.saveConversions(id,req)); }
}
