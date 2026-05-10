package com.cpq.masterdata.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.masterdata.dto.MasterDataOverviewDTO;
import com.cpq.masterdata.dto.PagedTableDataDTO;
import com.cpq.masterdata.service.MasterDataService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for the UI-4 Master Data Maintenance page.
 *
 * <p>Base path: {@code /api/cpq/master-data}
 *
 * <p>All endpoints are read-only (GET). No writes occur here.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /overview?customerId=      — summary of all 13 tables</li>
 *   <li>GET /table/{tableName}?customerId=&page=&size=&search=  — paginated rows</li>
 *   <li>GET /table/{tableName}/row/{rowId}  — single row detail</li>
 * </ul>
 */
@Path("/api/cpq/master-data")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
public class MasterDataResource {

    @Inject
    MasterDataService service;

    // -------------------------------------------------------------------------
    // GET /overview
    // -------------------------------------------------------------------------

    /**
     * Overview of all 13 registered physical tables.
     *
     * @param customerIdStr optional UUID string; when present, CUSTOMER-scoped tables
     *                      are filtered by customer_id
     */
    @GET
    @Path("/overview")
    public ApiResponse<MasterDataOverviewDTO> getOverview(
            @QueryParam("customerId") String customerIdStr) {

        UUID customerId = parseOptionalUuid(customerIdStr, "customerId");
        return ApiResponse.success(service.getOverview(customerId));
    }

    // -------------------------------------------------------------------------
    // GET /table/{tableName}
    // -------------------------------------------------------------------------

    /**
     * Paginated rows from a single physical table.
     *
     * @param tableName    must be one of the 13 registered table names
     * @param customerIdStr optional UUID string for CUSTOMER-scoped filtering
     * @param page         0-based page index (default 0)
     * @param size         rows per page [1, 200] (default 50)
     * @param search       optional substring filter on the table's searchField (ILIKE)
     */
    @GET
    @Path("/table/{tableName}")
    public ApiResponse<PagedTableDataDTO> queryTable(
            @PathParam("tableName") String tableName,
            @QueryParam("customerId") String customerIdStr,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size,
            @QueryParam("search") String search) {

        if (size < 1 || size > 200) {
            throw new BusinessException(400, "size 必须在 [1, 200] 范围内");
        }
        if (page < 0) {
            throw new BusinessException(400, "page 不能为负数");
        }

        UUID customerId = parseOptionalUuid(customerIdStr, "customerId");
        return ApiResponse.success(service.queryTable(tableName, customerId, page, size, search));
    }

    // -------------------------------------------------------------------------
    // GET /table/{tableName}/row/{rowId}
    // -------------------------------------------------------------------------

    /**
     * Single row detail.
     *
     * @param tableName must be one of the 13 registered table names
     * @param rowId     primary key value; UUID format for most tables, part_no string for mat_part
     */
    @GET
    @Path("/table/{tableName}/row/{rowId}")
    public ApiResponse<Map<String, Object>> getRowDetail(
            @PathParam("tableName") String tableName,
            @PathParam("rowId") String rowId) {

        return ApiResponse.success(service.getRowDetail(tableName, rowId));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID parseOptionalUuid(String raw, String paramName) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, paramName + " UUID 格式不合法: " + raw);
        }
    }
}
