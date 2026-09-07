package com.cpq.task260819v9;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 需求文档.md §9.4 —— <b>AC-118 / AC-124 / AC-125 / AC-126</b>（版本语义 · 全版本视图 · 漂移探针 · ::text）。
 *
 * <h3>本类不改任何全局状态</h3>
 * 全部只读 SQL + 只读端点。AC-126 的反证是<b>一条 SELECT</b>（故意让 PG 报类型错），不写任何数据。
 *
 * <h3>AC-125 的分工：静态不变量在本类，启动期反证的证据由后端 #2 提供</h3>
 * AC-125 有两半：
 * <ol>
 *   <li><b>不变量</b>（26 张视图列 = 主表列 + {@code is_current}，双向无差集）—— 本类
 *       {@link #ac125_versionViewsHaveNoColumnDrift} 负责，纯 SQL，随时可跑。</li>
 *   <li><b>「不一致则启动失败」</b>—— 要证伪它必须 {@code ALTER TABLE ds_cost_*}，那是 task-260902 的表、
 *       迁移 checksum 已锁死，属 {@code CLAUDE.md} §3.2 契约销毁 + backtask 全局约束③，
 *       <b>测试工程师没有批准权</b>。
 *       ✅ <b>该半已由 cpq-backend #2 在克隆库 {@code cpq_b42_flyway} 上完成 A/B 实证</b>
 *       （2026-09-03，主线转述并将在闸门 B 引用原始输出）：
 *       <pre>
 *       A 轮（无漂移）：正常启动
 *         [builder] 全版本视图自检通过：26 张 v_&lt;主表&gt;_all，逐列与主表双向一致（+is_current）
 *       B 轮（注入 ALTER TABLE ds_cost_basic_material_bom ADD COLUMN drift_probe）：启动失败
 *         IllegalStateException: [builder] 核价全版本视图 v_&lt;主表&gt;_all 与 ds_cost_* 主表不一致，共 1 处：
 *           - v_ds_cost_basic_material_bom_all 缺列 drift_probe（该列在取数配置器里永远查不到）
 *       </pre>
 *       ⇒ <b>AC-125 不再是交付缺口</b>；{@code golden/ac125-drift-probe.sh} 随之作废，
 *       保留仅作方法留痕（不要再向共享库报批执行它）。</li>
 * </ol>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V9VersionAndDriftTest extends V9TestBase {

    // ═══════════════════════════════════════════════════════════════
    // AC-118（单点·不变量）同一轴值在主表只有一个 distinct version_no
    // 🚫 不写死 v5 / v7 —— §9.1.2 明令禁止写死版本号（会漂移，实测 2026-09-03 已从 v5 涨到 v7）
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(118)
    @DisplayName("AC-118: 主表只存当前版本 —— 同一轴值 count(DISTINCT version_no) > 1 的组数 = 0")
    void ac118_mainTableHoldsOnlyCurrentVersion() {
        // ① AC 原文点名的那张表（逐字实现 AC 里给的 SQL）
        long violations = scalarLong(
                "SELECT count(*) FROM (SELECT production_no FROM ds_cost_basic_material_bom "
                        + "GROUP BY production_no HAVING count(DISTINCT version_no) > 1) t");
        long rows = scalarLong("SELECT count(*) FROM ds_cost_basic_material_bom");
        long groups = scalarLong("SELECT count(DISTINCT production_no) FROM ds_cost_basic_material_bom");
        System.out.println("[AC-118] 紧邻取基准（库 cpq_db_0724）：ds_cost_basic_material_bom 行数=" + rows
                + " 轴值组数=" + groups + " 违规组数=" + violations);

        // 阳性对照：表非空、且真的有分组，否则「违规 0」只是空跑（testing.md §3 红线 3）
        assertTrue(rows > 0, notReady("AC-118",
                "ds_cost_basic_material_bom 是空表，『违规组数=0』等于断言从未执行", "灌数据方"));
        assertTrue(groups > 0, notReady("AC-118", "ds_cost_basic_material_bom 无任何轴值分组", "灌数据方"));

        assertEquals(0L, violations,
                "AC-118①: 同一轴值在主表内 version_no 只应有一个 distinct 值（§9.1.2「主表只存当前版本」）。"
                        + "违规组数=" + violations
                        + "\n  违规明细=" + strList("SELECT production_no || ' 有 ' || count(DISTINCT version_no) || ' 个版本' "
                        + "FROM ds_cost_basic_material_bom GROUP BY production_no HAVING count(DISTINCT version_no) > 1"));

        // ② 推广到全部带版本的 ds_cost_* 主表 —— S-31 的整套设计都建立在这条不变量上，
        //    只验一张表会漏掉「某张表被写坏」的情况。
        StringBuilder bad = new StringBuilder();
        int checked = 0;
        for (String t : versionedCostMainTables()) {
            long n = scalarLong("SELECT count(*) FROM " + t);
            if (n == 0) {
                System.out.println("[AC-118②] " + t + " 空表，跳过（无数据可违规，也无证据力）");
                continue;
            }
            long v = scalarLong("SELECT count(*) FROM (SELECT production_no FROM " + t
                    + " GROUP BY production_no HAVING count(DISTINCT version_no) > 1) x");
            System.out.println("[AC-118②] " + t + " rows=" + n + " violations=" + v);
            checked++;
            if (v != 0) {
                bad.append("\n  ").append(t).append(" 违规组数=").append(v);
            }
        }
        assertTrue(checked > 0, notReady("AC-118",
                "全部带版本的 ds_cost_* 主表都是空表，②等于空跑", "灌数据方"));
        assertEquals("", bad.toString(), "AC-118②: 全部带版本核价主表都应满足『一个轴值一个版本』：" + bad);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-125（单点·漂移探针）26 张全版本视图列 = 主表列 + is_current，双向无差集
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(125)
    @DisplayName("AC-125: v_<主表>_all 视图列 = 主表列 + is_current（双向无差集），且覆盖全部带版本核价主表")
    void ac125_versionViewsHaveNoColumnDrift() {
        List<String> mains = versionedCostMainTables();
        System.out.println("[AC-125] 紧邻取基准：带 _history 的 ds_cost_* 主表 = " + mains.size() + " 张 " + mains);
        assertFalse(mains.isEmpty(), notReady("AC-125",
                "库里没有带 _history 的 ds_cost_* 主表", "task-260902 V405~V408"));
        assertEquals(26, mains.size(),
                "AC-125 前置：S-31① 说 26 张（基础核价 9 + 明细核价 17），实测=" + mains.size() + " " + mains
                        + "\n  数字对不上先停下来核对 task-260902 的表结构，别继续断言。");

        List<String> views = strList(
                "SELECT table_name FROM information_schema.views WHERE table_schema='public' "
                        + "AND table_name LIKE 'v\\_ds\\_cost\\_%\\_all' ORDER BY 1");
        System.out.println("[AC-125] 实际存在的全版本视图 = " + views.size() + " 个 " + views);

        StringBuilder err = new StringBuilder();
        int compared = 0;
        for (String main : mains) {
            String view = "v_" + main + "_all";
            List<String> viewCols = columnsOf(view);
            if (viewCols.isEmpty()) {
                err.append("\n  缺视图：").append(view)
                        .append("（S-31① 要求为每张带版本核价主表建全版本视图）");
                continue;
            }
            TreeSet<String> expected = new TreeSet<>(columnsOf(main));
            assertFalse(expected.isEmpty(), "AC-125: 主表 " + main + " 在 information_schema 查不到列");
            expected.add("is_current");

            TreeSet<String> actual = new TreeSet<>(viewCols);
            TreeSet<String> missing = new TreeSet<>(expected);
            missing.removeAll(actual);
            TreeSet<String> extra = new TreeSet<>(actual);
            extra.removeAll(expected);
            compared++;
            if (!missing.isEmpty() || !extra.isEmpty()) {
                err.append("\n  ").append(view).append(" 视图漏列=").append(missing).append(" 多列=").append(extra);
            }
        }
        System.out.println("[AC-125] 逐视图比对完成，比对数=" + compared);
        assertTrue(compared > 0, notReady("AC-125",
                "一个 v_ds_cost_*_all 视图都不存在，逐列比对等于空跑", "cpq-backend #1 / B-44①"));
        assertEquals("", err.toString(),
                "AC-125: 全版本视图与主表列必须无差集（视图列 = 主表列 + is_current）：" + err
                        + "\n  🚨 这是漂移探针：UNION 显式列举列 ⇒ task-260902 给主表加列时视图不会报错，"
                        + "只会**静默丢列**，对方的自检照样通过 ⇒ 新列永远查不到且零信号（D-84′）。"
                        + "\n  ⚠️ 本用例只验『不变量此刻成立』；AC-125 的另一半『启动期自检不一致则启动失败』"
                        + "由 cpq-backend #2 在克隆库 cpq_b42_flyway 上做过 A/B 实证（无漂移正常启动 / "
                        + "注入 ALTER TABLE ... ADD COLUMN drift_probe 后抛 IllegalStateException 并点名缺列），"
                        + "证据由主线在闸门 B 引用。⇒ 本条不是交付缺口。");

        // 反向：报价侧那 13 张不该建视图（S-31 明写「报价侧不建」）
        List<String> quoteViews = strList(
                "SELECT table_name FROM information_schema.views WHERE table_schema='public' "
                        + "AND table_name LIKE 'v\\_ds\\_quote\\_%\\_all' ORDER BY 1");
        assertTrue(quoteViews.isEmpty(),
                "AC-125 反向: 报价侧不建全版本视图（S-31：版本切换是核价侧独有功能），实际建了=" + quoteViews);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-126（反证型）::text 转换
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(126)
    @DisplayName("AC-126【反证】: 去掉 ::text 必须报 operator does not exist: integer = text；加上则正常执行")
    void ac126_versionColumnMustBeCastToText() {
        // 找一张有数据的带版本核价主表（不写死表名，紧邻从库里挑）
        String table = null;
        for (String t : versionedCostMainTables()) {
            if (scalarLong("SELECT count(*) FROM " + t) > 0) {
                table = t;
                break;
            }
        }
        assertNotNull(table, notReady("AC-126",
                "所有带版本核价主表都是空表，类型断言等于空跑", "灌数据方"));
        System.out.println("[AC-126] 选用表 = " + table);

        // 前置事实：version_no 真的是 integer（D-85 的前提）
        String type = scalarStr("SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name=?1 AND column_name='version_no'", table);
        System.out.println("[AC-126] " + table + ".version_no 的 PG 类型 = " + type);
        assertEquals("integer", type,
                "AC-126 前置：D-85 的整个理由是『ds_*.version_no 是 integer 而宏绑 text[]』。"
                        + "若类型变了，本条 AC 的前提就变了，请回主线复核，别自行改断言。");

        // ── ①【反证】去掉 ::text —— 复现 VersionFilterMacro 展开后的形状：
        //    (版本列) IS NOT DISTINCT FROM k.v，其中 k.v 来自 :__vfVer::text[]
        String falsify = "SELECT count(*) FROM " + table + " a "
                + "JOIN (SELECT unnest(CAST('{1}' AS text[])) AS v) k "
                + "ON (a.version_no) IS NOT DISTINCT FROM k.v";
        String errMsg = null;
        try {
            long n = scalarLong(falsify);
            fail("AC-126【反证失败】: 去掉 ::text 后本应报 `operator does not exist: integer = text`，"
                    + "但查询成功返回 " + n + " —— 说明这条反证没有真的破坏到它保护的条件，"
                    + "本条 AC 的『绿』不可信。SQL=\n" + falsify);
        } catch (Exception e) {
            errMsg = deepMessage(e);
            System.out.println("[AC-126 反证] 未加 ::text → 报错：" + errMsg);
        }
        assertTrue(errMsg != null && errMsg.contains("operator does not exist: integer = text"),
                "AC-126【反证】: 去掉 ::text 必须报『operator does not exist: integer = text』，"
                        + "实际报的是：" + errMsg
                        + "\n  报别的错说明我构造的反证形状与宏展开后的形状不一致，反证无效。");

        // ── ②【正向】加上 ::text 后正常执行，且不报该错
        String ok = "SELECT count(*) FROM " + table + " a "
                + "JOIN (SELECT unnest(CAST('{1}' AS text[])) AS v) k "
                + "ON (a.version_no::text) IS NOT DISTINCT FROM k.v";
        long okRows = scalarLong(ok);
        System.out.println("[AC-126 正向] 加 ::text → 返回 " + okRows + " 行");

        // ── ③【产物层】编译产物里的 :versionFilter 必须真的带上 ::text
        //    （②只证明了 PG 层面加了就行；③才证明编译器真的加了 —— 两者缺一不可）
        List<String> templatesWithMacro = strList(
                "SELECT sql_view_name FROM component_sql_view "
                        + "WHERE sql_template LIKE '%:versionFilter%' AND sql_template LIKE '%ds\\_cost\\_%' "
                        + "AND sql_template NOT LIKE '%version\\_no::text%'");
        assertTrue(templatesWithMacro.isEmpty(),
                "AC-126③: 已落库的、读 ds_cost_* 的视图里有 " + templatesWithMacro.size()
                        + " 个用了 :versionFilter 但版本列没加 ::text，它们在真实执行时必炸："
                        + templatesWithMacro
                        + "\n  （产物层的正向断言另见 AC-109②，本条只兜底存量）");
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-124 版本列表专用查询
    // 正向：返回集合 = 主表 ∪ _history 对该轴值的 DISTINCT version_no
    // 反向：该端点执行期间不得跑页签视图 SQL
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(1241)
    @DisplayName("AC-124①【数据层】D-86 专用查询语义：主表 ∪ _history 的 distinct version_no（用真实多版本料号）")
    void ac124a_dedicatedQuerySemantics() {
        String table = "ds_cost_basic_material_bom";
        // 紧邻挑一个真实的多版本轴值 —— 🚫 不写死 3120014539，也不写死它是第几版
        List<Object[]> cands = rowList(
                "SELECT production_no, count(DISTINCT version_no) FROM ("
                        + "  SELECT production_no, version_no FROM " + table
                        + "  UNION SELECT production_no, version_no FROM " + table + "_history) u "
                        + "GROUP BY 1 HAVING count(DISTINCT version_no) > 1 ORDER BY 2 DESC, 1");
        System.out.println("[AC-124①] 紧邻取基准：" + table + " 的多版本轴值 = " + fmt(cands));
        assertFalse(cands.isEmpty(), notReady("AC-124",
                table + " 里没有任何轴值同时在主表与 _history 出现过 —— 版本列表无从验证", "灌数据方"));

        String axis = String.valueOf(cands.get(0)[0]);
        TreeSet<String> union = new TreeSet<>(strList(
                "SELECT DISTINCT version_no::text FROM " + table + " WHERE production_no=?1 "
                        + "UNION SELECT DISTINCT version_no::text FROM " + table + "_history WHERE production_no=?1",
                axis));
        System.out.println("[AC-124①] 轴值=" + axis + " 的 主表∪_history distinct version_no = " + union);
        assertTrue(union.size() > 1,
                "AC-124①: 选中的轴值必须真有多个版本，否则『集合相等』会因为只有 1 个元素而失去证据力。实际=" + union);

        // D-86 明写的专用查询形态：两张表、一个轴列条件、零 JOIN
        TreeSet<String> dedicated = new TreeSet<>(strList(
                "SELECT version_no::text FROM " + table + " WHERE production_no=?1 "
                        + "UNION SELECT version_no::text FROM " + table + "_history WHERE production_no=?1", axis));
        assertEquals(union, dedicated,
                "AC-124①: D-86 的专用查询结果应等于 主表 ∪ _history 的 distinct version_no");
    }

    @Test
    @Order(1242)
    @DisplayName("AC-124②【端点层 + 反向】version-options 返回正确集合，且不跑页签视图 SQL（附阳性对照）")
    void ac124b_endpointDoesNotRunTabViewSql() {
        String coid = System.getenv("V9_COSTING_ORDER_ID");
        if (coid == null || coid.isBlank()) {
            coid = System.getProperty("V9_COSTING_ORDER_ID");
        }
        if (coid == null || coid.isBlank()) {
            fail(notReady("AC-124②",
                    "没有一张『组件读 ds_cost_* 新数据集』的核价单可用于调 "
                            + "GET /api/cpq/costing-orders/{coid}/version-options。"
                            + "实测当前 component_sql_view 150 个视图里引用 ds_* 的 = 0（N-16 明确不重绑 107 个存量视图），"
                            + "因此端点层无法被新数据集路径覆盖",
                    "主线：需裁决是否为本 AC 专门造一张绑新数据集组件的核价单夹具")
                    + "\n  💡 有可用核价单时：V9_COSTING_ORDER_ID=<uuid> ./mvnw test -Dtest=V9VersionAndDriftTest"
                    + "\n  🚨 在此之前 AC-124 的『端点层 + 反向断言』一律判定为【未验证】，"
                    + "🚫 不得因为 AC-124① 数据层是绿的就把整条 AC 记成通过。");
        }

        Response r = RestAssured.given().cookie("CPQ_SESSION", session())
                .when().get("/api/cpq/costing-orders/{coid}/version-options", coid);
        System.out.println("[AC-124②] version-options → HTTP " + r.statusCode() + " body=" + r.asString());
        assertEquals(200, r.statusCode(), "AC-124②: version-options 应 200，实际=" + r.statusCode()
                + " body=" + r.asString());
        assertFalse(r.asString().isBlank(), "AC-124②: 响应体为空，后续断言等于空跑");

        // 反向断言的取证手段说明（testing.md §4.4：断言「某事没发生」必须有阳性对照）
        //
        // 🚨 pg_stat_statements 在共享库 cpq_db_0724 上**未安装**（pg_extension 只有 plpgsql，
        //    实测 2026-09-03），装它要改 shared_preload_libraries + 重启 PG = 共享环境变更（§3.2），
        //    测试工程师没有批准权 ⇒ 「SQL 日志取证」这条路走不通。
        //
        // 可行的替代：把该组件自己的 sql_template 换成**保证执行必失败**的语句，
        // 再调本端点。若端点仍返回正确版本集合 ⇒ 它确实没跑页签视图 SQL（构造性证明，非统计证明）。
        // 阳性对照 = 先证明那条坏 SQL 真的一执行就报错。
        //
        // ⚠️ 这会临时改一行 component_sql_view.sql_template（共享库全局状态），必须在 finally 还原并自检还原成功。
        // 🚦 本步骤需要主线先给出 componentId —— 没有核价单夹具时它同样无从执行。
        fail("AC-124② 反向断言【未验证】：取证手段受限，见上方注释。"
                + "\n  已排除：pg_stat_statements 未安装且不可装（共享库 §3.2）。"
                + "\n  建议主线在下列二者中裁决其一："
                + "\n    (a) 由 cpq-backend #1 在实现里暴露一个可测的『页签视图 SQL 执行计数』（测试可读），或"
                + "\n    (b) 在克隆库上开 log_statement=all 跑一次，人工核对日志（需批准建克隆库）。"
                + "\n  🚫 在取得证据之前，不得把 AC-124 记成通过 —— D-86 的全部收益就在这条反向断言上。");
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    private static String deepMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        int guard = 0;
        while (c != null && guard++ < 12) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append(" | ");
            }
            c = c.getCause();
        }
        return sb.toString();
    }

    private static String fmt(List<Object[]> rows) {
        List<String> l = new ArrayList<>();
        for (Object[] r : rows) {
            l.add(java.util.Arrays.toString(r));
        }
        return l.toString();
    }
}
