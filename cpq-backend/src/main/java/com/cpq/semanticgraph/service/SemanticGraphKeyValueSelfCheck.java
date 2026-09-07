package com.cpq.semanticgraph.service;

import com.cpq.builder.compiler.CompileDialect;
import com.cpq.component.service.ComponentService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 启动期<b>语义图键值列 ↔ 权威值域</b>自检（task-260819 · backtask B-56）。
 *
 * <h3>为什么必须有：同一个缺陷犯了两次</h3>
 * 需求文档 <b>D-39</b>（2026-08-21 裁决）把两件东西分得明明白白：
 * <ul>
 *   <li><b>存储值</b> = {@code "BOM"} —— {@code component.tab_type} /
 *       {@link ComponentService#VALID_TAB_TYPES} / {@code semantic_tab_view.tab_type} /
 *       {@code builder_config.tabType}，四处统一；</li>
 *   <li><b>显示名</b> = 「BOM 树」 —— Select 的 label、图谱页文案、文档正文。</li>
 * </ul>
 * 这条裁决本身就是<b>为了修第一次犯的错而写的</b>（前端把 {@code TAB_TYPES} 写成 {@code "BOM 树"}，
 * {@code includes()} 静默 miss，存量 {@code tab_type='BOM'} 的组件打开取数配置 Tab 被误初始化成「主件」）。
 * 然后 {@code V413} 种子在<b>库这一侧</b>犯了镜像版本的同一个错：把显示名写进了键值列，三个方言全错。
 * 后果是 {@code PUT /api/cpq/components/{id}/builder} 直接 400 ——「BOM 树」页签的新组件<b>根本存不进去</b>
 * （已由 {@code V417} 修正）。
 *
 * <p>⇒ 教训不是「下次小心点」，是<b>只活在文档里的裁决拦不住第三次</b>。本类把 D-39
 * 从一句文档变成一条<b>机器可校验、违反就起不来</b>的约束。形态照抄
 * {@code com.cpq.dataset.registry.DatasetSchemaSelfCheck}（同一套 {@code @Observes StartupEvent} +
 * 不一致直接 {@code IllegalStateException}）。
 *
 * <h3>只校验「权威值域在别处」的列 —— 这是刻意的边界</h3>
 * 本类<b>不自己发明值域</b>。每条规则都必须指向一个<b>已经存在于代码里的权威声明</b>，
 * 直接引用它（而不是抄一份字面量）—— 否则这个自检本身就变成了它要消灭的那种<b>双写漂移</b>。
 * 按此判据逐列过了一遍语义图的键值列，纳入 3 条、明确排除 5 条：
 *
 * <table border="1">
 *   <caption>语义图键值列取舍</caption>
 *   <tr><th>列</th><th>取舍</th><th>理由</th></tr>
 *   <tr><td>{@code semantic_tab_view.tab_type}</td><td>✅ 纳入</td>
 *       <td>权威值域 = {@link ComponentService#VALID_TAB_TYPES}（跨模块：组件保存期据此 400）。
 *           <b>本次缺陷就在这一列</b>，且失败形态是「配置器存不进去」，离缺陷现场很远，很难反查。</td></tr>
 *   <tr><td>{@code semantic_tab_view.dialect}</td><td>✅ 纳入</td>
 *       <td>权威值域 = {@link CompileDialect} 枚举名。写歪了这一行<b>永远匹配不上任何编译请求</b>
 *           （{@code SemanticCompiler#resolveTabView} 按三段坐标过滤），表现为 404 而不是「值非法」。</td></tr>
 *   <tr><td>{@code semantic_node.dialect}</td><td>✅ 纳入</td><td>同上，节点侧同一枚举。</td></tr>
 *   <tr><td>{@code semantic_edge.cardinality}</td><td>🚫 排除</td>
 *       <td>库里已有 {@code CHECK chk_card}，DB 就拦住了，再查一遍是纯重复。</td></tr>
 *   <tr><td>{@code semantic_tab_view_node.role}</td><td>🚫 排除</td><td>同上，{@code CHECK chk_role}。</td></tr>
 *   <tr><td>{@code semantic_edge.edge_kind}</td><td>🚫 排除</td>
 *       <td>没有独立权威声明（编译器是一串 {@code switch} 分支），我在这里列一份就是**新造一处双写**。
 *           而且它<b>本来就不静默</b>：{@code SemanticCompiler} 对不认识的取值直接抛
 *           「本列的连接类型暂不支持: X」。加了反而会在别人正常扩 {@code edge_kind}（如本任务刚加的
 *           {@code NARROW}）时把所有人挡在门外。</td></tr>
 *   <tr><td>{@code semantic_node.node_kind}</td><td>🚫 排除</td>
 *       <td>同样没有独立权威声明（{@code "SHEET".equals(...)} 散在校验器/映射器里）。
 *           ⚠️ 这一条是本次<b>最接近边界</b>的取舍：它确实会静默降级。<b>先决条件是有人把
 *           {@code SHEET/LOOKUP/FUNCTION} 收成一个常量或枚举</b>，那时再纳入，一行即可（见下方 RULES）。</td></tr>
 *   <tr><td>{@code semantic_tab_view.variant_key}</td><td>🚫 排除</td>
 *       <td>取值就是 {@code node_key}（费用类变体），没有封闭值域可比。</td></tr>
 * </table>
 *
 * <h3>为什么<b>不</b>限定 {@code status='ACTIVE'}</h3>
 * 因为 {@code status} 这一列<b>全工程只写不读</b>：图的唯一装载点
 * {@code SemanticGraphLoader} 用的是 {@code SemanticTabView.listAll()} / {@code SemanticNode.listAll()}，
 * <b>没有任何 status 过滤</b>（2026-09-05 实测）。⇒ {@code INACTIVE} 行<b>照样参与编译</b>。
 * 若本自检只看 {@code ACTIVE}，就会在「运行时读、自检不看」的那批行上留出一个<b>正好是盲区的盲区</b>。
 * 另外 {@code (tab_type, variant_key, dialect)} 是唯一键，非法值即便在停用行里也仍占着键位、随时可被复活。
 *
 * <p>📌 并发任务 {@code task-260904} 的 v2 方案曾计划把 6 行置 {@code INACTIVE}，其 <b>v3 已取消</b>
 * （原因正是「status 只写不读，置了是 no-op」）—— 45 行全部保持 {@code ACTIVE}，与本选择无冲突。
 * 将来真要拿 {@code status} 做软删除，<b>正确动作是先让装载器读它</b>，那时本类跟着加过滤即可。
 *
 * <h3>踩雷时怎么办</h3>
 * 报错会点名<b>表.列 / 违规值 / 行数 / 合法值域 / 样本 id</b>。正确动作是<b>写一条新迁移把数据改对</b>，
 * 🚫 不是放宽这里的值域、也不是关掉开关 —— {@code cpq.semanticgraph.key-value-check.enabled=false}
 * 的唯一合法场景是「迁移还没落到目标库」的一次性排障。
 */
@ApplicationScoped
public class SemanticGraphKeyValueSelfCheck {

    private static final Logger LOG = Logger.getLogger(SemanticGraphKeyValueSelfCheck.class);

    /**
     * 一条规则 = 一个键值列 + 它的<b>权威值域来自哪里</b>。
     *
     * @param table     物理表名（拼进 SQL，故只允许本类内写死的常量，🚫 不接受外部输入）
     * @param column    键值列名（同上）
     * @param domain    合法取值集合 —— 必须<b>直接引用权威声明</b>，🚫 不许在这里抄字面量
     * @param authority 权威声明的出处，报错时原样打给人看
     */
    private record KeyColumnRule(String table, String column, Set<String> domain, String authority) { }

    /**
     * 🚨 新增一行前先问：<b>这个值域在别处已经有权威声明了吗？</b>
     * 没有就先去把它收成常量/枚举，再回来引用它。在这里现列一份字面量 =
     * 又造了一处双写漂移，正是本类要消灭的东西。
     */
    private static final List<KeyColumnRule> RULES = List.of(
            new KeyColumnRule("semantic_tab_view", "tab_type",
                    ComponentService.VALID_TAB_TYPES,
                    "ComponentService.VALID_TAB_TYPES（需求文档 D-39：存储值，显示名「BOM 树」只在前端 label）"),
            new KeyColumnRule("semantic_tab_view", "dialect",
                    dialectNames(),
                    "CompileDialect 枚举（AC-116：方言决定用哪一行页签视图声明）"),
            new KeyColumnRule("semantic_node", "dialect",
                    dialectNames(),
                    "CompileDialect 枚举（AC-116：方言决定节点属于哪套数据集）")
    );

    private static Set<String> dialectNames() {
        Set<String> s = new LinkedHashSet<>();
        for (CompileDialect d : CompileDialect.values()) {
            s.add(d.graphDialect());
        }
        return s;
    }

    @Inject
    DataSource dataSource;

    /**
     * 关掉自检的唯一合法场景：迁移尚未落到目标库的一次性排障。
     * 🚫 dev / 生产不得关闭 —— 关掉就等于把 D-39 这类裁决放回「只活在文档里」的状态。
     */
    @ConfigProperty(name = "cpq.semanticgraph.key-value-check.enabled", defaultValue = "true")
    boolean enabled;

    public void onStartup(@Observes StartupEvent ev) {
        if (!enabled) {
            LOG.warn("[semantic-graph] 键值列自检已被 cpq.semanticgraph.key-value-check.enabled=false 关闭 "
                    + "—— 种子把显示名写进键值列这类缺陷不再被拦截（D-39 第二次事故的成因）");
            return;
        }
        List<String> problems = check();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "[semantic-graph] 语义图键值列取值越出权威值域，共 " + problems.size() + " 处。"
                    + "\n  ⚠️ 正确动作是**写一条新迁移把数据改对**，🚫 不是放宽值域、更不是关掉自检。"
                    + "\n  - " + String.join("\n  - ", problems));
        }
    }

    /** @return 全部违规描述；空列表 = 全部落在值域内。可被测试直接调用。 */
    public List<String> check() {
        List<String> problems = new ArrayList<>();
        int rulesRun = 0;
        long rowsScanned = 0;
        for (KeyColumnRule r : RULES) {
            if (!tableExists(r.table())) {
                problems.add(r.table() + " 表不存在 —— 语义图迁移（V388 起）未落到本库，无法校验 "
                        + r.table() + "." + r.column());
                continue;
            }
            rulesRun++;
            rowsScanned += checkOne(r, problems);
        }
        // 🚨 只有真的没问题才准说「通过」。
        //    第一版这行写在 return 前、不看 problems —— B-56 的变异实验里日志同时打出
        //    「键值列自检通过」和 IllegalStateException，正是 testing.md 说的那种
        //    「读日志的人会据此判绿」的假信号。日志措辞本身也是验证链的一环。
        if (problems.isEmpty()) {
            LOG.infof("[semantic-graph] 键值列自检通过：%d 条规则 / 共扫描 %d 行（%s）",
                    rulesRun, rowsScanned,
                    String.join(", ", RULES.stream().map(r -> r.table() + "." + r.column()).toList()));
        } else {
            LOG.errorf("[semantic-graph] 键值列自检**未通过**：%d 处违规 / %d 条规则 / 共扫描 %d 行",
                    problems.size(), rulesRun, rowsScanned);
        }
        return problems;
    }

    /**
     * 单列校验。按<b>取值分组</b>查（不是逐行查）——
     * 一列的取值最多几十种，SQL 条数与表行数无关，恒定 1 条。
     *
     * <p>🚫 <b>刻意不加 {@code status='ACTIVE'} 过滤</b>：装载器 {@code SemanticGraphLoader} 是
     * {@code listAll()}，停用行照样参与编译（详见类注释）。
     *
     * @param problems 违规描述<b>追加</b>到这里
     * @return 本列实际扫过的行数 —— 上报到汇总日志，让「0 行的空跑」在 INFO 级也现形
     */
    private long checkOne(KeyColumnRule r, List<String> problems) {
        String sql = "SELECT " + r.column() + " AS v, count(*) AS n, "
                + "       string_agg(id::text, ', ' ORDER BY id::text) AS ids "
                + "FROM " + r.table() + " GROUP BY 1 ORDER BY 1";
        long total = 0;
        Set<String> seen = new TreeSet<>();
        try (Connection cn = dataSource.getConnection();
             PreparedStatement ps = cn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String value = rs.getString("v");
                long n = rs.getLong("n");
                total += n;
                seen.add(String.valueOf(value));
                if (value != null && r.domain().contains(value)) {
                    continue;
                }
                String ids = rs.getString("ids");
                problems.add(String.format(
                        "%s.%s 取值「%s」不在合法值域内：%d 行。合法值域 = %s（权威出处：%s）。样本 id：%s",
                        r.table(), r.column(),
                        value == null ? "<NULL>" : value,
                        n,
                        new TreeSet<>(r.domain()),
                        r.authority(),
                        abbreviate(ids)));
            }
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "[semantic-graph] 读取 " + r.table() + "." + r.column() + " 失败", ex);
        }

        // 🚨 空表 = 本条规则是**空跑**，而空跑打印出来和「全部通过」长得一模一样（testing.md §4.4）。
        //    不当失败处理（种子还没灌的库是合法状态），但必须让它在日志里显形。
        if (total == 0) {
            LOG.warnf("[semantic-graph] %s 表 0 行 —— %s.%s 的值域校验本次是**空跑**，"
                            + "别把它当成「校验通过」。种子（V413）是否已落库？",
                    r.table(), r.table(), r.column());
        } else {
            LOG.debugf("[semantic-graph] %s.%s：%d 行 / %d 种取值 %s",
                    r.table(), r.column(), total, seen.size(), seen);
        }
        return total;
    }

    private boolean tableExists(String table) {
        try (Connection cn = dataSource.getConnection();
             PreparedStatement ps = cn.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            ps.setString(1, "public." + table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("[semantic-graph] 探测表 " + table + " 是否存在失败", ex);
        }
    }

    /** id 列表可能很长，报错信息里截断 —— 定位用得上几个就够，全量在库里。 */
    private static String abbreviate(String ids) {
        if (ids == null) {
            return "(无)";
        }
        return ids.length() <= 200 ? ids : ids.substring(0, 200) + " …(已截断)";
    }
}
