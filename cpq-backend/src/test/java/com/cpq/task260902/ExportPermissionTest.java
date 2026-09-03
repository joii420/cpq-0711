package com.cpq.task260902;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260902 · 权限（T-03 / T-09 的接口层，AC-3 / AC-9 + api.md 全局约定）。
 *
 * <h3>⚠️ 关于这里的 {@link TestProfile}：它是<b>显式声明意图</b>，不是「打开了什么」</h3>
 * 仓库里两处 RBAC 开关<b>值相反</b>，profile-specific 的那份优先（test.md §0.5，主线 2026-09-02 实证）：
 * <ul>
 *   <li>{@code src/test/resources/application.properties:5} = {@code false} ❌ <b>被覆盖</b></li>
 *   <li>{@code src/main/resources/application-test.properties:86} = <b>{@code true}</b> ✅ 生效</li>
 * </ul>
 * ⇒ <b>RBAC 在测试里本来就开着</b>（实证：本任务没碰过的 {@code DepartmentResourceTest}
 * 在本 worktree 里 4 条全挂在 401）。本类的 {@code @TestProfile} 因此是<b>冗余但无害</b>的：
 * 把「本类依赖 RBAC 开启」这件事写进代码，将来若有人把那个值改回 false，本类不会静默变假绿。
 * <p>🚫 <b>反过来的做法是禁止的</b>：不许用 {@code @TestProfile} 把 RBAC 关掉来「让用例好跑」——
 * 关掉后「非管理员 → 403」恒红、「管理员 → 200」恒绿（哪怕 {@code @RoleAllowed} 一个字都没标），
 * 正是 test.md §5② 点名的假绿。
 *
 * <h3>🚨 权限必须验两个方向（test.md §5②）</h3>
 * 只验「管理员能调通」不算数 —— 注解漏标时它照样通过。本类三个方向都验：
 * <ol>
 *   <li><b>未登录 → 401</b></li>
 *   <li><b>非管理员 → 403</b>，且<b>先证明那个会话真的登录成功了</b>
 *       （{@code GET /auth/me} 返 200 且 role=SALES_MANAGER）——
 *       否则拿到的 401 会被误读成「403 的近似」，等于没验</li>
 *   <li><b>管理员 → 200</b>（阳性对照：证明端点存在、cookie 机制работает，403 不是因为路由错了）</li>
 * </ol>
 *
 * <h3>全局状态登记（test.md §1）</h3>
 * <ul>
 *   <li>建一个 {@code t260902_sales}（SALES_MANAGER / ACTIVE / is_first_login=false）→ {@code @AfterEach} 删除。</li>
 *   <li>对 {@code admin} 只做<b>解锁</b>（{@code failed_login_attempts=0, locked_until=NULL}）——
 *       🚫 <b>不改密码、不改状态、不改角色</b>。与 {@code e2e/global-setup.ts} 的既有做法一致。</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(ExportPermissionTest.RbacOnProfile.class)
class ExportPermissionTest {

