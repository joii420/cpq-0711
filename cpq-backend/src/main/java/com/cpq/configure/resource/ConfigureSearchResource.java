package com.cpq.configure.resource;

import com.cpq.common.security.RoleAllowed;
import com.cpq.configure.dto.ExistingPartMaterialDTO;
import com.cpq.configure.service.MaterialRecipeService;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 选配抽屉 P1 步骤的料号统一搜索 + P2 锁定路径材质取数端点.
 *
 * <p>GET /api/cpq/quotations/configure/search-parts?q=<keyword>&size=50
 * <p>GET /api/cpq/quotations/configure/existing-part/{hfPartNo}/material
 */
@Path("/api/cpq/quotations/configure")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ConfigureSearchResource {

    @Inject
    EntityManager em;

    @Inject
    MaterialRecipeService materialRecipeService;

    @Inject
    com.cpq.configure.service.ConfigureProductService configureProductService;

    @GET
    @Path("/search-parts")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchParts(
            @QueryParam("q") String q,
            @QueryParam("size") @DefaultValue("50") int size) {

        if (q == null || q.isBlank()) return Collections.emptyList();
        int safeSize = Math.min(Math.max(size, 1), 200);
        String pattern = "%" + q.trim() + "%";

        // 选配 Step1 搜索"已有配件" — V6 数据源（AP-53 老表禁用 + 续 5 字典绑定迁 V6）：
        //   - 表：mat_part → material_master
        //   - 材质（recipe）：material_master.material_recipe_id LEFT JOIN material_recipe（V265 绑定迁 V6）
        //     · 绑定时 recipeCode/Symbol/Name/Type 取字典值（Step2 字典派一致；Step1 展示如 "AgCu 银铜合金"）
        //     · 未绑定回退 material_type（粗分类，如 "1.银点类"）
        //   - 不再做"子件排除"过滤（2026-05-31 用户决策：彻底移除）：
        //     · 背景：2026-05-27 曾加 NOT EXISTS 过滤，把「真实/导入 BOM 的 ASSEMBLY 子件」当中间装配料号排除，
        //       只保留「可作为顶层报价的料号」。但组合产品(COMPOSITE)流程的本意就是挑选这类基础配件
        //       （如 Ag 铆钉 10110002 是装配 3120012004/5/6 的子件，父件 config_fingerprint IS NULL，被旧过滤剔除 → 搜不到）。
        //     · 该过滤对 SIMPLE/COMPOSITE 同一接口无差别生效，导致组合配件搜索 0 条。用户选择彻底移除，
        //       接受副作用：中间装配子件在独立产品(SIMPLE)搜索里也会出现。详见 RECORD.md [2026-05-31]。
        //   - status_code：V6 无停产维度，固定 'Y'
        //   - size_info → dimension：V6 字段重命名
        //   - 跨客户搜索（与 V44 行为一致，不限定当前报价单客户）
        List<Object[]> rows = em.createNativeQuery(
                "SELECT mm.material_no, mm.material_name, mm.specification, mm.dimension, " +
                "       'Y' AS status_code, " +
                "       mm.material_recipe_id AS recipe_id, " +
                "       mr.code AS recipe_code, " +
                "       COALESCE(mr.symbol, mm.material_type) AS recipe_symbol, " +
                "       COALESCE(mr.name, mm.material_type) AS recipe_name, " +
                "       COALESCE(mr.spec_label, mm.specification) AS recipe_spec, " +
                "       COALESCE(mr.recipe_type, mm.material_type) AS recipe_type " +
                "FROM material_master mm " +
                "LEFT JOIN material_recipe mr ON mr.id = mm.material_recipe_id " +
                "WHERE ( mm.material_no ILIKE :p OR " +
                "        COALESCE(mm.material_name,'') ILIKE :p OR " +
                "        COALESCE(mm.specification,'') ILIKE :p OR " +
                "        COALESCE(mm.dimension,'') ILIKE :p OR " +
                "        COALESCE(mm.material_type,'') ILIKE :p OR " +
                "        COALESCE(mr.symbol,'') ILIKE :p OR " +
                "        COALESCE(mr.code,'') ILIKE :p ) " +
                "ORDER BY mm.material_no " +
                "LIMIT :s")
            .setParameter("p", pattern)
            .setParameter("s", safeSize)
            .getResultList();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("hfPartNo", r[0]);
            m.put("partName", r[1]);
            m.put("specification", r[2]);
            m.put("sizeInfo", r[3]);
            m.put("statusCode", r[4]);
            m.put("recipeId", r[5]);
            m.put("recipeCode", r[6]);
            m.put("recipeSymbol", r[7]);
            m.put("recipeName", r[8]);
            m.put("recipeSpec", r[9]);
            m.put("recipeType", r[10]);
            out.add(m);
        }
        return out;
    }

    /**
     * task-260902 · B-2（api.md §2.1，AC-1 / AC-2）：客户产品编号占用校验。
     *
     * <p>{@code GET /api/cpq/quotations/configure/check-product-no?customerNo=&productNo=}
     * <p>未占用 → {@code {"taken": false}}；已占用 → {@code {"taken":true,"hfPartNo":…,"createdAt":…}}。
     *
     * <p>占用口径 = {@code sel_product_no}（选配来的）∪ {@code material_customer_map}（导入来的）。
     * 前端在步骤 1 输入框 debounce 400ms 后调用，<b>不阻塞输入</b>，只驱动提示与「下一步」禁用态。
     */
    @GET
    @Path("/check-product-no")
    public Map<String, Object> checkProductNo(@QueryParam("customerNo") String customerNo,
                                              @QueryParam("productNo") String productNo) {
        return configureProductService.checkProductNo(customerNo, productNo);
    }

    /**
     * task-260902 · B-7（api.md §2.2，AC-5 / AC-16）：外购件候选。
     *
     * <p>{@code GET /api/cpq/quotations/configure/outsourced-parts?keyword=&page=1&size=20}
     * <p>判据（闸门 A0 已裁决）：{@code WHERE material_master.material_type = '外购件'}。
     *
     * <p>⚠️ <b>返回 0 条是正常业务状态</b>（AC-16）：实测当前库仅 1 条
     * （{@code TEST-Q13-CODE / 组成件1}，规格与单重均空）。前端必须渲染空态而非「加载中…」（AP-31 族）。
     */
    @GET
    @Path("/outsourced-parts")
    public Map<String, Object> outsourcedParts(@QueryParam("keyword") String keyword,
                                               @QueryParam("page") @DefaultValue("1") int page,
                                               @QueryParam("size") @DefaultValue("20") int size) {
        return configureProductService.listOutsourcedParts(keyword, page, size);
    }

    /**
     * 选配 Step2 锁定路径取材质数据 — 用户在 Step1 选了已存在料号后,
     * Step2 渲染元素配比表用此端点.
     *
     * <p>实现详见 {@link MaterialRecipeService#getForExistingPart(String)} 与
     * docs/选配与基础数据料号材质关系.md 第五节决策树.
     *
     * @return 字典派 (recipeBound=true) 或 BOM 派 (recipeBound=false) 的统一 DTO
     */
    @GET
    @Path("/existing-part/{hfPartNo}/material")
    public ExistingPartMaterialDTO existingPartMaterial(
            @PathParam("hfPartNo") String hfPartNo) {
        return materialRecipeService.getForExistingPart(hfPartNo);
    }
}
