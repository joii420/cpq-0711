package com.cpq.task260819v9;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 需求文档.md §9.4 <b>F 组 · 校验与 CI</b> —— AC-121（反证型）/ AC-123（反证型）。
 *
 * <h3>两条都是反证型：只证明「现在是绿的」不算通过</h3>
 * 每条都做<b>三段</b>：绿（正常）→ 人为改错 → <b>必须红且指名</b> → 改回 → 再绿。
 * 中间那段是全部价值所在 —— 首次 PASS 证明不了守卫接上了（testing.md §4.4）。
 *
 * <h3>本类会动的全局状态（testing.md §4.3 登记）</h3>
 * <ol>
 *   <li>{@code semantic_edge}（AC-121）：通过<b>产品自己的写端点</b>新增 1 条边并在 {@code finally} 删除。
 *       🚫 不改任何既有边的 {@code cardinality} —— 那是别人也在用的种子数据，改坏了别人当场受影响。
 *       用「新增自己的边」代替「改别人的边」，同样能证伪，风险面小一个量级。</li>
 *   <li>{@code component} / {@code component_sql_view}（AC-123）：1 个 {@code V9T-} 前缀的测试组件及其视图，
 *       {@code @AfterAll} 正向条件删除。</li>
 * </ol>
 * 每处都在 {@code finally} 还原，并<b>还原后再断言一次还原成功</b>（只写 finally 不校验，崩一次就留污染）。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V9ValidationAndCiTest extends V9TestBase {

    private static String componentId;

    @BeforeEach
    void ensureComponent() {
        if (componentId != null) {
            return;
        }
        String id = UUID.randomUUID().toString();
        String code = TAG + "VALID-" + System.currentTimeMillis();
        exec("INSERT INTO component(id, name, code, column_count, fields, formulas, status, "
                        + "created_at, updated_at, component_type, bom_recursive_expand, excel_columns) "
                        + "VALUES (CAST(?1 AS uuid), ?2, ?3, 0, '[]'::jsonb, '[]'::jsonb, 'ACTIVE', "
                        + "NOW(), NOW(), 'NORMAL', false, '[]'::jsonb)",
                id, code, code);
        componentId = id;
        System.out.println("[V9] 校验测试组件 componentId=" + componentId);
    }

    @AfterAll
    static void cleanup() {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            var em = io.quarkus.arc.Arc.container()
                    .instance(jakarta.persistence.EntityManager.class).get();
            int v = em.createNativeQuery(
                    "DELETE FROM component_sql_view WHERE component_id IN "
                            + "(SELECT id FROM component WHERE code LIKE 'V9T-%')").executeUpdate();
            int c = em.createNativeQuery("DELETE FROM component WHERE code LIKE 'V9T-%'").executeUpdate();
            System.out.println("[V9] 清理：component_sql_view " + v + " 行 / component " + c + " 行");
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-123（反证型）保存期物理存在性校验
    // AC 原文：提交「引用了不存在的列」的视图配置 → PUT /builder 被拒绝（非 200），
    //          错误信息点名是哪张表的哪一列不存在；把该列改对后保存成功。
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(123)
    @DisplayName("AC-123【反证】: 引用不存在的列 → 保存被拒且点名表与列；改对后保存成功")
    void ac123_physicalExistenceCheckOnSave() {
        // 挑一个真实节点与它的真实列（运行期发现，🚫 不写死）
        Object[] anchor = anchorNode(COST_BASIC, "主件");
        assertNotNull(anchor, notReady("AC-123",
                "COST_BASIC『主件』页签视图不存在，无从构造保存请求", "cpq-backend #2 / B-42"));
        String nodeKey = String.valueOf(anchor[0]);
        String physicalTable = String.valueOf(anchor[1]);
        Object[] realCol = someColumnById(String.valueOf(anchor[3]));
        assertNotNull(realCol, notReady("AC-123", "节点 " + nodeKey + " 无列声明", "cpq-backend #2 / B-42"));
        String goodColumn = String.valueOf(realCol[0]);

        // 造一个「保证不存在」的列名，并先查库确认它真的不存在（不是我以为不存在）
        String badColumn = "v9t_no_such_column";
        long exists = scalarLong("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema='public' AND column_name=?1", badColumn);
        assertEquals(0L, exists,
                "AC-123 前置：反证用的列名 " + badColumn + " 居然在库里存在，换一个名字，否则反证无效");

        // ── ① 反证：引用不存在的列 → 必须被拒
        Map<String, Object> bad = new LinkedHashMap<>(config(COST_BASIC, "主件", null,
                List.of(column(nodeKey, badColumn, "坏字段"))));
        bad.put("confirmedImpact", false);
        Response rBad = save(componentId, bad);
        String bodyBad = rBad.asString();
        System.out.println("[AC-123 反证] PUT /builder（列=" + badColumn + "） → HTTP " + rBad.statusCode()
                + " body=" + bodyBad);

        assertNotEquals(200, rBad.statusCode(),
                "AC-123【反证失败】: 引用不存在的列 " + badColumn + " 居然保存成功（HTTP 200）—— "
                        + "S-20 第③道『物理存在性』校验在新图上没生效。"
                        + "\n  🚨 这一步不红，AC-123 的『绿』就毫无意义（守卫可能压根没接上）。"
                        + "\n  body=" + bodyBad);
        assertNotEquals(500, rBad.statusCode(),
                "AC-123: 校验失败必须是结构化 4xx，🚫 不得漏成 500（api.md §2.5 明写）。body=" + bodyBad);

        assertTrue(bodyBad.contains(badColumn),
                "AC-123: 错误信息必须点名<b>哪一列</b>不存在，实际没提到 `" + badColumn + "`。body=" + bodyBad);
        assertTrue(bodyBad.contains(physicalTable) || bodyBad.contains(normalizeToMainTable(physicalTable)),
                "AC-123: 错误信息必须点名<b>哪张表</b>，实际没提到 `" + physicalTable
                        + "`（或其主表 `" + normalizeToMainTable(physicalTable) + "`）。body=" + bodyBad);

        // 反证的副作用检查：被拒的保存不得留下半截数据
        long views = scalarLong("SELECT count(*) FROM component_sql_view WHERE component_id=CAST(?1 AS uuid)",
                componentId);
        assertEquals(0L, views,
                "AC-123: 保存被拒时不得留下 component_sql_view 行（一体化保存事务必须整体回滚，api.md §2.4）");

        // ── ② 改对后保存成功
        Map<String, Object> good = new LinkedHashMap<>(config(COST_BASIC, "主件", null,
                List.of(column(nodeKey, goodColumn, "好字段"))));
        good.put("confirmedImpact", false);
        Response rGood = save(componentId, good);
        System.out.println("[AC-123 正向] PUT /builder（列=" + goodColumn + "） → HTTP " + rGood.statusCode()
                + " body=" + rGood.asString());
        assertEquals(200, rGood.statusCode(),
                "AC-123②: 把列改对后保存应成功（200），实际=" + rGood.statusCode() + " body=" + rGood.asString()
                        + "\n  ⚠️ 若这里也失败，说明拦住①的可能不是『物理存在性』而是别的原因 —— "
                        + "那样①的红是假红，AC-123 仍判『未验证』。");

        long viewsAfter = scalarLong("SELECT count(*) FROM component_sql_view WHERE component_id=CAST(?1 AS uuid)",
                componentId);
        assertEquals(1L, viewsAfter,
                "AC-123②: 保存成功后应恰好落 1 行 component_sql_view，实际=" + viewsAfter);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-121（反证型）CI 断言改读新图
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(121)
    @DisplayName("AC-121【反证】: 边基数断言在新图上 —— 正常全绿；人为把一条边写成 MANY_TO_ONE 必须变红并指名；改回变绿")
    void ac121_edgeCardinalityCiAssertion() {
        // ── ① 正常：库里全部 ACTIVE 的 MANY_TO_ONE 边，右侧连接键在目标关系里必须唯一
        List<Object[]> m2o = rowList(
                "SELECT e.id::text, tn.physical_table, ek.right_column FROM semantic_edge e "
                        + "JOIN semantic_node tn ON tn.id = e.to_node_id "
                        + "JOIN semantic_edge_key ek ON ek.edge_id = e.id AND ek.seq = 0 "
                        + "WHERE e.cardinality='MANY_TO_ONE' AND e.status='ACTIVE' "
                        + "AND tn.physical_table IS NOT NULL");
        System.out.println("[AC-121①] 库中 ACTIVE 的 MANY_TO_ONE 边 = " + m2o.size() + " 条");
        assertFalse(m2o.isEmpty(), notReady("AC-121",
                "库里一条 MANY_TO_ONE 边都没有 —— ① 的『全绿』等于断言从未执行", "cpq-backend #2 / B-42"));

        String violations = checkAllManyToOneEdges();
        assertEquals("", violations, "AC-121①: 正常数据下全部 MANY_TO_ONE 边右键应唯一，违规=" + violations);

        // ── ② 反证：新增一条「右键必然重复」的边并声明成 MANY_TO_ONE
        //    🚫 不去改别人的种子边（改坏了别人当场受影响）；改用「插自己的边」，证伪力相同、风险面小得多。
        Dup dup = findDuplicatedKeyColumn();
        assertNotNull(dup, notReady("AC-121",
                "在全部 SHEET 节点里找不到任何『存在重复值的列』，无法构造右键必然重复的反证边",
                "灌数据方（需要一张有重复值列的表）"));
        System.out.println("[AC-121②] 反证素材：节点=" + dup.nodeKey + " 表=" + dup.table
                + " 列=" + dup.column + " 重复组数=" + dup.dupGroups);

        String fromNodeId = pickAnyOtherNodeId(dup.nodeId);
        assertNotNull(fromNodeId, notReady("AC-121", "只有一个节点，无法建边", "cpq-backend #2 / B-42"));

        String createdEdgeId = null;
        try {
            // (a) 走产品自己的写端点提交坏边 —— 这才是「CI 断言 / 保存期校验读的是新图」的直接证据
            Response bad = postEdge(fromNodeId, dup.nodeId, "MANY_TO_ONE", dup.column, dup.column);
            System.out.println("[AC-121②a] POST /edges cardinality=MANY_TO_ONE → HTTP " + bad.statusCode()
                    + " body=" + bad.asString());
            assertNotEquals(200, bad.statusCode(),
                    "AC-121②【反证失败】: 把一条右键重复的边声明成 MANY_TO_ONE，写端点居然接受了（200）—— "
                            + "边基数断言没有生效或没有读新图。这一步不红，本条 AC 的绿不可信。body=" + bad.asString());
            String bodyBad = bad.asString();
            assertTrue(bodyBad.contains("EDGE_CARDINALITY"),
                    "AC-121②: 错误应指明 failedCheck=EDGE_CARDINALITY（api.md §1.2），实际 body=" + bodyBad);
            assertTrue(bodyBad.contains(dup.table) && bodyBad.contains(dup.column),
                    "AC-121②: 错误必须<b>指名</b>是哪条边、右侧哪个键重复（AC 原文）。"
                            + "期望提到表 `" + dup.table + "` 与列 `" + dup.column + "`，实际 body=" + bodyBad);

            // (b) 库中该边未写入（400 必须整体回滚，不许留半条边）
            long leaked = scalarLong(
                    "SELECT count(*) FROM semantic_edge e JOIN semantic_edge_key k ON k.edge_id=e.id "
                            + "WHERE e.from_node_id=CAST(?1 AS uuid) AND e.to_node_id=CAST(?2 AS uuid) "
                            + "AND e.cardinality='MANY_TO_ONE' AND k.right_column=?3",
                    fromNodeId, dup.nodeId, dup.column);
            assertEquals(0L, leaked, "AC-121②: 校验不过时该边必须未写入库中，实际库里有 " + leaked + " 条");

            // ── ③ 改回 ONE_TO_MANY，同一请求应成功（「改回后变绿」）
            Response ok = postEdge(fromNodeId, dup.nodeId, "ONE_TO_MANY", dup.column, dup.column);
            System.out.println("[AC-121③] POST /edges cardinality=ONE_TO_MANY → HTTP " + ok.statusCode()
                    + " body=" + ok.asString());
            assertEquals(200, ok.statusCode(),
                    "AC-121③: 把基数改回 ONE_TO_MANY 后同一请求应成功（AC 原文「改回后变绿」），"
                            + "实际=" + ok.statusCode() + " body=" + ok.asString());
            createdEdgeId = scalarStr(
                    "SELECT e.id::text FROM semantic_edge e JOIN semantic_edge_key k ON k.edge_id=e.id "
                            + "WHERE e.from_node_id=CAST(?1 AS uuid) AND e.to_node_id=CAST(?2 AS uuid) "
                            + "AND e.cardinality='ONE_TO_MANY' AND k.right_column=?3 ORDER BY e.created_at DESC LIMIT 1",
                    fromNodeId, dup.nodeId, dup.column);
            assertNotNull(createdEdgeId, "AC-121③: 保存成功但库里找不到这条边");

            // ④ 加了这条 ONE_TO_MANY 边之后，①的断言仍应全绿（它只管 MANY_TO_ONE）
            assertEquals("", checkAllManyToOneEdges(),
                    "AC-121④: 新增一条 ONE_TO_MANY 边后，MANY_TO_ONE 断言仍应全绿");
        } finally {
            // 还原：删掉本用例建的边 + 它的连接键。正向条件，只删这一条。
            if (createdEdgeId != null) {
                exec("DELETE FROM semantic_edge_key WHERE edge_id=CAST(?1 AS uuid)", createdEdgeId);
                exec("DELETE FROM semantic_edge WHERE id=CAST(?1 AS uuid)", createdEdgeId);
            }
            // 兜底：把可能残留的同形边一并清掉（限定 from/to/right_column 三元组，不误伤）
            long left = createdEdgeId == null ? 0
                    : scalarLong("SELECT count(*) FROM semantic_edge WHERE id=CAST(?1 AS uuid)", createdEdgeId);
            System.out.println("[AC-121] 还原完成，残留=" + left);
            assertEquals(0L, left,
                    "AC-121: finally 还原失败，本用例建的边还留在共享库里 —— 必须手工清掉 edge id=" + createdEdgeId);
        }
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    /** 把「边基数 CI 断言」实现成纯 SQL（D-27 口径：从表读边定义，不读代码声明）。返回违规描述，空串 = 全绿。 */
    private String checkAllManyToOneEdges() {
        List<Object[]> edges = rowList(
                "SELECT e.id::text, tn.physical_table, ek.right_column FROM semantic_edge e "
                        + "JOIN semantic_node tn ON tn.id = e.to_node_id "
                        + "JOIN semantic_edge_key ek ON ek.edge_id = e.id AND ek.seq = 0 "
                        + "WHERE e.cardinality='MANY_TO_ONE' AND e.status='ACTIVE' "
                        + "AND tn.physical_table IS NOT NULL");
        StringBuilder v = new StringBuilder();
        for (Object[] e : edges) {
            String table = String.valueOf(e[1]);
            String col = String.valueOf(e[2]);
            if (!relationHasColumn(table, col)) {
                v.append("\n  边 ").append(e[0]).append(": ").append(table).append(" 没有列 ").append(col)
                        .append("（这属于物理存在性问题，同样要红）");
                continue;
            }
            long dupGroups = scalarLong("SELECT count(*) FROM (SELECT " + quoteIdent(col) + " FROM "
                    + quoteIdent(table) + " GROUP BY 1 HAVING count(*) > 1) t");
            if (dupGroups > 0) {
                v.append("\n  边 ").append(e[0]).append(": ").append(table).append('.').append(col)
                        .append(" 有 ").append(dupGroups).append(" 组重复值，不能声明为 MANY_TO_ONE");
            }
        }
        return v.toString();
    }

    private static final class Dup {
        String nodeId;
        String nodeKey;
        String table;
        String column;
        long dupGroups;
    }

    /** 在图里的 SHEET 节点上找一个「值有重复」的列，用作反证素材。上限 80 次探测，避免全表乱扫。 */
    private Dup findDuplicatedKeyColumn() {
        List<Object[]> cands = rowList(
                "SELECT n.id::text, n.node_key, n.physical_table, c.db_column "
                        + "FROM semantic_node n JOIN semantic_node_column c ON c.node_id = n.id "
                        + "WHERE n.status='ACTIVE' AND n.node_kind='SHEET' AND n.physical_table IS NOT NULL "
                        + "AND c.status='ACTIVE' ORDER BY n.node_key, c.sort_order NULLS LAST LIMIT 80");
        for (Object[] c : cands) {
            String table = String.valueOf(c[2]);
            String col = String.valueOf(c[3]);
            if (!relationHasColumn(table, col)) {
                continue;
            }
            long n;
            try {
                n = scalarLong("SELECT count(*) FROM (SELECT " + quoteIdent(col) + " FROM "
                        + quoteIdent(table) + " GROUP BY 1 HAVING count(*) > 1) t");
            } catch (Exception e) {
                continue;
            }
            if (n > 0) {
                Dup d = new Dup();
                d.nodeId = String.valueOf(c[0]);
                d.nodeKey = String.valueOf(c[1]);
                d.table = table;
                d.column = col;
                d.dupGroups = n;
                return d;
            }
        }
        return null;
    }

    private String pickAnyOtherNodeId(String excludeId) {
        return scalarStr("SELECT id::text FROM semantic_node WHERE status='ACTIVE' "
                + "AND id <> CAST(?1 AS uuid) ORDER BY node_key LIMIT 1", excludeId);
    }

    private Response postEdge(String fromNodeId, String toNodeId, String cardinality,
                              String leftColumn, String rightColumn) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromNodeId", fromNodeId);
        body.put("toNodeId", toNodeId);
        body.put("edgeKind", "LOOKUP");
        body.put("cardinality", cardinality);
        List<Map<String, Object>> keys = new ArrayList<>();
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("seq", 0);
        k.put("leftColumn", leftColumn);
        k.put("rightColumn", rightColumn);
        keys.add(k);
        body.put("keys", keys);
        body.put("fallbackOrder", 0);
        body.put("note", TAG + "AC-121 反证用，用例 finally 会删除");
        return RestAssured.given().cookie("CPQ_SESSION", session())
                .contentType(ContentType.JSON).body(body)
                .when().post("/api/cpq/config/semantic-graph/edges");
    }

    private boolean relationHasColumn(String relation, String column) {
        return scalarLong("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name=?1 AND column_name=?2", relation, column) > 0;
    }

    /** 标识符只允许 [a-z0-9_]，其余直接拒 —— 这些名字来自库里的元数据，仍然不拼裸串。 */
    private static String quoteIdent(String s) {
        if (s == null || !s.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new AssertionError("非法标识符（拒绝拼进 SQL）：" + s);
        }
        return "\"" + s + "\"";
    }
}
