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

    @GET
    @Path("/search-parts")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchParts(
            @QueryParam("q") String q,
            @QueryParam("size") @DefaultValue("50") int size) {

        if (q == null || q.isBlank()) return Collections.emptyList();
        int safeSize = Math.min(Math.max(size, 1), 200);
        String pattern = "%" + q.trim() + "%";

        // 选配 Step1 搜索"已有配件" — 只允许 SIMPLE 类型料号作为子配件,
        // 防止 COMPOSITE 父级料号(如 CFG-COMBO-xxx)被误选,导致嵌套组合(语义混乱 + 核价递归).
        List<Object[]> rows = em.createNativeQuery(
                "SELECT mp.part_no, mp.part_name, mp.specification, mp.size_info, mp.status_code, " +
                "       mr.id, mr.code, mr.symbol, mr.name, mr.spec_label, mr.recipe_type " +
                "FROM mat_part mp " +
                "LEFT JOIN material_recipe mr ON mr.id = mp.material_recipe_id " +
                "WHERE COALESCE(mp.status_code, 'Y') = 'Y' " +
                "  AND COALESCE(mp.product_type, 'SIMPLE') = 'SIMPLE' " +
                "  AND ( mp.part_no ILIKE :p OR mp.part_name ILIKE :p OR " +
                "        COALESCE(mp.specification,'') ILIKE :p OR " +
                "        COALESCE(mp.size_info,'') ILIKE :p OR " +
                "        COALESCE(mr.symbol,'') ILIKE :p OR " +
                "        COALESCE(mr.name,'') ILIKE :p ) " +
                "ORDER BY mp.part_no " +
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
