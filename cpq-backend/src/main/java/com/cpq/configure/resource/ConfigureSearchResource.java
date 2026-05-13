package com.cpq.configure.resource;

import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 选配抽屉 P1 步骤的料号统一搜索端点.
 *
 * <p>GET /api/cpq/quotations/configure/search-parts?q=<keyword>&size=50
 */
@Path("/api/cpq/quotations/configure")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ConfigureSearchResource {

    @Inject
    EntityManager em;

    @GET
    @Path("/search-parts")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchParts(
            @QueryParam("q") String q,
            @QueryParam("size") @DefaultValue("50") int size) {

        if (q == null || q.isBlank()) return Collections.emptyList();
        int safeSize = Math.min(Math.max(size, 1), 200);
        String pattern = "%" + q.trim() + "%";

        List<Object[]> rows = em.createNativeQuery(
                "SELECT mp.part_no, mp.part_name, mp.specification, mp.size_info, mp.status_code, " +
                "       mr.id, mr.code, mr.symbol, mr.name, mr.spec_label, mr.recipe_type " +
                "FROM mat_part mp " +
                "LEFT JOIN material_recipe mr ON mr.id = mp.material_recipe_id " +
                "WHERE COALESCE(mp.status_code, 'Y') = 'Y' " +
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
}
