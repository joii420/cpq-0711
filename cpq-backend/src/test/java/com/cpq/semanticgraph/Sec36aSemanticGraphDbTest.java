package com.cpq.semanticgraph;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求文档.md §3.6a 语义图落库 · 表结构 / 校验 / 权限（D-27 ~ D-30）—— AC-51 ~ AC-57。
 *
 * <h3>🔄 2026-09-05（用户裁决 {@code D-123}）：3 条换 v9 对象保留，4 条作废留碑，1 条待主线裁决</h3>
 *
 * <h4>📌 被作废的 4 个方法（保留历史价值说明，勿删）</h4>
 * <table>
 *   <tr><th>方法</th><th>它验的是什么</th><th>为什么退役</th><th>接替者</th></tr>
 *   <tr><td>{@code ac51_seedMigrationMatchesOriginalDeclaration}</td>
 *       <td>{@code GET /config/semantic-graph} 全图与
 *           {@code golden/semantic-graph-baseline.json} <b>逐字段</b>比对：
 *           17 个 Sheet 节点的 {@code physicalTable}/列数/{@code usedBy}/{@code discriminator}/孤儿标记、
 *           6 个查名与函数节点的 {@code nodeKind}/{@code funcSignature}、22 条边的
 *           {@code from/to/kind/cardinality/连接键/fallbackOrder/coalesceGroup}</td>
 *       <td><b>基线文件本身描述的是 V6 那张图</b>（23 节点 / 22 连接），而 {@code V413} 已把它整块删掉、
 *           {@code V410} 灌入的是三套 {@code ds_*} 的 44 节点 / 29 边。<br>
 *           ⚠️ 另有一个<b>结构性冲突</b>：本方法断言「{@code displayName} 互不重名」，而 v9 是
 *           <b>刻意重名</b>的 —— 10 个节点键 × 3 方言各一份（{@code test.md §4.3 坑1}），
 *           2026-09-05 实测 44 节点只有 28 个不同的 {@code display_name}。<b>这是 v9 的设计，不是数据错。</b></td>
 *       <td><b>{@code AC-101}</b>（三方言存在且各有 SHEET）· <b>{@code AC-102}</b>（V6 表命中 0 + 无悬挂边）·
 *           <b>{@code AC-104}</b>（列声明 ⇄ {@code information_schema} 双向无差集）·
 *           <b>{@code AC-105}</b>（{@code _history} 不进图）· <b>{@code AC-106}</b>（页签视图逐格相等）·
 *           <b>{@code AC-103}</b>（种子脚本可重放，md5 逐字节相同）—— 六条合起来比「跟一份手抄基线比对」更强：
 *           它们钉的是<b>真实 DDL</b>，而不是另一份可能一起写错的文件</td></tr>
 *   <tr><td>{@code ac52_edgeCardinalityOnlineInterception_negativeCase}</td>
 *       <td>反证：把一条真实一对多的边 {@code PUT} 成 {@code MANY_TO_ONE} → 400
 *           {@code SEMANTIC_VALIDATION_FAILED}/{@code EDGE_CARDINALITY} + 错误信息点名右侧键 +
 *           库中未写入；改回 {@code ONE_TO_MANY} 同一请求成功</td>
 *       <td><b>作废（已被覆盖）</b>，判据本身仍然有效，只是已有一份 v9 原生实现</td>
 *       <td><b>{@code AC-121}②③</b>（{@code V9ValidationAndCiTest.ac121b_edgeCardinalityFalsification}：
 *           新增一条右键重复的 {@code MANY_TO_ONE} 边必须被拒并指名，改成 {@code ONE_TO_MANY} 同一请求成功）<br>
 *           📌 v9 版比这一版<b>更安全</b>：它用「新增再撤回」而不是「原地改坏一条现网边」，
 *           不存在断言失败后把共享图留在改坏状态的窗口</td></tr>
 *   <tr><td>{@code ac52_thinSampleBlindSpot}</td>
 *       <td>样本不足盲区：目标表 &lt; 30 行的边，{@code assertStatus} 必须是 {@code THIN} 而不是 {@code PASS}
 *           （否则任何基数声明都能「碰巧」通过 —— 这是该断言的固有假阴性）</td>
 *       <td><b>判据没有对象</b>。2026-09-05 实测：全部 29 条边的 {@code assert_status} 一律为
 *           {@code 'NA'}、{@code assert_sample_rows} <b>全为 NULL</b> ——
 *           v9 种子由脚本机器生成（{@code B-42}），<b>压根没跑过在线基数抽样</b>，
 *           因此不存在「抽样了但样本太薄」这个状态。<br>
 *           🚫 不能「换个对象保留」：图里一条带 {@code assertSampleRows} 的边都没有，
 *           保留下来只会得到一条恒空跑的断言（四类假绿里的「断言从未执行」）</td>
 *       <td>🚨 <b>无接替者，这是一个已知缺口</b>：v9 AC 集合里没有任何一条覆盖「基数断言的样本充分性」。
 *           已随本次退役一并上报主线。防线由 {@link #tombstone_retiredSemanticGraphAcs_premiseStillHolds()}
 *           守着 —— 一旦有边带上了 {@code assert_sample_rows}，本条变红要求把 THIN 语义重新验起来</td></tr>
 *   <tr><td>{@code ac55_pathAmbiguityRejectedAtSaveTime_negativeCase}</td>
 *       <td>反证：在库中构造两条可达路径的边组合 → 保存该边被 {@code PATH_UNIQUENESS} 拒绝，
 *           错误信息列出两条路径各自的节点序列</td>
 *       <td><b>构造不出来</b>。v9 图是<b>星形</b>的：28 条 {@code NARROW} 边全部从各锚点指向同一个料号桥，
 *           而桥本身<b>没有出边</b>（2026-09-05 实测二跳路径 = 0）。本方法的构造手法依赖
 *           「anchor → mid → target 的二跳」，第一步就取不到 {@code secondHop}，自 v9 起恒 SKIP。</td>
 *       <td>🚨 <b>无接替者，这是一个交付缺口</b>：{@code AC-123} 只覆盖四道校验里的第③道「物理存在性」，
 *           <b>{@code PATH_UNIQUENESS} 在 AC-101~126 里没有任何一条覆盖</b>。
 *           与 {@code Sec31.ac10}（编译期路径歧义）是同一缺口的两面，已一并上报主线</td></tr>
 * </table>
 *
 * <h4>✅ 换 v9 对象后保留的 3 个方法</h4>
 * <ul>
 *   <li>{@link #ac53_physicalExistenceValidation_negativeCase()} —— 图写端点的物理存在性校验。
 *       {@code AC-123} 验的是 <b>builder 保存端点</b>上的同名校验，<b>不是图写端点</b>，两者不可互相替代。</li>
 *   <li>{@link #ac54_referentialIntegrityEnforcedByDbLayer_negativeCase()} —— 引用完整性由<b>库层</b>保证。
 *       <br>🔑 <b>安全性取证（2026-09-05，主动做在改用例之前）</b>：本方法会用 psql 直接 {@code DELETE}
 *       一个节点，若外键<b>没</b>拦住，它就会真的从共享图里删掉一行。因此先用
 *       <b>{@code BEGIN; DELETE …; ROLLBACK;}</b> 做了一次零风险预演，输出为：
 *       <pre>ERROR: update or delete on table "semantic_node" violates foreign key constraint
 *   "semantic_edge_from_node_id_fkey" on table "semantic_edge"
 * DETAIL: Key (id)=(e77545bb-…-b7be932020ea) is still referenced from table "semantic_edge".</pre>
 *       ⇒ 该节点被 FK 引用、{@code DELETE} 必然被拒，本方法的破坏动作<b>不可能真的删掉数据</b>。
 *       用例内仍会重新确认「被引用」这个前提，🚫 不靠上面这段输出吃老本。</li>
 *   <li>{@link #ac57_concurrencySafety()} —— 原 {@code ac57②}（20 并发预览无 500、无半新半旧）。
 *       原 ①（改 {@code fallback_order} 不重启即生效）与 ③（存量 {@code sql_template} 逐字未变）
 *       <b>在 v9 上都没有对象</b>：实测 29 条边的 {@code fallback_order} 全为 NULL；
 *       {@code component_sql_view} 里 {@code builder_config IS NOT NULL} 的行 = 0（{@code S-25} 已删净 21 个）。
 *       两者原本就是「找不到就 print 一句跳过」的软分支 —— <b>留着只会伪装成绿</b>，随本轮一并作废，
 *       前提由守卫方法钉住。<br>
 *       📌 ③ 的目的（本任务不改动存量视图 SQL）已由 <b>{@code AC-122}</b> 精确承担。</li>
 * </ul>
 *
 * <h4>⏸ 待主线裁决的 1 个方法</h4>
 * {@link #ac56_writeEndpointRolePermission_negativeCase()} —— 写端点的角色权限反证。
 * <b>它与 v8/v9 语义图无关</b>（验的是 RBAC 本身），因此<b>不属于本次「退役 v8 期用例」的范围</b>；
 * 它恒 SKIP 的原因是<b>测试环境的 Redis 会话缺陷</b>（{@code com.cpq.integration.PermissionTest} 基线同样复现），
 * 不是断言对象消失。<b>本轮原样保留、一个字未改</b>，请主线裁决是留着等环境修好，还是转 BACKLOG。
 * 🚫 我没有把它归到「已退役」—— 那会把一条<b>安全</b>验收项悄悄抹掉。
 *
 * <p>层级 = T-3（AC-57）/ T-2,T-3 反证（AC-53/54）。
 */
@QuarkusTest
@TestProfile(SemanticGraphTestSupport.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Sec36aSemanticGraphDbTest — AC-53/54/57② 换 v9 对象保留；🪦 AC-51/52/52THIN/55/57①③ 已作废；AC-56 待主线裁决")
class Sec36aSemanticGraphDbTest {

    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;

    @AfterEach
    void tearDown() throws Exception {
        SemanticGraphTestSupport.cleanupUsers(em, utx);
    }

    private long scalar(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    // ===================================================================
    // 🪦 作废前提守卫（会真的执行；任一前提被推翻就变红）
    // ===================================================================
    @Test
    @Order(1)
    @DisplayName("🪦 作废前提守卫: 无二跳路径(AC-55) + 无 assert_sample_rows(AC-52THIN) + 无 fallback_order(AC-57①) + 无 builder 视图(AC-57③)")
    void tombstone_retiredSemanticGraphAcs_premiseStillHolds() {
        long activeNodes = scalar("SELECT count(*) FROM semantic_node WHERE status='ACTIVE'");
        long activeEdges = scalar("SELECT count(*) FROM semantic_edge WHERE status='ACTIVE'");
        long twoHopPaths = scalar("SELECT count(*) FROM semantic_edge e1 JOIN semantic_edge e2 "
                + "ON e2.from_node_id = e1.to_node_id WHERE e1.status='ACTIVE' AND e2.status='ACTIVE'");
        long withSample = scalar("SELECT count(*) FROM semantic_edge WHERE assert_sample_rows IS NOT NULL");
        long withFallback = scalar("SELECT count(*) FROM semantic_edge WHERE fallback_order IS NOT NULL");
        // ⚠️ 必须排除本套用例自建的组件（TAG 前缀）—— Sec32/34/35/36 里好几条保留下来的用例
        //    本来就会保存 builder 配置，同批次跑完这个数字必然 > 0。首轮实测 8 个全是 SQLVB-TEST-*，
        //    不排除就会得到一条「自己把自己判红」的守卫（假红，同样是坏信号）。
        long builderViews = ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM component_sql_view v JOIN component c ON c.id = v.component_id "
                                + "WHERE v.builder_config IS NOT NULL AND c.name NOT LIKE :tag")
                .setParameter("tag", SemanticGraphTestSupport.TAG + "%").getSingleResult()).longValue();

        System.out.println("[🪦 Sec36a 留碑] ACTIVE 节点=" + activeNodes + " 边=" + activeEdges
                + " · 二跳路径=" + twoHopPaths + " · 带 assert_sample_rows 的边=" + withSample
                + " · 带 fallback_order 的边=" + withFallback + " · builder_config 非空的视图=" + builderViews);

        assertTrue(activeNodes > 0 && activeEdges > 0,
                "🚨 语义图为空（节点 " + activeNodes + " / 边 " + activeEdges + "）—— 这不是「作废前提成立」，"
                        + "是种子没就位。本条判定为【未验证】，🚫 不许当成通过。");

        assertEquals(0L, twoHopPaths,
                "🚦 AC-55（保存期路径歧义拦截）的作废前提被推翻：图里出现了 " + twoHopPaths + " 条二跳路径。\n"
                        + "  作废理由是「v9 是星形图（28 条 NARROW 边全指向料号桥、桥无出边），"
                        + "构造不出 anchor→mid→target 的第二条路径」。\n"
                        + "  🚨 重点：PATH_UNIQUENESS 在 v9 AC 集合（AC-101~126）里<b>本来就没有任何一条覆盖</b>，"
                        + "这是已上报主线的交付缺口。现在图里有多跳了 ⇒ 缺口从『暂时无对象』变成『真的没人管』，"
                        + "必须立刻把这条防线补上。");

        assertEquals(0L, withSample,
                "🚦 AC-52-THIN（样本不足盲区必须判 THIN 而非 PASS）的作废前提被推翻：有 " + withSample
                        + " 条边带上了 assert_sample_rows。\n"
                        + "  作废理由是「v9 种子由脚本机器生成、从未跑过在线基数抽样，assert_status 一律 NA、"
                        + "assert_sample_rows 全 NULL ⇒ 没有『抽样了但样本太薄』这个状态可验」。\n"
                        + "  抽样回来了 ⇒ 必须把 THIN 语义重新验起来，否则「样本 <30 行时任何基数声明都能碰巧通过」"
                        + "这个固有假阴性就没人挡了。");

        assertEquals(0L, withFallback,
                "🚦 AC-57①（改 fallback_order 不重启即生效）的作废前提被推翻：有 " + withFallback
                        + " 条边带上了 fallback_order。\n"
                        + "  作废理由是「v9 图里 fallback_order 全为 NULL，原用例的『找一条带 fallbackOrder 的边』"
                        + "永远找不到，只会 print 一句然后跳过 —— 那是伪装成绿的空跑」。\n"
                        + "  它回来了 ⇒ 热生效（改图不重启即生效、graphVersion 递增）这条防线要重新接上。");

        assertEquals(0L, builderViews,
                "🚦 AC-57③（存量 sql_template 逐字未变）的作废前提被推翻：component_sql_view 里出现了 "
                        + builderViews + " 个 builder_config 非空的视图。\n"
                        + "  作废理由是「S-25 已把 21 个 builder 产出视图连同组件一并删除，builder_config 非空 = 0"
                        + "（AC-113① 实测），原用例的比对对象为空」。\n"
                        + "  📌 本计数已排除 " + SemanticGraphTestSupport.TAG + "* 自建组件，所以这里的数字是"
                        + "「真的有非测试组件重新启用了 builder 模式」。\n"
                        + "  📌 「本任务不改动存量视图 SQL」这个目的已由 AC-122 精确承担。");
    }

    // ===================================================================
    // AC-53（边界·反证）物理存在性校验 —— 图写端点侧
    // ===================================================================
    @Test
    @Order(2)
    @DisplayName("AC-53(v9)【反证】: physicalTable 填不存在表名 / dbColumn 填不存在列名 → 均 400，分别点名，库无残留")
    void ac53_physicalExistenceValidation_negativeCase() {
        String bogusTable = "sqlvb_test_table_does_not_exist_xyz";
        String bogusNodeKey = "SQLVB_TEST_BOGUS_NODE_" + UUID.randomUUID().toString().substring(0, 8);
        String bogusTableNode = """
                { "nodeKey": "%s", "displayName": "不存在的表测试节点", "nodeKind": "SHEET",
                  "physicalTable": "%s", "scope": "NONE" }
                """.formatted(bogusNodeKey, bogusTable);
        Response r1 = RestAssured.given().contentType(ContentType.JSON)
                .body(bogusTableNode).post("/api/cpq/config/semantic-graph/nodes");
        assertTrue(r1.statusCode() >= 400,
                "① 表不存在应被拒绝，实际=" + r1.statusCode() + " body=" + r1.getBody().asString());
        assertEquals("PHYSICAL_EXISTENCE", r1.jsonPath().getString("failedCheck"),
                "① failedCheck 应为 PHYSICAL_EXISTENCE，实际=" + r1.getBody().asString());
        assertTrue(r1.getBody().asString().contains("表不存在"),
                "① 错误信息应点名『表不存在』，实际=" + r1.getBody().asString());
        assertEquals(0L, ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM semantic_node WHERE physical_table = :t")
                .setParameter("t", bogusTable).getSingleResult()).longValue(),
                "① 库中不应残留该非法节点");

        // ② 用一个 v9 图内真实存在的节点，给它加一个不存在的列名
        Object[] anchor = (Object[]) em.createNativeQuery(
                        "SELECT n.id, n.node_key, n.physical_table FROM semantic_tab_view v "
                                + "JOIN semantic_node n ON n.id = v.anchor_node_id "
                                + "WHERE v.dialect='QUOTE' AND v.tab_type='材质元素' AND v.status='ACTIVE'")
                .getSingleResult();
        String nodeId = String.valueOf(anchor[0]);
        System.out.println("[AC-53(v9)] 用真实节点 " + anchor[1] + "（" + anchor[2] + "）验列不存在分支");

        String bogusColumn = "sqlvb_bogus_column_xyz";
        String body = "{\"nodeId\":\"" + nodeId + "\",\"dbColumn\":\"" + bogusColumn + "\","
                + "\"displayName\":\"假列\",\"dataType\":\"TEXT\"}";
        Response r2 = RestAssured.given().contentType(ContentType.JSON)
                .body(body).post("/api/cpq/config/semantic-graph/nodes/" + nodeId + "/columns");
        assertTrue(r2.statusCode() >= 400,
                "② 列不存在应被拒绝，实际=" + r2.statusCode() + " body=" + r2.getBody().asString());
        assertTrue(r2.getBody().asString().contains("列不存在"),
                "② 错误信息应点名『列不存在，该表实有列为…』，实际=" + r2.getBody().asString());
        assertEquals(0L, ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM semantic_node_column WHERE db_column = :c")
                .setParameter("c", bogusColumn).getSingleResult()).longValue(),
                "② 库中不应残留该非法列");
    }

    // ===================================================================
    // AC-54（边界·反证·关键）引用完整性由库层保证 —— 必须绕过应用层
    // ===================================================================
    @Test
    @Order(3)
    @DisplayName("AC-54(v9)【反证·关键】: psql 直接 DELETE 被引用节点 → 库层外键拒绝并回滚；写端点删同节点给可读 409")
    void ac54_referentialIntegrityEnforcedByDbLayer_negativeCase() throws Exception {
        // 选一个 v9 图内「确实被引用」的节点（被边引用 + 被页签视图引用）。
        // 🔑 先证明「被引用」这个前提，再做破坏动作 —— 否则 DELETE 可能真的删掉一行共享数据。
        Object[] target = (Object[]) em.createNativeQuery(
                        "SELECT n.id, n.node_key, n.dialect, "
                                + "(SELECT count(*) FROM semantic_edge e WHERE e.from_node_id=n.id OR e.to_node_id=n.id), "
                                + "(SELECT count(*) FROM semantic_tab_view_node tvn WHERE tvn.node_id=n.id) "
                                + "FROM semantic_tab_view v JOIN semantic_node n ON n.id = v.anchor_node_id "
                                + "WHERE v.dialect='QUOTE' AND v.tab_type='材质元素' AND v.status='ACTIVE'")
                .getSingleResult();
        UUID nodeId = (UUID) target[0];
        String nodeKey = String.valueOf(target[1]);
        String dialect = String.valueOf(target[2]);
        long edgeRefs = ((Number) target[3]).longValue();
        long viewRefs = ((Number) target[4]).longValue();
        System.out.println("[AC-54(v9)] 目标节点 " + nodeKey + "/" + dialect + " id=" + nodeId
                + " 被边引用=" + edgeRefs + " 被页签视图引用=" + viewRefs);
        assertTrue(edgeRefs > 0,
                "🚨 前置不成立：该节点没有被任何 semantic_edge 引用 ⇒ 下面的 DELETE 可能会<b>真的删掉一行共享数据</b>。"
                        + "本条判定为【未验证】并立即停止，🚫 不许硬跑。实际 edgeRefs=" + edgeRefs);

        long before = scalar("SELECT count(*) FROM semantic_node WHERE id = '" + nodeId + "'");
        assertEquals(1L, before, "前置：目标节点应恰好存在 1 行，实际=" + before);

        // ①【破坏方式】不经应用层，直接用 psql 二进制对 test profile 的库执行 DELETE。
        // ⚠️ test profile 的默认库就是共享开发库 cpq_db_0724（application-test.properties:24，D-114）。
        String dbHost = System.getenv().getOrDefault("DB_HOST", "10.177.152.12");
        String dbName = System.getenv().getOrDefault("DB_NAME", "cpq_db_0724");
        String dbUser = System.getenv().getOrDefault("DB_USERNAME", "postgres");
        String dbPassword = System.getenv().getOrDefault("DB_PASSWORD", "joii5231");

        ProcessBuilder pb = new ProcessBuilder("psql", "-h", dbHost, "-U", dbUser, "-d", dbName,
                "-v", "ON_ERROR_STOP=1",
                "-c", "DELETE FROM semantic_node WHERE id = '" + nodeId + "'");
        pb.environment().put("PGPASSWORD", dbPassword);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output;
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
            output = reader.lines().reduce("", (a, b) -> a + "\n" + b);
        }
        assertTrue(proc.waitFor(15, TimeUnit.SECONDS), "psql 进程应在 15 秒内退出");
        int exitCode = proc.exitValue();
        System.out.println("[AC-54(v9)] psql exitCode=" + exitCode + " output=" + output);

        assertNotEquals(0, exitCode,
                "① psql DELETE 应因外键约束被数据库拒绝(非 0 退出码)，实际 exitCode=" + exitCode + " output=" + output);
        assertTrue(output.toLowerCase().contains("foreign key") || output.toLowerCase().contains("violat"),
                "① 输出应包含外键违反信息，实际 output=" + output);

        long after = scalar("SELECT count(*) FROM semantic_node WHERE id = '" + nodeId + "'");
        assertEquals(before, after, "① 数据库应已回滚，节点数应与破坏前相同，实际 前=" + before + " 后=" + after);

        // ② 走写端点删同一节点，应返回可读错误并列出还在被哪些边/页签视图引用
        Response deleteViaApi = RestAssured.given()
                .delete("/api/cpq/config/semantic-graph/nodes/" + nodeId);
        assertEquals(409, deleteViaApi.statusCode(),
                "② 走写端点删除被引用节点应返回 409 FK_STILL_REFERENCED，实际=" + deleteViaApi.statusCode()
                        + " body=" + deleteViaApi.getBody().asString());
        List<?> referencingEdges = deleteViaApi.jsonPath().getList("detail.referencingEdges");
        List<?> referencingTabViews = deleteViaApi.jsonPath().getList("detail.referencingTabViews");
        boolean hasReferenceList = (referencingEdges != null && !referencingEdges.isEmpty())
                || (referencingTabViews != null && !referencingTabViews.isEmpty());
        assertTrue(hasReferenceList,
                "② 应列出还有哪些边/哪些页签视图在引用该节点，实际=" + deleteViaApi.getBody().asString());

        long afterApi = scalar("SELECT count(*) FROM semantic_node WHERE id = '" + nodeId + "'");
        assertEquals(before, afterApi, "② 走端点删除被拒后，节点仍应在，实际 前=" + before + " 后=" + afterApi);
    }

    // ===================================================================
    // AC-56（边界·反证）写端点权限 —— ⏸ 待主线裁决，本轮一字未改
    // ===================================================================
    @Test
    @Order(4)
    @DisplayName("AC-56【反证·阻塞】: 需要真实 RBAC + 多角色登录，本测试环境登录墙(Redis CONNECTION_CLOSED)挡住，标记 SKIPPED")
    void ac56_writeEndpointRolePermission_negativeCase() {
        // ⏸ 2026-09-05 退役工作说明：本方法验的是 RBAC 角色区分（PRICING_MANAGER/SALES_MANAGER/SALES_REP
        // 写请求必须 403，SYSTEM_ADMIN 必须 2xx），与 v8/v9 语义图<b>无关</b>——
        // 它恒 SKIP 的原因是测试环境缺陷（真实 POST /auth/login 稳定 500 CONNECTION_CLOSED，
        // 用未改动的基线测试 com.cpq.integration.PermissionTest 复现出完全相同的错误），
        // 不是「断言对象消失」。因此它不属于本次「退役 v8 期语义图用例」的范围，本轮原样保留、一个字未改。
        // 🚫 没有把它归到「已退役」——那会把一条安全验收项悄悄抹掉。请主线裁决：留着等环境修好，还是转 BACKLOG。
        //
        // 另注：本类其余方法用 @TestProfile 关掉了 RBAC，那样跑 AC-56 毫无意义——
        // RBAC 关闭后所有角色都会 2xx，等于自己把断言做成假绿。
        Assumptions.assumeTrue(false,
                "[AC-56] 阻塞：验证角色 403 需要 RBAC 开启 + 真实多角色登录，但本测试环境登录会稳定触发"
                        + " Redis CONNECTION_CLOSED（PermissionTest 基线同样复现，与本任务无关）。"
                        + "标记为 SKIPPED，不是假绿——不删除本用例，待测试环境的 Redis 会话问题解决后应改回真实验证。"
                        + "⏸ 2026-09-05：本条不在 D-123 退役范围内，处置待主线裁决。");
    }

    // ===================================================================
    // AC-57②（保留）并发安全
    // ===================================================================
    @Test
    @Order(5)
    @DisplayName("AC-57②(v9): 20 并发预览全部成功、无 500、无半新半旧")
    void ac57_concurrencySafety() {
        // 🪦 原 ac57① / ③ 已随本轮作废（fallback_order 全 NULL / builder 视图为 0，两者都没有对象），
        //    见类头碑文与 tombstone_retiredSemanticGraphAcs_premiseStillHolds()。

        Object[] anchor = (Object[]) em.createNativeQuery(
                        "SELECT n.node_key, n.id FROM semantic_tab_view v "
                                + "JOIN semantic_node n ON n.id = v.anchor_node_id "
                                + "WHERE v.dialect='QUOTE' AND v.tab_type='材质元素' AND v.status='ACTIVE'")
                .getSingleResult();
        String anchorKey = String.valueOf(anchor[0]);
        String partNoColumn = String.valueOf(em.createNativeQuery(
                        "SELECT c.db_column FROM semantic_node_column c "
                                + "WHERE c.node_id=:nid AND c.status='ACTIVE' AND 'PART_NO' = ANY(c.roles) LIMIT 1")
                .setParameter("nid", anchor[1]).getSingleResult());
        // 用一个真有数据的料号——0 行也能「并发成功」，但那样验不到取数路径上的竞态
        String partNo = String.valueOf(em.createNativeQuery(
                "SELECT material_no FROM ds_quote_element_bom GROUP BY material_no "
                        + "ORDER BY count(*) DESC LIMIT 1").getSingleResult());
        System.out.println("[AC-57②(v9)] 锚点=" + anchorKey + " 料号列=" + partNoColumn + " 料号=" + partNo);

        UUID componentId = UUID.fromString(RestAssured.given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + SemanticGraphTestSupport.TAG + "concurrency-" + UUID.randomUUID() + "\"}")
                .post("/api/cpq/components").jsonPath().getString("data.id"));

        // api.md §1.5②：/preview 请求体是裸 builder_config + 平级的预览参数，不包一层 "builderConfig"。
        String previewBody = "{\"dialect\":\"QUOTE\",\"tabType\":\"材质元素\",\"partNo\":\"" + partNo + "\",\"columns\":["
                + "{\"sourceNodeKey\":\"" + anchorKey + "\",\"sourceColumn\":\"" + partNoColumn
                + "\",\"fieldName\":\"材质料号\",\"isRowKey\":true,\"isPartNo\":true}]}";

        // 先单跑一次，确认这份请求体本身是好的、且真的取到行 —— 否则 20 个并发全 200 也可能是空跑
        Response warmup = RestAssured.given().contentType(ContentType.JSON)
                .body(previewBody).post("/api/cpq/components/" + componentId + "/builder/preview");
        assertEquals(200, warmup.statusCode(), "预热请求应成功: " + warmup.getBody().asString());
        Integer warmupRows = warmup.jsonPath().getObject("rowCount", Integer.class);
        assertNotNull(warmupRows, "预热请求应返回 rowCount，body=" + warmup.getBody().asString());
        assertTrue(warmupRows > 0,
                "🚨 预热请求返回 " + warmupRows + " 行 —— 并发跑一份恒 0 行的请求验不到取数路径上的竞态（空跑=假绿）。"
                        + "本条判定为【未验证】。body=" + warmup.getBody().asString());

        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger failures = new AtomicInteger(0);
        AtomicInteger serverErrors = new AtomicInteger(0);
        AtomicInteger rowMismatch = new AtomicInteger(0);
        try {
            List<Callable<Integer>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                tasks.add(() -> {
                    Response r = RestAssured.given().contentType(ContentType.JSON)
                            .body(previewBody).post("/api/cpq/components/" + componentId + "/builder/preview");
                    if (r.statusCode() == 200) {
                        Integer rc = r.jsonPath().getObject("rowCount", Integer.class);
                        // 「无半新半旧」：同一份配置在并发下必须给出同一个行数
                        if (rc == null || !rc.equals(warmupRows)) {
                            rowMismatch.incrementAndGet();
                        }
                    }
                    return r.statusCode();
                });
            }
            List<Future<Integer>> results = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);
            for (Future<Integer> f : results) {
                try {
                    int status = f.get();
                    if (status >= 500) {
                        serverErrors.incrementAndGet();
                    }
                    if (status >= 400) {
                        failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("并发预览被中断", e);
        } finally {
            pool.shutdownNow();
        }
        System.out.println("[AC-57②(v9)] 20 并发结果：500 次数=" + serverErrors.get()
                + " 失败次数=" + failures.get() + " 行数不一致次数=" + rowMismatch.get()
                + "（基准行数=" + warmupRows + "）");
        assertEquals(0, serverErrors.get(), "② 并发预览不应出现 500，实际 500 次数=" + serverErrors.get());
        assertEquals(0, failures.get(), "② 并发预览全部应成功，实际失败次数=" + failures.get());
        assertEquals(0, rowMismatch.get(),
                "② 并发下同一份配置的行数应恒等于 " + warmupRows + "（无半新半旧），实际不一致次数=" + rowMismatch.get());
    }
}
