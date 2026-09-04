package com.cpq.product.dataset;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * 「客户产品」只读列表端点（{@code task-260903} · B-1，服务 AC-2 / AC-3 / AC-14）。
 *
 * <pre>
 * GET /api/cpq/dataset/{dataset}/customer-parts?page=0&amp;size=20&amp;keyword=CUST-0004
 * 200 { "code":200, "message":"success",
 *       "data": { "total":17, "columns":[{name,label,type}...], "items":[...] } }
 * </pre>
 *
 * <p><b>本端点是 {@code task-260903} 按用户裁决（2026-09-03）自建的</b>，补的是
 * 「免版本表 {@code ds_quote_customer_part} 在新体系里没有查看入口」的缺口 —— 与
 * {@code task-260902} 为电镀方案补的 {@code GET /{dataset}/plating-schemes} 是同一类问题。
 * 🔧 <b>{@code task-260902} 的 {@code com.cpq.dataset} 包合并后，本类（连同
 * {@link DatasetCustomerPartService} / {@link DsCustomerParts}）应迁入该包统一维护。</b>
 * 路径与响应形状已按主源风格对齐，迁移时前端调用方一行都不用改。
 *
 * <p>🚫 <b>只读，无配套写端点</b>（{@code api.md §3}）：本页无编辑态，调写端点就产生第二条升版路径。
 * <p>🚫 与 {@code customer_material_mapping}（1 行死代码表）/ {@code material_customer_map}
 * （旧体系真表）<b>都无关</b>，本端点只读新体系的 {@code ds_quote_customer_part}。
 *
 * <p>权限：AC-16 —— 四个角色全部只读开放，与既有产品管理菜单一致，本任务不新增角色控制。
 */
@Path("/api/cpq/dataset")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class DatasetCustomerPartResource {

    @Inject
    DatasetCustomerPartService service;

    /**
     * @param dataset 数据集，当前仅 {@code quote}（其余返 400）
     * @param page    🚨 <b>0-based</b>，与主源 {@code GET parts} 一致。antd Table 的 {@code current}
     *                是 1-based，调用方必须传 {@code current - 1}
     * @param size    每页行数（上限 500）
     * @param keyword 模糊匹配 {@code customerNo} / {@code customerProductNo} / {@code materialNo}
     * @param sortBy  可选，见 {@code DatasetCustomerPartService.SORTABLE} 白名单；省略按写入顺序
     * @param sortDir {@code asc}（默认）/ {@code desc}
     */
    @GET
    @Path("/{dataset}/customer-parts")
    public ApiResponse<DsCustomerParts> listCustomerParts(
            @PathParam("dataset") String dataset,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("keyword") String keyword,
            @QueryParam("sortBy") String sortBy,
            @QueryParam("sortDir") String sortDir) {
        return ApiResponse.success(service.list(dataset, page, size, keyword, sortBy, sortDir));
    }
}
