package com.cpq.priceadjust.ac260920;

import com.cpq.common.security.RoleAllowed;
import com.cpq.priceadjust.ac260918.Rp0918aDb;
import com.cpq.priceadjust.ac260918.UpgradeInterceptor;
import com.cpq.priceadjust.ac260920.T920Fixture.Quote;
import com.cpq.priceadjust.resource.PriceAdjustReviewResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-29：两个新端点 {@code POST …/{reviewId}/compute-now}、{@code GET …/{reviewId}/row} 的权限。
 * 同一次运行里（S1-7）：
 * <ol>
 *   <li>④ <b>阳性对照先行</b>：既有带注解端点 {@code GET /reviews} 不带登录须 401，否则本条判「未执行」（RBAC 开关没生效）；</li>
 *   <li>① 未登录：两个端点 401，且该行 budget_status / updated_at 不变、无试算；</li>
 *   <li>② 已登录但角色为 SALES_REP：两个端点 403，同样不触发试算；</li>
 *   <li>③ PRICING_MANAGER：compute-now 202、row 200；随后该行确实被算成 READY（证明 ①② 的「不触发」观测有鉴别力）；</li>
 *   <li>代码核对：两个新方法上都有 {@code @RoleAllowed({"PRICING_MANAGER","SYSTEM_ADMIN"})}（反射读注解，不读方法体）。</li>
 * </ol>
 * ⑤ 还原实验（去掉一个新方法的注解须变红）需改实现源码，属执行阶段手工步骤，见回报。
 * 用户：本轮自建 {@code t260920-pm-*} / {@code t260920-rep-*} 两个账号（私有写，finally 按 id 删），各登录一次、会话缓存复用（登录限流）。
 */
@QuarkusTest
@TestProfile(T920Profiles.RbacOn.class)
class Ac260920PermissionTest {

    static final String PASSWORD = "T260920@pw";

    @Inject
    EntityManager em;

