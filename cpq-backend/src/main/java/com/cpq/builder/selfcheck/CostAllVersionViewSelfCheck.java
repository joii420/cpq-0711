package com.cpq.builder.selfcheck;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 启动期漂移自检：核价两套的 {@code v_<主表>_all} 全版本视图 ↔ 主表（task-260819 · v9 · B-44⑤ / AC-125）。
 *
 * <p><b>为什么必须是启动期，而不是 CI 或保存期</b>（D-84′，隔壁会话提出、主线认可）：
 * 这 26 张视图是 {@code SELECT <逐列列举>, true AS is_current FROM 主表 UNION ALL SELECT <同列>, false
 * FROM 主表_history}。PG 在 {@code CREATE VIEW} 时把列清单**固化**下来 ⇒ 当 {@code task-260902}
 * 给某张 {@code ds_cost_*} 主表<b>加一列</b>时：
 * <ul>
 *   <li>视图<b>不会报错</b>，只会静默地不包含新列；</li>
 *   <li>对方的 {@link com.cpq.dataset.registry.DatasetSchemaSelfCheck}（Registry ↔ 主表）
 *       <b>照样通过</b> —— 它根本不看我的视图；</li>
 *   <li>取数配置器这边继续能查、能编译、能预览，<b>只是那一列永远查不到</b>。</li>
 * </ul>
 * 三条加起来 = <b>零信号的静默故障</b>，与本任务反复在修的那一族同型。所以这道防线只能建在这里，
 * 而且必须"不一致 = 起不来"，不能降级成日志。
 *
 * <p>🚦 <b>repair-260909 补充（用户 2026-09-09 裁决「走甲」）—— 上面那段一个字没删，仍然成立</b>：
 * 本守卫防的<b>始终是</b>「{@code task-260902} 改了 {@code ds_cost_*} 主表的列而视图没跟上」这类<b>漂移</b>。
 * 但视图里也可能出现一种<b>不是漂移</b>的多余列：<b>有意为之的派生列</b>——
 * 譬如 {@code sales_material_no}，它是元素BOM视图内经 {@code material_master} 桥接算出来的连接键，
 * 主表里本就不该有（底表由导入器写，加物理列 = 多一处写入点 + 被归进锚点语义）。
 *
 * <p>⇒ 判据从「{@code is_current} 之外一律不许多」收窄为「<b>{@code is_current} 与
 * {@link #REGISTERED_DERIVED_COLUMNS} 逐张视图点名登记过的派生列</b>之外一律不许多」。
 * <b>登记在案 = 声明意图，未登记 = 漂移，照旧当场起不来。</b>
 * 这是<b>登记制</b>，不是开口子：登记表按<b>视图</b>而非全局生效，同名列出现在别的
 * {@code v_%_all} 上仍然打挂；往登记表加一条需要立项 + 用户裁决。
 *
 * <p>📌 对方明确<b>不</b>把这些视图纳入他们的自检 —— 那会让 {@code com.cpq.dataset} 依赖
 * {@code com.cpq.builder} 的产物，依赖方向反了。这是正确的架构判断，不是推诿。
 *
 * <p><b>检查三件事</b>（一次 {@code information_schema} 查询查完，🚫 无循环内查询）：
 * <ol>
 *   <li><b>列漂移</b>：每张 {@code v_X_all} 的列集合必须 = 主表 {@code X} 的列集合 ∪ {@code is_current}，
 *       <b>双向</b>（视图缺列 = 260902 加了列；视图多列 = 260902 删了列）。</li>
 *   <li><b>视图缺失</b>：语义图里 {@code semantic_node.physical_table} 指向的每张 {@code v_%_all} 都必须存在
 *       （有人手工 {@code DROP VIEW} 后编译产物会在运行期才炸）。</li>
 *   <li><b>新表未接</b>：每张有 {@code _history} 兄弟表的 {@code ds_cost_*} 主表都必须有对应视图
 *       （260902 新增一个带版本 sheet 时，核价侧的版本切换会<b>整张表不可切</b>且没有任何提示）。</li>
 * </ol>
 *
 * <p>⚠️ 依赖 Flyway {@code migrate-at-start} 已跑完 —— Quarkus 在 runtime-init 阶段执行迁移，
 * 早于 {@link StartupEvent}，顺序安全（与 {@code DatasetSchemaSelfCheck} 同款前提）。
 */
@ApplicationScoped
public class CostAllVersionViewSelfCheck {

    private static final Logger LOG = Logger.getLogger(CostAllVersionViewSelfCheck.class);

    /** 视图命名约定：{@code v_<主表>_all}。改这里等于改 V409 迁移的生成规则，两边必须同时改。 */
    private static final String VIEW_PREFIX = "v_";
    private static final String VIEW_SUFFIX = "_all";
    /** 每张视图都<b>必须</b>有的派生列（V409 的 COMMENT ON COLUMN）——:versionFilter 宏靠它展开。 */
    private static final String DERIVED_COLUMN = "is_current";

    /**
     * 🚦 <b>具名派生列登记表</b>（repair-260909，用户 2026-09-09 裁决「走甲」）。
     *
     * <p><b>语义是「登记制」，不是「放开」</b>：只有下表<b>逐张视图逐列</b>点名的派生列才被豁免；
     * 任何<b>未登记</b>的多余列照旧当场 {@link IllegalStateException} 打挂启动。
     * 🚫 不许改成日志、不许加开关绕过 —— 那正是本守卫存在的理由。
     *
     * <p><b>为什么按视图登记而不是全局集合</b>：{@code sales_material_no} 只在两张<b>元素BOM</b>视图上
     * 是有意为之的桥接列；它若出现在 {@code v_ds_cost_basic_material_bom_all} 等别的视图上，
     * 那就是真漂移，必须照打不误。全局集合会把这种情况一并放行 —— 按视图登记是<b>更强</b>的判据，
     * 也更贴合本守卫「逐张视图列集合相等」的原意。
     *
     * <p>⚠️ <b>登记表只「允许」不「要求」</b>：登记了但列还没建出来，不产生任何新约束。
     * 这条性质让守卫改动可以<b>先于</b>建列的迁移进 master（repair-260909 的两阶段落库顺序即依赖它）。
     *
     * <p>🚫 <b>往这里加一条 = 削弱哨兵，必须走任务立项 + 用户裁决，不许开发期顺手加</b>
     * （与 {@code Sec31CompileCorrectnessTest.REGISTERED_LOOKUP_NODE_KEYS} 同口径）。
     * 每条都必须写清：哪张视图、为什么是派生列、谁引入的。
     */
    private static final Map<String, Set<String>> REGISTERED_DERIVED_COLUMNS = Map.of(
            // ── repair-260909（用户裁决 A0-1：核价与报价对同一 (客户,料号,元素) 必须给出同一个数）──
            //    核价侧锚点是【生产料号】，而 f_material_element_price 按【销售料号】取价。
            //    sales_material_no 是视图内经 material_master 桥接（LEFT JOIN LATERAL … LIMIT 1）
            //    派生出来的连接键，**主表里不存在也不该存在**：底表由导入器写入，
            //    加物理列就多一处写入点，且会被归进锚点语义（backtask B-2 明令禁止）。
            "v_ds_cost_basic_element_bom_all",  Set.of("sales_material_no"),
            "v_ds_cost_detail_element_bom_all", Set.of("sales_material_no"));

    /**
     * 关掉自检的唯一合法场景：迁移尚未落到目标库的一次性排障。
     * 🚫 dev / 生产不得关闭 —— 关掉就等于把"加列静默丢列"放回零信号状态。
     */
    @ConfigProperty(name = "cpq.builder.costview-check.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    DataSource dataSource;

    public void onStartup(@Observes StartupEvent ev) {
        if (!enabled) {
            LOG.warn("[builder] 全版本视图漂移自检已被 cpq.builder.costview-check.enabled=false 关闭"
                    + " —— ds_cost_* 主表加列将不再被拦截（视图会静默丢列）");
            return;
        }
        List<String> problems = check();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "[builder] 核价全版本视图 v_<主表>_all 与 ds_cost_* 主表不一致，共 " + problems.size()
                    + " 处（V409__task260819_v9_cost_all_version_views.sql 需随 task-260902 的表结构变更重新生成）：\n  - "
                    + String.join("\n  - ", problems));
        }
    }

    /** @return 全部差异描述；空列表 = 一致。可被测试直接调用（反证型断言用）。 */
    public List<String> check() {
        List<String> problems = new ArrayList<>();

        // ── 一次查完：ds_cost_* 全部表/视图的列 + 语义图声明的视图名（2 条 SQL，与表数无关） ──
        Map<String, Set<String>> cols = new LinkedHashMap<>();
        Set<String> declaredViews = new LinkedHashSet<>();
        try (Connection cn = dataSource.getConnection(); Statement st = cn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT table_name, column_name FROM information_schema.columns "
                    + "WHERE table_schema='public' "
                    + "  AND (table_name LIKE 'ds\\_cost\\_%' OR table_name LIKE 'v\\_ds\\_cost\\_%\\_all')")) {
                while (rs.next()) {
                    cols.computeIfAbsent(rs.getString(1), k -> new TreeSet<>()).add(rs.getString(2));
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT DISTINCT physical_table FROM semantic_node "
                    + "WHERE physical_table LIKE 'v\\_%\\_all' AND status='ACTIVE'")) {
                while (rs.next()) declaredViews.add(rs.getString(1));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("[builder] 读取 information_schema / semantic_node 失败", ex);
        }

        Set<String> presentViews = new LinkedHashSet<>();
        for (String name : cols.keySet()) {
            if (name.startsWith(VIEW_PREFIX) && name.endsWith(VIEW_SUFFIX)) presentViews.add(name);
        }

        // ① 列漂移（双向）
        for (String view : presentViews) {
            String main = baseTableOf(view);
            Set<String> mainCols = cols.get(main);
            if (mainCols == null || mainCols.isEmpty()) {
                problems.add("视图 " + view + " 的主表 " + main + " 不存在");
                continue;
            }
            Set<String> viewCols = cols.get(view);
            for (String c : mainCols) {
                if (!viewCols.contains(c)) {
                    problems.add(view + " 缺列 " + c + "（" + main + " 加了列而视图没跟上 —— 该列在取数配置器里永远查不到）");
                }
            }
            Set<String> registeredDerived = REGISTERED_DERIVED_COLUMNS.getOrDefault(view, Set.of());
            for (String c : viewCols) {
                if (DERIVED_COLUMN.equals(c)) continue;
                // 🚦 登记在案的派生列 = 声明意图，不是漂移（repair-260909）。
                //    未登记的照旧打挂 —— 这一行是「登记制」与「放开」的唯一分界。
                if (registeredDerived.contains(c)) continue;
                if (!mainCols.contains(c)) {
                    problems.add(view + " 多出未在主表 " + main + " 出现的列 " + c
                            + "（既不是主表列，也不在 REGISTERED_DERIVED_COLUMNS 登记表里 ——"
                            + " 若确为有意新增的派生列，须走立项 + 用户裁决后登记，🚫 不许开发期顺手加）");
                }
            }
            if (!viewCols.contains(DERIVED_COLUMN)) {
                problems.add(view + " 缺派生列 " + DERIVED_COLUMN + " —— :versionFilter 宏无法展开");
            }
        }

        // ② 语义图指向的视图必须存在
        for (String declared : declaredViews) {
            if (!presentViews.contains(declared)) {
                problems.add("semantic_node.physical_table 指向的视图不存在: " + declared);
            }
        }

        // ③ 有 _history 的 ds_cost_* 主表必须已建视图
        for (String name : cols.keySet()) {
            if (!name.startsWith("ds_cost_") || name.endsWith("_history")) continue;
            if (!cols.containsKey(name + "_history")) continue; // 免版本表，本就没有全版本视图
            String expected = VIEW_PREFIX + name + VIEW_SUFFIX;
            if (!presentViews.contains(expected)) {
                problems.add("带版本主表 " + name + " 没有全版本视图 " + expected
                        + "（task-260902 新增了带版本 sheet？请重跑 gen_v9_semantic_seed.py 生成新迁移）");
            }
        }

        if (problems.isEmpty()) {
            LOG.infof("[builder] 全版本视图自检通过：%d 张 v_<主表>_all，逐列与主表双向一致（+is_current）", presentViews.size());
        }
        return problems;
    }

    /** {@code v_ds_cost_basic_material_bom_all} → {@code ds_cost_basic_material_bom}。 */
    private static String baseTableOf(String view) {
        return view.substring(VIEW_PREFIX.length(), view.length() - VIEW_SUFFIX.length());
    }
}
