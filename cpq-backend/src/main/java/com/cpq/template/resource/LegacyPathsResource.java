package com.cpq.template.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.datasource.sqlview.SqlViewRuntimeContext.OwnerType;
import com.cpq.template.entity.Template;
import com.cpq.template.util.BnfPathLinter;
import com.cpq.template.util.BnfPathLinter.LintLevel;
import com.cpq.template.util.BnfPathLinter.LintResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 遗留 BNF 路径盘点端点（Phase 2 迁移自 costing 包）。
 *
 * <p>GET /api/cpq/templates/legacy-paths
 *
 * <p>扫描所有 PUBLISHED + DRAFT template 的 {@code excel_view_config} 列，逐列调 {@link BnfPathLinter}，
 * 返回 WARN 或 ERROR 级别的列清单。供运维 / 管理员主动盘点存量路径负债。
 *
 * <p>V249 起迁移自 costing 包，扫描目标从 {@code costing_template.columns} 改为
 * {@code template.excel_view_config}（这才是 LinkedExcelView 的实际渲染源）。
 *
 * <p>响应格式（JSON 数组）：
 * <pre>
 * [
 *   {
 *     "templateId": "uuid",
 *     "templateName": "核价-汇总演示模板",
 *     "status": "PUBLISHED",
 *     "colKey": "L",
 *     "variablePath": "v_costing_summary_full.material_cost",
 *     "lintLevel": "WARN",
 *     "message": "PG 视图直引建议迁移到本模板自有 SQL 视图（AP-53）",
 *     "suggestion": "建议新建 ..."
 *   }
 * ]
 * </pre>
 */
@Path("/api/cpq/templates/legacy-paths")
@Produces(MediaType.APPLICATION_JSON)
public class LegacyPathsResource {

    private static final Logger LOG = Logger.getLogger(LegacyPathsResource.class);

    @Inject
    BnfPathLinter linter;

    /** Task 3.1: 列定义统一从 EXCEL 组件解析。 */
    @Inject
    com.cpq.quotation.service.ExcelColumnResolver excelColumnResolver;

    /**
     * 扫描所有非 ARCHIVED template 的 excel_view_config，返回 WARN/ERROR 项清单。
     *
     * <p>注意：JAX-RS 路由 "legacy-paths" 字面量优先于 {@code /{templateId}} UUID 模板，
     * 不会被 TemplateSqlViewResource 误匹配。
     */
    @GET
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<Map<String, Object>>> listLegacyPaths() {
        List<Template> templates = Template.find(
                "status IN ('DRAFT', 'PUBLISHED') ORDER BY createdAt DESC").list();

        List<Map<String, Object>> findings = new ArrayList<>();

        for (Template t : templates) {
            if (t.excelViewConfig == null || t.excelViewConfig.isBlank()
                    || "[]".equals(t.excelViewConfig.trim())
                    || "{}".equals(t.excelViewConfig.trim())) {
                continue;
            }
            try {
                // Task 3.1: 列定义统一从 EXCEL 组件解析（含旧裸数组向后兼容）
                List<Map<String, Object>> columns = excelColumnResolver.getEffectiveColumns(t);
                for (Map<String, Object> col : columns) {
                    String colKey = col.get("col_key") != null
                            ? col.get("col_key").toString() : "?";
                    Object vp = col.get("variable_path");
                    if (vp == null || vp.toString().isBlank()) continue;
                    String variablePath = vp.toString().trim();

                    LintResult result = linter.lint(variablePath, OwnerType.TEMPLATE, t.status);
                    if (result.level == LintLevel.WARN || result.level == LintLevel.ERROR) {
                        Map<String, Object> finding = new LinkedHashMap<>();
                        finding.put("templateId", t.id.toString());
                        finding.put("templateName", t.name);
                        finding.put("status", t.status);
                        finding.put("colKey", colKey);
                        finding.put("variablePath", variablePath);
                        finding.put("lintLevel", result.level.name());
                        finding.put("message", result.message);
                        finding.put("suggestion", result.suggestion);
                        findings.add(finding);
                    }
                }
            } catch (Exception e) {
                LOG.warnf("[LegacyPaths] failed to parse excel_view_config for templateId=%s: %s",
                        t.id, e.getMessage());
            }
        }

        LOG.infof("[LegacyPaths] scanned %d templates, found %d legacy paths",
                templates.size(), findings.size());
        return ApiResponse.success(findings);
    }

}