    @Test
    void ac29_newEndpointsRequireLoginAndRole() {
        Rp0918aDb db = new Rp0918aDb(em);
        T920Fixture fx = new T920Fixture(db);
        UpgradeInterceptor upgrades = UpgradeInterceptor.install();
        String suffix = fx.run;
        UUID pmId = UUID.randomUUID();
        UUID repId = UUID.randomUUID();
        try {
            // ④ 阳性对照：本次运行权限校验确实开着
            Response ctl = RestAssured.given().get(T920Api.REVIEWS);
            T920Evidence.log("AC-29", "④ 阳性对照 GET /reviews 未登录 = " + ctl.statusCode());
            assertEquals(401, ctl.statusCode(), "AC-29④：既有带注解端点未登录应 401 —— 否则本次运行 RBAC 没开，AC-29 判「未执行」");

            createUser(db, pmId, "t260920-pm-" + suffix, "PRICING_MANAGER");
            createUser(db, repId, "t260920-rep-" + suffix, "SALES_REP");
            Map<String, String> pm = login("t260920-pm-" + suffix);
            Map<String, String> rep = login("t260920-rep-" + suffix);
            T920Api asPm = T920Api.withCookies(pm);

            fx.createCustomerAndStrategy();
            Quote q = fx.createQuote("P", 2);
            UUID vPrev = fx.insertVersion("PREV", "SUPERSEDED", T920Fixture.P_PREV, 7_200);
            for (String m : q.materials()) fx.setPointer(m, vPrev);
            fx.addScope(q.materials());
            fx.setElementPriceTarget(T920Fixture.P_TARGET);
            UUID v = asPm.generateVersion(fx.customerNo);
            assertEquals(0, fx.awaitVersionSettled(v, 180_000), "前提：首轮算完");
            fx.awaitQuiet(3_000, 60_000);
            String m1 = q.material(1);
            UUID rid = fx.reviewId(v, m1);
            db.exec("UPDATE material_price_review SET budget_status = 'QUEUED', updated_at = now() - interval '1 hour' WHERE id = :id",
                "id", rid);
            String before = rowStamp(db, rid);

            Map<String, T920Api> denied = new LinkedHashMap<>();
            denied.put("①未登录", T920Api.anonymous());
            denied.put("②SALES_REP", T920Api.withCookies(rep));
            Map<String, Integer> expect = Map.of("①未登录", 401, "②SALES_REP", 403);
            long t0 = System.currentTimeMillis();
            for (Map.Entry<String, T920Api> e : denied.entrySet()) {
                Response cn = e.getValue().computeNow(rid);
                Response row = e.getValue().row(rid);
                T920Evidence.log("AC-29", e.getKey() + "：compute-now=" + cn.statusCode() + " " + cn.asString() + "；row=" + row.statusCode());
                assertEquals(expect.get(e.getKey()).intValue(), cn.statusCode(), "AC-29" + e.getKey() + "：compute-now");
                assertEquals(expect.get(e.getKey()).intValue(), row.statusCode(), "AC-29" + e.getKey() + "：row");
            }
            T920Fixture.sleep(3_000);
            long dry = upgrades.callsSince(t0).stream().filter(c -> Boolean.TRUE.equals(c.dryRun()) && c.touches(Set.of(v))).count();
            assertEquals(before, rowStamp(db, rid), "AC-29①②：未触发任何试算 —— budget_status 与 updated_at 不变");
            assertEquals(0, dry, "AC-29①②：无试算调用");

            // ③ 正确角色
            Response cn = asPm.computeNow(rid);
            Response row = asPm.row(rid);
            assertEquals(202, cn.statusCode(), "AC-29③：PRICING_MANAGER compute-now 202: " + cn.asString());
            assertEquals(200, row.statusCode(), "AC-29③：PRICING_MANAGER row 200: " + row.asString());
            long deadline = System.currentTimeMillis() + 60_000;
            while (!"READY".equals(fx.review(v, m1)[1]) && System.currentTimeMillis() < deadline) T920Fixture.sleep(300);
            T920Evidence.log("AC-29", "③ PM：compute-now=" + cn.statusCode() + " row=" + row.statusCode() + "；之后该行=" + fx.review(v, m1)[1]);
            assertEquals("READY", fx.review(v, m1)[1], "阳性对照：正确角色确实触发试算 ⇒ ①② 的「不变」有鉴别力");

            // 代码核对：两个新方法上的注解
            for (String suffixPath : List.of("compute-now", "/row")) {
                Method m = findByPath(suffixPath);
                assertNotNull(m, "PriceAdjustReviewResource 上找不到路径以 " + suffixPath + " 结尾的方法");
                RoleAllowed ra = m.getAnnotation(RoleAllowed.class);
                assertNotNull(ra, "AC-29：方法 " + m.getName() + " 上应有 @RoleAllowed");
                assertEquals(new TreeSet<>(List.of("PRICING_MANAGER", "SYSTEM_ADMIN")), new TreeSet<>(Arrays.asList(ra.value())),
                    "AC-29：" + m.getName() + " 的角色集合");
            }
        } finally {
            upgrades.clearRules();
            fx.awaitQuiet(3_000, 60_000);
            fx.cleanup();
            db.exec("DELETE FROM \"user\" WHERE id IN (:ids) AND username LIKE 't260920-%'", "ids", List.of(pmId, repId));
        }
    }

    private static Method findByPath(String tail) {
        for (Method m : PriceAdjustReviewResource.class.getDeclaredMethods()) {
            Path p = m.getAnnotation(Path.class);
            if (p != null && p.value().replaceAll("/+$", "").endsWith(tail)) return m;
        }
        return null;
    }

    private static void createUser(Rp0918aDb db, UUID id, String username, String role) {
        db.exec("INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, status, is_first_login, created_at, "
                + "updated_at) VALUES (:id, :u, :u, :e, :h, :r, 'ACTIVE', false, now(), now())",
            "id", id, "u", username, "e", username + "@t260920.local", "h", BCrypt.hashpw(PASSWORD, BCrypt.gensalt(12)), "r", role);
    }

    private static final Map<String, Map<String, String>> SESSIONS = new LinkedHashMap<>();

    private static Map<String, String> login(String username) {
        return SESSIONS.computeIfAbsent(username, u -> {
            Response r = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"username\":\"" + u + "\",\"password\":\"" + PASSWORD + "\"}").post("/api/cpq/auth/login");
            if (r.statusCode() != 200 || r.getCookies().isEmpty()) {
                throw new AssertionError("【环境未就绪，非产品缺陷】" + u + " 登录 " + r.statusCode() + " " + r.asString()
                    + "（排查：Redis 登录限流）⇒ AC-29 记「未验证」");
            }
            return new LinkedHashMap<>(r.getCookies());
        });
    }

    private static String rowStamp(Rp0918aDb db, UUID id) {
        return db.text("SELECT budget_status || '@' || updated_at FROM material_price_review WHERE id = :id", "id", id);
    }
}
