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
 * <p>GET /api/cpq/quotations/configure/search-parts?customerNo=&q=<keyword>&size=50
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

    /**
     * <b>task-260910 · B-7 / B-8</b>（api.md §2.1，AC-5 / AC-7 / AC-8 / AC-9）：已有零件搜索。
     *
     * <p>{@code GET /quotations/configure/search-parts?customerNo=&q=&size=50}
     *
     * <h3>本次三处变更</h3>
     * <ol>
     *   <li><b>数据源 {@code material_master} → {@code ds_quote_material}</b>（AC-5）。
     *       实测老表 48 行 / 新表 2727 行 ⇒ 老 SQL <b>漏掉约 98% 的料号</b>
     *       （真实料号 {@code T260907M-AC17ANCHOR} 走老 SQL 命中 0 条）。</li>
     *   <li><b>{@code customerNo} 必填 + {@code WHERE customer_no = :customerNo}</b>（D-2，AC-7）。
     *       ⚠️ 这是对现状「跨客户搜索」的<b>语义变更</b>，用户原话：「查询要携带客户编号，
     *       不要查询出其他客户的料号数据」。缺参数 → 400 {@code CUSTOMER_NO_REQUIRED}，
     *       🚫 不许兜底成「查全部客户」——那正是本次要修的行为。</li>
     *   <li><b>材质判据改为「JOIN {@code material_recipe.code} 命中」</b>（D-1，AC-8 / AC-9），
     *       单值材质字段 → {@code materials[]} 多值。</li>
     * </ol>
     *
     * <h3>🚫 判据不许用 {@code output_material_type}</h3>
     * 实测该列有 8 种值（{@code 成品} 2645 / {@code ASSEMBLY} 55 / {@code RECIPE} 41 / NULL 3 /
     * {@code 产出类型1} 2 / {@code 产出类型2} 2 / {@code OUTSOURCED} 1 / {@code 零件} 1）——
     * 它是<b>用户自填的业务字段</b>，决定不了「是不是材质」。用户原话：「不要根据
     * {@code b.output_material_type='RECIPE'} 判断是否是材质，这个字段没有特殊含义……
     * 要去材质表关联判断是否是材质」。
     * <p>可证伪的两个方向都实查过：① {@code PERF0909-B00001} 的 BOM 行声明
     * {@code output_material_type='成品'}、{@code input_material_no='T260907T-RM01'}，
     * 而该值在 {@code material_recipe} 里<b>不存在</b>（0 行）⇒ 不能当材质带出（AC-9①）；
     * ② 反向有 3 行 {@code output_material_type} 为 NULL / {@code 零件} 但
     * {@code input_material_no} 能 JOIN 上材质表（{@code S0002→992} ×2、{@code T260907-M1→00006}）
     * ⇒ 必须带出（AC-9②）。
     *
     * <h3>「料号 LEFT / 材质 INNER」的落地形状</h3>
     * api.md 那段 SQL 写的是 {@code LEFT JOIN 料件BOM} + {@code JOIN material_recipe} ——
     * 直接连写会被后面的 INNER 反向吞掉左连接的行。这里落成<b>两条 SQL</b>：
     * 第 1 条只查料号（材质表只出现在 {@code EXISTS} 关键词子句里，不影响命中集），
     * 第 2 条按第 1 条的结果集一次 {@code IN} 批量取材质。
     * ⇒ 外购件与组合父料号（没有材质行）<b>不会被吞掉</b>，而材质那一跳仍是 INNER（判据本身）。
     *
     * <h3>N+1</h3>
     * 恒 <b>2 条 SQL</b>（结果为空时 1 条），与命中料号数 / 材质数无关。
     * 🚫 不许改成「先查料号再逐个查材质」。
     */
    @GET
    @Path("/search-parts")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchParts(
            @QueryParam("customerNo") String customerNo,
            @QueryParam("q") String q,
            @QueryParam("size") @DefaultValue("50") int size) {

        // D-2：客户编号必填。🚫 缺失时不许静默跨客户查（api.md §3 的 CUSTOMER_NO_REQUIRED）。
        if (customerNo == null || customerNo.isBlank()) {
            throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                "CUSTOMER_NO_REQUIRED", "已有零件搜索必须携带客户编号(customerNo)");
        }
        if (q == null || q.isBlank()) return Collections.emptyList();
        int safeSize = Math.min(Math.max(size, 1), 200);
        String pattern = "%" + q.trim() + "%";

        // ① 料号本体（1 条 SQL）。
        //    - status_code：新表无停产维度，固定 'Y'（沿用 V6 时代口径）
        //    - dimension → sizeInfo
        //    - 关键词面：料号 / 品名 / 规格 / 尺寸 / 料号类型 + 该料号材质的 symbol / code
        //      （材质那一段放 EXISTS，只影响「能不能被搜到」，不影响返回集的行数）
        //    - 🚫 不做「子件排除」过滤（2026-05-31 用户决策：彻底移除；组合产品流程本意就是挑基础配件）
        List<Object[]> rows = em.createNativeQuery(
                "SELECT m.material_no, m.material_name, m.specification, m.dimension " +
                "FROM ds_quote_material m " +
                "WHERE m.customer_no = :cn " +
                "  AND ( m.material_no ILIKE :p OR " +
                "        COALESCE(m.material_name,'') ILIKE :p OR " +
                "        COALESCE(m.specification,'') ILIKE :p OR " +
                "        COALESCE(m.dimension,'') ILIKE :p OR " +
                "        COALESCE(m.material_type,'') ILIKE :p OR " +
                "        EXISTS (SELECT 1 FROM ds_quote_material_bom b " +
                "                  JOIN material_recipe mr ON mr.code = b.input_material_no " +
                "                 WHERE b.customer_no = m.customer_no " +
                "                   AND b.material_no = m.material_no " +
                "                   AND ( COALESCE(mr.symbol,'') ILIKE :p OR " +
                "                         COALESCE(mr.code,'') ILIKE :p )) ) " +
                "ORDER BY m.material_no " +
                "LIMIT :s")
            .setParameter("cn", customerNo)
            .setParameter("p", pattern)
            .setParameter("s", safeSize)
            .getResultList();

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        Map<String, List<Map<String, Object>>> materialsByPartNo = new HashMap<>();
        List<String> partNos = new ArrayList<>(rows.size());
        for (Object[] r : rows) {                                  // 循环体内零查库（纯 DTO 组装）
            Map<String, Object> m = new HashMap<>();
            m.put("hfPartNo", r[0]);
            m.put("partName", r[1]);
            m.put("specification", r[2]);
            m.put("sizeInfo", r[3]);
            m.put("statusCode", "Y");
            List<Map<String, Object>> mats = new ArrayList<>();
            m.put("materials", mats);
            out.add(m);
            if (r[0] != null) {
                partNos.add(r[0].toString());
                materialsByPartNo.put(r[0].toString(), mats);
            }
        }
        if (partNos.isEmpty()) return out;

        // ② 材质（1 条 SQL，IN 批量）。JOIN 命中 = 是材质（D-1）；🚫 不带 output_material_type 条件。
        List<Object[]> matRows = em.createNativeQuery(
                "SELECT b.material_no, mr.code, mr.symbol, mr.name, mr.spec_label, mr.recipe_type, " +
                "       b.material_ratio " +
                "FROM ds_quote_material_bom b " +
                "JOIN material_recipe mr ON mr.code = b.input_material_no " +
                "WHERE b.customer_no = :cn AND b.material_no IN (:nos) " +
                "ORDER BY b.material_no, b.item_seq")
            .setParameter("cn", customerNo)
            .setParameter("nos", partNos)
            .getResultList();
        for (Object[] r : matRows) {                               // 循环体内零查库（纯内存分发）
            List<Map<String, Object>> bucket = materialsByPartNo.get(String.valueOf(r[0]));
            if (bucket == null) continue;
            Map<String, Object> mat = new HashMap<>();
            mat.put("recipeCode", r[1]);
            mat.put("recipeSymbol", r[2]);
            mat.put("recipeName", r[3]);
            mat.put("recipeSpec", r[4]);
            mat.put("recipeType", r[5]);
            // 📌 api.md §2.1 的 materials[] 只列了上面 5 个字段；ratio 是**加法式补充**
            //    （前端 types/configure.ts 的 SearchPartMaterial 已声明 ratio）。已在回报中登记。
            mat.put("ratio", r[6] == null ? null : r[6].toString());
            bucket.add(mat);
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
     * task-260902 · B-7 → <b>task-260910 · B-9 改写</b>（api.md §2.2，AC-6）：外购件候选。
     *
     * <p>{@code GET /api/cpq/quotations/configure/outsourced-parts?customerNo=&keyword=&page=1&size=20}
     * <p>判据：{@code WHERE ds_quote_material.customer_no = :customerNo AND material_type = '外购件'}
     * （task-260910 · B-9，D-2/D-3；{@code customerNo} 必填，缺失 400）。
     *
     * <p>⚠️ <b>返回 0 条是正常业务状态</b>（AC-16）：候选完全取决于基础数据里有多少料号被标成
     * 「外购件」，共享开发库上这个数字随导入随时变（2026-09-09 一天之内实测到过 1 / 8 / 6 条）。
     * ⇒ 🚫 <b>不要把某个具体条数写进判据或断言</b>；前端必须渲染空态而非「加载中…」（AP-31 族）。
     */
    @GET
    @Path("/outsourced-parts")
    public Map<String, Object> outsourcedParts(@QueryParam("customerNo") String customerNo,
                                               @QueryParam("keyword") String keyword,
                                               @QueryParam("page") @DefaultValue("1") int page,
                                               @QueryParam("size") @DefaultValue("20") int size) {
        return configureProductService.listOutsourcedParts(customerNo, keyword, page, size);
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
