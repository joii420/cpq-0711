package com.cpq.system.ddl.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.system.ddl.dto.DdlOperationDTO;
import com.cpq.system.ddl.dto.ExtendColumnRequest;
import com.cpq.system.ddl.service.DdlExtensionService;
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
 * REST API for runtime ALTER TABLE ADD COLUMN (DDL Extension).
 *
 * <p>All endpoints require SYSTEM_ADMIN role.
 * Base path: /api/system/ddl
 *
 * <p>v5.1 §3.4 TECH-4
 */
@Path("/api/system/ddl")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DdlExtensionResource {

    @Inject
    DdlExtensionService ddlExtensionService;

    @Inject
    SessionHelper sessionHelper;

    /**
     * Execute ALTER TABLE ADD COLUMN on the target table.
     *
     * <p>Request body: {@link ExtendColumnRequest}
     * Response: {@link DdlOperationDTO} with status=SUCCESS and migrationContent for copy-to-git.
     *
     * <p>Returns 400 if validation fails, 409 if DDL lock held, 423 if import lock active,
     * 500 if ALTER fails (with compensation DROP COLUMN attempted).
     */
    @POST
    @Path("/extend-column")
    @RoleAllowed("SYSTEM_ADMIN")
    public Response extendColumn(@Valid ExtendColumnRequest request,
                                 @Context HttpServerRequest httpRequest) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        DdlOperationDTO result = ddlExtensionService.extendColumn(request, userId);
        return Response.ok(ApiResponse.success(result)).build();
    }

    /**
     * List DDL operation history (newest first, paginated).
     *
     * @param page         zero-based page index (default 0)
     * @param size         page size (default 20)
     * @param status       optional filter: SUCCESS | FAILED
     */
    @GET
    @Path("/history")
    @RoleAllowed("SYSTEM_ADMIN")
    public Response listHistory(@QueryParam("page") @DefaultValue("0") int page,
                                @QueryParam("size") @DefaultValue("20") int size,
                                @QueryParam("status") String status) {
        List<DdlOperationDTO> list = ddlExtensionService.listHistory(page, size, status);
        return Response.ok(ApiResponse.success(list)).build();
    }

    /**
     * List the 15 tables eligible for runtime column extension.
     */
    @GET
    @Path("/extensible-tables")
    @RoleAllowed("SYSTEM_ADMIN")
    public Response listExtensibleTables() {
        List<String> tables = ddlExtensionService.listExtensibleTables();
        return Response.ok(ApiResponse.success(tables)).build();
    }

    /**
     * List existing columns for a given table (reads information_schema.columns).
     * Used by the frontend wizard to detect duplicate column names before submission.
     *
     * @param tableName must be in the extensible-tables whitelist
     */
    @GET
    @Path("/columns/{tableName}")
    @RoleAllowed("SYSTEM_ADMIN")
    public Response listColumns(@PathParam("tableName") String tableName) {
        List<String> columns = ddlExtensionService.listExistingColumns(tableName);
        return Response.ok(ApiResponse.success(columns)).build();
    }
}
