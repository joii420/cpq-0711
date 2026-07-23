package com.cpq.datasource.sqlview;

import com.cpq.common.exception.BusinessException;
import com.cpq.template.entity.TemplateSqlView;
import com.cpq.template.service.TemplateSqlViewService;
import com.cpq.component.dto.RuntimeContext;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.component.service.ComponentSqlViewService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
// SqlViewRuntimeContext 同包，无需 import

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 组件级数据源 SQL 视图执行通路（方案 §5.1 / §5.2，Phase 1 扩展 owner-aware 路由 §6.4）。
 *
 * <p>当 BNF path 以 {@code $} 或 {@code $$} 开头时，DataLoader 旁路 CachedPathParser /
 * CachedSqlCompiler / ImplicitJoinRewriter 主链路，直接走本执行器。本执行器：
 *
 * <ol>
 *   <li>用正则把 path 拆成 {@code $name | $$code.name} + 可选谓词 + 列名</li>
 *   <li>从 {@link SqlViewRuntimeContext} ThreadLocal 读取 owner 信息，路由到对应查找服务</li>
 *   <li>隔离边界强制：{@code $$} 跨引用在 Excel 模板上下文直接抛 {@link BusinessException}</li>
 *   <li>构造 {@code SELECT <col> FROM (<sql_template>) inner_q WHERE <谓词>}</li>
 *   <li>支持 {@code :hfPartNos} 数组形式 batch filter（外层注入）</li>
 *   <li>命名占位符 {@code :xxx} → {@code ?} 重写 + 顺序绑定</li>
 *   <li>JDBC PreparedStatement 执行，输出 {@code List&lt;Map&lt;String,Object&gt;&gt;}（与 DataLoader 同形态）</li>
 * </ol>
 *
 * <p>设计取舍：不走 CachedPathParser 是为了避免在 SchemaContext 注册临时虚拟表（线程安全 +
 * ApplicationScoped 单例约束）。代价：不享受 AST 缓存；收益：核心 BNF 链路 0 触动。
 *
 * <p>Phase 2 隔离边界（V249 更新：COSTING_TEMPLATE → TEMPLATE）：
 * <ul>
 *   <li>{@code isCross=true}（$$形态）且 ownerType=TEMPLATE → 抛 {@link BusinessException}</li>
 *   <li>{@code isCross=true}（$$形态）且 ownerType=COMPONENT → 走现有 GLOBAL 组件视图查（沿用现状）</li>
 *   <li>{@code isCross=false}（$形态）且 ownerType=COMPONENT → lookup component_sql_view</li>
 *   <li>{@code isCross=false}（$形态）且 ownerType=TEMPLATE → lookup template_sql_view</li>
 *   <li>ownerType=null → 抛 {@link BusinessException}</li>
 * </ul>
 */
@ApplicationScoped
public class SqlViewExecutor {

    private static final Logger LOG = Logger.getLogger(SqlViewExecutor.class);

