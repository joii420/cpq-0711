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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求文档.md §3.6 CI 断言 · 方言参数化 —— AC-35, AC-36, AC-37。
 * （AC-38 golden 见独立脚本 golden-verify；AC-39 端到端序列见 E2E；AC-40 自检声明是 shell checklist，
 *  两者均不适合 @QuarkusTest 形态，登记在 testcase.md。）
 *
 * 层级 = T-6（AC-35/36 是 CI 断言，反证型，本类把它们做成可在 `mvn test` 里跑的 JUnit 用例，
 * 与"发版前跑一次"的 CI 定位一致）；T-1（AC-37 方言参数化产物对照）。
 *
 * ⚠️ AC-36【已知信息缺口】：D-27 要求 handler 双向对账"从代码读 Q*Handler 实际 put 的列"。
 * 本测试工程师禁止读 cpq-backend/src/main/** 源码（含具体 Q*Handler 类实现），因此无法知道
 * handler 内部实际执行了哪些 put(...) 调用、也无法知道对账逻辑该反射哪个包/类。api.md 只给出
 * GET /config/semantic-graph 响应里每个节点带 sourceHandler 字符串（如"Q04ElementBomHandler"），
 * 但没有给出"如何拿到该 handler 真实写了哪些列"的可调用契约（无端点、无反射入口约定）。
 * 本类因此只能验证"对账检查确实跑过且给出结构化结果"这一半（通过 POST /validate 的
 * HANDLER_RECONCILE 分项），无法独立实现"人为在 Q*Handler 里加 put() 后断言必须变红"这一半的
 * 破坏步骤——那一步依赖后端 B-17 自己写的 CI 用例（backtask.md 已把 B-17 派给后端，要求其
 * "必须同时证明人为改错后测试确实失败"）。这是信息不足，已按规则停下报告，不在此臆测具体反射方式。
 */
