package com.cpq.priceadjust.resource;

import com.cpq.common.security.RoleAllowed;
import com.cpq.priceadjust.dto.PriceRevisionsResponse;
import com.cpq.priceadjust.dto.RevisionPreviewResponse;
import com.cpq.priceadjust.service.QuotationPriceRevisionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * task-0729 屏 7 · 报价单侧价格版本（api.md §4.1 / §4.2）。
 *
 * <p><b>为什么单独一个 Resource 而不是并进 {@code QuotationResource}</b>：路径虽挂在
 * {@code /api/cpq/quotations} 命名空间下（前端 {@code priceRevisionService.ts} 已按此写死），
 * 但响应体必须是 <b>裸 DTO</b>——前端 {@code api.ts} 拦截器只做一层 {@code response.data}
 * （axios 层）解包，<b>不解 {@code ApiResponse}{code,message,data} 信封</b>。而
 * {@code QuotationResource} 全类统一返回 {@code ApiResponse}。两种约定混在同一个类里，日后按
 * "照抄邻居方法" 的习惯改动必然出错（多套一层信封 → 前端读到 undefined，编译期无感）。
 * 独立成类把约定差异物理隔开，并在此注释说明原因。
 *
 * <p>JAX-RS 允许多个资源类共享同一 {@code @Path} 根，只要完整路径不冲突——本类的两个
 * 子路径在 {@code QuotationResource} 中均不存在。
 *
 * <p><b>读权限</b>：裁决 18 —— 屏 7 销售只读可见，故与 {@code QuotationResource} 类级角色集
 * 保持一致（销售能打开自己的单就能看版本轨迹）。本类**无任何写端点**（裁决 14）。
 */
@Path("/api/cpq/quotations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class QuotationPriceRevisionResource {

    @Inject QuotationPriceRevisionService service;

    /** §4.1 版本轨迹表 + 料号级价格版本表（屏 7 两张表同源，一次返回）。 */
    @GET
    @Path("/{quotationId}/price-revisions")
    public PriceRevisionsResponse getPriceRevisions(@PathParam("quotationId") UUID quotationId) {
        return service.getPriceRevisions(quotationId);
    }

    /** §4.2 切版只读预览（裁决 14：不落库）。 */
    @GET
    @Path("/{quotationId}/price-revisions/{revisionId}/preview")
    public RevisionPreviewResponse getPreview(@PathParam("quotationId") UUID quotationId,
                                              @PathParam("revisionId") UUID revisionId) {
        return service.getPreview(quotationId, revisionId);
    }
}
