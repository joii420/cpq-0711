package com.cpq.priceadjust.resource;

import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.dto.*;
import com.cpq.priceadjust.service.PriceAdjustComparisonColumnService;
import com.cpq.priceadjust.service.PriceAdjustStrategyService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
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
    /** §1.8 template-series 的实现仍在比对列服务里（它是比对列配置区的数据源），只是端点挂本类。 */
    @Inject PriceAdjustComparisonColumnService comparisonColumnService;
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

    /**
     * §1.8 屏 1 比对列配置区的模板系列选择器数据源（按客户查其 QUOTATION 模板系列）。
     *
     * <p>🔒 <b>2026-08-04 从 {@link PriceAdjustComparisonColumnResource} 迁来 —— 它在那里
     * 运行时永久 404</b>。原写法是类级 {@code @Path("/api/cpq/price-adjust")} + 方法级
     * {@code @Path("/strategies/{customerNo}/template-series")}，拼出的完整路径没错，但
     * JAX-RS 的分发是<b>两阶段</b>的（JSR-370 §3.7.2）：先按<b>类级</b> {@code @Path} 选出
     * 匹配最具体的资源类，<b>再</b>在该类内部找方法，<b>不回溯到其他类</b>。本类的类级路径
     * {@code /api/cpq/price-adjust/strategies} 比它的 {@code /api/cpq/price-adjust} 更具体，
     * 于是请求先落到本类，本类当时没有这个方法 → 404，永远到不了那边。
     *
     * <p><b>通则（新增端点前必查）</b>：<b>方法级路径的第一段，不得等于另一个资源类的类级
     * 路径末段</b>。本包里 {@code /api/cpq/price-adjust} 与
     * {@code /api/cpq/price-adjust/strategies} 是嵌套关系，因此挂在前者上的方法级路径不能以
     * {@code /strategies} 开头（{@code /reviews} 同理，见
     * {@link PriceAdjustReviewResource}）。
     *
     * <p>⚠️ 这类问题<b>编译不报错、单测不报错、grep {@code @Path} 也查不出来</b>（路径字符串
     * 明明在），只在运行时 404。对账端点必须实跑 curl 看是否 404，不能只 grep 注解。
     *
     * <p>迁移后完整路径 = 类级 {@code /api/cpq/price-adjust/strategies} + 方法级
     * {@code /{customerNo}/template-series}，与 api.md §1.8 <b>逐字不变</b>，前端零改动。
     */
    @GET
    @Path("/{customerNo}/template-series")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public List<TemplateSeriesDTO> listTemplateSeries(@PathParam("customerNo") String customerNo) {
        return comparisonColumnService.listTemplateSeries(customerNo);
    }
}