    /**
     * BNF $ 引用路径 grammar:
     * <pre>
     *   ^(?:\$\$(componentCode)\.(sqlViewName)|\$(sqlViewName))     # 命名空间前缀
     *   (?:\[(predicate)\])?                                        # 可选谓词
     *   \.(column)$                                                 # 列名
     * </pre>
     */
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "^(?:\\$\\$([A-Za-z][A-Za-z0-9_-]*)\\.([a-z_][a-z0-9_]*)|\\$([a-z_][a-z0-9_]*))" +
            "(?:\\[([^\\]]*)\\])?" +
            "\\.([a-z_][a-z0-9_]*)$"
    );

    /**
     * Driver path 形态（无 column 后缀）：
     *   $view_name[predicate]?         本组件
     *   $$componentCode.view_name[pred]?  跨组件
     *
     * <p>作为 driver path 整行返回（component_sql_view.sql_template 的所有列）。
     */
    private static final Pattern DRIVER_PATH_PATTERN = Pattern.compile(
            "^(?:\\$\\$([A-Za-z][A-Za-z0-9_-]*)\\.([a-z_][a-z0-9_]*)|\\$([a-z_][a-z0-9_]*))" +
            "(?:\\[([^\\]]*)\\])?$"
    );

    /** :xxx 命名占位符匹配。 */
    // 负 lookbehind (?<!:) 排除 PG `::cast` 语法（如 ::uuid / ::varchar / ::text）
    private static final Pattern NAMED_PARAM = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");

    /** SQL 标识符白名单（列名 / 别名 / 视图名）：字母数字下划线，长度限制 80。 */
    private static final Pattern SQL_IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,79}$");

    @Inject
    DataSource dataSource;

    @Inject
    ComponentSqlViewService sqlViewService;

    @Inject
    TemplateSqlViewService templateSqlViewService;

    /**
     * 判断 path 是否需要本执行器处理。
     */
    public boolean isSqlViewPath(String path) {
        return path != null && path.startsWith("$");
    }

    /**
     * 单条 path 执行（Phase 1 改造：owner-aware 路由 + 隔离边界强制）。
     *
     * @param path    含 $ 前缀的 BNF 路径
     * @param ctx     运行时上下文（命名占位符值来源）
     * @param partNos 批量料号（外层 batch；为 null 表示无 ANY 过滤）
     * @return 查询结果（与 {@link com.cpq.formula.dataloader.DataLoader} 同形态）
     */
    public List<Map<String, Object>> execute(String path, RuntimeContext ctx, List<String> partNos) {
        Matcher m = PATH_PATTERN.matcher(path.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("非法的 SQL 视图路径语法：" + path);
        }

        boolean isCross = m.group(1) != null;
        String componentCode = isCross ? m.group(1) : null;
        String viewName = isCross ? m.group(2) : m.group(3);
        String predicate = m.group(4);   // 可能为 null
        String column = m.group(5);

        SqlViewRuntimeContext.Snapshot owner = SqlViewRuntimeContext.get();

        // ── 列名白名单校验（防止注入）──
        if (!SQL_IDENT.matcher(column).matches()) {
            throw new IllegalArgumentException("非法列名：" + column);
        }

        // ══════════════════ Phase 2 隔离边界强制（V249：COSTING_TEMPLATE → TEMPLATE）══════════════════

        if (isCross) {
            // $$ 跨组件形态
            if (owner.ownerType == SqlViewRuntimeContext.OwnerType.TEMPLATE) {
                throw new BusinessException(400,
                        "模板 Excel 视图路径不允许跨组件引用（$$ 形态）。" +
                        "请改用本模板自有的 SQL 视图 $" + viewName + "。path=" + path);
            }
            // ownerType=COMPONENT（或 null 兼容）：走现有 GLOBAL 组件视图查
            return executeViaComponentSqlView(owner.componentId, viewName, predicate, column,
                    ctx, partNos, isCross, componentCode);
        }

        // $ 本 owner 形态：按 ownerType 路由
        if (owner.ownerType == SqlViewRuntimeContext.OwnerType.COMPONENT) {
            return executeViaComponentSqlView(owner.componentId, viewName, predicate, column,
                    ctx, partNos, false, null);
        }
        if (owner.ownerType == SqlViewRuntimeContext.OwnerType.TEMPLATE) {
            return executeViaTemplateSqlView(owner.templateId, viewName, predicate, column,
                    ctx, partNos);
        }

        throw new BusinessException(400,
                "SQL 视图路径解析失败：未设置 owner 上下文（ownerType=null）。" +
                "调用方需在 expand 或 evaluate 入口设置 SqlViewRuntimeContext。path=" + path);
    }

    // ──────────────── 私有：组件 SQL 视图执行 ─────────────────────────────────

    private List<Map<String, Object>> executeViaComponentSqlView(
            UUID componentId, String viewName, String predicate, String column,
            RuntimeContext ctx, List<String> partNos,
            boolean isCross, String componentCode) {

        Optional<ComponentSqlView> viewOpt = sqlViewService.lookupForResolver(
                componentId, viewName, isCross, componentCode);
        if (viewOpt.isEmpty()) {
            throw new IllegalArgumentException(
                    isCross
                            ? "跨组件 GLOBAL SQL 视图未找到：$$" + componentCode + "." + viewName
                            : "本组件 SQL 视图未找到：$" + viewName
                              + (componentId == null
                                  ? "（SqlViewRuntimeContext 未设置 componentId — 调用方需在 expand 入口 set ThreadLocal）"
                                  : "（componentId=" + componentId + "）")
            );
        }
        ComponentSqlView view = viewOpt.get();
        String sql = buildWrappedSql(column, applyPendingRewrite(applyVersionFilter(view.sqlTemplate)), predicate, partNos);
        return executeJdbc(sql, ctx, partNos, "path=$" + (isCross ? "$" + componentCode + "." : "") + viewName + "." + column);
    }

    // ──────────────── 私有：模板 SQL 视图执行（V249 改名 + 改 owner）────────────────────────────

    private List<Map<String, Object>> executeViaTemplateSqlView(
            UUID templateId, String viewName, String predicate, String column,
            RuntimeContext ctx, List<String> partNos) {

        Optional<TemplateSqlView> viewOpt =
                templateSqlViewService.lookupForResolver(templateId, viewName);
        if (viewOpt.isEmpty()) {
            throw new IllegalArgumentException(
                    "模板 SQL 视图未找到：$" + viewName
                    + "（templateId=" + templateId + "）。"
                    + "请在模板 SQL 视图 Tab 新建名为 '" + viewName + "' 的视图。");
        }
        TemplateSqlView view = viewOpt.get();
        String sql = buildWrappedSql(column, applyPendingRewrite(applyVersionFilter(view.sqlTemplate)), predicate, partNos);
        return executeJdbc(sql, ctx, partNos,
                "path=$" + viewName + "." + column + " [template=" + templateId + "]");
    }

    // ─────────────────── 判断 path 是否为 driver-style SQL 视图路径 ───────────

    /**
     * 判断 path 是否为 driver-style SQL 视图路径（不含 .column 后缀）。
     * 形如：$view_name / $view_name[predicate] / $$componentCode.view_name
     * 用于组件级 data_driver_path 把整行集作为 driver expansion。
     */
    public boolean isDriverViewPath(String path) {
        if (path == null) return false;
        String s = path.trim();
        return DRIVER_PATH_PATTERN.matcher(s).matches();
    }

    /**
     * Driver-style 执行：把 SQL 视图当作 driver path 整行返回（不取列名）。
     *
     * <p>Phase 2 防御性断言：driver path 仅允许在 COMPONENT 上下文使用（V249 更新）。
     * 模板 Excel 列是单值，不需要 driver path；若意外调用则抛 {@link BusinessException}。
     *
     * <p>SQL 形态: SELECT * FROM (sql_template) inner_q [WHERE predicate AND hf_part_no = ANY(...)]
     */
    public List<Map<String, Object>> executeAllRows(String path, RuntimeContext ctx, List<String> partNos) {
        SqlViewRuntimeContext.Snapshot owner = SqlViewRuntimeContext.get();

        // Phase 2 防御性断言：driver path 仅组件上下文可用（V249 COSTING_TEMPLATE → TEMPLATE）
        if (owner.ownerType == SqlViewRuntimeContext.OwnerType.TEMPLATE) {
            throw new BusinessException(400,
                    "driver 形态 $view 路径仅组件上下文可用（ownerType=TEMPLATE 时不允许）。path=" + path);
        }

        Matcher m = DRIVER_PATH_PATTERN.matcher(path.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("非法的 SQL 视图 driver 路径语法：" + path);
        }
        boolean isCross = m.group(1) != null;
        String componentCode = isCross ? m.group(1) : null;
        String viewName = isCross ? m.group(2) : m.group(3);
        String predicate = m.group(4);

        UUID currentComponentId = owner.componentId;
        Optional<ComponentSqlView> viewOpt = sqlViewService.lookupForResolver(
                currentComponentId, viewName, isCross, componentCode);
        if (viewOpt.isEmpty()) {
            throw new IllegalArgumentException(
                    isCross
                            ? "跨组件 GLOBAL SQL 视图未找到：$$" + componentCode + "." + viewName
                            : "本组件 SQL 视图未找到：$" + viewName
                              + (currentComponentId == null
                                  ? "（SqlViewRuntimeContext 未设置 componentId）"
                                  : "（componentId=" + currentComponentId + "）")
            );
        }
        ComponentSqlView view = viewOpt.get();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM (").append(applyPendingRewrite(applyVersionFilter(view.sqlTemplate))).append(") inner_q");

        List<String> whereParts = new ArrayList<>();
        if (predicate != null && !predicate.isBlank()) {
            whereParts.add("(" + predicate.trim() + ")");
        }
        if (partNos != null && !partNos.isEmpty()) {
            whereParts.add("inner_q.hf_part_no = ANY(:hfPartNos)");
        }
        if (!whereParts.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereParts));
        }

        Map<String, Object> namedParams = new HashMap<>(ctx.toNamedParams());
        enrichCustomerCode(namedParams);
        enrichPriceBaseDate(namedParams);
        if (partNos != null && !partNos.isEmpty()) {
            namedParams.put("hfPartNos", partNos);
        }
        injectCostingTreeVars(namedParams);
        injectPendingParam(namedParams);
        String expandedSql = sql.toString();
        RewrittenSql rewritten = rewriteNamedParams(expandedSql, namedParams);
        SqlDebugContext.record(rewritten.sql, rewritten.params);

        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(rewritten.sql)) {
            for (int i = 0; i < rewritten.params.size(); i++) {
                Object v = rewritten.params.get(i);
                if (v instanceof List<?> list) {
                    // 保留 null 元素为 SQL NULL（不可 String.valueOf，否则 null→"null" 字符串，
                    // 破坏 :spineKeys 对叶子节点(子件自身版本=NULL)的 NULL-safe 匹配 → 叶子被误滤空）。
                    String[] arr = list.stream()
                            .map(x -> x == null ? null : String.valueOf(x))
                            .toArray(String[]::new);
                    ps.setArray(i + 1, conn.createArrayOf("text", arr));
                } else {
                    ps.setObject(i + 1, v);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>(cols);
                    for (int c = 1; c <= cols; c++) {
                        row.put(meta.getColumnLabel(c), rs.getObject(c));
                    }
                    rows.add(row);
                }
            }
        } catch (Exception e) {
            LOG.warnf("[SqlViewExecutor] executeAllRows failed path=%s: %s", path, e.getMessage());
            throw new RuntimeException("SQL 视图 driver 执行失败：" + path + " — " + e.getMessage(), e);
        }
        LOG.debugf("[SqlViewExecutor] driver path=%s rows=%d", path, rows.size());
        return rows;
    }

    // ────────────────────────── 内部工具 ────────────────────────────────────

    /**
     * 构造包装 SQL：SELECT {col} FROM ({sqlTemplate}) inner_q [WHERE predicate AND hf_part_no = ANY(:hfPartNos)]
     */
    private String buildWrappedSql(String column, String sqlTemplate, String predicate, List<String> partNos) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(column).append(" FROM (")
           .append(sqlTemplate).append(") inner_q");

        List<String> whereParts = new ArrayList<>();
        if (predicate != null && !predicate.isBlank()) {
            whereParts.add("(" + predicate.trim() + ")");
        }
        if (partNos != null && !partNos.isEmpty()) {
            whereParts.add("inner_q.hf_part_no = ANY(:hfPartNos)");
        }
        if (!whereParts.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereParts));
        }
        return sql.toString();
    }

    /**
     * JDBC 执行 + 返回结果（通用执行逻辑）。
     */
    private List<Map<String, Object>> executeJdbc(String sql, RuntimeContext ctx, List<String> partNos,
                                                    String logContext) {
        Map<String, Object> namedParams = new HashMap<>(ctx.toNamedParams());
        enrichCustomerCode(namedParams);
        enrichPriceBaseDate(namedParams);
        if (partNos != null && !partNos.isEmpty()) {
            namedParams.put("hfPartNos", partNos);
        }
        injectCostingTreeVars(namedParams);
        injectPendingParam(namedParams);
        String expandedSql = sql;
        RewrittenSql rewritten = rewriteNamedParams(expandedSql, namedParams);
        SqlDebugContext.record(rewritten.sql, rewritten.params);

        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(rewritten.sql)) {

            for (int i = 0; i < rewritten.params.size(); i++) {
                Object v = rewritten.params.get(i);
                if (v instanceof List<?> list) {
                    // 保留 null 元素为 SQL NULL（见 executeAllRows 同款注释：null→"null" 会破坏 spineKeys 叶子匹配）。
                    String[] arr = list.stream()
                            .map(x -> x == null ? null : String.valueOf(x))
                            .toArray(String[]::new);
                    ps.setArray(i + 1, conn.createArrayOf("text", arr));
                } else {
                    ps.setObject(i + 1, v);
                }
            }

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>(cols);
                    for (int c = 1; c <= cols; c++) {
                        row.put(meta.getColumnLabel(c), rs.getObject(c));
                    }
                    rows.add(row);
                }
            }
        } catch (Exception e) {
            LOG.warnf("[SqlViewExecutor] execute failed %s: %s", logContext, e.getMessage());
            throw new RuntimeException("SQL 视图执行失败：" + logContext + " — " + e.getMessage(), e);
        }

        LOG.debugf("[SqlViewExecutor] %s rows=%d", logContext, rows.size());
        return rows;
    }

    /**
     * 进程级缓存：customer UUID → customer.code（如 CUST-1269）。
     * customer.code 是业务主键，极少变更，进程级缓存安全。
     */
    private final Map<UUID, String> customerCodeCache = new ConcurrentHashMap<>();

    /**
     * 补充 {@code :customerCode} 命名占位符：从 {@code :customerId}(UUID) 解析 customer.code。
     *
     * <p>V6 基础资料表 customer_no 列存的是 code（如 CUST-1269）而非 UUID，
     * SQL 视图按客户过滤须用 {@code customer_no = :customerCode}。
     * 上层只传 customerId(UUID) 时本方法自动解析补全，让 :customerId 与 :customerCode 同时可用。
     *
     * <p>若 ctx 已显式给了 customerCode（toNamedParams 已含）则不覆盖。
     */
    private void enrichCustomerCode(Map<String, Object> namedParams) {
        if (namedParams.containsKey("customerCode")) return;
        Object cid = namedParams.get("customerId");
        if (cid instanceof UUID uid) {
            String code = customerCodeCache.computeIfAbsent(uid, this::queryCustomerCode);
            if (code != null && !code.isBlank()) {
                namedParams.put("customerCode", code);
            }
        }
    }

    /**
     * 进程级缓存：quotationId → 报价单创建日期（task-0722 §11.2 取价基准日）。
     * created_at 不可变，缓存安全（与 customerCodeCache 同款模式）。
     */
    private final Map<UUID, LocalDate> priceBaseDateCache = new ConcurrentHashMap<>();

    /**
     * 补充 {@code :priceBaseDate} 命名占位符（task-0722 元素价格策略）。
     *
     * <p>基准日 = 该报价单的创建日期（需求 §11.2）；无 quotationId 上下文（配置期预览 / 策略试算）
     * 回退为当天。quotation 表无独立"单据日期"列，取 {@code created_at::date}。
     *
     * <p><b>取值来源（与 {@link #enrichCustomerCode} 同款"读 namedParams 而非 SqlViewRuntimeContext"模式，
     * 而非按 backtask 字面描述读 {@link SqlViewRuntimeContext#get()}.quotationId）</b>：
     * {@link com.cpq.component.service.ComponentDriverService#expand} 系列方法（COMP-0029/COMP-0040
     * 走 data_driver_path 的驱动展开主链路）调用 {@code SqlViewRuntimeContext.setNested(componentId, null,
     * null, null)}，quotationId 参数硬编码传 null——这意味着组件 driver 展开路径下
     * {@code SqlViewRuntimeContext.get().quotationId} 恒为 null，若直接读它 priceBaseDate 会永远回退今天，
     * 起不到"基准日=报价单创建日"的效果（§11.2 的核心诉求落空）。
     *
     * <p>真正贯穿 driver 展开链路的是 {@link QuotationIdContext}（ThreadLocal，由
     * {@code ComponentResource.batchExpand} / {@code CardSnapshotService} / {@code ConfigureSnapshotService}
     * 在渲染入口 set），经 {@code DataLoader.loadByPath} 读出后塞进
     * {@code RuntimeContext.quotation.id} → {@code ctx.toNamedParams()} 自动暴露为
     * {@code namedParams.get("quotationId")}——与 {@code :customerId}/{@code :customerCode} 完全同一条
     * 注入管线。因此本方法与 {@link #enrichCustomerCode} 一样从 {@code namedParams} 取值，而不是另开一条
     * 依赖 {@link SqlViewRuntimeContext} 的通路。
     */
    private void enrichPriceBaseDate(Map<String, Object> namedParams) {
        if (namedParams.containsKey("priceBaseDate")) return;   // 上层显式给了则不覆盖
        Object qidObj = namedParams.get("quotationId");
        UUID qid = (qidObj instanceof UUID u) ? u : null;
        LocalDate d = (qid == null) ? LocalDate.now()
                                     : priceBaseDateCache.computeIfAbsent(qid, this::queryQuotationDate);
        namedParams.put("priceBaseDate", d);
    }

    /** 查报价单创建日期（按 UUID）。失败/无记录时回退今天，不抛异常打断取价链路。 */
    private LocalDate queryQuotationDate(UUID quotationId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT created_at FROM quotation WHERE id = ?")) {
            ps.setObject(1, quotationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    OffsetDateTime odt = rs.getObject(1, OffsetDateTime.class);
                    if (odt != null) return odt.toLocalDate();
                }
            }
        } catch (Exception e) {
            LOG.warnf("[SqlViewExecutor] 解析报价单创建日期失败 id=%s，priceBaseDate 回退今天: %s",
                    quotationId, e.getMessage());
        }
        return LocalDate.now();
    }

    /**
     * 若当前线程有核价树变量上下文，注入 :production_part_nos / :total_material_no 两个 text[]。
     *
     * <p>S4（2026-07）：{@code v.productionPartNos} 分支当前不会被 {@code BomTreeRenderService
     * #queryRecursive} 触发——递归 SQL 走裸 JDBC（{@code PreparedStatement} 直接拼 {@code ?} 绑参），
     * 不经过本 {@code SqlViewExecutor}。保留此分支是为将来递归 SQL 若改走 {@code SqlViewExecutor} /
     * 内嵌 {@code $view} 预留（届时 {@code :production_part_nos} 命名参数可复用同一套注入协议），
     * 不是死代码，勿删。
     *
     * <p>task-0713 B3：<b>无条件</b>同时注入 {@code :__vfPart / :__vfVer}（供 {@link VersionFilterMacro}
     * 渲染模式绑定），哪怕当前线程根本没有 {@link BomTreeVarsContext} 或该组件没有 override ——
     * 也必须绑定「空数组」而非让占位符落回 {@link #rewriteNamedParams} 的字面量 {@code NULL} 兜底。
     * 原因：{@code x <> ALL(NULL::text[])} 在 SQL 里求值为 NULL（非真），而
     * {@code x <> ALL(ARRAY[]::text[])} 求值为 TRUE（空集合上的 ALL 恒真）——两者天差地别。
     * 若不绑定空数组，任何未显式设置 {@code BomTreeVarsContext} 的既有调用路径（如
     * {@code ensureCardValues}/{@code CardSnapshotService#expandFlatDriverBaseRows} 对非树核价模板的
     * 直接 {@code componentDriverService.expand} 调用）一旦遇到含 {@code :versionFilter} 的 $view，
     * 会让该视图的 WHERE 谓词整体退化为 SQL NULL、直接返回 0 行——即无覆盖上下文时版本视图整批失明，
     * 而非按预期退化为 {@code is_current}（AP-31/37/53 同类"看似无关代码路径致视图失明"的新增变体，
     * 特此重点记录）。
     */
    private void injectCostingTreeVars(Map<String, Object> namedParams) {
        BomTreeVarsContext.Vars v = BomTreeVarsContext.get();
        if (v != null) {
            if (v.productionPartNos != null) namedParams.put("production_part_nos", v.productionPartNos);
            if (v.totalMaterialNo != null)   namedParams.put("total_material_no", v.totalMaterialNo);
        }
        SqlViewRuntimeContext.Snapshot owner = SqlViewRuntimeContext.get();
        Map<String, String> ov = java.util.Collections.emptyMap();
        if (v != null && v.overridesByComponent != null && owner != null && owner.componentId != null) {
            ov = v.overridesByComponent.getOrDefault(owner.componentId, java.util.Collections.emptyMap());
        }
        List<String> vfPart = new ArrayList<>(ov.keySet());
        List<String> vfVer = new ArrayList<>(vfPart.size());
        for (String p : vfPart) vfVer.add(ov.get(p));
        namedParams.put("__vfPart", vfPart);
        namedParams.put("__vfVer", vfVer);
    }

    /**
     * 若 sql_template 含 {@code :versionFilter} 宏，按当前 {@link BomTreeVarsContext.Mode}
     * 选择展开形态（LIST→TRUE 放开版本过滤；RENDER/无上下文→按 override 渲染）；不含宏则原样返回
     * （零开销，绝大多数组件走此分支）。
     */
    private String applyVersionFilter(String sqlTemplate) {
        if (!VersionFilterMacro.containsMacro(sqlTemplate)) return sqlTemplate;
        BomTreeVarsContext.Vars v = BomTreeVarsContext.get();
        boolean listMode = v != null && v.mode == BomTreeVarsContext.Mode.LIST;
        return listMode ? VersionFilterMacro.expandForListing(sqlTemplate)
                         : VersionFilterMacro.expandForExecution(sqlTemplate);
    }

    /**
     * task-0721 报价数据版本升级 · B3.2 —— pending 感知改写接缝。
     *
     * <p>仅当当前线程上下文是「报价单 + 非冻结态」时启用（{@code quotationId != null &&
     * !isQuotationFrozen()}）：核价侧/无 quotationId 上下文/已提交冻结报价单一律原样返回
     * （AC-17 核价侧零回归 + AC-10 已建单不受影响）。挂在 {@link #applyVersionFilter} 之后调用
     * （先版本宏、后 pending 替换，backtask B3.2）。
     *
     * <p>改写失败（如白名单表列元数据解析异常）安全降级为原始模板——本单会看不到 pending 数据
     * （退化为跟未开启本功能一样），但不影响查询正确性；真正的结构性问题由启动期
     * {@link QuoteViewValidationService} fail-fast 拦在应用起不来那一步，运行时理论不会再触发这里的降级。
     */
    private String applyPendingRewrite(String sqlTemplate) {
        SqlViewRuntimeContext.Snapshot owner = SqlViewRuntimeContext.get();
        if (owner.quotationId == null || owner.isQuotationFrozen()) return sqlTemplate;
        try (Connection conn = dataSource.getConnection()) {
            QuotePendingRewriter.Result r = QuotePendingRewriter.rewrite(sqlTemplate, conn);
            return r.sql;
        } catch (Exception e) {
            LOG.warnf("[SqlViewExecutor] pending 改写失败，安全降级为原始模板（本次渲染看不到 pending 数据）: %s",
                e.getMessage());
            return sqlTemplate;
        }
    }

    /** 若当前是「报价单 + 非冻结态」上下文，补充 {@code :pq} 命名占位符（{@link QuotePendingRewriter#PENDING_PARAM}）。 */
    private void injectPendingParam(Map<String, Object> namedParams) {
        SqlViewRuntimeContext.Snapshot owner = SqlViewRuntimeContext.get();
        if (owner.quotationId != null && !owner.isQuotationFrozen()) {
            namedParams.put(QuotePendingRewriter.PENDING_PARAM, owner.quotationId);
        }
    }

    /** 查 customer.code（按 UUID）。失败返 null（占位符降级为 NULL，视图过滤返 0 行）。 */
    private String queryCustomerCode(UUID id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT code FROM customer WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            LOG.warnf("[SqlViewExecutor] 解析 customer code 失败 id=%s: %s", id, e.getMessage());
            return null;
        }
    }

    private static class RewrittenSql {
        final String sql;
        final List<Object> params;
        RewrittenSql(String sql, List<Object> params) {
            this.sql = sql;
            this.params = params;
        }
    }

    /**
     * 把 SQL 中所有 {@code :xxx} 命名占位符替换为 {@code ?}，按出现顺序收集对应值。
     *
     * <p>注意：未在 namedParams 中提供的占位符会被替换为 NULL（保留语义合法但运行时可能查不到行）。
     */
    private RewrittenSql rewriteNamedParams(String sql, Map<String, Object> namedParams) {
        StringBuilder out = new StringBuilder();
        List<Object> params = new ArrayList<>();
        Matcher m = NAMED_PARAM.matcher(sql);
        int lastEnd = 0;
        while (m.find()) {
            out.append(sql, lastEnd, m.start());
            String name = m.group(1);
            Object value = namedParams.get(name);
            if (value == null) {
                // 安全降级：未绑定的占位符替换为 NULL
                out.append("NULL");
            } else {
                out.append("?");
                params.add(value);
            }
            lastEnd = m.end();
        }
        out.append(sql, lastEnd, sql.length());
        return new RewrittenSql(out.toString(), params);
    }

    /**
     * 提取所有 :xxx 占位符（去重，保持顺序）。供 dry-run / 诊断使用。
     */
    public List<String> extractNamedParams(String sql) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = NAMED_PARAM.matcher(sql);
        while (m.find()) seen.add(m.group(1));
        return new ArrayList<>(seen);
    }
}
