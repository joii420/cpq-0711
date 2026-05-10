package com.cpq.changelog;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * REST endpoints for the UI-7 Change Log page.
 *
 * <p>Base path: {@code /api/cpq/change-log}
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /search?customerId=&hfPartNo=&tableName=&fieldName=
 *               &changedAtFrom=&changedAtTo=&importance=&changeSource=
 *               &page=&size=</li>
 *   <li>GET /export?...same filters...&format=csv|xlsx</li>
 * </ul>
 */
@Path("/api/cpq/change-log")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})
public class ChangeLogResource {

    @Inject
    ChangeLogService service;

    // -------------------------------------------------------------------------
    // GET /search
    // -------------------------------------------------------------------------

    /**
     * Paginated search of change log entries.
     *
     * @param customerIdStr  optional customer UUID string
     * @param hfPartNo       optional HF part number
     * @param tableName      optional table name filter
     * @param fieldName      optional field name filter
     * @param changedAtFrom  optional ISO-8601 start datetime (inclusive)
     * @param changedAtTo    optional ISO-8601 end datetime (inclusive)
     * @param importance     optional CRITICAL | IMPORTANT | NORMAL
     * @param changeSource   optional V5_IMPORT | MANUAL_EDIT | SYSTEM_INIT | SYNC
     * @param page           0-based page index (default 0)
     * @param size           page size [1,200] (default 50)
     */
    @GET
    @Path("/search")
    public ApiResponse<PageResult<ChangeLogEntryDTO>> search(
            @QueryParam("customerId")    String customerIdStr,
            @QueryParam("hfPartNo")      String hfPartNo,
            @QueryParam("tableName")     String tableName,
            @QueryParam("fieldName")     String fieldName,
            @QueryParam("changedAtFrom") String changedAtFromStr,
            @QueryParam("changedAtTo")   String changedAtToStr,
            @QueryParam("importance")    String importance,
            @QueryParam("changeSource")  String changeSource,
            @QueryParam("page") @DefaultValue("0")  int page,
            @QueryParam("size") @DefaultValue("50") int size) {

        if (size < 1 || size > 200) {
            throw new BusinessException(400, "size 必须在 [1, 200] 范围内");
        }
        if (page < 0) {
            throw new BusinessException(400, "page 不能为负数");
        }

        ChangeLogSearchParams params = buildParams(
                customerIdStr, hfPartNo, tableName, fieldName,
                changedAtFromStr, changedAtToStr, importance, changeSource);

        return ApiResponse.success(service.search(params, page, size));
    }

    // -------------------------------------------------------------------------
    // GET /export
    // -------------------------------------------------------------------------

    /**
     * Streaming export of change log entries.
     *
     * @param format csv | xlsx
     * @param other  same filter params as /search
     */
    @GET
    @Path("/export")
    @Produces({MediaType.APPLICATION_OCTET_STREAM, "text/csv",
               "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    public Response export(
            @QueryParam("customerId")    String customerIdStr,
            @QueryParam("hfPartNo")      String hfPartNo,
            @QueryParam("tableName")     String tableName,
            @QueryParam("fieldName")     String fieldName,
            @QueryParam("changedAtFrom") String changedAtFromStr,
            @QueryParam("changedAtTo")   String changedAtToStr,
            @QueryParam("importance")    String importance,
            @QueryParam("changeSource")  String changeSource,
            @QueryParam("format")  @DefaultValue("csv") String format) {

        ChangeLogSearchParams params = buildParams(
                customerIdStr, hfPartNo, tableName, fieldName,
                changedAtFromStr, changedAtToStr, importance, changeSource);

        StreamingOutput stream = service.export(params, format);

        String mediaType;
        String filename;
        if ("xlsx".equalsIgnoreCase(format)) {
            mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            filename  = "change_log.xlsx";
        } else {
            mediaType = "text/csv;charset=UTF-8";
            filename  = "change_log.csv";
        }

        return Response.ok(stream, mediaType)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ChangeLogSearchParams buildParams(
            String customerIdStr, String hfPartNo, String tableName, String fieldName,
            String changedAtFromStr, String changedAtToStr, String importance, String changeSource) {

        ChangeLogSearchParams p = new ChangeLogSearchParams();
        p.customerId    = parseOptionalUuid(customerIdStr, "customerId");
        p.hfPartNo      = blankToNull(hfPartNo);
        p.tableName     = blankToNull(tableName);
        p.fieldName     = blankToNull(fieldName);
        p.changedAtFrom = parseOptionalDateTime(changedAtFromStr, "changedAtFrom");
        p.changedAtTo   = parseOptionalDateTime(changedAtToStr, "changedAtTo");
        p.importance    = blankToNull(importance);
        p.changeSource  = blankToNull(changeSource);
        return p;
    }

    private UUID parseOptionalUuid(String raw, String paramName) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, paramName + " UUID 格式不合法: " + raw);
        }
    }

    private OffsetDateTime parseOptionalDateTime(String raw, String paramName) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (Exception e) {
            throw new BusinessException(400, paramName + " 日期时间格式不合法 (需 ISO-8601): " + raw);
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
