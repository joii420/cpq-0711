package com.cpq.elementprice.pricetable;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 价格表查询端点（task-0722 · B6，契约见 api.md §3）+ B7.1 各源最新价（§4.1）
 * + update-0724 · B6 价格手工维护（新建/修改/删除/变更历史，契约见 api.md §1~§3、§5）。
 */
@Path("/api/cpq/element-price")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class PriceTableResource {

    @Inject
    PriceTableService service;

    @Inject
    PriceMaintenanceService maintenanceService;

    @Inject
    SessionHelper sessionHelper;

    @Context
    HttpServerRequest httpRequest;

    @GET
    @Path("/prices")
    public PageResult<ElementPriceRowDTO> listDetail(
            @QueryParam("sourceId") UUID sourceId,
            @QueryParam("from") String fromStr,
            @QueryParam("to") String toStr,
            @QueryParam("keyword") String keyword,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return service.listDetail(sourceId, parseDate(fromStr, "from"), parseDate(toStr, "to"), keyword, page, size);
    }

    @GET
    @Path("/prices/matrix")
    public PriceMatrixDTO matrix(
            @QueryParam("sourceId") UUID sourceId,
            @QueryParam("from") String fromStr,
            @QueryParam("to") String toStr,
            @QueryParam("keyword") String keyword) {
        return service.matrix(sourceId, parseDate(fromStr, "from"), parseDate(toStr, "to"), keyword);
    }

    @GET
    @Path("/prices/export")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response exportDetail(
            @QueryParam("sourceId") UUID sourceId,
            @QueryParam("from") String fromStr,
            @QueryParam("to") String toStr,
            @QueryParam("keyword") String keyword) {
        byte[] xlsx = service.exportDetail(sourceId, parseDate(fromStr, "from"), parseDate(toStr, "to"), keyword);
        return xlsxResponse(xlsx, "元素价格明细.xlsx");
    }

    @GET
    @Path("/prices/matrix/export")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response exportMatrix(
            @QueryParam("sourceId") UUID sourceId,
            @QueryParam("from") String fromStr,
            @QueryParam("to") String toStr,
            @QueryParam("keyword") String keyword) {
        byte[] xlsx = service.exportMatrix(sourceId, parseDate(fromStr, "from"), parseDate(toStr, "to"), keyword);
        return xlsxResponse(xlsx, "元素价格矩阵.xlsx");
    }

    @GET
    @Path("/latest-by-source")
    public List<ElementLatestPriceDTO> latestBySource(@QueryParam("elementCode") String elementCode) {
        return service.latestBySource(elementCode);
    }

    // ──────────────────────────── update-0724 · B6：手工维护 4 端点 ────────────────────────────

    @POST
    @Path("/prices")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(CreatePriceRequest req) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        ElementPriceRowDTO dto = maintenanceService.create(req, userId);
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    @PUT
    @Path("/prices/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public ElementPriceRowDTO update(@PathParam("id") UUID id, UpdatePriceRequest req) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        return maintenanceService.update(id, req, userId);
    }

    @DELETE
    @Path("/prices/{id}")
    public Response delete(@PathParam("id") UUID id) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        maintenanceService.delete(id, userId);
        return Response.noContent().build();
    }

    @GET
    @Path("/prices/history")
    public PageResult<PriceHistoryDTO> history(
            @QueryParam("sourceId") UUID sourceId,
            @QueryParam("from") String fromStr,
            @QueryParam("to") String toStr,
            @QueryParam("keyword") String keyword,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return service.listHistory(sourceId, parseDate(fromStr, "from"), parseDate(toStr, "to"), keyword, page, size);
    }

    // ── helpers ──

    private LocalDate parseDate(String s, String field) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            throw new BusinessException(400, field + " 日期格式无效，期望 yyyy-MM-dd: " + s);
        }
    }

    private Response xlsxResponse(byte[] xlsx, String chineseFilename) {
        String encoded = URLEncoder.encode(chineseFilename, StandardCharsets.UTF_8).replace("+", "%20");
        return Response.ok(xlsx)
                .header("Content-Disposition", "attachment; filename=\"export.xlsx\"; filename*=UTF-8''" + encoded)
                .build();
    }
}
