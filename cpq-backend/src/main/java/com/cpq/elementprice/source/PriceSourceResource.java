package com.cpq.elementprice.source;

import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 价格源管理端点（task-0722 · B4，契约见 api.md §1）。
 * 裸 DTO / 裸数组响应（对齐 ElementResource 等既有端点，非 ApiResponse 信封）。
 */
@Path("/api/cpq/element-price/sources")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class PriceSourceResource {

    @Inject
    PriceSourceService service;

    @Inject
    SessionHelper sessionHelper;

    @Context
    HttpServerRequest httpRequest;

    @GET
    public List<PriceSourceDTO> list(@QueryParam("status") String status,
                                      @QueryParam("keyword") String keyword) {
        return service.list(status, keyword);
    }

    @POST
    public PriceSourceDTO create(PriceSourceUpsertRequest req) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        return service.create(req, userId);
    }

    @PUT
    @Path("/{id}")
    public PriceSourceDTO update(@PathParam("id") UUID id, PriceSourceUpsertRequest req) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        return service.update(id, req, userId);
    }

    @POST
    @Path("/{id}/status")
    public PriceSourceDTO updateStatus(@PathParam("id") UUID id, Map<String, String> body) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        String status = body == null ? null : body.get("status");
        return service.updateStatus(id, status, userId);
    }
}
