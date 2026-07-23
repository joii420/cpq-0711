package com.cpq.elementprice.strategy;

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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * 价格策略端点（task-0722 · B8 CRUD / B9 历史 / B10 试算，契约见 api.md §5~§7）。
 */
@Path("/api/cpq/element-price/strategies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class StrategyResource {

    @Inject
    StrategyService service;

    @Inject
    SessionHelper sessionHelper;

    @Context
    HttpServerRequest httpRequest;

    /** GET /strategies?customerNo= — 读取某客户（或 _GLOBAL_）的全部策略。*/
    @GET
    public StrategyBundleDTO getBundle(@QueryParam("customerNo") String customerNo) {
        return service.getBundle(customerNo);
    }

    /** PUT /strategies/default — 保存客户级默认策略（新建或覆盖）。*/
    @PUT
    @Path("/default")
    public StrategyDTO saveDefault(StrategyUpsertRequest req) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        return service.saveDefault(req, userId);
    }

    /** POST /strategies/exceptions — 新建元素级例外。*/
    @POST
    @Path("/exceptions")
    public StrategyDTO createException(StrategyUpsertRequest req) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        return service.createException(req, userId);
    }

    /** PUT /strategies/exceptions/{id} — 修改元素级例外。*/
    @PUT
    @Path("/exceptions/{id}")
    public StrategyDTO updateException(@PathParam("id") UUID id, StrategyUpsertRequest req) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        return service.updateException(id, req, userId);
    }

    /** DELETE /strategies/exceptions/{id} — 删除元素级例外。*/
    @DELETE
    @Path("/exceptions/{id}")
    public Response deleteException(@PathParam("id") UUID id) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        service.deleteException(id, userId);
        return Response.noContent().build();
    }

    /** POST /strategies/simulate — 策略试算（只读，不落库）。*/
    @POST
    @Path("/simulate")
    public List<SimulateRowDTO> simulate(SimulateRequest req) {
        return service.simulate(req);
    }

    /** GET /strategies/history?customerNo=&elementCode=&from=&to=&changedBy=&page=&size= — 变更历史（只读）。*/
    @GET
    @Path("/history")
    public PageResult<StrategyHistoryDTO> history(
            @QueryParam("customerNo") String customerNo,
            @QueryParam("elementCode") String elementCode,
            @QueryParam("from") String fromStr,
            @QueryParam("to") String toStr,
            @QueryParam("changedBy") String changedBy,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return service.history(customerNo, elementCode, parseDateTime(fromStr, false), parseDateTime(toStr, true),
                changedBy, page, size);
    }

    /** 宽松解析：接受完整 ISO-8601 时间戳，或退化接受 yyyy-MM-dd（当天起 / 止）。*/
    private OffsetDateTime parseDateTime(String s, boolean endOfDay) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s.trim());
        } catch (DateTimeParseException ignore) {
            try {
                LocalDate d = LocalDate.parse(s.trim());
                return endOfDay
                        ? d.atTime(23, 59, 59).atOffset(java.time.ZoneOffset.UTC)
                        : d.atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                throw new BusinessException(400, "日期格式无效: " + s);
            }
        }
    }
}
