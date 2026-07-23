package com.cpq.elementprice.pricetable;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 价格表查询端点（task-0722 · B6，契约见 api.md §3）+ B7.1 各源最新价（§4.1）。
 */
@Path("/api/cpq/element-price")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class PriceTableResource {

    @Inject
    PriceTableService service;

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
