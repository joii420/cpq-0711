package com.cpq.elementprice;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REST resource for element reference price management.
 *
 * <p>UI-3 元素价格中心 v1 (Phase 4 #20).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /api/cpq/element-prices/reference?elementName=&priceDate=   — latest MANUAL price ≤ date</li>
 *   <li>GET  /api/cpq/element-prices/history?elementName=&from=&to=&page=&size= — paginated history</li>
 *   <li>POST /api/cpq/element-prices/manual   (SYSTEM_ADMIN only) — upsert reference price</li>
 *   <li>GET  /api/cpq/element-prices/available-elements — distinct element names from mat_bom</li>
 * </ul>
 */
@Path("/api/cpq/element-prices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ElementPriceResource {

    @Inject
    ElementPriceService service;

    @Inject
    SessionHelper sessionHelper;

    /**
     * GET /reference?elementName=Ag&priceDate=2026-04-27
     *
     * Returns the most recent MANUAL price for the element whose price_date ≤ priceDate.
     * Returns HTTP 200 with data=null when no price exists (not 404).
     */
    @GET
    @Path("/reference")
    public ApiResponse<ElementReferenceDTO> getReference(
            @QueryParam("elementName") String elementName,
            @QueryParam("priceDate") String priceDateStr) {
        LocalDate priceDate = parseDateOrToday(priceDateStr);
        ElementReferenceDTO dto = service.getReference(elementName, priceDate);
        return ApiResponse.success(dto);
    }

    /**
     * GET /history?elementName=Ag&from=2026-03-28&to=2026-04-27&page=0&size=20
     *
     * Lists MANUAL price history in descending date order.
     * Defaults: from = today-30d, to = today, page = 0, size = 20.
     */
    @GET
    @Path("/history")
    public ApiResponse<List<ElementReferenceDTO>> listHistory(
            @QueryParam("elementName") String elementName,
            @QueryParam("from") String fromStr,
            @QueryParam("to") String toStr,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        LocalDate from = parseDate(fromStr);
        LocalDate to = parseDate(toStr);
        return ApiResponse.success(service.listHistory(elementName, from, to, page, size));
    }

    /**
     * POST /manual
     *
     * Restricted to SYSTEM_ADMIN. Upserts a MANUAL reference price for today.
     * Idempotent: calling twice with the same elementName on the same day overwrites.
     */
    @POST
    @Path("/manual")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<ElementReferenceDTO> upsertManual(
            @Valid UpsertManualPriceRequest request,
            @Context HttpServerRequest httpRequest) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        ElementReferenceDTO result = service.upsertManual(
                request.elementName,
                request.price,
                request.currency,
                request.unit,
                request.note,
                userId);
        return ApiResponse.success(result);
    }

    /**
     * GET /available-elements
     *
     * Returns distinct element names from mat_bom (bom_type=ELEMENT).
     * Used by front-end dropdown when filling element prices in a quotation.
     */
    @GET
    @Path("/available-elements")
    public ApiResponse<List<String>> listAvailableElements() {
        return ApiResponse.success(service.listAvailableElements());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private LocalDate parseDateOrToday(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return LocalDate.now();
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            throw new com.cpq.common.exception.BusinessException(400,
                    "日期格式无效，期望 yyyy-MM-dd: " + dateStr);
        }
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            throw new com.cpq.common.exception.BusinessException(400,
                    "日期格式无效，期望 yyyy-MM-dd: " + dateStr);
        }
    }
}
