package com.cpq.costing.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.costing.dto.ComparisonConfigDTO;
import com.cpq.costing.dto.ComparisonConfigRequest;
import com.cpq.costing.dto.ComparisonDataDTO;
import com.cpq.costing.dto.ComparisonMetaDTO;
import com.cpq.costing.service.ComparisonViewService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * task-0717 报价单比对视图 — 新端点（连线配置版）。
 * 契约见 dev-docs/task-0717-比对视图/api.md。
 *
 * <p>与旧 {@code CostingSheetResource} 的 {@code GET /comparison}、{@code POST /comparison/export}
 * 互不影响、保留不动（api.md §0.4）。角色同 {@link com.cpq.costing.resource.CostingSheetResource}。
 */
@Path("/api/cpq/quotations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ComparisonViewResource {

    @Inject
    ComparisonViewService service;

    /** 连线抽屉数据源：两侧「页签 → 可比对值」目录（api.md §1）。 */
    @GET
    @Path("/{id}/comparison-view/meta")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ComparisonMetaDTO> getMeta(@PathParam("id") UUID quotationId) {
        return ApiResponse.success(service.getMeta(quotationId));
    }

    /** 逐销售料号 × 两侧 × 逐页签取值矩阵（api.md §2）。frozen 默认 false（编辑态口径）。 */
    @GET
    @Path("/{id}/comparison-view/data")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ComparisonDataDTO> getData(
            @PathParam("id") UUID quotationId,
            @QueryParam("frozen") @DefaultValue("false") boolean frozen) {
        return ApiResponse.success(service.getData(quotationId, frozen));
    }

    /** 读该桶比对列配置（api.md §3）。 */
    @GET
    @Path("/{id}/comparison-view/config")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ComparisonConfigDTO> getConfig(
            @PathParam("id") UUID quotationId,
            @QueryParam("bucket") String bucket) {
        return ApiResponse.success(service.getConfig(quotationId, bucket));
    }

    /** 全量覆盖保存该桶比对列配置（api.md §4）。 */
    @PUT
    @Path("/{id}/comparison-view/config")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ComparisonConfigDTO> putConfig(
            @PathParam("id") UUID quotationId,
            @QueryParam("bucket") String bucket,
            ComparisonConfigRequest request) {
        com.fasterxml.jackson.databind.JsonNode columns = request == null ? null : request.columns;
        return ApiResponse.success(service.upsertConfig(quotationId, bucket, columns));
    }
}
