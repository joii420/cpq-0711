package com.cpq.product.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.product.dto.ProcessDTO;
import com.cpq.product.dto.ProcessUpsertRequest;
import com.cpq.product.service.ProcessService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

@Path("/api/cpq/processes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
public class ProcessResource {

    @Inject
    ProcessService processService;

    /** GET /api/cpq/processes[?category=MACHINING] — list all or filter by category. */
    @GET
    public ApiResponse<List<ProcessDTO>> list(@QueryParam("category") String category) {
        if (category != null && !category.isBlank()) {
            return ApiResponse.success(processService.listByCategory(category));
        }
        return ApiResponse.success(processService.listAll());
    }

    /** GET /api/cpq/processes/{id} — single process detail. */
    @GET
    @Path("/{id}")
    public ApiResponse<ProcessDTO> detail(@PathParam("id") UUID id) {
        return ApiResponse.success(processService.getById(id));
    }

    /** POST /api/cpq/processes — create a new process (SYSTEM_ADMIN only). */
    @POST
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Response create(ProcessUpsertRequest req) {
        ProcessDTO created = processService.create(req);
        return Response.status(Response.Status.CREATED).entity(ApiResponse.success(created)).build();
    }

    /** PUT /api/cpq/processes/{id} — update an existing process (SYSTEM_ADMIN only). */
    @PUT
    @Path("/{id}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<ProcessDTO> update(@PathParam("id") UUID id, ProcessUpsertRequest req) {
        return ApiResponse.success(processService.update(id, req));
    }

    /** DELETE /api/cpq/processes/{id} — soft-delete: sets status=DISABLED (SYSTEM_ADMIN only). */
    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Response delete(@PathParam("id") UUID id) {
        processService.deleteSoft(id);
        return Response.noContent().build();
    }

    // Per-product process binding endpoints are in ProductProcessResource.
}
