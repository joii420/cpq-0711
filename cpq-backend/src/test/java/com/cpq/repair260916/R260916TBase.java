package com.cpq.repair260916;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260916 · <b>测试分片 S-2（私有写片，{@code cpq_db_test}）</b>公共基座。
 *
 * <h3>本片认领的 AC</h3>
 * {@code AC-5}（取值优先级与空值）、{@code AC-8}（按组件重编译的参数与权限）。
 * 断言来源只有 {@code 问题说明.md ⑥} 原文 + {@code api.md §1} 契约 + {@code test.md §3/§4}。
 * 🚫 未读 {@code cpq-backend/src/main/java/**}、迁移目录、{@code Dev*} 自测。
 *
 * <h3>共库纪律（{@code testing.md §4.5}）</h3>
 * <ul>
 *   <li>造数一律带前缀 {@link #PREFIX}（客户号 / 销售料号 / 生产料号 / 组件名）+ 本轮 {@link #RUN}；</li>
 *   <li>🚫 无全局计数断言：{@code operation_log} / {@code component_sql_view} 一律按<b>自己的组件 id</b> 过滤；</li>
 *   <li>🚫 不改任何共享对象：{@code material_recipe} 只读引用 {@code 00144}；{@code user} 表只读（只登录，不解锁、不建号）；</li>
 *   <li>清理 {@link #cleanupAll()} 的每一条 DELETE 都被前缀或自建 id 限死，并在清理后做残留自检。</li>
 * </ul>
 *
 * <h3>本片写入面（前缀口径）</h3>
 * <pre>
 *   ds_quote_material                     customer_no   LIKE 'R260916-T-%'
 *   ds_quote_incoming_fixed_fee           customer_no   LIKE 'R260916-T-%'
 *   ds_cost_basic_material                production_no LIKE 'R260916-T-%'
 *   ds_cost_basic_incoming_process_fee    production_no LIKE 'R260916-T-%'
 *   ds_cost_detail_material               production_no LIKE 'R260916-T-%'
 *   ds_cost_detail_incoming_other_fixed_fee production_no LIKE 'R260916-T-%'
 *   component (+ CASCADE component_sql_view) name LIKE 'R260916-T-%'
 *   operation_log                         target_id IN (本片组件 id)
 * </pre>
 */
abstract class R260916TBase {

    /** 分片专属前缀（派工口径）。 */
    static final String PREFIX = "R260916-T-";
    /** 本轮随机后缀：防上一轮残留冒充本轮数据。4 位，保证 customer_no（varchar 20）不超长。 */
    static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
    /** AC-3 / 需求 E-5：材料名列的视图列名逐字不变。 */
    static final String QUOTE_NAME_COL = "_物料_材料名";
    /** 两库都存在的材质号（问题说明 ⑥「数据源说明」允许只读引用）。 */
    static final String RECIPE_CODE = "00144";

    static final String SALES_USER = "t260903_sales";
    static final String SALES_PWD = "Admin@2026";

    @Inject
    EntityManager em;

    // ═══════════════════ 会话（静态缓存：登录带 Redis 限流 30 次/分/IP） ═══════════════════

    private static Map<String, String> ADMIN_COOKIES;
    private static Map<String, String> SALES_COOKIES;

    /**
     * admin 会话。🚫 刻意不执行「解锁 admin」之类的 UPDATE（共享库全局状态）；
     * 登录失败即判【环境未就绪】，全部 AC 记「未验证」，报主线。
     */
    protected Map<String, String> adminCookies() {
        if (ADMIN_COOKIES == null) {
            ADMIN_COOKIES = login("admin", "Admin@2026");
        }
        return ADMIN_COOKIES;
    }

    /**
     * 非管理员会话：既有测试账号 {@code t260903_sales}（SALES_REP，口令见
     * {@code dev-docs/task-260903-产品管理页重做/test.md} 与 {@code e2e/product-hub.helpers.ts}）。
     * 只登录、不改。
     */
    protected Map<String, String> salesCookies() {
        if (SALES_COOKIES == null) {
            SALES_COOKIES = login(SALES_USER, SALES_PWD);
        }
        return SALES_COOKIES;
    }

    private static Map<String, String> login(String username, String password) {
        Response r = RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", password))
                .post("/api/cpq/auth/login").thenReturn();
        if (r.statusCode() != 200 || r.getCookies().isEmpty()) {
            throw new AssertionError("🔴【环境未就绪，非产品缺陷】" + username + " 登录返 " + r.statusCode()
                    + " body=" + r.asString()
                    + "\n  依次排查：① Redis 登录限流；② 账号被置 INACTIVE / locked_until；③ 口令变更。"
                    + "\n  🚫 本片不自行改 user 表，本类全部 AC 记【未验证】，请报主线。");
        }
        return new LinkedHashMap<>(r.getCookies());
    }

    protected RequestSpecification asAdmin() {
        return RestAssured.given().cookies(adminCookies()).contentType(ContentType.JSON);
    }

    /** 🚨 假绿守卫：被鉴权/路由挡在业务层之外时，「断言非 200」会照样通过。 */
    protected static void assertReachedBusinessLayer(Response r, String when) {
        if (r.statusCode() == 401 || r.statusCode() == 403) {
            throw new AssertionError(when + "：admin 请求被鉴权拦下（" + r.statusCode()
                    + "）—— harness 故障，不是 AC 结论。body=" + r.asString());
        }
        assertFalse(r.statusCode() == 404 && r.asString().contains("RESTEASY"),
                when + "：端点 404（RESTEasy 路由未命中）⇒ 路径与 api.md 不一致或端点未实现。body=" + r.asString());
        assertFalse(r.statusCode() == 405, when + "：405 ⇒ HTTP 方法与 api.md 不一致。body=" + r.asString());
    }

    // ═══════════════════ SQL 小工具 ═══════════════════

    private Query bind(String sql, Object... params) {
        Query q = em.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) {
            q.setParameter(i + 1, params[i]);
        }
        return q;
    }

    protected long count(String sql, Object... params) {
        return ((Number) bind(sql, params).getSingleResult()).longValue();
    }

    protected String scalar(String sql, Object... params) {
        List<?> r = bind(sql, params).getResultList();
        return r.isEmpty() || r.get(0) == null ? null : r.get(0).toString();
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> rows(String sql, Object... params) {
        return bind(sql, params).getResultList();
    }

    /** 独立事务写入（提交后 preview 的只读连接才看得到）。 */
    protected void exec(String sql, Object... params) {
        QuarkusTransaction.requiringNew().run(() -> bind(sql, params).executeUpdate());
    }

    protected static String fingerprint() {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(UUID.randomUUID().toString().getBytes());
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ═══════════════════ 前置断言（派工 d 段；不成立 ⇒ 停下报主线） ═══════════════════

    protected void assertSharedPreconditions() {
        String sym = scalar("SELECT symbol FROM material_recipe WHERE code = ?1", RECIPE_CODE);
        System.out.println("[S-2 前置] material_recipe." + RECIPE_CODE + ".symbol=" + sym);
        assertNotNull(sym, "🔴【前置不成立，报主线】material_recipe 中没有 " + RECIPE_CODE
                + " ⇒ AC-5「只在材质表」情形造不出来（本片不许往 material_recipe 写数据）");
        long basic = count("SELECT count(*) FROM ds_cost_basic_material WHERE production_no = ?1", RECIPE_CODE);
        long detail = count("SELECT count(*) FROM ds_cost_detail_material WHERE production_no = ?1", RECIPE_CODE);
        System.out.println("[S-2 前置] ds_cost_basic_material(" + RECIPE_CODE + ")=" + basic
                + " ds_cost_detail_material(" + RECIPE_CODE + ")=" + detail);
        assertEquals(0L, basic, "🔴【前置不成立，报主线】ds_cost_basic_material 已有 production_no="
                + RECIPE_CODE + " ⇒ AC-5 ④「只在材质表」前提不成立");
        assertEquals(0L, detail, "🔴【前置不成立，报主线】ds_cost_detail_material 已有 production_no="
                + RECIPE_CODE + " ⇒ AC-5 ④（明细核价）「只在材质表」前提不成立");
    }

    protected String recipeSymbol() {
        return scalar("SELECT symbol FROM material_recipe WHERE code = ?1", RECIPE_CODE);
    }

    // ═══════════════════ 组件 ═══════════════════

    protected final List<UUID> createdComponentIds = new ArrayList<>();

    protected UUID createComponent(String label) {
        String name = PREFIX + label + "-" + RUN;
        Response r = asAdmin().body(Map.of("name", name)).post("/api/cpq/components").thenReturn();
        assertReachedBusinessLayer(r, "建组件(" + label + ")");
        assertEquals(200, r.statusCode(), "建组件应 200，实际=" + r.statusCode() + " body=" + r.asString());
        UUID id = UUID.fromString(r.jsonPath().getString("data.id"));
        createdComponentIds.add(id);
        System.out.println("[S-2 造数] component " + name + " id=" + id
                + " code=" + r.jsonPath().getString("data.code"));
        return id;
    }

    // ═══════════════════ 清理（finally 语义，命中面限死在前缀 / 自建 id） ═══════════════════

    protected void cleanupAll() {
        List<String> errs = new ArrayList<>();
        String like = PREFIX + "%";
        String[][] prefixed = {
                {"ds_quote_incoming_fixed_fee", "customer_no"},
                {"ds_quote_material", "customer_no"},
                {"ds_cost_basic_incoming_process_fee", "production_no"},
                {"ds_cost_basic_material", "production_no"},
                {"ds_cost_detail_incoming_other_fixed_fee", "production_no"},
                {"ds_cost_detail_material", "production_no"},
        };
        for (String[] t : prefixed) {
            try {
                exec("DELETE FROM " + t[0] + " WHERE " + t[1] + " LIKE ?1", like);
            } catch (RuntimeException e) {
                errs.add(t[0] + ": " + e);
            }
        }
        for (UUID id : createdComponentIds) {
            try {
                exec("DELETE FROM operation_log WHERE target_id = ?1", id);
                // component_sql_view 外键 ON DELETE CASCADE
                exec("DELETE FROM component WHERE id = ?1", id);
            } catch (RuntimeException e) {
                errs.add("component " + id + ": " + e);
            }
        }
        // 兜底：上一轮异常退出留下的本片组件（名字带前缀）
        try {
            exec("DELETE FROM operation_log WHERE target_id IN (SELECT id FROM component WHERE name LIKE ?1)", like);
            exec("DELETE FROM component WHERE name LIKE ?1", like);
        } catch (RuntimeException e) {
            errs.add("component-by-prefix: " + e);
        }
        if (!errs.isEmpty()) {
            System.out.println("[S-2 cleanup] ⚠️ " + errs);
        }

        // 残留自检
        Map<String, Long> residue = new LinkedHashMap<>();
        for (String[] t : prefixed) {
            residue.put(t[0], count("SELECT count(*) FROM " + t[0] + " WHERE " + t[1] + " LIKE ?1", like));
        }
        residue.put("component", count("SELECT count(*) FROM component WHERE name LIKE ?1", like));
        long opl = 0;
        for (UUID id : createdComponentIds) {
            opl += count("SELECT count(*) FROM operation_log WHERE target_id = ?1", id);
            opl += count("SELECT count(*) FROM component_sql_view WHERE component_id = ?1", id);
        }
        residue.put("operation_log+component_sql_view(自建id)", opl);
        System.out.println("[S-2 residue] " + residue);
        createdComponentIds.clear();
        long total = residue.values().stream().mapToLong(Long::longValue).sum();
        assertEquals(0L, total, "还原自检：本片夹具仍有残留 " + residue + " —— 共享测试库必须清干净。清理错误=" + errs);
    }
}
