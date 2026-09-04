package com.cpq.dataset.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.dataset.dto.*;
import com.cpq.dataset.exception.DatasetValidationException;
import com.cpq.dataset.exception.DatasetVersionConflictException;
import com.cpq.dataset.service.DatasetMaintenanceService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 三套数据集的<b>维护端</b>端点（task-260902 · B-9 / B-10 / B-11）。
 *
 * <p>契约逐字见 {@code api.md §2 ~ §8}。路径风格照搬现有
 * {@code PricingBasicDataMaintenanceResource}（{@code /parts/{axis}/sheets/{sheetKey}/rows} 同构），
 * 便于前端 {@code createSheetApi(basePath)} 工厂复用同一套 URL 拼装（fronttask F-1）。
 *
 * <h2>三条不能忘的约束</h2>
 * <ul>
 *   <li>🚨 <b>闸门 A0 · D-13</b>：本类是<b>新建</b>的，与
 *       {@code PricingBasicDataMaintenanceResource} 完全并行 —— 现有端点一个字节都不改（AC-43）。</li>
 *   <li>导入端点 {@code POST /dataset/{dataset}/import}（B-8）<b>不在本类</b>，
 *       在 {@code DatasetImportResource} —— 两个 Resource 分开，避免并行开发时互相覆盖。</li>
 *   <li><b>B-11 权限（AC-31）</b>：写端点仅 {@code PRICING_MANAGER} / {@code SYSTEM_ADMIN}；
 *       读端点对销售角色开放。⚠️ 项目里<b>没有</b>名为 {@code SALES} 的角色，实际角色是
 *       {@code SALES_REP} / {@code SALES_MANAGER}，故 api.md §0 的「SALES」按这两个展开。</li>
 * </ul>
 */
