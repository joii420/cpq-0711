package com.cpq.task260915;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260915 · 分片 S-B · <b>AC-20</b>：导出端 N+1 消除（开工后扩范围，用户裁决本期顺手修）。
 *
 * <p><b>起因</b>：{@code ComponentExportService:88} 的视图查询在组件循环里，导出 86 个组件 = 86 次查库。
 * <b>不是本次引入的缺陷</b>。
 *
 * <p><b>AC-20 断言</b>：导出过程中针对 {@code component_sql_view} 的 SQL 条数<b>与组件数无关</b> ——
 * 组件数 10 的目录与组件数 86 的目录，该查询条数<b>相等且 ≤ 2</b>。
 *
 * <h3>为什么夹具用自建目录而不是直接拿 {@code 取值配置器测试}</h3>
 * AC-20 前置写的「86 个组件」是 {@code cpq_db_0724} 的实查值；{@code cpq_db_test} 里该目录只有
 * <b>9 个</b>（同 AC-12 的 6 vs 5）。硬拿它当"86 组件目录"会得到一条与实现无关的红。
 * 故本类<b>自建</b> 10 / 86 两个私有目录（AC 原文的两个数量级），另对基准目录做一次<b>只读</b>测量
 * （组件数现查、不硬编码），三次条数互相比对。
 *
 * <h3>计数手法</h3>
 * 同 AC-13：测试作用域 {@link Sb260915Profile}（{@code quarkus.hibernate-orm.statistics=true}）
 * + {@link Statistics#getQueries()} 按 SQL 文本累计执行次数。🚫 没有改任何共享配置文件。
 *
 * <h3>⚠️ 未验证项</h3>
 * AC-20 的「<b>不回归</b>：导出结果与改动前逐字段相等」需要拿到<b>改动前</b>的导出结果，
 * 即跨代码版本的 A/B。本片<b>不做</b>（在同一 worktree 里还原旧实现会干扰后端工程师，已报主线另行安排）。
 * 本类的 T3 只负责<b>把改动后的导出快照落盘成证据</b>，供主线做 A/B 比对。
 */
@QuarkusTest
@TestProfile(Sb260915Profile.class)
@DisplayName("task-260915 S-B · AC-20 导出端不随组件数发查询")
class Ac20ExportSqlViewN1Test extends Sb260915TestBase {

    /** AC-20 的计数口径：命中这张表的查询。 */
    private static final String VIEW_TABLE = "component_sql_view";

    private static final int SMALL = 10;
    private static final int LARGE = 86;

    @Test
    @DisplayName("AC-20-a: 导出 10 组件目录与 86 组件目录，针对 component_sql_view 的 SQL 条数相等且 ≤2")
    void exportSqlViewQueryCountIsIndependentOfComponentCount() throws Exception {
        Statistics st = statistics();

        UUID small = createDirectory("AC20-N" + SMALL);
        UUID large = createDirectory("AC20-N" + LARGE);
        bulkInsertComponentsWithView(small, SMALL);
        bulkInsertComponentsWithView(large, LARGE);

        // 前置：两个目录的组件数必须真的不同，否则"条数相等"没有判别力
        assertEquals((long) SMALL, componentCountIn(small), "前置：小目录应有 " + SMALL + " 个组件");
        assertEquals((long) LARGE, componentCountIn(large), "前置：大目录应有 " + LARGE + " 个组件");

        st.clear();
        Response rs = exportDirectory(small);
        long kinds1 = st.getQueries().length;
        long viewSqlSmall = countQueriesTouching(st, VIEW_TABLE, "N=" + SMALL);
        assertEquals(200, rs.statusCode(), "导出小目录应 200，body=" + rs.asString());
        assertEquals(SMALL, M.readTree(rs.asString()).path("components").size(),
                "前置：小目录应导出 " + SMALL + " 个组件（导出不按 status 过滤）");

        st.clear();
        Response rl = exportDirectory(large);
        long kinds2 = st.getQueries().length;
        long viewSqlLarge = countQueriesTouching(st, VIEW_TABLE, "N=" + LARGE);
        assertEquals(200, rl.statusCode(), "导出大目录应 200，body 前 500 字=" 
                + rl.asString().substring(0, Math.min(500, rl.asString().length())));
        assertEquals(LARGE, M.readTree(rl.asString()).path("components").size(),
                "前置：大目录应导出 " + LARGE + " 个组件");

        System.out.println("[AC-20-a] N=" + SMALL + " 查询种类=" + kinds1 + " 命中 " + VIEW_TABLE + " 执行次数=" + viewSqlSmall);
        System.out.println("[AC-20-a] N=" + LARGE + " 查询种类=" + kinds2 + " 命中 " + VIEW_TABLE + " 执行次数=" + viewSqlLarge);

        // 防空跑 ①：统计机制自身生效
        assertTrue(kinds1 > 0 && kinds2 > 0,
                "Hibernate Statistics 一条查询都没抓到（" + kinds1 + " / " + kinds2 + " 种）⇒ 统计没开，"
                        + "下面的「相等」是两个 0 相等的假绿");

        // 防空跑 ②：这张表必须真的被查过
        assertTrue(viewSqlLarge >= 1,
                "命中 " + VIEW_TABLE + " 的查询为 0 —— 两种可能，都不是「通过」：\n"
                        + " (a) 计数口径错（导出读视图走的不是这张表名，或不经 Hibernate 统计）⇒ 报主线改口径；\n"
                        + " (b) 查询根本没发生（导出没读视图）⇒ 夹具的 86 个组件每个都挂了视图，"
                        + "真没读的话导出内容也该是空的，先看上面导出的 sqlViews。\n"
                        + "🚫 不许把 0 当「没有 N+1」结案");

        assertEquals(viewSqlSmall, viewSqlLarge,
                "AC-20：导出对 " + VIEW_TABLE + " 的查询条数应与组件数无关，实际 N=" + SMALL + " 为 "
                        + viewSqlSmall + " 条、N=" + LARGE + " 为 " + viewSqlLarge + " 条 —— "
                        + "随 N 增长即组件循环里逐个查视图（原 ComponentExportService:88 的形态）");
        assertTrue(viewSqlLarge <= 2,
                "AC-20：批量化后该查询应固定 ≤2 条，实际 " + viewSqlLarge + " 条");
    }

    @Test
    @DisplayName("AC-20-b: 基准目录「取值配置器测试」只读导出 —— 条数同样 ≤2（组件数现查，不硬编码 86）")
    void baselineDirectoryExportAlsoConstant() throws Exception {
        Statistics st = statistics();
        UUID baseline = directoryIdByName("取值配置器测试");

        // 导出不按 status 过滤 ⇒ 期望组件数也不过滤（AC-20 前置原文）
        long comps = ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM component WHERE directory_id = :dir")
                .setParameter("dir", baseline).getSingleResult()).longValue();
        assertTrue(comps > 0, "前置：基准目录一个组件都没有 ⇒ 本条空跑");

        st.clear();
        Response r = exportDirectory(baseline);
        long viewSql = countQueriesTouching(st, VIEW_TABLE, "基准目录");
        assertEquals(200, r.statusCode(), "导出基准目录应 200");
        JsonNode bundle = M.readTree(r.asString());
        assertEquals(comps, bundle.path("components").size(),
                "导出组件数应等于目录里的组件数（导出不按 status 过滤）：DB 现查 " + comps
                        + "，导出 " + bundle.path("components").size());

        System.out.println("[AC-20-b] 基准目录组件数=" + comps + " 命中 " + VIEW_TABLE + " 执行次数=" + viewSql);
        assertTrue(viewSql >= 1, "命中 " + VIEW_TABLE + " 的查询为 0 —— 同 AC-20-a 的两种归因，不许当通过");
        assertTrue(viewSql <= 2, "AC-20：基准目录导出对 " + VIEW_TABLE + " 的查询条数应 ≤2，实际 " + viewSql);
    }

    @Test
    @DisplayName("AC-20-c（证据产出）: 把基准目录的导出快照落盘，供主线做「改动前 vs 改动后」A/B 比对")
    void dumpExportSnapshotForCrossVersionDiff() throws Exception {
        UUID baseline = directoryIdByName("取值配置器测试");
        Response r = exportDirectory(baseline);
        assertEquals(200, r.statusCode(), "导出应 200");

        JsonNode bundle = M.readTree(r.asString());
        assertTrue(bundle.path("components").size() > 0,
                "前置：快照里必须有组件，空快照做不了 A/B 比对");

        Path out = evidenceDir().resolve("AC-20-导出快照-改动后-" + baseline + ".json");
        Files.writeString(out, bundle.toPrettyString(), StandardCharsets.UTF_8);
        System.out.println("[AC-20-c] 导出快照已落盘: " + out.toAbsolutePath()
                + "（组件数=" + bundle.path("components").size() + "）");
        assertTrue(Files.size(out) > 0, "快照文件为空");

        // ⚠️ 本条不是「不回归」的结论：它只产出改动后的一半证据。
        // 另一半（改动前的同一目录导出）必须在改动前的代码版本上跑，由主线安排 A/B。
    }

    // ────────────────────────────────────────────────────────────────────

    private Statistics statistics() {
        Statistics s = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        return s;
    }

    /**
     * ⚠️ 同时认 SQL 表名（{@code component_sql_view}）与实体名（{@code ComponentSqlView}）——
     * Panache/HQL 查询在 {@link Statistics#getQueries()} 里的 key 是 <b>HQL 文本</b>，里面没有表名。
     * 首轮只按表名匹配数到 0，那是计量口径漏了 HQL 这一路，不是"没有查询"。
     */
    private long countQueriesTouching(Statistics st, String table, String tag) {
        String entity = entityNameOf(table);
        long total = 0;
        dumpAllQueries(st, tag);
        for (String q : st.getQueries()) {
            String lower = q.toLowerCase();
            if (lower.contains(table) || lower.contains(entity)) {
                long c = st.getQueryStatistics(q).getExecutionCount();
                total += c;
                System.out.printf("  [%s][命中 %s] exec=%d sql=%s%n", tag, table, c,
                        q.length() > 200 ? q.substring(0, 200) + "…" : q);
            }
        }
        return total;
    }

    /** {@code component_sql_view} → {@code componentsqlview}（HQL 里出现的是实体名）。 */
    private static String entityNameOf(String table) {
        StringBuilder sb = new StringBuilder();
        for (String part : table.split("_")) {
            sb.append(part);
        }
        return sb.toString();
    }

    /** 把这一轮统计到的全部查询打出来 —— 计数为 0 时唯一能分清「口径错」与「没发生」的证据。 */
    private void dumpAllQueries(Statistics st, String tag) {
        String[] qs = st.getQueries();
        System.out.println("  [" + tag + "] 本轮统计到的查询共 " + qs.length + " 种:");
        for (String q : qs) {
            System.out.println("    exec=" + st.getQueryStatistics(q).getExecutionCount() + " | "
                    + (q.length() > 300 ? q.substring(0, 300) + "…" : q));
        }
    }

    /** 一个事务里批量插 n 个组件，每个挂 1 条 SQL 视图（逐个开事务插 96 行太慢）。 */
    private void bulkInsertComponentsWithView(UUID dirId, int n) {
        String stamp = UUID.randomUUID().toString().substring(0, 8);
        inTx(() -> {
            for (int i = 0; i < n; i++) {
                UUID cid = UUID.randomUUID();
                em.createNativeQuery(
                                "INSERT INTO component(id, directory_id, name, code, column_count, fields, formulas, "
                                        + "excel_columns, component_type, status, created_at, updated_at) VALUES "
                                        + "(:id, :dir, :name, :code, 0, '[]', '[]', '[]', 'NORMAL', 'ACTIVE', NOW(), NOW())")
                        .setParameter("id", cid)
                        .setParameter("dir", dirId)
                        .setParameter("name", PREFIX + "AC20 组件 " + i)
                        .setParameter("code", PREFIX + "AC20-" + stamp + "-" + i)
                        .executeUpdate();
                em.createNativeQuery(
                                "INSERT INTO component_sql_view(id, component_id, sql_view_name, sql_template, "
                                        + "declared_columns, required_variables, scope, status, created_at, updated_at) "
                                        + "VALUES (:id, :cid, :vn, 'SELECT 1 AS x', '[]', '{}', 'COMPONENT', "
                                        + "'ACTIVE', NOW(), NOW())")
                        .setParameter("id", UUID.randomUUID())
                        .setParameter("cid", cid)
                        .setParameter("vn", "rt_sb_ac20_v" + i)
                        .executeUpdate();
            }
            return 0;
        });
    }

    /** 任务目录下的 证据/ 目录（从 user.dir 上溯定位 worktree 根）。 */
    private static Path evidenceDir() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++, cur = cur.getParent()) {
            Path p = cur.resolve("dev-docs").resolve("task-260915-组件导出导入往返保真").resolve("证据");
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("定位不到任务目录下的 证据/（user.dir=" + Path.of("").toAbsolutePath() + "）");
    }
}
