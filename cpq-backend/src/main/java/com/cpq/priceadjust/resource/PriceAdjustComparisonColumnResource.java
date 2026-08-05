package com.cpq.priceadjust.resource;

import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.dto.ComparisonColumnsDTO;
import com.cpq.priceadjust.dto.PutComparisonColumnsRequest;
import com.cpq.priceadjust.dto.TemplateSeriesDTO;
import com.cpq.priceadjust.service.PriceAdjustComparisonColumnService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * task-0729 B6 · 比对列配置端点（api.md §1.8/§1.9/§1.10）。
 *
 * <p>🔒 2026-08-03 修正：响应体裸 DTO，不套 {@code ApiResponse} 信封（详见
 * {@link PriceAdjustStrategyResource} 类注释）。
 */
@Path("/api/cpq/price-adjust")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PriceAdjustComparisonColumnResource {

    @Inject PriceAdjustComparisonColumnService service;
    @Inject SessionHelper sessionHelper;
    @Context HttpServerRequest httpRequest;

    // 🔒 §1.8 template-series 已于 2026-08-04 迁往 PriceAdjustStrategyResource。
    //    原先挂在本类（类级 @Path("/api/cpq/price-adjust") + 方法级 "/strategies/{customerNo}/template-series"）
    //    时【运行时永久 404】：JAX-RS 先按类级 @Path 选最具体的资源类（JSR-370 §3.7.2），
    //    "/api/cpq/price-adjust/strategies" 比 "/api/cpq/price-adjust" 更具体 → 命中
    //    PriceAdjustStrategyResource → 类内无匹配方法 → 直接 404，【不回溯】到本类。
    //    通则：方法级路径的第一段不得等于另一个资源类的类级路径末段。详见迁往处的注释。

    /** §1.9 读取比对列配置（未配置返回默认列）。 */
    @GET
    @Path("/comparison-columns")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ComparisonColumnsDTO getColumns(
            @QueryParam("customerNo") String customerNo,
            @QueryParam("templateSeriesId") UUID templateSeriesId) {
        return service.getColumns(customerNo, templateSeriesId);
    }

    /** §1.10 唯一写入口。保存后异步重算该客户×模板系列下的 PENDING 料号。 */
    @PUT
    @Path("/comparison-columns")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public PriceAdjustComparisonColumnService.PutResult putColumns(PutComparisonColumnsRequest req) {
        UUID actorId = sessionHelper.getCurrentUserId(httpRequest);
        return service.putColumns(
            req != null ? req.customerNo : null,
            req != null ? req.templateSeriesId : null,
            req != null ? req.columns : null,
            actorId);
    }
}
