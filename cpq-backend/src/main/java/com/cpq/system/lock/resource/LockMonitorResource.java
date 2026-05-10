package com.cpq.system.lock.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.SessionHelper;
import com.cpq.system.lock.dto.DdlLockStatusDTO;
import com.cpq.system.lock.dto.ProductImportLockDTO;
import com.cpq.system.lock.dto.ReleaseLockRequest;
import com.cpq.system.lock.service.DdlOperationLockService;
import com.cpq.system.lock.service.ProductImportLockService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

/**
 * Admin-only lock monitoring and management.
 */
@Path("/api/system/locks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LockMonitorResource {

    @Inject
    ProductImportLockService productImportLockService;

    @Inject
    DdlOperationLockService ddlOperationLockService;

    @Inject
    SessionHelper sessionHelper;

    // ---- Product import locks ----

    @GET
    @Path("/product-imports")
    public Response listProductImports(@Context HttpServerRequest request) {
        requireSystemAdmin(request);
        List<ProductImportLockDTO> list = productImportLockService.listActive();
        return Response.ok(ApiResponse.success(list)).build();
    }

    @POST
    @Path("/product-imports/{id}/release")
    public Response releaseProductImport(@PathParam("id") UUID lockId,
                                         @Valid ReleaseLockRequest body,
                                         @Context HttpServerRequest request) {
        requireSystemAdmin(request);
        UUID adminId = sessionHelper.getCurrentUserIdOrFallback(request);
        productImportLockService.forceRelease(lockId, adminId);
        return Response.ok(ApiResponse.success()).build();
    }

    // ---- DDL lock ----

    @GET
    @Path("/ddl")
    public Response getDdlStatus(@Context HttpServerRequest request) {
        requireSystemAdmin(request);
        DdlLockStatusDTO status = ddlOperationLockService.status();
        return Response.ok(ApiResponse.success(status)).build();
    }

    @POST
    @Path("/ddl/release")
    public Response releaseDdl(@Context HttpServerRequest request) {
        requireSystemAdmin(request);
        UUID adminId = sessionHelper.getCurrentUserIdOrFallback(request);
        ddlOperationLockService.forceRelease(adminId);
        return Response.ok(ApiResponse.success()).build();
    }

    private void requireSystemAdmin(HttpServerRequest request) {
        sessionHelper.requireSystemAdmin(request);
    }
}