    public static class RbacOnProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "true");
        }
    }

    private static final String MATERIAL_EXPORT = "/api/cpq/material-recipes/export";
    private static final String PROCESS_EXPORT  = "/api/cpq/v6/process-master/export";
    private static final String USER_EXPORT     = "/api/cpq/users/export";
    private static final String USER_TEMPLATE   = "/api/cpq/users/import/template";
    private static final String USER_IMPORT     = "/api/cpq/users/import";

    /** api.md 全局约定：三个导出 + 模板下载都标 {@code @RoleAllowed({"SYSTEM_ADMIN"})}。 */
    private static final List<String> GET_ENDPOINTS =
            List.of(MATERIAL_EXPORT, PROCESS_EXPORT, USER_EXPORT, USER_TEMPLATE);

    private static final String NON_ADMIN = "t260902_sales";
    private static final String NON_ADMIN_PWD = "T260902@Sales";
    private static final String ADMIN = "admin";
    private static final String ADMIN_PWD = "Admin@2026";

    @Inject
    EntityManager em;

    @BeforeEach
    void seedNonAdminAndUnlockAdmin() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM \"user\" WHERE username = :u")
              .setParameter("u", NON_ADMIN).executeUpdate();
            em.createNativeQuery(
                "INSERT INTO \"user\" (username, full_name, email, password_hash, role, status, " +
                "                      is_first_login, failed_login_attempts) " +
                "VALUES (:u, 'T260902 权限测试销售经理', :mail, :hash, 'SALES_MANAGER', 'ACTIVE', false, 0)")
              .setParameter("u", NON_ADMIN)
              .setParameter("mail", NON_ADMIN + "@t260902.invalid")
              .setParameter("hash", BCrypt.hashpw(NON_ADMIN_PWD, BCrypt.gensalt(12)))
              .executeUpdate();
            // 只解锁，🚫 不动 admin 的密码/状态/角色
            em.createNativeQuery(
                "UPDATE \"user\" SET failed_login_attempts = 0, locked_until = NULL WHERE username = 'admin'")
              .executeUpdate();
        });
    }

    @AfterEach
    void removeNonAdmin() {
        QuarkusTransaction.requiringNew().run(() ->
            em.createNativeQuery("DELETE FROM \"user\" WHERE username = :u")
              .setParameter("u", NON_ADMIN).executeUpdate());
        long left = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM \"user\" WHERE username = '" + NON_ADMIN + "'")
                .getSingleResult()).longValue();
        assertEquals(0, left, "还原自检失败：权限用例的 " + NON_ADMIN + " 没删干净");
    }

    // ═══════════════════ 方向 ①：未登录 → 401 ═══════════════════

    /** AC-3 / AC-9：未登录调用 ⇒ HTTP 401。 */
    @Test
    void unauthenticated_allEndpoints_return401() {
        for (String path : GET_ENDPOINTS) {
            Response r = RestAssured.given().when().get(path).thenReturn();
            System.out.println("[AC-3/9·未登录] GET " + path + " → " + r.statusCode()
                    + " body=" + brief(r));
            assertEquals(401, r.statusCode(),
                    "AC-3/AC-9：未登录调用 " + path + " 必须 401，实际 " + r.statusCode()
                    + "（200 = 端点没标 @RoleAllowed；403 = 鉴权与授权的顺序反了）");
        }
        Response imp = RestAssured.given()
                .multiPart("file", "x.xlsx", new byte[]{1, 2, 3},
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .post(USER_IMPORT).thenReturn();
        System.out.println("[AC-3/9·未登录] POST " + USER_IMPORT + " → " + imp.statusCode());
        assertEquals(401, imp.statusCode(),
                "api.md § B-5：用户导入端点未登录也必须 401（🚨 它是写接口，漏标注解后果最重），实际 "
                + imp.statusCode());
    }

    // ═══════════ 方向 ②：非管理员（且确证已登录）→ 403 ═══════════

    /**
     * AC-3 / AC-9：{@code SALES_MANAGER} 的会话直接调导出 ⇒ HTTP <b>403</b>，
     * 响应体 {@code {"code":403,"message":"无权限访问"}}。
     *
     * <p>🚨 前置断言不可省：先用同一个 cookie 打 {@code GET /auth/me}，
     * 必须 200 且 role=SALES_MANAGER。<b>不做这一步，401 会被当成「403 的近似」</b>，
     * 于是「权限拦住了」这个结论其实是「压根没登录成功」。
     */
    @Test
    void nonAdminSession_isLoggedIn_thenAllEndpoints_return403() {
        Map<String, String> cookies = login(NON_ADMIN, NON_ADMIN_PWD);

        // ── 阳性前置：这个会话真的登录成功了吗 ──
        Response me = RestAssured.given().cookies(cookies).when().get("/api/cpq/auth/me").thenReturn();
        System.out.println("[AC-3/9·非管理员] /auth/me → " + me.statusCode() + " body=" + brief(me));
        assertEquals(200, me.statusCode(),
                "前置失败：非管理员会话没登录成功（/auth/me 返 " + me.statusCode() + "）—— "
                + "此时下面拿到的任何 4xx 都不能证明权限拦截生效");
        String role = me.jsonPath().getString("data.role");
        assertEquals("SALES_MANAGER", role,
                "前置失败：会话角色应为 SALES_MANAGER，实际 " + role);

        // ── 正式断言 ──
        for (String path : GET_ENDPOINTS) {
            Response r = RestAssured.given().cookies(cookies).when().get(path).thenReturn();
            System.out.println("[AC-3/9·非管理员] GET " + path + " → " + r.statusCode()
                    + " body=" + brief(r));
            assertEquals(403, r.statusCode(),
                    "AC-3/AC-9：SALES_MANAGER 调用 " + path + " 必须 403，实际 " + r.statusCode()
                    + "。200 = 🚨 方法级 @RoleAllowed({\"SYSTEM_ADMIN\"}) 没标上（类级放开了 4 个角色，"
                    + "RoleFilter 取 methodAnno != null ? methodAnno : classAnno）");
        }

        // AC-3 原文点名了响应体形态，逐字断言一次
        Response mat = RestAssured.given().cookies(cookies).when().get(MATERIAL_EXPORT).thenReturn();
        assertEquals(403, mat.jsonPath().getInt("code"),
                "AC-3：响应体的 code 应为 403，实际 body=" + mat.asString());
        assertEquals("无权限访问", mat.jsonPath().getString("message"),
                "AC-3：响应体的 message 应逐字为「无权限访问」，实际 body=" + mat.asString());

        Response imp = RestAssured.given().cookies(cookies)
                .multiPart("file", "x.xlsx", new byte[]{1, 2, 3},
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .post(USER_IMPORT).thenReturn();
        System.out.println("[AC-3/9·非管理员] POST " + USER_IMPORT + " → " + imp.statusCode());
        assertEquals(403, imp.statusCode(),
                "api.md § B-5：非管理员调用用户导入必须 403，实际 " + imp.statusCode());
    }

    // ═══════════ 方向 ③：管理员 → 200（阳性对照）═══════════

    /**
     * 阳性对照：同样的 cookie 机制下，{@code SYSTEM_ADMIN} 必须能调通。
     * <p>没有这一条，上面的 403 可能只是<b>路由不存在 / 过滤器把所有人都拦了</b>造成的，
     * 那种「全绿」什么都没证明。
     */
    @Test
    void adminSession_allEndpoints_return200() {
        Map<String, String> cookies = login(ADMIN, ADMIN_PWD);
        Response me = RestAssured.given().cookies(cookies).when().get("/api/cpq/auth/me").thenReturn();
        assertEquals(200, me.statusCode(), "前置失败：admin 会话没登录成功，body=" + brief(me));
        assertEquals("SYSTEM_ADMIN", me.jsonPath().getString("data.role"),
                "前置失败：admin 的角色不是 SYSTEM_ADMIN，实际 body=" + brief(me));

        for (String path : GET_ENDPOINTS) {
            Response r = RestAssured.given().cookies(cookies).when().get(path).thenReturn();
            System.out.println("[AC-1/9·管理员] GET " + path + " → " + r.statusCode()
                    + " bytes=" + r.asByteArray().length);
            assertEquals(200, r.statusCode(),
                    "AC-1/AC-9：SYSTEM_ADMIN 调用 " + path + " 必须 200，实际 " + r.statusCode()
                    + " body=" + brief(r));
            assertTrue(r.asByteArray().length > 0, "导出返回了 0 字节：" + path);
            // 只做「确实是个 xlsx」的最低判据；单元格断言在各 *ExportApiTest 里
            byte[] b = r.asByteArray();
            assertTrue(b.length > 4 && b[0] == 'P' && b[1] == 'K',
                    "导出的不是 xlsx（zip 魔数 PK 对不上）：" + path);
        }
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    /**
     * API 登录，返回会话 cookie。
     * <p>🚨 带退避重试：登录限流 30 次/分/IP，整套用例反复重跑很容易打满。
     * 打满后表现为「登录失败」，<b>看起来像鉴权坏了</b>，实际是测试基础设施问题
     * （task-260901 E2E 2026-09-02 实跑踩到）。
     */
    private Map<String, String> login(String username, String password) {
        Response last = null;
        for (int i = 0; i < 3; i++) {
            last = RestAssured.given().contentType(ContentType.JSON)
                    .body(Map.of("username", username, "password", password))
                    .post("/api/cpq/auth/login").thenReturn();
            if (last.statusCode() == 200) {
                Map<String, String> c = new LinkedHashMap<>(last.getCookies());
                assertTrue(!c.isEmpty(), "登录 200 但没拿到任何 cookie（会话机制变了？）");
                System.out.println("[login] " + username + " OK，cookies=" + c.keySet());
                return c;
            }
            System.out.println("[login] " + username + " 第 " + (i + 1) + " 次失败 status="
                    + last.statusCode() + " body=" + brief(last)
                    + (last.statusCode() == 429 ? "（疑似登录限流 30/min/IP，退避重试）" : ""));
            try { Thread.sleep(3000L * (i + 1)); } catch (InterruptedException ignored) { }
        }
        assertNotNull(last);
        throw new AssertionError("登录连续 3 次失败：" + username + " status=" + last.statusCode()
                + " body=" + last.asString()
                + "。429 = 登录限流（测试基础设施问题，不是产品缺陷）；423/401 = 账号被锁或密码不对。"
                + "🚫 不要把它读成「权限功能坏了」。");
    }

    private String brief(Response r) {
        String s;
        try { s = r.asString(); } catch (Exception e) { return "<binary>"; }
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