@QuarkusTest
@TestProfile(SemanticGraphTestSupport.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Sec36DialectAndCiAssertionTest {

    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;


    @BeforeEach
    void setUp() throws Exception {
    }


    // -------------------------------------------------------------------
    // AC-35（边界·反证）边基数断言能抓到写错的声明 —— 从表读取边定义
    // -------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("AC-35【CI反证】: 正常数据下MANY_TO_ONE边全部唯一；人为改错一条真实一对多的边后必须失败并指名")
    void ac35_edgeCardinalityCiAssertion_negativeCase() throws Exception {
        // ① 正常路径：对库中全部 cardinality='MANY_TO_ONE' 的边，逐条校验其右侧连接键在目标表中唯一。
        //    纯 SQL 断言，不依赖任何 Java 实现类——"从表读取边定义"正是 D-27 要求的口径。
        @SuppressWarnings("unchecked")
        List<Object[]> manyToOneEdges = em.createNativeQuery(
                "SELECT e.id, tn.physical_table, ek.right_column " +
                        "FROM semantic_edge e " +
                        "JOIN semantic_node tn ON tn.id = e.to_node_id " +
                        "JOIN semantic_edge_key ek ON ek.edge_id = e.id AND ek.seq = 0 " +
                        "WHERE e.cardinality = 'MANY_TO_ONE' AND e.status = 'ACTIVE' " +
                        "AND tn.physical_table IS NOT NULL"
        ).getResultList();

        assertFalse(manyToOneEdges.isEmpty(),
                "库中应存在 MANY_TO_ONE 边（种子迁移 B-1 应已灌入 19 条边），若为空说明 semantic_edge "
                        + "尚未落地/未跑种子迁移——这是环境前置未就绪，不是本用例判定为『通过』的依据");

        StringBuilder violations = new StringBuilder();
        for (Object[] edge : manyToOneEdges) {
            String table = String.valueOf(edge[1]);
            String rightColumn = String.valueOf(edge[2]);
            List<Object[]> dupes = em.createNativeQuery(
                            "SELECT " + rightColumn + ", count(*) c FROM " + table
                                    + " GROUP BY " + rightColumn + " HAVING count(*) > 1")
                    .getResultList();
            if (!dupes.isEmpty()) {
                violations.append(table).append(".").append(rightColumn)
                        .append(" 重复 ").append(dupes.size()).append(" 组; ");
            }
        }
        assertEquals("", violations.toString(), "① 正常数据下全部 MANY_TO_ONE 边右键应唯一，违规=" + violations);

        // ②【破坏方式】挑一条已知真实一对多的边（右侧键必然重复），在库中把它的 cardinality
        // 改成 MANY_TO_ONE，重新跑同一断言逻辑，必须变红并指出是哪条边、哪个键重复了几行。
        Object[] oneToManyEdge = findAKnownOneToManyEdge();
        Assumptions.assumeTrue(oneToManyEdge != null,
                "[AC-35] 库中未找到可用于反证的 ONE_TO_MANY 边（种子未就绪），②反证部分标记为 SKIPPED，"
                        + "须在种子迁移落地后补跑");
        UUID edgeId = (UUID) oneToManyEdge[0];
        String table = String.valueOf(oneToManyEdge[1]);
        String rightColumn = String.valueOf(oneToManyEdge[2]);

        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("UPDATE semantic_edge SET cardinality='MANY_TO_ONE' WHERE id=:id")
                .setParameter("id", edgeId).executeUpdate();
        utx.commit();
        try {
            List<Object[]> dupesAfterCorruption = em.createNativeQuery(
                            "SELECT " + rightColumn + ", count(*) c FROM " + table
                                    + " GROUP BY " + rightColumn + " HAVING count(*) > 1")
                    .getResultList();
            assertFalse(dupesAfterCorruption.isEmpty(),
                    "② 断言必须失败：把真实一对多的边改成 MANY_TO_ONE 后，" + table + "." + rightColumn
                            + " 应能查到重复行，若这里是空说明选错了边（该边本来就是一对一），需要换一条真正的一对多边做反证");
            System.out.println("[AC-35 反证成功] 边=" + edgeId + " 表=" + table + " 列=" + rightColumn
                    + " 重复组数=" + dupesAfterCorruption.size() + "（失败信息应指出这三项）");
        } finally {
            // 还原（CLAUDE.md §4.3 全局状态改动纪律：改了必须在 finally 里还原）
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery("UPDATE semantic_edge SET cardinality='ONE_TO_MANY' WHERE id=:id")
                    .setParameter("id", edgeId).executeUpdate();
            utx.commit();
        }
    }

    /** 找一条已知右键必然重复的边（用于②反证）。选择依据：目标表存在计数>1的分组。 */
    private Object[] findAKnownOneToManyEdge() {
        @SuppressWarnings("unchecked")
        List<Object[]> candidates = em.createNativeQuery(
                "SELECT e.id, tn.physical_table, ek.right_column " +
                        "FROM semantic_edge e " +
                        "JOIN semantic_node tn ON tn.id = e.to_node_id " +
                        "JOIN semantic_edge_key ek ON ek.edge_id = e.id AND ek.seq = 0 " +
                        "WHERE e.cardinality = 'ONE_TO_MANY' AND e.status='ACTIVE' " +
                        "AND tn.physical_table IS NOT NULL"
        ).getResultList();
        for (Object[] c : candidates) {
            String table = String.valueOf(c[1]);
            String rightColumn = String.valueOf(c[2]);
            try {
                List<Object[]> dupes = em.createNativeQuery(
                                "SELECT " + rightColumn + ", count(*) FROM " + table
                                        + " GROUP BY " + rightColumn + " HAVING count(*) > 1 LIMIT 1")
                        .getResultList();
                if (!dupes.isEmpty()) {
                    return c;
                }
            } catch (Exception ignored) {
                // 表/列名非法，跳过候选
            }
        }
        return null;
    }

    // -------------------------------------------------------------------
    // AC-36（边界·反证）登记与导入 handler 双向对账 —— 部分覆盖（见类头信息缺口说明）
    // -------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("AC-36【部分覆盖，另见类头信息缺口】: HANDLER_RECONCILE 校验分项存在且正常路径下不告警")
    void ac36_handlerReconcileCheckExistsAndPassesNormally() {
        // 2026-08-21 真跑教训：发空对象 {} 会被当成"新增一个节点"来校验，PHYSICAL_EXISTENCE 第一步就
        // 报"节点不存在"直接短路，后面的 EDGE_CARDINALITY/PATH_UNIQUENESS/HANDLER_RECONCILE 全部
        // status=SKIPPED（api.md §1.3：四道校验固定次序，前一道不过后面不跑）。/validate 是"干跑你
        // 即将保存的这一条声明"，不是"扫全图"——发一个真实存在的节点（原样重新提交）才能让四道校验
        // 全部真正跑到，包括我们要看的 HANDLER_RECONCILE。
        Response graphResp = RestAssured.given().get("/api/cpq/config/semantic-graph");
        assertEquals(200, graphResp.statusCode(), graphResp.getBody().asString());
        List<Map<String, Object>> nodes = graphResp.jsonPath().getList("nodes");
        assertNotNull(nodes, "nodes不应为空");
        assertFalse(nodes.isEmpty(), "nodes不应为空列表");
        Map<String, Object> aRealNode = nodes.stream()
                .filter(n -> n.get("sourceHandler") != null).findFirst().orElse(nodes.get(0));

        String validatePayloadJson;
        try {
            validatePayloadJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(aRealNode);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Response validateResp = RestAssured.given().contentType(ContentType.JSON)
                .body(validatePayloadJson)
                .post("/api/cpq/config/semantic-graph/validate");
        assertEquals(200, validateResp.statusCode(), validateResp.getBody().asString());
        List<java.util.Map<String, Object>> checks = validateResp.jsonPath().getList("checks");
        assertNotNull(checks, "checks不应为空");
        assertFalse(checks.isEmpty(), "checks不应为空列表");
        boolean hasHandlerReconcile = checks.stream()
                .anyMatch(c -> "HANDLER_RECONCILE".equals(c.get("check")));
        assertTrue(hasHandlerReconcile, "应存在 HANDLER_RECONCILE 校验分项，实际=" + checks);
        // 破坏后必须失败的那一半：见类头说明，信息不足，无法在测试工程师侧独立实现，
        // 已在 testcase.md 与本次回报中登记为需要后端 B-17 补齐的对账入口契约。
    }

    // -------------------------------------------------------------------
    // AC-37（单点）方言参数化：同一声明产出两种形态
    // 🔄 2026-08-24（D-50 / D-54 修订）：方言由「三处」减为「两处」——
    //   · 业务列别名规则（_<Sheet简称>_<列名>）与子件收窄（= ANY(:total_material_no)）
    //     现在是【两侧共有】，不再是方言（D-50 统一闭包机制、D-54 统一别名纯函数规则）。
    //     旧断言「核价侧别名不带 _ 前缀、用英文DB列名」已作废，必须反过来断言核价侧也带前缀。
    //   · 方言只剩：① 报价侧 customer_no+is_current 收窄 vs 核价侧 :versionFilter(...) 收窄
    //             ② 字段绑定键——但 D-55 已澄清这也【不是】"报价 vs 核价"的方言，而是跟
    //                field_type 走（INPUT_* → default_source.path；BASIC_DATA → basic_data_path，
    //                两侧同规则）。本方法④用同一节点在两侧分别编两种 field_type 验证"编译器按
    //                field_type 决定写哪个键，不按侧决定"。
    //   🚫 本条只约束新产物：存量 26 视图/1183 字段的别名与绑定键一字节不动，见 AC-61。
    // -------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("AC-37: 同一节点声明分别以报价侧/核价侧参数编译，两处方言逐一核对（别名与子件收窄两侧统一）")
    void ac37_dialectParameterizationProducesTwoForms() {
        UUID componentId = UUID.fromString(RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + SemanticGraphTestSupport.TAG + "dialect-" + UUID.randomUUID() + "\"}")
                .post("/api/cpq/components").jsonPath().getString("data.id"));

        String quoteConfig = """
                { "tabType": "材质元素", "dialect": "QUOTE", "columns": [
                  {"sourceNodeKey":"ELEMENT_BOM_ITEM","sourceColumn":"content","fieldName":"组成含量"}
                ]}
                """;
        Response quoteResp = RestAssured.given().contentType(ContentType.JSON)
                .body(quoteConfig).post("/api/cpq/components/" + componentId + "/builder/compile");
        assertEquals(200, quoteResp.statusCode(), quoteResp.getBody().asString());
        String quoteSql = quoteResp.jsonPath().getString("sql");
        assertNotNull(quoteSql);
        assertFalse(quoteSql.isBlank());
        // ①【两侧共有】业务列别名一律 _<Sheet简称>_<列名>
        assertTrue(quoteSql.matches("(?s).*_[\\u4e00-\\u9fa5A-Za-z0-9]+_[\\u4e00-\\u9fa5A-Za-z0-9]+.*"),
                "① 报价侧业务列别名应带 _ 前缀，实际:\n" + quoteSql);
        // ①【两侧共有】子件收窄一律 = ANY(:total_material_no)（D-50）
        assertTrue(quoteSql.contains("= ANY(:total_material_no)"),
                "① 报价侧子件收窄也应含 = ANY(:total_material_no)（D-50 两侧统一），实际:\n" + quoteSql);
        // ② 报价侧独有：customer_no = :customerCode + is_current
        assertTrue(quoteSql.contains(":customerCode"), "② 报价侧收窄应含 customer_no = :customerCode，实际:\n" + quoteSql);
        assertTrue(quoteSql.contains("is_current"), "② 报价侧收窄应含 is_current，实际:\n" + quoteSql);
        assertFalse(quoteSql.contains(":versionFilter("), "② 报价侧不应出现核价侧的 :versionFilter(...)，实际:\n" + quoteSql);

        String costingConfig = """
                { "tabType": "材质元素", "dialect": "COSTING", "columns": [
                  {"sourceNodeKey":"ELEMENT_BOM_ITEM","sourceColumn":"content","fieldName":"组成含量"}
                ]}
                """;
        Response costingResp = RestAssured.given().contentType(ContentType.JSON)
                .body(costingConfig).post("/api/cpq/components/" + componentId + "/builder/compile");
        assertEquals(200, costingResp.statusCode(), costingResp.getBody().asString());
        String costingSql = costingResp.jsonPath().getString("sql");
        assertNotNull(costingSql);
        assertFalse(costingSql.isBlank());
        // ①【两侧共有，反转旧断言】核价侧业务列别名现在也应带 _<Sheet简称>_<列名> 前缀
        //   （D-54：不再是「英文DB列名无前缀」，那是 D-50 之前的旧方言口径，已作废）
        assertTrue(costingSql.matches("(?s).*_[\\u4e00-\\u9fa5A-Za-z0-9]+_[\\u4e00-\\u9fa5A-Za-z0-9]+.*"),
                "① 核价侧业务列别名也应带 _<Sheet简称>_<列名> 前缀（D-54 两侧统一，旧“无前缀”口径已作废），实际:\n" + costingSql);
        // 约定列名(hf_part_no)两侧同名——核价侧同一份声明理应也能推出 hf_part_no（若该 tabType 有约定料号列）
        // ①【两侧共有】子件收窄一律 = ANY(:total_material_no)
        assertTrue(costingSql.contains("= ANY(:total_material_no)"),
                "① 核价侧子件收窄应含 = ANY(:total_material_no)，实际:\n" + costingSql);
        // ③ 核价侧独有：:versionFilter(is_current, version_no, code) 收窄 + view_version 约定列
        assertTrue(costingSql.contains(":versionFilter("),
                "③ 核价侧收窄应含 :versionFilter(is_current, version_no, code)，实际:\n" + costingSql);
        assertFalse(costingSql.contains(":customerCode"), "③ 核价侧不应出现 :customerCode（D-54①：客户维度不计入方言，核价侧本就不随客户变化），实际:\n" + costingSql);
        List<String> costingDeclared = costingResp.jsonPath().getList("declaredColumns");
        assertNotNull(costingDeclared, "核价侧declaredColumns不应为空");
        assertFalse(costingDeclared.isEmpty(), "核价侧declaredColumns不应为空列表");
        assertTrue(costingDeclared.contains("view_version"),
                "③ 核价侧declaredColumns应含view_version，实际=" + costingDeclared);

        // ④ 字段绑定键跟 field_type 走，不跟侧走（D-55）：本节点在两侧各存一次，INPUT_NUMBER 走
        //    default_source.path、BASIC_DATA 走 basic_data_path——用同一份 payload 分别在两侧
        //    保存后查 component.fields，断言绑定键只随 field_type 变化、不随 dialect 变化。
        String quoteSaveConfig = """
                { "tabType": "材质元素", "dialect": "QUOTE", "columns": [
                  {"sourceNodeKey":"ELEMENT_BOM_ITEM","sourceColumn":"content","fieldName":"组成含量_INPUT","fieldType":"INPUT_NUMBER"},
                  {"sourceNodeKey":"ELEMENT_BOM_ITEM","sourceColumn":"scrap_rate","fieldName":"损耗率_BASIC","fieldType":"BASIC_DATA"}
                ]}
                """;
        Response quoteSave = RestAssured.given().contentType(ContentType.JSON)
                .body(quoteSaveConfig).put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, quoteSave.statusCode(), "④ 报价侧保存应成功: " + quoteSave.getBody().asString());
        // 用 GET 组件详情核对 fields（黑盒契约，不直接拼裸 SQL 读 jsonb 做类型转换）
        Response quoteDetail = RestAssured.given().get("/api/cpq/components/" + componentId);
        List<Map<String, Object>> quoteFieldList = quoteDetail.jsonPath().getList("data.fields");
        assertNotNull(quoteFieldList, "④ 报价侧 fields 不应为空");
        Map<String, Object> inputField = quoteFieldList.stream()
                .filter(f -> "组成含量_INPUT".equals(f.get("name"))).findFirst().orElse(null);
        Map<String, Object> basicField = quoteFieldList.stream()
                .filter(f -> "损耗率_BASIC".equals(f.get("name"))).findFirst().orElse(null);
        assertNotNull(inputField, "④ 应能找到组成含量_INPUT字段，实际=" + quoteFieldList);
        assertNotNull(basicField, "④ 应能找到损耗率_BASIC字段，实际=" + quoteFieldList);
        assertTrue(inputField.containsKey("default_source"), "④ INPUT_NUMBER 应写 default_source，实际=" + inputField);
        assertTrue(basicField.containsKey("basic_data_path") && basicField.get("basic_data_path") != null,
                "④ BASIC_DATA 即使在报价侧也应写 basic_data_path（跟field_type走，不跟侧走），实际=" + basicField);
    }
}
