package com.cpq.task260915;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260915 · 分片 S-B · <b>AC-13</b>：导入不引入 N+1。
 *
 * <p><b>AC-13 原文</b>：构造两个包，分别含 <b>5 个</b>和 <b>10 个</b>组件（形态相同，只是个数不同），
 * 各导入一次，分别统计<b>树身份判定环节</b>发出的 SQL 条数；断言两次条数<b>完全相等</b>且 <b>≤ 2</b>
 * （{@code TabSemanticResolver.isTreeTabBatch} 的既有承诺：固定 ≤2 条 SQL，与入参个数无关）。
 *
 * <h3>计数手法（🚫 不动任何全局配置）</h3>
 * {@code test.md §3 S-B} 明确禁止为了测这条去改共享配置文件。本类用<b>测试作用域</b>的
 * {@link Sb260915Profile}（{@code quarkus.hibernate-orm.statistics=true}）+ Hibernate
 * {@link Statistics#getQueries()} <b>按 SQL 文本</b>统计执行次数 —— 手法与既有
 * {@code SqlCountNPlusOneGuardTest} / {@code Ac8NoN1Test} 完全一致（那两处已实证：裸
 * {@code em.createNativeQuery} 同样按查询文本被记录，不受"日志没打印"的盲区影响）。
 *
 * <p>「树身份判定环节」的口径 = <b>命中 {@code semantic_tab_view} 的查询</b>：需求文档 §④ 记载分支①
 * 的解析依赖就是这张表。这是黑盒代理指标，不读实现代码。
 *
 * <h3>三条防空跑</h3>
 * <ol>
 *   <li><b>计数器自证</b>：先断言 {@code Statistics} 这一轮确实抓到了查询（抓到 0 条 ⇒ 统计没开，
 *       后面"两次相等"是两个 0 相等，纯假绿）。</li>
 *   <li><b>路径自证</b>：两次导入都必须真的落库 5 / 10 个组件（导入 400 了就不存在"判定环节"）。</li>
 *   <li><b>判别力自证</b>：夹具组件<b>带 tree_ref 且带 builderConfig</b> —— 这正是必须走树身份判定的形态。
 *       若命中条数为 0，本测试<b>失败</b>并要求人工归因：要么判定没走 semantic_tab_view（口径要改），
 *       要么闸门根本没执行（断言空跑）。🚫 不许直接把 0 当"没有 N+1"结案。</li>
 * </ol>
 *
 * <p><b>⚠️ 未验证项</b>：AC-13 的「反向断言：改动前的实现在这一环节发出的条数为 0」需要在改动前的代码上
 * 跑一次，本片<b>不做</b>（不在工作区里还原旧实现）。已在回报里标注「未验证」。
 */
@QuarkusTest
@TestProfile(Sb260915Profile.class)
@DisplayName("task-260915 S-B · AC-13 导入的树身份判定不随组件数增长")
class Ac13TreeResolveSqlCountTest extends Sb260915TestBase {

    /** 「树身份判定环节」的黑盒口径：命中这张表的查询（需求文档 §④：分支①解析依赖 semantic_tab_view）。 */
    private static final String TREE_RESOLVE_TABLE = "semantic_tab_view";

    /** 实查 cpq_db_test：坐标 (BOM,'',QUOTE) 存在且 ACTIVE ⇒ 树身份成立，导入不该被闸门拒。 */
    private static final String BOM_BUILDER_CONFIG =
            "{\"dialect\":\"QUOTE\",\"tabType\":\"BOM\",\"switches\":null,\"axisScope\":\"CLOSURE\","
                    + "\"variantKey\":\"\",\"priceStrategy\":null,\"builderVersion\":1,\"columns\":[]}";

    @Test
    @DisplayName("AC-13: 5 组件包与 10 组件包，命中 semantic_tab_view 的 SQL 条数相等且 ≤2")
    void treeIdentityResolutionSqlCountIsIndependentOfComponentCount() throws Exception {
        Statistics st = statistics();

        UUID dir5 = createDirectory("AC13-N5");
        UUID dir10 = createDirectory("AC13-N10");

        String pack5 = buildTreePack(5);
        String pack10 = buildTreePack(10);

        // ── 第一次：5 个组件 ──────────────────────────────────────────────
        st.clear();
        Response r5 = commit(dir5, pack5, "?conflictPolicy=RENAME");
        long queries5 = st.getQueries().length;
        long treeSql5 = countQueriesTouching(st, TREE_RESOLVE_TABLE, "N=5");
        assertEquals(200, r5.statusCode(),
                "AC-13 前置：5 组件包必须导入成功，否则统计的是失败路径。HTTP " + r5.statusCode()
                        + " body=" + r5.asString());
        assertEquals(5L, componentCountIn(dir5), "AC-13 前置：5 个组件应全部落库（本片私有目录）");

        // ── 第二次：10 个组件 ─────────────────────────────────────────────
        st.clear();
        Response r10 = commit(dir10, pack10, "?conflictPolicy=RENAME");
        long queries10 = st.getQueries().length;
        long treeSql10 = countQueriesTouching(st, TREE_RESOLVE_TABLE, "N=10");
        assertEquals(200, r10.statusCode(),
                "AC-13 前置：10 组件包必须导入成功。HTTP " + r10.statusCode() + " body=" + r10.asString());
        assertEquals(10L, componentCountIn(dir10), "AC-13 前置：10 个组件应全部落库（本片私有目录）");

        System.out.println("[AC-13] N=5  : 统计到查询种类=" + queries5 + " 命中 " + TREE_RESOLVE_TABLE + " 的执行次数=" + treeSql5);
        System.out.println("[AC-13] N=10 : 统计到查询种类=" + queries10 + " 命中 " + TREE_RESOLVE_TABLE + " 的执行次数=" + treeSql10);

        // 防空跑 ①：统计机制自身必须生效
        assertTrue(queries5 > 0 && queries10 > 0,
                "Hibernate Statistics 一条查询都没抓到（N=5 抓到 " + queries5 + " 种，N=10 抓到 " + queries10
                        + " 种）⇒ quarkus.hibernate-orm.statistics 没生效，下面「两次相等」是两个 0 相等的假绿。"
                        + "先修计数手段，不要读结论");

        // 防空跑 ③：判定环节必须真的被执行到
        assertTrue(treeSql10 >= 1,
                "命中 " + TREE_RESOLVE_TABLE + " 的查询为 0 —— 两种可能，都不是「通过」：\n"
                        + " (a) 树身份判定不走 semantic_tab_view ⇒ 本测试的黑盒口径要改（报主线，别改断言了事）；\n"
                        + " (b) 导入根本没执行树身份判定 ⇒ 夹具没带上 tree_ref/builderConfig，断言空跑。\n"
                        + "夹具形态：每个组件都复制自素材 COMP-0002（含 2 处 tree_ref）且 sqlViews[0] 带 "
                        + "builderConfig(tabType=BOM)");

        // AC-13 正题
        assertEquals(treeSql5, treeSql10,
                "AC-13：树身份判定环节的 SQL 条数应与组件个数无关，实际 N=5 为 " + treeSql5
                        + " 条、N=10 为 " + treeSql10 + " 条 —— 随 N 增长即逐组件查库（N+1）");
        assertTrue(treeSql10 <= 2,
                "AC-13：TabSemanticResolver.isTreeTabBatch 的既有承诺是固定 ≤2 条 SQL，实际 " + treeSql10 + " 条");
    }

    // ────────────────────────────────────────────────────────────────────

    private Statistics statistics() {
        Statistics s = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        return s;
    }

    /**
     * 命中某张表的查询的<b>执行次数合计</b>（不是查询种类数 —— N+1 的表现正是同一条文本执行 N 次）。
     *
     * <p>⚠️ 匹配同时认 <b>SQL 表名</b>（{@code semantic_tab_view}，裸 native query 的统计 key）
     * 与 <b>实体名</b>（{@code SemanticTabView}，Panache/HQL 的统计 key 是 HQL 文本，里面没有表名）。
     * 首轮只按表名匹配，两次都数到 0 —— 那是<b>本测试的计量口径漏了 HQL 这一路</b>，
     * 不是"没有 N+1"（见 260915 第一轮执行日志）。
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
                        q.length() > 300 ? q.substring(0, 300) + "…" : q);
            }
        }
        return total;
    }

    /** {@code semantic_tab_view} → {@code semantictabview}（HQL 里出现的是实体名，全小写后比对）。 */
    private static String entityNameOf(String table) {
        StringBuilder sb = new StringBuilder();
        for (String part : table.split("_")) {
            sb.append(part);
        }
        return sb.toString();
    }

    /** 把这一轮统计到的<b>全部</b>查询打出来 —— 计数为 0 时，唯一能分清"口径错"与"没发生"的证据。 */
    private void dumpAllQueries(Statistics st, String tag) {
        String[] qs = st.getQueries();
        System.out.println("  [" + tag + "] 本轮统计到的查询共 " + qs.length + " 种:");
        for (String q : qs) {
            System.out.println("    exec=" + st.getQueryStatistics(q).getExecutionCount() + " | "
                    + (q.length() > 300 ? q.substring(0, 300) + "…" : q));
        }
    }

    /**
     * 造一个含 {@code n} 个"树页签 + tree_ref 公式"组件的 1.1 包：逐个复制素材里的 COMP-0002
     * （真实的 18 字段 / 13 公式 / 含 2 处 tree_ref），换新 id 与唯一 code，并给它的 sqlView 补上
     * {@code builderConfig} + {@code builderVersion}（1.0 老包没有这两项 —— 那正是本任务修的缺陷，
     * 而 AC-13 要测的是修好之后的判定路径）。
     */
    private String buildTreePack(int n) {
        ObjectNode legacy = loadLegacyBundle();
        JsonNode template = null;
        for (JsonNode c : legacy.path("components")) {
            if ("COMP-0002".equals(c.path("code").asText())) {
                template = c;
            }
        }
        assertTrue(template != null, "前置：素材里找不到 COMP-0002，AC-13 的夹具造不出来");
        assertTrue(template.toString().contains("tree_ref"),
                "前置：COMP-0002 必须含 tree_ref —— 没有树 token 就不会触发树身份判定，本测试空跑");

        String stamp = UUID.randomUUID().toString().substring(0, 8);
        ArrayNode comps = M.createArrayNode();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ObjectNode item = template.deepCopy();
            String code = PREFIX + "N1-" + stamp + "-" + i;
            codes.add(code);
            item.put("id", UUID.randomUUID().toString());
            item.put("code", code);
            item.put("name", PREFIX + "N1 物料副本 " + i);
            for (JsonNode v : item.path("sqlViews")) {
                ObjectNode view = (ObjectNode) v;
                view.set("builderConfig", readJson(BOM_BUILDER_CONFIG));
                view.put("builderVersion", 1);
                view.put("status", "ACTIVE");
            }
            comps.add(item);
        }

        ObjectNode bundle = M.createObjectNode();
        bundle.put("bundleVersion", "1.1");
        bundle.set("components", comps);
        ObjectNode deps = M.createObjectNode();
        deps.set("globalVariables", M.createArrayNode());
        deps.set("datasources", M.createArrayNode());
        bundle.set("dependencies", deps);
        return bundle.toString();
    }

    private JsonNode readJson(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
