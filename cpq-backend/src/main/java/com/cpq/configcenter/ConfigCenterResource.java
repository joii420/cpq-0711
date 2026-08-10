package com.cpq.configcenter;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.component.entity.Component;
import com.cpq.datasource.resolver.DataSourceResolverRegistry;
import com.cpq.datasource.resolver.HttpApiResolver;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.service.TemplateFreezeDriftService;
import com.cpq.template.service.TemplateService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * K3/K4: 配置中心管理工具端点.
 *
 * <ul>
 *   <li>GET  /api/cpq/config-center/health             — 健康/统计 (运维监控用)</li>
 *   <li>POST /api/cpq/config-center/refresh-all-snapshots — 强制重新对齐已发布模板快照 (SYSTEM_ADMIN)</li>
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

    /** task-0806 B11：预览路径算 fieldDriftCount 用。 */
    @Inject
    TemplateFreezeDriftService templateFreezeDriftService;

    @Inject
    SessionHelper sessionHelper;

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
     * K4 → task-0806 B7 改造（api.md §6 A5）：强制重新对齐已发布模板快照到当前活组件配置
     * ——即 D3 一次性迁移（V382）的可重复版本。<b>明确破坏不可变性</b>，仅供运维紧急通道使用。
     *
     * <p>原实现循环调用已退役的 {@code refreshSnapshotsByComponent}
     * （{@code O(N_template × N_component)}，本身违反 CLAUDE.md N+1 铁律）；新实现委派
     * {@link TemplateService#forceRealignSnapshots}，3 条参数化 SQL，与模板数/组件数无关。
     *
     * <p>Body: {@code { "templateIds": ["uuid1","uuid2"], "confirm": false } }——
     * {@code templateIds} 不传/空 = 全部 PUBLISHED+ARCHIVED；{@code confirm} 缺省 {@code false}
     * = 仅预览零写入，{@code true} 才真正执行并写 {@code operation_log} 审计（按受影响模板各写一行）。
     */
    @POST
    @Path("/refresh-all-snapshots")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<Map<String, Object>> refreshAllSnapshots(
            Map<String, Object> body, @Context HttpServerRequest httpRequest) {
        List<UUID> requestedIds = null;
        if (body != null && body.get("templateIds") instanceof List<?> tidList) {
            requestedIds = tidList.stream()
                    .filter(Objects::nonNull)
                    .map(o -> UUID.fromString(o.toString()))
                    .collect(Collectors.toList());
        }
        boolean confirm = body != null && Boolean.TRUE.equals(body.get("confirm"));

        List<UUID> resolvedIds = templateService.resolvePublishedOrArchivedTemplateIds(requestedIds);

        if (!confirm) {
            return ApiResponse.success(buildPreview(resolvedIds));
        }

        // 快照重写 + operation_log 审计必须同生共死——委派 TemplateService 的单一
        // @Transactional 方法（跨 bean 调用，拦截器正常生效），而不是在本类内部自调用
        // 一个 protected @Transactional 方法（那样会因 CDI self-invocation 静默跳过拦截器，
        // 两次写各自落在独立事务，审计失败时快照重写已提交，破坏原子性）。
        UUID operatorId = sessionHelper.getCurrentUserIdOrFallback(httpRequest);
        Map<String, Object> realign = templateService.forceRealignSnapshotsWithAudit(resolvedIds, operatorId);

        @SuppressWarnings("unchecked")
        List<String> logIds = (List<String>) realign.get("operationLogIds");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preview", false);
        out.put("refreshedTemplates", realign.get("refreshedTemplates"));
        out.put("refreshedRows", realign.get("refreshedRows"));
        out.put("operationLogId", (logIds == null || logIds.isEmpty()) ? null : logIds.get(logIds.size() - 1));
        out.put("operationLogIds", logIds);
        LOG.warnf("[admin-backdoor] refresh-all-snapshots 已执行：已破坏 %d 个模板的不可变性", resolvedIds.size());
        return ApiResponse.success(out);
    }

    /** confirm=false：仅预览，零写入。 */
    private Map<String, Object> buildPreview(List<UUID> resolvedIds) {
        List<Map<String, Object>> affectedTemplates = new ArrayList<>();
        for (UUID tid : resolvedIds) {
            Template t = Template.findById(tid);
            if (t == null) continue;
            long tabCount = TemplateComponent.count("templateId", tid);
            int fieldDriftCount = 0;
            try {
                Map<String, Object> drift = templateFreezeDriftService.driftOf(tid);
                Object cnt = drift.get("driftCount");
                fieldDriftCount = (cnt instanceof Number n) ? n.intValue() : 0;
            } catch (Exception e) {
                LOG.warnf("[admin-backdoor] refresh-all-snapshots 预览：drift 计算失败 templateId=%s: %s",
                        tid, e.getMessage());
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("templateId", t.id.toString());
            m.put("name", t.name);
            m.put("version", t.version);
            m.put("status", t.status);
            m.put("tabCount", tabCount);
            m.put("fieldDriftCount", fieldDriftCount);
            affectedTemplates.add(m);
        }
        long affectedQuotationCount = templateService.countQuotationsReferencingTemplates(resolvedIds);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preview", true);
        out.put("affectedTemplates", affectedTemplates);
        out.put("affectedTemplateCount", resolvedIds.size());
        out.put("affectedQuotationCount", affectedQuotationCount);
        out.put("warning", "此操作将改写已发布模板的冻结快照，破坏版本不可变性。受影响的在途报价单渲染结果可能变化。");
        LOG.warnf("[admin-backdoor] refresh-all-snapshots 预览（未执行）：affectedTemplateCount=%d "
                + "affectedQuotationCount=%d", resolvedIds.size(), affectedQuotationCount);
        return out;
    }

}