@Path("/api/cpq/dataset")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DatasetMaintenanceResource {

    /** 读端点角色集：核价 / 系统管理员 + 销售两档（api.md §0「SALES」的实际展开）。 */
    private static final String[] READ_ROLES =
        {"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"};

    @Inject DatasetMaintenanceService service;
    @Inject SessionHelper sessionHelper;

    @Context HttpServerRequest httpRequest;

    // ==================================================================
    // 读端点（B-9）
    // ==================================================================

    /** api.md §2 —— 带版本 sheet 元数据（AC-26：tab 数量与顺序由本端点决定，前端不写死）。 */
    @GET
    @Path("/{dataset}/sheets")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<DsSheetsResponse> sheets(@PathParam("dataset") String dataset) {
        return ApiResponse.success(service.listSheets(dataset));
    }

    /**
     * api.md §3 —— 料号列表（AC-25）。
     *
     * <p>⚠️ {@code page} 是 <b>0-based</b>（api.md §3 明写，与现有 {@code /pricing-basic-data/parts}
     * 的 1-based 不同）。默认值写 {@code 0} 而不是 {@code 1}，别顺手对齐旧端点。
     *
     * <p>{@code configured}（B-15）：{@code true}=只看已配齐、{@code false}=只看未配齐、
     * <b>不传=全部</b>。对应原型「配置状态」下拉。⚠️ 类型必须是<b>包装类</b> {@code Boolean} 而不是
     * {@code boolean} —— 用基本类型时不传会被 JAX-RS 兜底成 {@code false}，等于<b>默认只显示未配齐</b>，
     * 且不报错。
     */
    @GET
    @Path("/{dataset}/parts")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<DsPartsPage> parts(
            @PathParam("dataset") String dataset,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("keyword") String keyword,
            @QueryParam("sortBy") String sortBy,
            @QueryParam("sortDir") @DefaultValue("asc") String sortDir,
            @QueryParam("configured") Boolean configured) {
        return ApiResponse.success(service.listParts(dataset, keyword, page, size, sortBy, sortDir, configured));
    }

    /** api.md §4 —— 抽屉徽标。无数据的 sheet 也会出现在数组里（{@code versionNo=null}，AC-32）。 */
    @GET
    @Path("/{dataset}/parts/{axisValue}/overview")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<DsOverview> overview(
            @PathParam("dataset") String dataset,
            @PathParam("axisValue") String axisValue) {
        return ApiResponse.success(service.overview(dataset, axisValue));
    }

    /**
     * api.md §5 —— 行数据。
     *
     * <p>{@code version} 省略 = 当前版本；给历史版本号则读 {@code _history} 并置
     * {@code isLatest=false} / {@code readOnly=true}（AC-29）。
     * 该轴值从未有过数据 → {@code rows=[]} + {@code versionNo=null}，🚫 不是 404（AC-32）。
     */
    @GET
    @Path("/{dataset}/parts/{axisValue}/sheets/{sheetKey}/rows")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<DsRows> rows(
            @PathParam("dataset") String dataset,
            @PathParam("axisValue") String axisValue,
            @PathParam("sheetKey") String sheetKey,
            @QueryParam("version") Integer version) {
        return ApiResponse.success(service.readRows(dataset, axisValue, sheetKey, version));
    }

    /** api.md §6 —— 版本列表（倒序，最新在前；历史版读自 {@code _history}）。 */
    @GET
    @Path("/{dataset}/parts/{axisValue}/sheets/{sheetKey}/versions")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<DsVersions> versions(
            @PathParam("dataset") String dataset,
            @PathParam("axisValue") String axisValue,
            @PathParam("sheetKey") String sheetKey) {
        return ApiResponse.success(service.versions(dataset, axisValue, sheetKey));
    }

    /**
     * api.md §8 —— 主数据下拉（只读）。
     *
     * <p>🚨 复用的是<b>查询逻辑</b>，路径是<b>新开</b>的 —— 现有
     * {@code /pricing-basic-data/lookup/{masterType}} 不改（AC-43）。
     */
    @GET
    @Path("/{dataset}/lookup/{masterType}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<DsLookupResponse> lookup(
            @PathParam("dataset") String dataset,
            @PathParam("masterType") String masterType,
            @QueryParam("keyword") String keyword,
            @QueryParam("limit") @DefaultValue("20") int limit) {
        return ApiResponse.success(service.lookup(dataset, masterType, keyword, limit));
    }

    /**
     * api.md §8.5 —— 电镀方案<b>只读</b>列表（B-14 / AC-49 / AC-50 / AC-51）。
     *
     * <p>{@code {dataset}} 仅接受 {@code quote} 与 {@code cost-detail}；
     * {@code cost-basic} 没有电镀方案表 → <b>404</b>。
     * {@code columns} 按数据集下发（报价 10 列 / 详细核价 8 列），前端不得写死。
     *
     * <p>🚫 <b>本端点没有配套的写端点</b>（AC-51）：电镀方案是免版本表，
     * 写入只经导入通道 {@code POST /dataset/{dataset}/import}。
     */
    @GET
    @Path("/{dataset}/plating-schemes")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<DsPlatingSchemes> platingSchemes(
            @PathParam("dataset") String dataset,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(service.listPlatingSchemes(dataset, keyword, page, size));
    }

    // ==================================================================
    // 写端点（B-10 + B-11）
    // ==================================================================

    /**
     * api.md §3.5 —— 免版本物料表的<b>单列更新</b>（B-20 / D-31 / AC-65）。
     *
     * <p>body 形如 {@code {"production_no": "P-001"}}，键是 DB 列名。
     * 只有 {@code ColumnDef.partEditable} 白名单里的列能改（当前<b>只有</b>
     * {@code ds_quote_material.production_no}），其余一律 400 并点名该字段。
     *
     * <h3>🚦 权限 = 四个角色（与本类其他写端点<b>刻意不同</b>）</h3>
     * 其他写端点是 {@code PRICING_MANAGER} / {@code SYSTEM_ADMIN}，这里多开了两个销售角色。
     * <b>这是用户在知悉风险后的裁决（D-31），不是笔误，🚫 不要"顺手对齐"改回去。</b>
     * 留痕：主线建议沿用两角色，理由是这一列是「报价料号 → 核价数据」的桥，改错一个字
     * 那张单就取到另一个料号的核价数据，不报错不告警。用户仍裁决开放给四个角色。
     *
     * <p>返回 {@code ApiResponse<DsPartPatchResult>}；400（白名单 / 超长）与 404（料号不存在）
     * 走 {@code BusinessException} → {@code GlobalExceptionMapper}，本类不自己拼响应。
     */
    @PUT
    @Path("/{dataset}/parts/{axisValue}")
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<DsPartPatchResult> updatePart(
            @PathParam("dataset") String dataset,
            @PathParam("axisValue") String axisValue,
            Map<String, Object> patch) {
        String operator = sessionHelper.getCurrentUserIdOrFallback(httpRequest).toString();
        return ApiResponse.success(service.updatePart(dataset, axisValue, patch, operator));
    }

    /**
     * api.md §7 —— 保存整组（走 R-4 升版）。
     *
     * <p><b>B-11 权限（AC-31）</b>：仅 {@code PRICING_MANAGER} / {@code SYSTEM_ADMIN}，
     * 其余角色由 {@code RoleFilter} 拦成 403。
     *
     * <p><b>为什么返回 {@code Response} 而不是 {@code ApiResponse}</b>：409（AC-41）与 400（校验）
     * 需要在 {@code data} 里带结构化载荷（{@code currentVersion} / {@code errors}）。
     * 走 {@code GlobalExceptionMapper} 就得改那个现有类 —— 闸门 A0 · D-13 明令「现有代码一行不许改」，
     * 故在本端点就地构造响应。异常仍是从 {@code @Transactional} 的 service 里抛出的，事务照常回滚。
     */
    @PUT
    @Path("/{dataset}/parts/{axisValue}/sheets/{sheetKey}/rows")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response saveRows(
            @PathParam("dataset") String dataset,
            @PathParam("axisValue") String axisValue,
            @PathParam("sheetKey") String sheetKey,
            DsSaveRowsRequest req) {
        String operator = sessionHelper.getCurrentUserIdOrFallback(httpRequest).toString();
        try {
            DsSaveRowsResult result = service.saveRows(dataset, axisValue, sheetKey, req, operator);
            return Response.ok(ApiResponse.success(result)).build();
        } catch (DatasetVersionConflictException e) {
            // AC-41：{"message": "数据已被他人更新至 v{n}，请刷新后重试",
            //         "data": {"currentVersion": n, "baseVersion": m}}
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("currentVersion", e.getCurrentVersion());
            data.put("baseVersion", e.getBaseVersion());
            return Response.status(409).entity(ApiResponse.error(409, e.getMessage(), data)).build();
        } catch (DatasetValidationException e) {
            // api.md §7：与导入同构，{"data": {"errors": [{sheet,row,column,reason}, ...]}}。
            return Response.status(400)
                .entity(ApiResponse.error(400, e.getMessage(), Map.of("errors", e.getErrors())))
                .build();
        }
    }
}
