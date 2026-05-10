package com.cpq.product.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.product.dto.ProductProcessDTO;
import com.cpq.product.service.ProcessService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-product process binding endpoints.
 *
 * <p>Extracted from {@code ProcessResource} (T4 P1 fix): the original class was
 * declared with {@code @Path("/api/cpq/processes")} but exposed sub-paths that
 * <em>started with</em> {@code /products/...}. JAX-RS concatenates these to
 * {@code /api/cpq/processes/products/{id}/processes}, not the documented
 * {@code /api/cpq/products/{id}/processes} — so the routes were unreachable
 * via the API.md path.
 */
@Path("/api/cpq/products/{productId}/processes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
public class ProductProcessResource {

    @Inject
    ProcessService processService;

    @GET
    public ApiResponse<List<ProductProcessDTO>> list(@PathParam("productId") UUID productId) {
        return ApiResponse.success(processService.getProductProcesses(productId));
    }

    @PUT
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> bind(@PathParam("productId") UUID productId,
                                   Map<String, Object> body) {
        return doBind(productId, body);
    }

    @POST
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> bindCompat(@PathParam("productId") UUID productId,
                                         Map<String, Object> body) {
        return doBind(productId, body);
    }

    @DELETE
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> unbindAll(@PathParam("productId") UUID productId) {
        processService.unbindAll(productId);
        return ApiResponse.success();
    }

    @SuppressWarnings("unchecked")
    private ApiResponse<Void> doBind(UUID productId, Map<String, Object> body) {
        if (body == null) body = Map.of();
        Object items = body.get("processes");
        if (items instanceof List) {
            processService.bindProcesses(productId, (List<Map<String, Object>>) items);
        } else {
            Object ids = body.get("processIds");
            if (ids instanceof List) {
                List<Map<String, Object>> converted = ((List<?>) ids).stream()
                        .map(id -> Map.<String, Object>of("processId", id.toString(), "sortOrder", 0, "isRequired", false))
                        .collect(java.util.stream.Collectors.toList());
                processService.bindProcesses(productId, converted);
            } else {
                processService.bindProcesses(productId, List.of());
            }
        }
        return ApiResponse.success();
    }
}
