package com.cpq.system.lock.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.SessionHelper;
import com.cpq.system.lock.service.ProductImportLockService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

/**
 * Lock operations accessible to the lock holder (not admin-only).
 */
@Path("/api/cpq/import/locks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ImportLockResource {

    @Inject
    ProductImportLockService productImportLockService;

    @Inject
    SessionHelper sessionHelper;

    /**
     * Heartbeat: extend lock TTL.
     * Only the lock holder can call this.
     */
    @POST
    @Path("/{id}/heartbeat")
    public Response heartbeat(@PathParam("id") UUID lockId,
                              @Context HttpServerRequest request) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(request);
        productImportLockService.heartbeat(lockId, userId);
        return Response.ok(ApiResponse.success()).build();
    }
}
