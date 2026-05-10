package com.cpq.product.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.product.dto.ProcessDTO;
import com.cpq.product.service.ProcessService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/cpq/processes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
public class ProcessResource {

    @Inject
    ProcessService processService;

    @GET
    public ApiResponse<List<ProcessDTO>> list(@QueryParam("category") String category) {
        if (category != null && !category.isBlank()) {
            return ApiResponse.success(processService.listByCategory(category));
        }
        return ApiResponse.success(processService.listAll());
    }

    // Per-product process binding endpoints moved to ProductProcessResource (T4 P1 fix).
    // Original sub-paths /products/{id}/processes resolved to
    // /api/cpq/processes/products/{id}/processes which was unreachable via the documented API.
}
