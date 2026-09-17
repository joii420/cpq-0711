package com.cpq.repair260916;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
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

/**
 * repair-260916 · S-2 · <b>AC-8 按组件重编译的参数与权限</b>（T2.5 ~ T2.10）。
 *
 * <blockquote>AC-8 原文（问题说明 ⑥ · 边界）：<br>
 * ① {@code componentIds} 缺失或为空数组 → HTTP 400，body {@code code='COMPONENT_IDS_REQUIRED'}，零写入；<br>
 * ② {@code componentIds} 里有<b>不存在</b>的 id（与存在的 id 混在一起）→ HTTP 404，{@code code='COMPONENT_NOT_FOUND'}，
 *    body 列出缺失的 id；<b>存在的那些也不写</b>；<br>
 * ③ 含一个<b>没有取数配置器视图</b>的组件 → HTTP 200，该 id 出现在 {@code skippedComponentIds}，不写它，其余照常；<br>
 * ④ 对同一组组件<b>连续执行两次</b> {@code confirm=true} → 第二次 {@code changed=0}、{@code operationLogIds} 为空、
 *    {@code operation_log} 行数不再增加；<br>
 * ⑤ 非 SYSTEM_ADMIN 角色（如 SALES_REP）调用 → HTTP 403；未登录 → HTTP 401；<br>
 * ⑥ 列表中某个组件的 {@code builder_config} 无法反序列化 → HTTP 500，{@code code='RECOMPILE_CONFIG_CORRUPT'}，
 *    <b>列表中其他组件的视图也不写</b>（整体回滚）。
 * </blockquote>
 * 契约细节（字段名、错误体裸格式、审计列）取自 {@code api.md §1}。
 *
 * <h3>夹具：「过期视图」怎么造（全在本片自建组件上）</h3>
 * 自建组件 → {@code PUT /builder} 保存（此刻按当前连表配置编译，文本已是最新）→
 * 在<b>自己那一行</b>的 {@code sql_template} 前面加一段注释标记 {@link #STALE_MARK}。
 * ⇒ 该视图「已落库文本 ≠ 当前编译产物」，重编译执行时<b>必然</b>改写它、并写 1 行审计。
 * 这样「零写入」断言才有靶子：若实现错误地写了，标记会消失 / {@code updated_at} 会变 / 审计会多一行。
 *
 * <h3>零写入的观察手段 + 阳性对照（testing.md §4.4 / test.md §4 E-2）</h3>
 * {@link #snapshot(UUID)} = 该组件全部视图的（名 / sql md5 / declared_columns / builder_config / builder_version / updated_at）
 * + 组件（fields / formulas / row_key_fields / updated_at）md5 + 按该组件 id 过滤的 {@code operation_log} 行数。
 * ② 与 ⑥ 在错误请求<b>之前</b>先用同一观察手段看到一次真实写入，证明它不是恒等。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ac8RecompileComponentsTest extends R260916TBase {

    private static final String ENDPOINT = "/api/cpq/config-center/recompile-components";
    private static final String STALE_MARK = "/* " + PREFIX + "STALE */ ";

    @BeforeEach
    void setUp() {
        assertSharedPreconditions();
    }

    @AfterEach
    void tearDown() {
        cleanupAll();
    }

    // ═══════════════════ 夹具 ═══════════════════

    private static Map<String, Object> col(String nodeKey, String column, String fieldName, boolean partNo) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sourceNodeKey", nodeKey);
        c.put("sourceColumn", column);
        c.put("fieldName", fieldName);
        if (partNo) {
            c.put("isRowKey", true);
            c.put("isPartNo", true);
        }
        return c;
    }

    private static Map<String, Object> builderSaveBody() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dialect", "QUOTE");
        m.put("tabType", "费用类");
        m.put("variantKey", "INCOMING_FIXED_FEE");
        m.put("columns", List.of(
                col("INCOMING_FIXED_FEE", "input_material_no", "料号", true),
                col("MAT_NAME_LK", "material_name", "材料名", false),
                col("INCOMING_FIXED_FEE", "base_value", "基准值", false)));
        m.put("confirmedImpact", false);
        return m;
    }

    /** 建一个带取数配置器视图的组件（PUT /builder 真实保存），返回组件 id。 */
    private UUID createBuilderComponent(String label) {
        UUID id = createComponent(label);
        Response r = asAdmin().body(builderSaveBody()).put("/api/cpq/components/" + id + "/builder").thenReturn();
        assertReachedBusinessLayer(r, "PUT builder(" + label + ")");
        assertEquals(200, r.statusCode(), "夹具：PUT /builder 应 200，实际=" + r.statusCode() + " body=" + r.asString());
        assertEquals(1L, count("SELECT count(*) FROM component_sql_view WHERE component_id = ?1 "
                + "AND builder_config IS NOT NULL", id), "夹具自检：" + label + " 应恰有 1 个取数配置器视图");
        return id;
    }

    private String builderViewName(UUID cid) {
        String n = scalar("SELECT sql_view_name FROM component_sql_view WHERE component_id = ?1 "
                + "AND builder_config IS NOT NULL", cid);
        assertNotNull(n, "夹具：组件 " + cid + " 没有取数配置器视图");
        return n;
    }

    private String componentCode(UUID cid) {
        return scalar("SELECT code FROM component WHERE id = ?1", cid);
    }

    /** 把自己那一行的已落库 SQL 弄「过期」（文本 ≠ 当前编译产物）。 */
    private void makeStale(UUID cid) {
        exec("UPDATE component_sql_view SET sql_template = ?1 || sql_template "
                + "WHERE component_id = ?2 AND builder_config IS NOT NULL "
                + "AND left(sql_template, length(?1)) <> ?1", STALE_MARK, cid);
        assertTrue(isStale(cid), "夹具自检：过期标记没打上 " + cid);
    }

    private boolean isStale(UUID cid) {
        String sql = scalar("SELECT sql_template FROM component_sql_view WHERE component_id = ?1 "
                + "AND builder_config IS NOT NULL", cid);
        return sql != null && sql.startsWith(STALE_MARK);
    }

    private long opLogCount(UUID cid) {
        return count("SELECT count(*) FROM operation_log WHERE target_id = ?1", cid);
    }

    /** 零写入观察手段（见类注释）。 */
    private String snapshot(UUID cid) {
        String views = scalar("SELECT coalesce(string_agg(sql_view_name || '|' || md5(sql_template) || '|' "
                + "|| coalesce(declared_columns::text,'') || '|' || coalesce(builder_config::text,'') || '|' "
                + "|| coalesce(builder_version::text,'') || '|' || coalesce(updated_at::text,''), ';' "
                + "ORDER BY sql_view_name), '<no-view>') FROM component_sql_view WHERE component_id = ?1", cid);
        String comp = scalar("SELECT md5(coalesce(fields::text,'') || '|' || coalesce(formulas::text,'') || '|' "
                + "|| coalesce(row_key_fields::text,'') || '|' || updated_at::text) FROM component WHERE id = ?1", cid);
        return "views=" + views + " || comp=" + comp + " || oplog=" + opLogCount(cid);
    }

    private Response recompile(Map<String, String> cookies, Object body) {
        var spec = RestAssured.given().contentType(ContentType.JSON);
        if (cookies != null) {
            spec = spec.cookies(cookies);
        }
        Response r = spec.body(body).post(ENDPOINT).thenReturn();
        System.out.println("---- recompile-components body=" + body + " → " + r.statusCode() + "\n"
                + abbreviate(r.asString()));
        return r;
    }

    private Response recompileAdmin(Object body) {
        Response r = recompile(adminCookies(), body);
        assertReachedBusinessLayer(r, "recompile-components");
        return r;
    }

    private static Map<String, Object> req(List<?> ids, Boolean confirm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("componentIds", ids);
        if (confirm != null) {
            m.put("confirm", confirm);
        }
        return m;
    }

    private static List<String> ids(UUID... u) {
        List<String> l = new ArrayList<>();
        for (UUID x : u) {
            l.add(x.toString());
        }
        return l;
    }

    private static String abbreviate(String s) {
        return s == null ? "null" : (s.length() <= 3000 ? s : s.substring(0, 3000) + " …(truncated)");
    }

    /** 阳性对照：单独对 cid 执行一次，必须观察到写入；然后恢复成过期态。 */
    private void positiveControlWriteObservable(UUID cid, String label) {
        String before = snapshot(cid);
        long logBefore = opLogCount(cid);
        Response ok = recompileAdmin(req(ids(cid), true));
        assertEquals(200, ok.statusCode(), label + " 阳性对照：单独执行应 200。body=" + ok.asString());
        assertEquals(1, ok.jsonPath().getInt("data.changed"), label + " 阳性对照：changed 应为 1");
        String after = snapshot(cid);
        System.out.println("[" + label + " 阳性对照] before=" + before + "\n  after=" + after);
        assertNotEquals(before, after, label + " 阳性对照失败：执行后观察手段没看到变化 ⇒ 零写入断言无效");
        assertFalse(isStale(cid), label + " 阳性对照：执行后过期标记应被改写掉");
        assertEquals(logBefore + 1, opLogCount(cid), label + " 阳性对照：该组件审计应 +1");
        makeStale(cid);
    }

    // ═══════════════════ T2.5 · AC-8 ① ═══════════════════

    @Test
    @Order(1)
    @DisplayName("T2.5 AC-8①: componentIds 缺失 / null / 空数组 → 400 COMPONENT_IDS_REQUIRED，零写入")
    void t25_idsRequired() {
        UUID a = createBuilderComponent("AC8-1-A");
        makeStale(a);
        String s0 = snapshot(a);

        List<Object> bodies = new ArrayList<>();
        bodies.add(Map.of("confirm", true));                  // 缺失
        Map<String, Object> nullIds = new LinkedHashMap<>();
        nullIds.put("componentIds", null);
        nullIds.put("confirm", true);
        bodies.add(nullIds);                                  // null
        bodies.add(req(List.of(), true));                     // 空数组
        bodies.add(req(List.of(), false));                    // 空数组 · 预览

        for (Object body : bodies) {
            Response r = recompileAdmin(body);
            assertEquals(400, r.statusCode(), "AC-8①：body=" + body + " 应 400，实际=" + r.statusCode()
                    + " resp=" + r.asString());
            assertEquals("COMPONENT_IDS_REQUIRED", r.jsonPath().getString("code"),
                    "AC-8①：错误码（api.md §1.7 裸体格式）。resp=" + r.asString());
        }
        String s1 = snapshot(a);
        System.out.println("[T2.5] s0=" + s0 + "\n  s1=" + s1);
        assertEquals(s0, s1, "AC-8① 零写入：自建过期视图 / 审计被改动了（空参数不许被当成「全部」处理）");
        assertTrue(isStale(a), "AC-8① 零写入：过期标记不应被改写");
    }

    @Test
    @Order(2)
    @DisplayName("T2.5b AC-8①（api.md §1.7 参数校验）: 非 UUID 元素 → 400 INVALID_COMPONENT_ID + invalidIds，零写入")
    void t25b_invalidUuid() {
        UUID a = createBuilderComponent("AC8-1B-A");
        makeStale(a);
        String s0 = snapshot(a);
        String bad = PREFIX + "not-a-uuid";
        Response r = recompileAdmin(req(List.of(a.toString(), bad), true));
        assertEquals(400, r.statusCode(), "api.md §1.7：非法 UUID 应 400。resp=" + r.asString());
        assertEquals("INVALID_COMPONENT_ID", r.jsonPath().getString("code"), "resp=" + r.asString());
        List<String> invalid = r.jsonPath().getList("invalidIds", String.class);
        assertNotNull(invalid, "api.md §1.7：应带 invalidIds。resp=" + r.asString());
        assertTrue(invalid.contains(bad), "invalidIds 应含 " + bad + "，实际=" + invalid);
        assertEquals(s0, snapshot(a), "零写入：合法 id 的那个组件也不许被写");
    }

    // ═══════════════════ T2.6 · AC-8 ② ═══════════════════

    @Test
    @Order(3)
    @DisplayName("T2.6 AC-8②: 存在 + 不存在的 id 混合 → 404 COMPONENT_NOT_FOUND + missingIds；存在的也不写（先做阳性对照）")
    void t26_missingIdWholeRequest404() {
        UUID a = createBuilderComponent("AC8-2-A");
        makeStale(a);
        UUID missing = UUID.randomUUID();
        assertEquals(0L, count("SELECT count(*) FROM component WHERE id = ?1", missing), "构造自检：随机 id 不应存在");

        // 预览也能看到它会被改（证明它是「会被写」的对象）
        Response pv = recompileAdmin(req(ids(a), false));
        assertEquals(200, pv.statusCode(), "阳性对照（预览）应 200。resp=" + pv.asString());
        assertEquals(1, pv.jsonPath().getInt("data.changed"), "阳性对照（预览）：过期视图应计入 changed");
        assertEquals(a.toString(), pv.jsonPath().getString("data.changes[0].componentId"));
        assertEquals(builderViewName(a), pv.jsonPath().getString("data.changes[0].sqlViewName"));

        positiveControlWriteObservable(a, "T2.6");

        String s0 = snapshot(a);
        for (Boolean confirm : new Boolean[]{true, false}) {
            Response r = recompileAdmin(req(ids(a, missing), confirm));
            assertEquals(404, r.statusCode(), "AC-8②（confirm=" + confirm + "）应 404。resp=" + r.asString());
            assertEquals("COMPONENT_NOT_FOUND", r.jsonPath().getString("code"), "resp=" + r.asString());
            List<String> miss = r.jsonPath().getList("missingIds", String.class);
            System.out.println("[T2.6 实际值] confirm=" + confirm + " missingIds=" + miss);
            assertNotNull(miss, "AC-8②：body 应列出缺失的 id。resp=" + r.asString());
            assertEquals(List.of(missing.toString()), miss, "AC-8②：missingIds 应恰为缺失的那个 id");
        }
        String s1 = snapshot(a);
        System.out.println("[T2.6] s0=" + s0 + "\n  s1=" + s1);
        assertEquals(s0, s1, "AC-8②：存在的那个组件也不许写（视图 / 审计）");
        assertTrue(isStale(a), "AC-8②：过期标记应原样保留");
    }

    // ═══════════════════ T2.7 · AC-8 ③ ═══════════════════

    @Test
    @Order(4)
    @DisplayName("T2.7 AC-8③: 含无取数配置器视图的组件（空白 / 仅手写视图）→ 200，进 skippedComponentIds 且不写；其余照常")
    void t27_componentWithoutBuilderViewSkipped() {
        UUID a = createBuilderComponent("AC8-3-A");
        makeStale(a);
        UUID blank = createComponent("AC8-3-BLANK");
        UUID legacy = createComponent("AC8-3-LEGACY");
        String legacyView = ("r260916_t_legacy_" + RUN).toLowerCase();
        exec("INSERT INTO component_sql_view (component_id, sql_view_name, sql_template) VALUES (?1, ?2, ?3)",
                legacy, legacyView, "SELECT 1 AS x");
        assertEquals(0L, count("SELECT count(*) FROM component_sql_view WHERE component_id = ?1", blank),
                "构造自检：空白组件不应有视图");
        assertEquals(1L, count("SELECT count(*) FROM component_sql_view WHERE component_id = ?1 "
                + "AND builder_config IS NULL", legacy), "构造自检：手写视图应恰 1 行且 builder_config 为空");
        String sBlank = snapshot(blank);
        String sLegacy = snapshot(legacy);
        long logA = opLogCount(a);
        String viewA = builderViewName(a);

        Response r = recompileAdmin(req(ids(a, blank, legacy), true));
        assertEquals(200, r.statusCode(), "AC-8③ 应 200。resp=" + r.asString());
        List<String> skipped = r.jsonPath().getList("data.skippedComponentIds", String.class);
        List<String> changedNames = r.jsonPath().getList("data.changedViewNames", String.class);
        List<String> logIds = r.jsonPath().getList("data.operationLogIds", String.class);
        System.out.println("[T2.7 实际值] skipped=" + skipped + " changedViewNames=" + changedNames
                + " operationLogIds=" + logIds + " componentCount=" + r.jsonPath().getInt("data.componentCount")
                + " views=" + r.jsonPath().getInt("data.views"));
        assertNotNull(skipped, "resp=" + r.asString());
        assertTrue(skipped.contains(blank.toString()), "AC-8③：空白组件应在 skippedComponentIds，实际=" + skipped);
        assertTrue(skipped.contains(legacy.toString()),
                "AC-8③：仅有手写视图（builder_config 为空）的组件应在 skippedComponentIds，实际=" + skipped);
        assertFalse(skipped.contains(a.toString()), "AC-8③：有取数配置器视图的组件不应被跳过");
        assertEquals(3, r.jsonPath().getInt("data.componentCount"), "api.md §1.4：componentCount = 去重后组件数");
        assertEquals(1, r.jsonPath().getInt("data.views"), "api.md §1.4：views 只数取数配置器视图");

        // 「其余照常」
        assertEquals(1, r.jsonPath().getInt("data.changed"), "AC-8③：其余照常 ⇒ 过期视图应被改写");
        assertEquals(List.of(viewA), changedNames, "AC-8③：changedViewNames 应恰为 A 的视图");
        assertNotNull(logIds);
        assertEquals(1, logIds.size(), "AC-8③：应写 1 行审计");
        assertFalse(isStale(a), "AC-8③：A 的视图应已改写");
        assertEquals(logA + 1, opLogCount(a), "AC-8③：A 的审计 +1");

        // 「不写它」
        assertEquals(sBlank, snapshot(blank), "AC-8③：空白组件不应被写（含不应凭空生成视图 / 审计）");
        assertEquals(sLegacy, snapshot(legacy), "AC-8③：手写视图组件不应被写");
    }

    // ═══════════════════ T2.8 · AC-8 ④ ═══════════════════

    @Test
    @Order(5)
    @DisplayName("T2.8 AC-8④: 同一组连续执行两次 → 第二次 changed=0、operationLogIds 空、审计不增；首次审计列取值符合 api.md §1.6")
    void t28_secondRunIsNoop() {
        UUID a = createBuilderComponent("AC8-4-A");
        UUID b = createBuilderComponent("AC8-4-B");
        makeStale(a);
        makeStale(b);
        String compMd5Before = scalar("SELECT md5(fields::text||'|'||formulas::text) FROM component WHERE id = ?1", a);
        long logA0 = opLogCount(a);
        long logB0 = opLogCount(b);

        Response first = recompileAdmin(req(ids(a, b), true));
        assertEquals(200, first.statusCode(), "第一次执行应 200。resp=" + first.asString());
        assertEquals(false, first.jsonPath().getBoolean("data.preview"));
        assertEquals(2, first.jsonPath().getInt("data.changed"), "第一次：两个过期视图都应改写");
        List<String> firstLogIds = first.jsonPath().getList("data.operationLogIds", String.class);
        List<String> firstNames = first.jsonPath().getList("data.changedViewNames", String.class);
        assertEquals(2, firstLogIds.size(), "第一次：审计 id 应 2 个");
        assertEquals(firstNames.size(), firstLogIds.size(), "api.md §1.5：operationLogIds 与 changedViewNames 一一对应");
        assertEquals(logA0 + 1, opLogCount(a));
        assertEquals(logB0 + 1, opLogCount(b));

        // 审计列（api.md §1.6）—— 只查本次返回的 id
        for (int i = 0; i < firstLogIds.size(); i++) {
            List<Object[]> row = rows("SELECT operation_type, target_type, target_id::text, summary, "
                    + "details->>'sqlViewName', details->>'source', operator_id::text "
                    + "FROM operation_log WHERE id = ?1", UUID.fromString(firstLogIds.get(i)));
            assertEquals(1, row.size(), "operationLogIds[" + i + "] 在库里找不到");
            Object[] c = row.get(0);
            System.out.println("[T2.8 审计实际值] " + java.util.Arrays.toString(c));
            assertEquals("COMPONENT_VIEW_RECOMPILE", c[0]);
            assertEquals("COMPONENT", c[1]);
            String expectedTarget = firstNames.get(i).equals(builderViewName(a)) ? a.toString() : b.toString();
            assertEquals(expectedTarget, c[2], "target_id 应为该视图所属组件");
            assertEquals(firstNames.get(i), c[4], "details.sqlViewName 与 changedViewNames 同序");
            assertEquals("recompile-components", c[5], "details.source");
            assertTrue(String.valueOf(c[3]).contains(firstNames.get(i)), "summary 应含视图名");
            assertNotNull(c[6], "operator_id 不应为空");
        }
        String sA1 = snapshot(a);
        String sB1 = snapshot(b);
        String sqlA1 = scalar("SELECT sql_template FROM component_sql_view WHERE component_id = ?1", a);

        Response second = recompileAdmin(req(ids(a, b), true));
        assertEquals(200, second.statusCode(), "第二次执行应 200。resp=" + second.asString());
        int changed2 = second.jsonPath().getInt("data.changed");
        List<String> logIds2 = second.jsonPath().getList("data.operationLogIds", String.class);
        List<String> unchanged2 = second.jsonPath().getList("data.unchangedViewNames", String.class);
        System.out.println("[T2.8 实际值] 第二次 changed=" + changed2 + " operationLogIds=" + logIds2
                + " unchangedViewNames=" + unchanged2);
        assertEquals(0, changed2, "AC-8④：第二次 changed 应为 0");
        assertNotNull(logIds2, "AC-8④：operationLogIds 应为空数组而非缺失。resp=" + second.asString());
        assertTrue(logIds2.isEmpty(), "AC-8④：第二次 operationLogIds 应为空");
        assertEquals(logA0 + 1, opLogCount(a), "AC-8④：A 的审计行数不再增加");
        assertEquals(logB0 + 1, opLogCount(b), "AC-8④：B 的审计行数不再增加");
        assertEquals(sqlA1, scalar("SELECT sql_template FROM component_sql_view WHERE component_id = ?1", a),
                "AC-8④：第二次不应改变 SQL 文本");
        assertTrue(unchanged2 != null && unchanged2.containsAll(firstNames),
                "api.md §1.5：第二次两个视图都应列入 unchangedViewNames，实际=" + unchanged2);

        // 仅打印（AC-7③ 属 S-全局，这里只作私有数据旁证，不作本片判定）
        System.out.println("[T2.8 旁证·不判定] component fields/formulas md5 before=" + compMd5Before + " after="
                + scalar("SELECT md5(fields::text||'|'||formulas::text) FROM component WHERE id = ?1", a)
                + "\n  第二次前后快照是否相同 A=" + sA1.equals(snapshot(a)) + " B=" + sB1.equals(snapshot(b)));
    }

    // ═══════════════════ T2.9 · AC-8 ⑤ ═══════════════════

    @Test
    @Order(6)
    @DisplayName("T2.9 AC-8⑤: SALES_REP → 403；未登录 → 401；均零写入（admin 同请求 200 作对照）")
    void t29_rbac() {
        UUID a = createBuilderComponent("AC8-5-A");
        makeStale(a);
        String s0 = snapshot(a);

        // 会话自证：sales 会话确实有效且角色是 SALES_REP（否则 403/401 的来源说不清）
        Response me = RestAssured.given().cookies(salesCookies()).get("/api/cpq/auth/me").thenReturn();
        System.out.println("[T2.9] sales /auth/me → " + me.statusCode() + " " + abbreviate(me.asString()));
        assertEquals(200, me.statusCode(), "🔴【环境】sales 会话无效，403 断言无意义。resp=" + me.asString());
        assertEquals("SALES_REP", scalar("SELECT role FROM \"user\" WHERE username = ?1", SALES_USER),
                "🔴【环境】" + SALES_USER + " 角色不是 SALES_REP ⇒ 本条无意义，报主线");

        for (Boolean confirm : new Boolean[]{true, false}) {
            Response sales = recompile(salesCookies(), req(ids(a), confirm));
            assertEquals(403, sales.statusCode(), "AC-8⑤：SALES_REP（confirm=" + confirm + "）应 403。resp="
                    + sales.asString());
            Response anon = recompile(null, req(ids(a), confirm));
            assertEquals(401, anon.statusCode(), "AC-8⑤：未登录（confirm=" + confirm + "）应 401。resp="
                    + anon.asString());
        }
        assertEquals(s0, snapshot(a), "AC-8⑤：被拒请求不应写入");
        assertTrue(isStale(a));

        // 对照：同一请求 admin 预览可达（证明 403 来自角色，而非端点不存在/参数错）
        Response admin = recompileAdmin(req(ids(a), false));
        assertEquals(200, admin.statusCode(), "对照：admin 预览应 200。resp=" + admin.asString());
        assertEquals(s0, snapshot(a), "对照：预览零写入");
    }

    // ═══════════════════ T2.10 · AC-8 ⑥ ═══════════════════

    @Test
    @Order(7)
    @DisplayName("T2.10 AC-8⑥: 列表中一个 builder_config 损坏 → 500 RECOMPILE_CONFIG_CORRUPT，其他组件视图也不写（先做阳性对照）")
    void t210_corruptConfigRollsBackAll() {
        // 正常组件放在损坏组件的前后各一个（组件编号按创建顺序分配），
        // 使无论实现按什么顺序处理，都至少有一个正常视图先于损坏视图被处理 ⇒ 回滚才真正被考到
        UUID a1 = createBuilderComponent("AC8-6-A1");
        UUID bad = createBuilderComponent("AC8-6-BAD");
        UUID a2 = createBuilderComponent("AC8-6-A2");
        System.out.println("[T2.10] 组件编号 a1=" + componentCode(a1) + " bad=" + componentCode(bad)
                + " a2=" + componentCode(a2) + "（视图名 a1=" + builderViewName(a1) + " bad=" + builderViewName(bad)
                + " a2=" + builderViewName(a2) + "）");
        makeStale(a1);
        makeStale(a2);

        // 阳性对照：两个正常组件单独执行都能观察到写入
        positiveControlWriteObservable(a1, "T2.10-a1");
        positiveControlWriteObservable(a2, "T2.10-a2");

        // 损坏自己那一行的 builder_config（类型错配：tabType 给数组、columns 给字符串）
        exec("UPDATE component_sql_view SET builder_config = CAST(?1 AS jsonb) "
                        + "WHERE component_id = ?2 AND builder_config IS NOT NULL",
                "{\"dialect\":\"QUOTE\",\"tabType\":[\"费用类\"],\"columns\":\"" + PREFIX + "CORRUPT\"}", bad);
        String badView = builderViewName(bad);
        String s1 = snapshot(a1);
        String s2 = snapshot(a2);
        String sb = snapshot(bad);

        for (Boolean confirm : new Boolean[]{true, false}) {
            Response r = recompileAdmin(req(ids(a1, bad, a2), confirm));
            System.out.println("[T2.10 实际值] confirm=" + confirm + " status=" + r.statusCode() + " body=" + r.asString());
            assertEquals(500, r.statusCode(), "AC-8⑥（confirm=" + confirm + "）应 500。resp=" + r.asString());
            assertEquals("RECOMPILE_CONFIG_CORRUPT", r.jsonPath().getString("code"), "resp=" + r.asString());
            assertEquals(badView, r.jsonPath().getString("sqlViewName"), "api.md §1.7 extra：sqlViewName 应指向损坏视图");
            assertEquals(bad.toString(), r.jsonPath().getString("componentId"), "api.md §1.7 extra：componentId");
        }
        assertEquals(s1, snapshot(a1), "AC-8⑥：整体回滚 ⇒ 正常组件 a1 的视图 / 审计不应有任何变化");
        assertEquals(s2, snapshot(a2), "AC-8⑥：整体回滚 ⇒ 正常组件 a2 的视图 / 审计不应有任何变化");
        assertEquals(sb, snapshot(bad), "AC-8⑥：损坏组件本身也不应被写");
        assertTrue(isStale(a1) && isStale(a2), "AC-8⑥：正常组件的过期标记应原样保留");
    }
}
