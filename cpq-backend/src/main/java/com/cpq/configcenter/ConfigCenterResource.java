package com.cpq.configcenter;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.component.entity.Component;
import com.cpq.datasource.resolver.DataSourceResolverRegistry;
import com.cpq.datasource.resolver.HttpApiResolver;
import com.cpq.template.entity.Template;
import com.cpq.template.service.TemplateService;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * K3/K4: 配置中心管理工具端点.
 *
 * <ul>
 *   <li>GET  /api/cpq/config-center/health             — 健康/统计 (运维监控用)</li>
 *   <li>POST /api/cpq/config-center/refresh-all-snapshots — 全量刷新 template snapshot (SYSTEM_ADMIN)</li>
 * </ul>
 */
@Path("/api/cpq/config-center")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ConfigCenterResource {

    private static final Logger LOG = Logger.getLogger(ConfigCenterResource.class);

    @Inject
    EntityManager em;

    @Inject
    DataSourceResolverRegistry resolverRegistry;

    @Inject
    HttpApiResolver httpApiResolver;

    @Inject
    TemplateService templateService;

    /** K3: 配置中心健康/统计快照 */
    @GET
    @Path("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            long gvDefCount = ((Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM global_variable_definition").getSingleResult()).longValue();
            long gvKvCount = ((Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM global_variable_definition WHERE value_source_type = 'KV_TABLE'")
                    .getSingleResult()).longValue();
            long gvCostingCount = ((Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM global_variable_definition WHERE value_source_type = 'COSTING_VIEW'")
                    .getSingleResult()).longValue();
            long gvValueCount = ((Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM global_variable_value").getSingleResult()).longValue();
            long compCount = Component.count();
            long tplCount = Template.count();
            long tplPublishedCount = Template.count("status", "PUBLISHED");

            Map<String, Object> gvar = new LinkedHashMap<>();
            gvar.put("definitions_total", gvDefCount);
            gvar.put("definitions_kv_table", gvKvCount);
            gvar.put("definitions_costing_view", gvCostingCount);
            gvar.put("values_total", gvValueCount);
            out.put("global_variables", gvar);

            Map<String, Object> structure = new LinkedHashMap<>();
            structure.put("components_total", compCount);
            structure.put("templates_total", tplCount);
            structure.put("templates_published", tplPublishedCount);
            out.put("structure", structure);

            Map<String, Object> resolvers = new LinkedHashMap<>();
            resolvers.put("registered_types", resolverRegistry.registeredTypes());
            resolvers.put("http_api_enabled", !httpApiResolver.debugAllowedHosts().isEmpty());
            resolvers.put("http_api_allowed_hosts_count", httpApiResolver.debugAllowedHosts().size());
            out.put("data_source_resolvers", resolvers);

            out.put("status", "OK");
        } catch (Exception e) {
            LOG.warnf("config-center health check failed: %s", e.getMessage());
            out.put("status", "DEGRADED");
            out.put("error", e.getMessage());
        }
        return ApiResponse.success(out);
    }

    /**
     * K4: 全量刷新 template snapshot.
     *
     * <p>遍历所有 template.components_snapshot 中引用到的 componentId 集合, 逐个调用
     * {@link TemplateService#refreshSnapshotsByComponent}. 适合 schema 大变更后批量修复.
     *
     * <p>性能: O(N_template * N_component_in_snapshot). 工程量量级 ≤ 几百次 jsonb 操作,
     * 可接受. 失败单个不影响其他.
     *
     * @return 受影响的 componentId → 模板数 映射 + 总计
     */
    @POST
    @Path("/refresh-all-snapshots")
    @RoleAllowed({"SYSTEM_ADMIN"})
    @Transactional
    public ApiResponse<Map<String, Object>> refreshAllSnapshots() {
        @SuppressWarnings("unchecked")
        java.util.List<Object[]> componentIdsRaw = em.createNativeQuery(
                "SELECT DISTINCT (comp ->> 'componentId')::uuid AS cid " +
                "FROM template, jsonb_array_elements(components_snapshot) AS comp " +
                "WHERE components_snapshot IS NOT NULL AND comp ? 'componentId'")
                .getResultList();

        Map<String, Integer> perComponent = new LinkedHashMap<>();
        int totalTouched = 0;
        int errors = 0;
        for (Object row : componentIdsRaw) {
            UUID cid = row instanceof UUID u ? u
                    : (row instanceof Object[] arr ? UUID.fromString(arr[0].toString())
                       : UUID.fromString(row.toString()));
            try {
                java.util.List<UUID> affected = templateService.refreshSnapshotsByComponent(cid);
                perComponent.put(cid.toString(), affected.size());
                totalTouched += affected.size();
            } catch (Exception e) {
                LOG.warnf("refresh failed for componentId=%s: %s", cid, e.getMessage());
                errors++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("components_processed", componentIdsRaw.size());
        out.put("templates_touched_total", totalTouched);
        out.put("errors", errors);
        out.put("per_component", perComponent);
        LOG.infof("[K4 refresh-all] components=%d totalTouched=%d errors=%d",
                componentIdsRaw.size(), totalTouched, errors);
        return ApiResponse.success(out);
    }
}
