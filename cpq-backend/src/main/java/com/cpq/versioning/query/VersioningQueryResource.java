package com.cpq.versioning.query;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for versioning history queries (UI-5 / UI-6).
 *
 * <p>Base path: {@code /api/cpq/versioning}
 *
 * <p>All endpoints are read-only (GET).
 * <ul>
 *   <li>GET /history?tableName=&customerId=&hfPartNo=&page=&size=</li>
 *   <li>GET /row/{tableName}/{recordId}</li>
 *   <li>GET /compare?tableName=&recordIdA=&recordIdB=</li>
 * </ul>
 */
@Path("/api/cpq/versioning")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
public class VersioningQueryResource {

    @Inject
    VersioningQueryService service;

    // -------------------------------------------------------------------------
    // GET /history
    // -------------------------------------------------------------------------

    /**
     * Paginated history of all versions for a business-key filter.
     *
     * @param tableName  mat_process | mat_fee | plating_fee
     * @param customerIdStr required customer UUID string
     * @param hfPartNo   optional HF part number filter
     * @param page       0-based page (default 0)
     * @param size       page size [1,200] (default 50)
     */
    @GET
    @Path("/history")
    public ApiResponse<PageResult<VersionHistoryItemDTO>> listHistory(
            @QueryParam("tableName")   String tableName,
            @QueryParam("customerId")  String customerIdStr,
            @QueryParam("hfPartNo")    String hfPartNo,
            @QueryParam("page")  @DefaultValue("0")  int page,
            @QueryParam("size")  @DefaultValue("50") int size) {

        if (size < 1 || size > 200) {
            throw new BusinessException(400, "size 必须在 [1, 200] 范围内");
        }
        if (page < 0) {
            throw new BusinessException(400, "page 不能为负数");
        }

        UUID customerId = parseRequiredUuid(customerIdStr, "customerId");
        return ApiResponse.success(service.listHistory(tableName, customerId, hfPartNo, page, size));
    }

    // -------------------------------------------------------------------------
    // GET /row/{tableName}/{recordId}
    // -------------------------------------------------------------------------

    /**
     * Full column detail for a single versioned row.
     *
     * @param tableName mat_process | mat_fee | plating_fee
     * @param recordId  UUID primary key of the row
     */
    @GET
    @Path("/row/{tableName}/{recordId}")
    public ApiResponse<Map<String, Object>> getRowDetail(
            @PathParam("tableName") String tableName,
            @PathParam("recordId")  String recordId) {

        UUID id = parseRequiredUuid(recordId, "recordId");
        return ApiResponse.success(service.getRowDetail(tableName, id));
    }

    // -------------------------------------------------------------------------
    // GET /compare
    // -------------------------------------------------------------------------

    /**
     * Field-level comparison between two version rows.
     *
     * @param tableName  mat_process | mat_fee | plating_fee
     * @param recordIdAStr UUID of row A (older version)
     * @param recordIdBStr UUID of row B (newer version)
     */
    @GET
    @Path("/compare")
    public ApiResponse<VersionCompareDTO> compareVersions(
            @QueryParam("tableName")  String tableName,
            @QueryParam("recordIdA")  String recordIdAStr,
            @QueryParam("recordIdB")  String recordIdBStr) {

        UUID idA = parseRequiredUuid(recordIdAStr, "recordIdA");
        UUID idB = parseRequiredUuid(recordIdBStr, "recordIdB");
        return ApiResponse.success(service.compareVersions(tableName, idA, idB));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID parseRequiredUuid(String raw, String paramName) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(400, paramName + " 不能为空");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, paramName + " UUID 格式不合法: " + raw);
        }
    }
}
