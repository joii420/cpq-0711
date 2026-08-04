package com.cpq.component.resource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.cpq.common.security.RoleAllowed;
import com.cpq.component.service.FormulaIdBinder;

/**
 * BL-0098：把 FORMULA 字段的<b>隐式</b>绑定一次性固化成显式 {@code formula_id}。
 *
 * <p>V375 迁移只做了机械part（补 id + 显式 {@code formula_name} 的纯映射翻译）。
 * 剩下「完全没绑、靠位置猜」的字段必须复用 {@link FormulaIdBinder#bindFormulaIdsToFields}
 * ——它内部走 {@code FormulaCalculator} 的真实求值口径，与在 SQL 里另写一套相比不会口径漂移。
 *
 * <p><b>值不变保证（用户裁决 D5）</b>：固化的就是这个字段<b>今天实际在用</b>的那条公式，
 * 因此固化前后算出来的钱一分不变。可疑配置（如把「材料成本」隐式绑到「银点材料成本公式」）
 * <b>原样固化</b>，另出清单交用户判断，改绑另开任务。
 */
@Path("/api/cpq/admin/formula-binding")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
// 🚨 本项目鉴权是 opt-in 的：RoleFilter:65 对没有 @RoleAllowed 的资源「skip auth check too」，
//    不写这个注解 = 端点完全不鉴权（实测无 token 返 200）。本端点能改写全部组件的公式绑定，
//    必须限死 SYSTEM_ADMIN（对齐 QuoteBackfillAdminResource 等同类一次性维护端点）。
@RoleAllowed({"SYSTEM_ADMIN"})
public class FormulaBindingAdminResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    /**
     * @param dryRun {@code true}（默认）只出清单不写库；显式传 {@code false} 才真正固化
     */
    @POST
    @Path("/consolidate")
    @Transactional
    public Response consolidate(@QueryParam("dryRun") Boolean dryRun) {
        boolean dry = dryRun == null || dryRun;
        List<Map<String, Object>> report = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
            "SELECT id, code, name, fields, formulas FROM component "
            + "WHERE jsonb_typeof(fields) = 'array' AND jsonb_typeof(formulas) = 'array' "
            + "ORDER BY code")
            .getResultList();

        int changed = 0;
        for (Object[] r : rows) {
            UUID id = (UUID) r[0];
            String code = String.valueOf(r[1]);
            String compName = String.valueOf(r[2]);
            try {
                List<Map<String, Object>> fields = MAPPER.readValue(String.valueOf(r[3]),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
                List<Map<String, Object>> formulas = MAPPER.readValue(String.valueOf(r[4]),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));

                // 固化前记录哪些 FORMULA 字段还没绑（条件公式豁免，它不走单条绑定）
                List<String> before = new ArrayList<>();
                for (Map<String, Object> f : fields) {
                    if ("FORMULA".equals(f.get("field_type"))
                        && f.get("conditional_formula") == null
                        && (f.get("formula_id") == null
                            || String.valueOf(f.get("formula_id")).isBlank())) {
                        before.add(String.valueOf(f.get("name")));
                    }
                }
                if (before.isEmpty()) continue;

                FormulaIdBinder.ensureFormulaIds(formulas);
                FormulaIdBinder.bindFormulaIdsToFields(fields, formulas);

                for (Map<String, Object> f : fields) {
                    String fname = String.valueOf(f.get("name"));
                    if (!before.contains(fname)) continue;
                    Object boundId = f.get("formula_id");
                    String boundName = null;
                    if (boundId != null) {
                        for (Map<String, Object> fm : formulas) {
                            if (String.valueOf(boundId).equals(String.valueOf(fm.get("id")))) {
                                boundName = String.valueOf(fm.get("name"));
                                break;
                            }
                        }
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("componentCode", code);
                    item.put("componentName", compName);
                    item.put("fieldName", fname);
                    item.put("resolvedFormulaId", boundId);
                    item.put("resolvedFormulaName", boundName);
                    item.put("status", boundId == null ? "UNRESOLVABLE" : "CONSOLIDATED");
                    report.add(item);
                }

                if (!dry) {
                    em.createNativeQuery(
                        "UPDATE component SET fields = cast(:f as jsonb), "
                        + "formulas = cast(:fm as jsonb) WHERE id = :id")
                        .setParameter("f", MAPPER.writeValueAsString(fields))
                        .setParameter("fm", MAPPER.writeValueAsString(formulas))
                        .setParameter("id", id)
                        .executeUpdate();
                    changed++;
                }
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("componentCode", code);
                err.put("status", "ERROR");
                err.put("message", e.getMessage());
                report.add(err);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dry);
        out.put("componentsUpdated", changed);
        out.put("itemCount", report.size());
        out.put("items", report);
        return Response.ok(out).build();
    }
}
