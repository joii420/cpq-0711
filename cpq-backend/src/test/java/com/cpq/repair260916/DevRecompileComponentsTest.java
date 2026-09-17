package com.cpq.repair260916;

import com.cpq.builder.service.BuilderRecompileService;
import com.cpq.common.security.RoleAllowed;
import com.cpq.configcenter.ConfigCenterResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260916 B-6：<b>后端开发者自测</b>（测试工程师的验收用例在同包、不带 {@code Dev} 前缀，互不修改）。
 *
 * <p>覆盖：按组件预览零写入 · 执行只改所列组件视图 · 全量路径计数/键集合不变 · 404/400 零写入 ·
 * 坏配置整体回滚 · 第二次执行 {@code changed=0} · 迁移 V444 连线结构。
 *
 * <p>数据：全部自造（组件编码前缀 {@code R260916-B-}），id 由本轮随机高位 + 序号构成，
 * 🚫 不读写任何存量组件；{@code @AfterAll} 只按本轮自造 id 精确删除。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DevRecompileComponentsTest {

    static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    static final long MSB = UUID.randomUUID().getMostSignificantBits();
    /** 序号越小 uuid 越小 ⇒ 视图按 componentId 排序时的处理顺序可控（坏配置测试依赖它）。 */
    static UUID fid(int n) { return new UUID(MSB, 0x1000L + n); }

    static final UUID A = fid(1);        // INCOMING_FIXED_FEE，旧文本（无材质表）
    static final UUID B = fid(2);        // SELF_PROCESS_FEE，旧文本
    static final UUID C = fid(3);        // 不在请求里的对照组件（同数据源、旧文本）
    static final UUID NOVIEW = fid(4);   // 没有取数配置器视图
    static final UUID CORRUPT = fid(5);  // builder_config 无法反序列化（排在 A 之后处理）
    static final UUID D = fid(6);        // 坏配置测试专用（A 已被前面的用例改过，另起一个）
    static final List<UUID> ALL = List.of(A, B, C, NOVIEW, CORRUPT, D);

    static final String CFG_FIXED = "{\"columns\": ["
            + "{\"fieldName\": \"销售料号\", \"viewColumn\": \"_来料固定加工费_销售料号\", \"sourceColumn\": \"material_no\", \"resolvedRoles\": [\"ROW_KEY\"], \"sourceNodeKey\": \"INCOMING_FIXED_FEE\", \"resolvedDataType\": \"TEXT\"},"
            + "{\"fieldName\": \"项次\", \"viewColumn\": \"_来料固定加工费_项次\", \"sourceColumn\": \"item_seq\", \"resolvedRoles\": [\"SORT\"], \"sourceNodeKey\": \"INCOMING_FIXED_FEE\", \"resolvedDataType\": \"NUMBER\"},"
            + "{\"fieldName\": \"材料名\", \"viewColumn\": \"_物料_材料名\", \"sourceColumn\": \"material_name\", \"resolvedRoles\": [\"PART_NAME\"], \"sourceNodeKey\": \"MAT_NAME_LK\", \"resolvedDataType\": \"TEXT\"},"
            + "{\"fieldName\": \"料号\", \"viewColumn\": \"_来料固定加工费_投入料号\", \"sourceColumn\": \"input_material_no\", \"resolvedRoles\": [\"PART_NO\", \"ROW_KEY\"], \"sourceNodeKey\": \"INCOMING_FIXED_FEE\", \"resolvedDataType\": \"TEXT\"},"
            + "{\"fieldName\": \"基准值\", \"viewColumn\": \"_来料固定加工费_基准值\", \"sourceColumn\": \"base_value\", \"resolvedRoles\": [], \"sourceNodeKey\": \"INCOMING_FIXED_FEE\", \"resolvedDataType\": \"NUMBER\"}"
            + "], \"dialect\": \"QUOTE\", \"tabType\": \"费用类\", \"switches\": null, \"axisScope\": \"CLOSURE\", \"variantKey\": \"INCOMING_FIXED_FEE\", \"priceStrategy\": null, \"builderVersion\": null}";

    static final String CFG_SELF = "{\"columns\": ["
            + "{\"fieldName\": \"销售料号\", \"viewColumn\": \"_自制加工费_销售料号\", \"sourceColumn\": \"material_no\", \"resolvedRoles\": [\"ROW_KEY\"], \"sourceNodeKey\": \"SELF_PROCESS_FEE\", \"resolvedDataType\": \"TEXT\"},"
            + "{\"fieldName\": \"料号\", \"viewColumn\": \"_自制加工费_投入料号\", \"sourceColumn\": \"input_material_no\", \"resolvedRoles\": [\"PART_NO\", \"ROW_KEY\"], \"sourceNodeKey\": \"SELF_PROCESS_FEE\", \"resolvedDataType\": \"TEXT\"},"
            + "{\"fieldName\": \"材料名\", \"viewColumn\": \"_物料_材料名\", \"sourceColumn\": \"material_name\", \"resolvedRoles\": [\"PART_NAME\"], \"sourceNodeKey\": \"MAT_NAME_LK\", \"resolvedDataType\": \"TEXT\"},"
            + "{\"fieldName\": \"值\", \"viewColumn\": \"_自制加工费_值\", \"sourceColumn\": \"value\", \"resolvedRoles\": [], \"sourceNodeKey\": \"SELF_PROCESS_FEE\", \"resolvedDataType\": \"NUMBER\"}"
            + "], \"dialect\": \"QUOTE\", \"tabType\": \"费用类\", \"switches\": null, \"axisScope\": \"CLOSURE\", \"variantKey\": \"SELF_PROCESS_FEE\", \"priceStrategy\": null, \"builderVersion\": null}";

    /** V444 之前编译器对 CFG_FIXED 的产物形态（只连物料表）——作为「旧文本」落库。 */
    static final String OLD_SQL = "SELECT\n  dqiff.material_no AS hf_part_no,\n"
            + "  dqm.material_name AS \"_物料_材料名\"\n"
            + "FROM ds_quote_incoming_fixed_fee dqiff\n"
            + "  LEFT JOIN ds_quote_material dqm ON dqm.material_no = dqiff.input_material_no AND dqm.customer_no = dqiff.customer_no\n"
            + "WHERE dqiff.material_no = ANY(:total_material_no) AND dqiff.customer_no = :customerCode";

    @Inject EntityManager em;
    @Inject BuilderRecompileService recompileService;

    static String session;

    static String viewName(UUID cid) { return "r260916b_" + RUN + "_" + (cid.getLeastSignificantBits() - 0x1000L); }

    @BeforeAll
    static void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            EntityManager em = io.quarkus.arc.Arc.container().instance(EntityManager.class).get();
            int i = 0;
            for (UUID id : ALL) {
                i++;
                em.createNativeQuery("INSERT INTO component (id, name, code) VALUES (?1, ?2, ?3)")
                        .setParameter(1, id).setParameter(2, "R260916-B-" + RUN + "-" + i)
                        .setParameter(3, "R260916-B-" + RUN + "-" + i).executeUpdate();
            }
            insertView(em, A, CFG_FIXED);
            insertView(em, B, CFG_SELF);
            insertView(em, C, CFG_FIXED);
            insertView(em, CORRUPT, "[1, 2]");
            insertView(em, D, CFG_FIXED);
        });
    }

    static void insertView(EntityManager em, UUID cid, String cfg) {
        em.createNativeQuery("INSERT INTO component_sql_view (component_id, sql_view_name, sql_template, "
                        + "builder_config, builder_version) VALUES (?1, ?2, ?3, CAST(?4 AS jsonb), 1)")
                .setParameter(1, cid).setParameter(2, viewName(cid)).setParameter(3, OLD_SQL)
                .setParameter(4, cfg).executeUpdate();
    }

    @AfterAll
    static void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            EntityManager em = io.quarkus.arc.Arc.container().instance(EntityManager.class).get();
            UUID[] ids = ALL.toArray(new UUID[0]);
            em.createNativeQuery("DELETE FROM operation_log WHERE target_id = ANY(?1) AND operation_type = 'COMPONENT_VIEW_RECOMPILE'")
                    .setParameter(1, ids).executeUpdate();
            em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = ANY(?1)").setParameter(1, ids).executeUpdate();
            em.createNativeQuery("DELETE FROM component WHERE id = ANY(?1) AND code LIKE ?2")
                    .setParameter(1, ids).setParameter(2, "R260916-B-" + RUN + "-%").executeUpdate();
        });
    }

    // ───────────────────────────── helpers ─────────────────────────────

    String session() {
        if (session == null) {
            QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                    "UPDATE \"user\" SET failed_login_attempts = 0, locked_until = NULL WHERE username = 'admin'").executeUpdate());
            Response r = RestAssured.given().contentType(ContentType.JSON)
                    .body("{\"username\":\"admin\",\"password\":\"Admin@2026\"}").post("/api/cpq/auth/login");
            assertEquals(200, r.statusCode(), "admin 登录失败（环境前置，不是产品结论）: " + r.asString());
            session = r.cookie("CPQ_SESSION");
            assertNotNull(session);
        }
        return session;
    }

    Response call(String body) {
        return RestAssured.given().cookie("CPQ_SESSION", session()).contentType(ContentType.JSON)
                .body(body).post("/api/cpq/config-center/recompile-components");
    }

    static String ids(UUID... u) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < u.length; i++) sb.append(i == 0 ? "" : ",").append('"').append(u[i]).append('"');
        return sb.append(']').toString();
    }

    /** 本轮自造数据 + 指向自造组件的审计行数的「状态指纹」：零写入判据（共库，🚫 不用全局计数）。 */
    String fingerprint() {
        return QuarkusTransaction.requiringNew().call(() -> String.valueOf(em.createNativeQuery(
                "SELECT md5(COALESCE((SELECT string_agg(v.id||v.sql_template||v.declared_columns::text||"
                        + "COALESCE(v.builder_config::text,'')||COALESCE(v.builder_version,-1)||v.updated_at, '|' ORDER BY v.id) "
                        + "FROM component_sql_view v WHERE v.component_id = ANY(?1)),'') "
                        + "|| COALESCE((SELECT string_agg(c.id||c.fields::text||c.formulas::text||c.updated_at, '|' ORDER BY c.id) "
                        + "FROM component c WHERE c.id = ANY(?1)),'')) "
                        + "|| ':' || (SELECT count(*) FROM operation_log WHERE target_id = ANY(?1))")
                .setParameter(1, ALL.toArray(new UUID[0])).getSingleResult()));
    }

    String sqlOf(UUID cid) {
        return QuarkusTransaction.requiringNew().call(() -> (String) em.createNativeQuery(
                "SELECT sql_template FROM component_sql_view WHERE component_id = ?1").setParameter(1, cid).getSingleResult());
    }

    // ───────────────────────────── tests ─────────────────────────────

    @Test @Order(1)
    void migrationV444_elevenSourcesHaveTwoBranchPartNameGroup() {
        long ok = QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM (SELECT f.dialect, f.node_key, string_agg(e.fallback_order||':'||t.node_key, ',' ORDER BY e.fallback_order) sig "
                        + "FROM semantic_edge e JOIN semantic_node f ON f.id=e.from_node_id JOIN semantic_node t ON t.id=e.to_node_id "
                        + "WHERE e.status='ACTIVE' AND e.edge_kind='LOOKUP' AND e.coalesce_group='PART_NAME' "
                        + "AND f.node_key IN ('INCOMING_FIXED_FEE','INCOMING_OTHER_FEE','INCOMING_RECOVERY','INCOMING_ANNUAL','SELF_PROCESS_FEE','INCOMING_OTHER_FIXED_FEE','INCOMING_PROCESS_FEE') "
                        + "GROUP BY 1,2) x WHERE sig = '1:MAT_NAME_LK,2:RECIPE_NAME_LK'").getSingleResult()).longValue());
        assertEquals(11, ok, "V444 后 11 个数据源应各有 PART_NAME 组 1:物料表 / 2:材质表");
        String success = QuarkusTransaction.requiringNew().call(() -> String.valueOf(em.createNativeQuery(
                "SELECT success FROM flyway_schema_history WHERE version = '444'").getSingleResult()));
        assertEquals("true", success);
    }

    @Test @Order(2)
    void preview_zeroWrite_andShape() {
        String before = fingerprint();
        Response r = call("{\"componentIds\":" + ids(B, A, NOVIEW, A) + "}");   // 乱序 + 重复
        assertEquals(200, r.statusCode(), r.asString());
        String after = fingerprint();
        assertEquals(before, after, "预览必须零写入（含 operation_log 行数）");

        Map<String, Object> d = r.jsonPath().getMap("data");
        assertEquals(true, d.get("preview"));
        assertEquals(3, d.get("componentCount"), "去重后 3 个");
        assertEquals(2, d.get("views"));
        assertEquals(2, d.get("changed"));
        assertEquals(List.of(NOVIEW.toString()), d.get("skippedComponentIds"));
        assertEquals(List.of(), d.get("unchangedViewNames"));
        assertEquals(List.of("preview", "componentCount", "views", "changed", "changes",
                "unchangedViewNames", "skippedComponentIds", "warning"), new ArrayList<>(d.keySet()));
        List<Map<String, Object>> changes = r.jsonPath().getList("data.changes");
        assertEquals(List.of(viewName(A), viewName(B)), changes.stream().map(m -> m.get("sqlViewName")).toList(),
                "按 componentCode 升序（-1 在 -2 之前）");
        for (Map<String, Object> m : changes) {
            assertTrue(String.valueOf(m.get("newSqlTemplate")).contains("material_recipe"), m.toString());
            assertFalse(String.valueOf(m.get("oldSqlTemplate")).contains("material_recipe"));
            assertNotNull(m.get("componentCode"));
            assertNotNull(m.get("componentName"));
        }
        String newA = String.valueOf(changes.get(0).get("newSqlTemplate"));
        assertTrue(newA.contains("COALESCE(dqm.material_name, mr.symbol) AS \"_物料_材料名\""), newA);
        assertTrue(newA.contains("LEFT JOIN material_recipe mr ON mr.code = dqiff.input_material_no\n"), newA);
        assertTrue(newA.contains("LEFT JOIN ds_quote_material dqm ON dqm.material_no = dqiff.input_material_no AND dqm.customer_no = dqiff.customer_no"), newA);
    }

    @Test @Order(3)
    void badRequests_zeroWrite() {
        String before = fingerprint();
        Response r1 = call("{}");
        assertEquals(400, r1.statusCode());
        assertEquals("COMPONENT_IDS_REQUIRED", r1.jsonPath().getString("code"));
        Response r2 = call("{\"componentIds\":[],\"confirm\":true}");
        assertEquals(400, r2.statusCode());
        assertEquals("COMPONENT_IDS_REQUIRED", r2.jsonPath().getString("code"));
        Response r3 = call("{\"componentIds\":[\"" + A + "\",\"x-1\",\"1-1-1-1-1\"],\"confirm\":true}");
        assertEquals(400, r3.statusCode());
        assertEquals("INVALID_COMPONENT_ID", r3.jsonPath().getString("code"));
        assertEquals(List.of("x-1", "1-1-1-1-1"), r3.jsonPath().getList("invalidIds"));
        UUID ghost = new UUID(MSB, 0x9999L);
        Response r4 = call("{\"componentIds\":" + ids(A, ghost, B) + ",\"confirm\":true}");
        assertEquals(404, r4.statusCode());
        assertEquals("COMPONENT_NOT_FOUND", r4.jsonPath().getString("code"));
        assertEquals(List.of(ghost.toString()), r4.jsonPath().getList("missingIds"));
        assertEquals(before, fingerprint(), "400/404 必须零写入（存在的 A/B 也不写）");
        Response r5 = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"componentIds\":" + ids(A) + "}").post("/api/cpq/config-center/recompile-components");
        assertEquals(401, r5.statusCode());
    }

    @Test @Order(4)
    void corruptConfig_rollsBackWholeList() {
        String before = fingerprint();
        // 处理顺序按 componentId：D(6) 在 CORRUPT(5) 之后 —— 用 A 不行（A 在后续用例要用），故先放一个排在 CORRUPT 之前的
        // 组件：B(2) 已排在 CORRUPT 之前，B 会先被写（持久化上下文里），随后 CORRUPT 抛错 ⇒ 必须整体回滚。
        Response r = call("{\"componentIds\":" + ids(B, CORRUPT) + ",\"confirm\":true}");
        assertEquals(500, r.statusCode(), r.asString());
        assertEquals("RECOMPILE_CONFIG_CORRUPT", r.jsonPath().getString("code"));
        assertEquals(viewName(CORRUPT), r.jsonPath().getString("sqlViewName"));
        assertEquals(before, fingerprint(), "坏配置 ⇒ 同列表中已处理的 B 也不能落库");
        assertEquals(OLD_SQL, sqlOf(B));
        // 坏配置视图留在库里会让「全量重编译」整体 500（既有纪律），后续用例要跑全量预览 ⇒ 把它停用（仅本轮自造行）
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE component_sql_view SET status = 'INACTIVE' WHERE component_id = ?1 AND sql_view_name = ?2")
                .setParameter(1, CORRUPT).setParameter(2, viewName(CORRUPT)).executeUpdate());
    }

    @Test @Order(5)
    void execute_onlyListedViews_auditPerView_sameAsFullPath() {
        String cBefore = sqlOf(C);
        Response pv = call("{\"componentIds\":" + ids(A, B) + "}");
        Map<String, String> previewNew = new HashMap<>();
        for (Map<String, Object> m : pv.jsonPath().<Map<String, Object>>getList("data.changes")) {
            previewNew.put(String.valueOf(m.get("componentId")), String.valueOf(m.get("newSqlTemplate")));
        }
        // 全量路径（零写入预览）对同视图的产物
        BuilderRecompileService.RecompileOutcome full = recompileService.previewRecompile(List.of());
        long opBefore = QuarkusTransaction.requiringNew().call(() ->
                ((Number) em.createNativeQuery("SELECT count(*) FROM operation_log WHERE target_id = ANY(?1)")
                        .setParameter(1, ALL.toArray(new UUID[0])).getSingleResult()).longValue());

        Response r = call("{\"componentIds\":" + ids(A, B) + ",\"confirm\":true}");
        assertEquals(200, r.statusCode(), r.asString());
        Map<String, Object> d = r.jsonPath().getMap("data");
        assertEquals(List.of("preview", "componentCount", "views", "changed", "changedViewNames",
                "unchangedViewNames", "skippedComponentIds", "operationLogIds"), new ArrayList<>(d.keySet()));
        assertEquals(false, d.get("preview"));
        assertEquals(2, d.get("changed"));
        List<String> names = r.jsonPath().getList("data.changedViewNames");
        assertEquals(List.of(viewName(A), viewName(B)), names);
        List<String> logIds = r.jsonPath().getList("data.operationLogIds");
        assertEquals(2, logIds.size());

        for (UUID cid : List.of(A, B)) {
            String now = sqlOf(cid);
            assertEquals(previewNew.get(cid.toString()), now, "落库文本 = 预览 newSqlTemplate");
            assertEquals(full.newSqlByKey.get(cid + "::" + viewName(cid)), now, "落库文本 = 全量重编译产物");
        }
        assertEquals(cBefore, sqlOf(C), "不在请求里的同数据源组件不许动");
        long opAfter = QuarkusTransaction.requiringNew().call(() ->
                ((Number) em.createNativeQuery("SELECT count(*) FROM operation_log WHERE target_id = ANY(?1)")
                        .setParameter(1, ALL.toArray(new UUID[0])).getSingleResult()).longValue());
        assertEquals(opBefore + 2, opAfter);

        // 审计行与 changedViewNames 一一对应、同序
        for (int i = 0; i < 2; i++) {
            final String lid = logIds.get(i);
            Object[] row = QuarkusTransaction.requiringNew().call(() -> (Object[]) em.createNativeQuery(
                    "SELECT operation_type, target_type, target_id::text, summary, details->>'sqlViewName', "
                            + "details->>'source', (details->>'builderVersion'), details->>'newSqlMd5' = md5(v.sql_template) "
                            + "FROM operation_log l JOIN component_sql_view v ON v.component_id = l.target_id "
                            + "WHERE l.id = CAST(?1 AS uuid)").setParameter(1, lid).getSingleResult());
            UUID cid = i == 0 ? A : B;
            assertEquals("COMPONENT_VIEW_RECOMPILE", row[0]);
            assertEquals("COMPONENT", row[1]);
            assertEquals(cid.toString(), row[2]);
            assertEquals("按组件重编译取数视图 " + names.get(i), row[3]);
            assertEquals(names.get(i), row[4]);
            assertEquals("recompile-components", row[5]);
            assertEquals("1", row[6]);
            assertEquals(true, row[7]);
        }
        // component 表不动：fields/formulas/updated_at 与插入时相同（插入时 fields='[]'）
        long compTouched = QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM component WHERE id = ANY(?1) AND (fields::text <> '[]' OR updated_at <> created_at)")
                .setParameter(1, new UUID[]{A, B}).getSingleResult()).longValue());
        assertEquals(0, compTouched);
    }

    @Test @Order(6)
    void secondExecute_changedZero_noAudit() {
        String before = fingerprint();
        Response r = call("{\"componentIds\":" + ids(A, B) + ",\"confirm\":true}");
        assertEquals(200, r.statusCode(), r.asString());
        assertEquals(0, r.jsonPath().getInt("data.changed"));
        assertEquals(List.of(), r.jsonPath().getList("data.operationLogIds"));
        assertEquals(List.of(viewName(A), viewName(B)), r.jsonPath().getList("data.unchangedViewNames"));
        assertEquals(before, fingerprint(), "第二次执行零写入（含 updated_at 与审计行数）");
    }

    @Test @Order(7)
    void fullPath_countsAndKeysUnchanged() {
        long builderViews = QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM component_sql_view WHERE builder_config IS NOT NULL AND status = 'ACTIVE'")
                .getSingleResult()).longValue());
        String before = fingerprint();
        Response r = RestAssured.given().cookie("CPQ_SESSION", session()).contentType(ContentType.JSON)
                .body("{\"recompile\":true,\"confirm\":false,\"templateIds\":[]}")
                .post("/api/cpq/config-center/refresh-all-snapshots");
        assertEquals(200, r.statusCode(), r.asString());
        Map<String, Object> d = r.jsonPath().getMap("data");
        // 改动前基线（证据/基线-改动前/refresh-all-snapshots-预览-8081.json）的键集合
        assertEquals(new TreeSet<>(List.of("affectedQuotationCount", "affectedTemplateCount", "affectedTemplates",
                "axisScopeToWrite", "preview", "recompile", "recompileChanged", "recompileChangedViewNames",
                "recompileViews", "snapshotEntries", "snapshotEntriesStale", "snapshotStaleSamples",
                "snapshotTemplates", "warning")), new TreeSet<>(d.keySet()));
        assertEquals(before, fingerprint(), "全量预览零写入");
        assertEquals((int) builderViews, d.get("recompileViews"));
    }

    @Test @Order(0)
    void annotationsAndSignatures() throws Exception {
        RoleAllowed ra = ConfigCenterResource.class.getMethod("recompileComponents",
                Map.class, io.vertx.core.http.HttpServerRequest.class).getAnnotation(RoleAllowed.class);
        assertArrayEquals(new String[]{"SYSTEM_ADMIN"}, ra.value());
        // AC-9：既有公开方法签名不变
        assertNotNull(BuilderRecompileService.class.getMethod("previewRecompile", List.class));
        assertNotNull(BuilderRecompileService.class.getMethod("recompileAndRealign", List.class, UUID.class));
        assertNotNull(BuilderRecompileService.class.getMethod("recompileComponents", List.class, UUID.class)
                .getAnnotation(jakarta.transaction.Transactional.class));
        assertNull(BuilderRecompileService.class.getMethod("previewRecompileComponents", List.class)
                .getAnnotation(jakarta.transaction.Transactional.class));
    }
}
