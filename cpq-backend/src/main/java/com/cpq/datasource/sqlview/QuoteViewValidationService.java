package com.cpq.datasource.sqlview;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.template.entity.TemplateSqlView;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.postgresql.jdbc.PgResultSetMetaData;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * task-0721 报价数据版本升级 · B3.3 —— 启动期硬校验（fail-fast）。
 *
 * <p>枚举全部 {@code component_sql_view} + {@code template_sql_view}（status=ACTIVE），对每个跑
 * 「{@link QuotePendingRewriter#rewrite} → 占位符替换 → {@code LIMIT 0} 执行 → pgjdbc 基表元数据校验
 * {@code __v6_id} 锚点」。主位非白名单表（{@code anchorInjected=false}）视为"该视图不参与回填"，
 * 属正常情形，不计入失败；只有"改写声称会产生锚点但执行/元数据校验失败"才算真失败。
 *
 * <p>任一真失败 → 应用启动失败（{@code @Observes StartupEvent} 内抛异常会阻断 Quarkus 启动，
 * 与 {@code BnfTableMetaSyncer} 的"失败不阻断"策略刻意不同——AC-15 明确要求 fail-fast，不带病上线）。
 *
 * <p>结果快照存内存供 {@code GET /api/cpq/admin/quote-backfill/view-validation} 诊断查询
 * （api.md §4）；即便启动校验通过，运维仍可查最近一次快照。
 */
@ApplicationScoped
public class QuoteViewValidationService {

    private static final Logger LOG = Logger.getLogger(QuoteViewValidationService.class);

    @Inject DataSource dataSource;

    /** 单个视图的校验失败明细。 */
    public static final class Failure {
        public final String component;   // 组件名 / "TEMPLATE:<templateId>"
        public final String view;        // sql_view_name
        public final String reason;
        public Failure(String component, String view, String reason) {
            this.component = component; this.view = view; this.reason = reason;
        }
    }

    /** 一次校验的完整快照（供 admin 端点直接序列化）。 */
    public static final class Snapshot {
        public final Instant checkedAt;
        public final int total, ok, failed;
        public final List<Failure> failures;
        public Snapshot(Instant checkedAt, int total, int ok, int failed, List<Failure> failures) {
            this.checkedAt = checkedAt; this.total = total; this.ok = ok; this.failed = failed;
            this.failures = failures;
        }
    }

    private volatile Snapshot lastSnapshot;

    public Snapshot getLastSnapshot() {
        return lastSnapshot;
    }

    /** Quarkus 启动钩子：失败即抛异常阻断启动（fail-fast，AC-15）。 */
    void onStartup(@Observes StartupEvent ev) {
        Snapshot s = runValidation();
        lastSnapshot = s;
        if (s.failed > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("[QuoteViewValidationService] 报价侧 SQL 视图 pending 改写校验失败 ")
              .append(s.failed).append("/").append(s.total).append(" 个：\n");
            for (Failure f : s.failures) {
                sb.append("  - component=").append(f.component).append(" view=").append(f.view)
                  .append(" reason=").append(f.reason).append("\n");
            }
            LOG.error(sb.toString());
            throw new IllegalStateException(sb.toString());
        }
        LOG.infof("[QuoteViewValidationService] 校验通过 total=%d ok=%d（未命中白名单表的视图不计入统计范围外，已跳过）",
            s.total, s.ok);
    }

    /**
     * 跑一次完整校验（可被 admin 端点或测试重复调用；只读，不写库）。
     */
    public Snapshot runValidation() {
        List<Failure> failures = new ArrayList<>();
        int total = 0, ok = 0;
        try (Connection conn = dataSource.getConnection()) {
            // 组件 code -> name（诊断展示用，一次性批量取，避免逐视图 N+1）。
            Map<UUID, String> componentNames = new java.util.HashMap<>();
            for (Component c : Component.<Component>listAll()) componentNames.put(c.id, c.name);

            List<ComponentSqlView> compViews = ComponentSqlView.list("status = 'ACTIVE'");
            for (ComponentSqlView v : compViews) {
                String label = componentNames.getOrDefault(v.componentId, String.valueOf(v.componentId));
                CheckOutcome outcome = checkOne(v.sqlTemplate, conn);
                if (!outcome.applicable) continue; // 主位非白名单表：该视图无需回填，不计入统计分母
                total++;
                if (outcome.ok) ok++;
                else failures.add(new Failure(label, v.sqlViewName, outcome.reason));
            }

            List<TemplateSqlView> tplViews = TemplateSqlView.list("status = 'ACTIVE'");
            for (TemplateSqlView v : tplViews) {
                String label = "TEMPLATE:" + v.templateId;
                CheckOutcome outcome = checkOne(v.sqlTemplate, conn);
                if (!outcome.applicable) continue;
                total++;
                if (outcome.ok) ok++;
                else failures.add(new Failure(label, v.sqlViewName, outcome.reason));
            }
        } catch (Exception e) {
            // 校验器自身取连接/查表失败：视为一条基础设施失败，同样 fail-fast（宁可误报也不带病上线）。
            failures.add(new Failure("_infra_", "_startup_", "校验器自身异常: " + e.getMessage()));
        }
        return new Snapshot(Instant.now(), total, ok, failures.size(), failures);
    }

    private static final class CheckOutcome {
        final boolean applicable, ok;
        final String reason;
        CheckOutcome(boolean applicable, boolean ok, String reason) {
            this.applicable = applicable; this.ok = ok; this.reason = reason;
        }
    }

    /** 对单个 sql_template 跑改写 + LIMIT 0 + 锚点元数据校验。 */
    private CheckOutcome checkOne(String sqlTemplate, Connection conn) {
        // 与运行时链路对齐（SqlViewExecutor.applyPendingRewrite 挂在 applyVersionFilter 之后，
        // backtask B3.2）：先展开 :versionFilter(...) 宏，否则宏字面量原样留在模板里，
        // 被后续"未识别 :xxx 占位符→NULL"降级成 "NULL(is_current, ...)" 之类非法 SQL 而误判失败。
        // 启动期无 CostingTreeVarsContext（无 per-request 列出模式），统一按渲染模式展开。
        String withVersionFilter = VersionFilterMacro.containsMacro(sqlTemplate)
            ? VersionFilterMacro.expandForExecution(sqlTemplate) : sqlTemplate;
        QuotePendingRewriter.Result r;
        try {
            r = QuotePendingRewriter.rewrite(withVersionFilter, conn);
        } catch (Exception e) {
            // 改写本身抛错（如列元数据取不到）——若模板压根不含任何白名单表 token，这不该发生；
            // 若抛错说明白名单表存在但列解析失败，判定为真失败。
            return new CheckOutcome(true, false, "改写异常: " + e.getMessage());
        }
        if (!r.anchorInjected) {
            return new CheckOutcome(false, true, null); // 不适用：主位非白名单/无命中，非失败
        }
        // 负 lookbehind (?<!:) 排除 PG "::cast" 语法（如 ::uuid/::text）——与
        // SqlViewExecutor.NAMED_PARAM 的排除规则一致，否则 "::uuid" 里的第二个冒号会被误当命名占位符，
        // 替换后留下悬空单冒号导致 SQL 语法错误。
        String bound = ("SELECT * FROM (" + r.sql + ") _outer LIMIT 0")
            .replaceAll("(?<!:):pq\\b", "'" + UUID.randomUUID() + "'::uuid");
        // 其余 :xxx 命名占位符（customerId/customerCode/hfPartNos 等）批量替换为 NULL 字面量，
        // 与 SqlViewExecutor.rewriteNamedParams 的"未绑定占位符→NULL"安全降级语义一致，
        // 仅用于 LIMIT 0 语法/元数据探测，不依赖具体业务值。
        bound = bound.replaceAll("(?<!:):[A-Za-z_][A-Za-z0-9_]*\\b", "NULL");
        try (PreparedStatement ps = conn.prepareStatement(bound);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            boolean found = false;
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                if ("__v6_id".equalsIgnoreCase(meta.getColumnLabel(i))) {
                    found = true;
                    if (meta instanceof PgResultSetMetaData pgMeta) {
                        String baseTable = pgMeta.getBaseTableName(i);
                        String baseColumn = pgMeta.getBaseColumnName(i);
                        if (!QuotePendingRewriter.WHITELIST_TABLES.contains(baseTable)) {
                            return new CheckOutcome(true, false,
                                "改写后 __v6_id 基表越白名单: " + baseTable);
                        }
                        if (!"id".equalsIgnoreCase(baseColumn)) {
                            return new CheckOutcome(true, false,
                                "改写后 __v6_id 基列非 id: " + baseColumn);
                        }
                    }
                }
            }
            if (!found) {
                return new CheckOutcome(true, false, "改写后追不到 __v6_id 锚点列");
            }
        } catch (Exception e) {
            return new CheckOutcome(true, false, "LIMIT 0 执行失败: " + e.getMessage());
        }
        return new CheckOutcome(true, true, null);
    }
}
