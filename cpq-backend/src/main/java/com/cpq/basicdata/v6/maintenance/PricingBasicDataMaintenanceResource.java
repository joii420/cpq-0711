package com.cpq.basicdata.v6.maintenance;

import com.cpq.basicdata.v6.maintenance.dto.*;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * 核价基础数据维护端点（task-0712）。元数据驱动的一套通用接口，按 sheetKey + materialNo 参数化，覆盖 16 个版本组。
 *
 * <p>鉴权（C10）：读 = SALES_MANAGER/PRICING_MANAGER/SYSTEM_ADMIN；写 = PRICING_MANAGER/SYSTEM_ADMIN。
 * <p>system_type 固定 PRICING（服务端注入，不由前端传）。异常经 GlobalExceptionMapper 映射（400/404/409/422）。
 */
@Path("/api/cpq/pricing-basic-data")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PricingBasicDataMaintenanceResource {

    @Inject PricingMaintenanceService service;
    @Inject SessionHelper sessionHelper;

    @Context HttpServerRequest httpRequest;

    /** §1 有核价数据的销售料号列表（搜索 + 分页）。 */
    @GET
    @Path("/parts")
    @RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<PartListPage> parts(
            @QueryParam("keyword") String keyword,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return ApiResponse.success(service.listParts(keyword, page, size));
    }

    /** §2 16 组列定义元数据（前端可缓存）。data 包成 {"sheets":[...]}（api.md §2）。 */
    @GET
    @Path("/sheets")
    @RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<SheetsResponse> sheets() {
        return ApiResponse.success(new SheetsResponse(service.listSheets()));
    }

    /** §3 料号概览：16 组当前状态（抽屉 tab 徽标）。 */
    @GET
    @Path("/parts/{materialNo}/overview")
    @RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<OverviewDTO> overview(@PathParam("materialNo") String materialNo) {
        return ApiResponse.success(service.overview(materialNo));
    }

    /** §4 读取某组数据（当前版 / 历史版）。 */
    @GET
    @Path("/parts/{materialNo}/sheets/{sheetKey}/rows")
    @RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<RowsDTO> rows(
            @PathParam("materialNo") String materialNo,
            @PathParam("sheetKey") String sheetKey,
            @QueryParam("version") String version) {
        return ApiResponse.success(service.readRows(materialNo, sheetKey, version));
    }

    /** §5 版本列表（版本切换下拉 + 操作留痕）。 */
    @GET
    @Path("/parts/{materialNo}/sheets/{sheetKey}/versions")
    @RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<VersionsDTO> versions(
            @PathParam("materialNo") String materialNo,
            @PathParam("sheetKey") String sheetKey) {
        return ApiResponse.success(service.versions(materialNo, sheetKey));
    }

    /** §7 主表候选下拉（process / element / material）。 */
    @GET
    @Path("/lookup/{masterType}")
    @RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<LookupResponse> lookup(
            @PathParam("masterType") String masterType,
            @QueryParam("keyword") String keyword,
            @QueryParam("limit") @DefaultValue("20") int limit) {
        return ApiResponse.success(service.lookup(masterType, keyword, limit));
    }

    /** §6 保存整组（编辑升版）。 */
    @PUT
    @Path("/parts/{materialNo}/sheets/{sheetKey}/rows")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<SaveGroupResult> save(
            @PathParam("materialNo") String materialNo,
            @PathParam("sheetKey") String sheetKey,
            SaveGroupRequest req) {
        UUID userId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        return ApiResponse.success(service.saveGroup(materialNo, sheetKey, req, userId));
    }
}
