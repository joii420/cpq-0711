package com.cpq.priceadjust.resource;

import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.dto.*;
import com.cpq.priceadjust.service.PriceAdjustStrategyService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

/**
 * task-0729 策略 CRUD 补交 · api.md §1.1~§1.7（屏 1）。
 *
 * <p>🔒 响应体裸 DTO/裸数组，不套 {@code ApiResponse}{code,message,data} 信封——api.md §0.2
 * 明确"沿用 /api/cpq/element-price/* 风格，不另造包装层"，前端 {@code priceAdjustService.ts} /
 * {@code api.ts} 拦截器只做一层 {@code response.data} 解包，多套一层信封会让前端读到
 * {@code undefined}（此约定同样适用于本包其余 4 个既有 Resource，详见本次提交说明）。
 */
@Path("/api/cpq/price-adjust/strategies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PriceAdjustStrategyResource {

    @Inject PriceAdjustStrategyService service;
    @Inject SessionHelper sessionHelper;
    @Context HttpServerRequest httpRequest;

    /** §1.1 策略不存在时返回 200 + exists:false 空壳，不是 404。 */
    @GET
    @Path("/{customerNo}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public StrategyDTO getStrategy(@PathParam("customerNo") String customerNo) {
        return service.getStrategy(customerNo);
    }

    @PUT
    @Path("/{customerNo}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public StrategyDTO putStrategy(@PathParam("customerNo") String customerNo, PutStrategyRequest req) {
        UUID actorId = sessionHelper.getCurrentUserId(httpRequest);
        return service.putStrategy(customerNo, req, actorId);
    }

    @GET
    @Path("/{customerNo}/materials")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public PageResult<MaterialRowDTO> getMaterials(
            @PathParam("customerNo") String customerNo,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("customerPartNo") String customerPartNo,
            @QueryParam("customerMaterialName") String customerMaterialName,
            @QueryParam("materialNo") String materialNo,
            @QueryParam("materialName") String materialName,
            @QueryParam("selectedOnly") @DefaultValue("false") boolean selectedOnly) {
        return service.getMaterials(customerNo, page, size, customerPartNo, customerMaterialName,
            materialNo, materialName, selectedOnly);
    }

    @PUT
    @Path("/{customerNo}/materials")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response putMaterials(@PathParam("customerNo") String customerNo, PutMaterialsRequest req) {
        UUID actorId = sessionHelper.getCurrentUserId(httpRequest);
        service.putMaterials(customerNo, req, actorId);
        return Response.ok().build();
    }

    /** §1.5 pivot：元素 × 最近10个版本。{@code includeDisabled} 默认 true（前端恒显式带上）。 */
    @GET
    @Path("/{customerNo}/elements")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ElementsMatrixResponse getElements(
            @PathParam("customerNo") String customerNo,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("keyword") String keyword,
            @QueryParam("includeDisabled") Boolean includeDisabled) {
        return service.getElements(customerNo, page, size, keyword, includeDisabled);
    }

    @PUT
    @Path("/{customerNo}/elements")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response putElements(@PathParam("customerNo") String customerNo, PutElementsRequest req) {
        UUID actorId = sessionHelper.getCurrentUserId(httpRequest);
        service.putElements(customerNo, req, actorId);
        return Response.ok().build();
    }

    @GET
    @Path("/{customerNo}/logs")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public PageResult<StrategyLogDTO> getLogs(
            @PathParam("customerNo") String customerNo,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return service.getLogs(customerNo, page, size);
    }
}
