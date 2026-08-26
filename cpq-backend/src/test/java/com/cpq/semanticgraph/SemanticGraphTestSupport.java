package com.cpq.semanticgraph;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.mindrot.jbcrypt.BCrypt;

import java.util.Map;
import java.util.UUID;

/**
 * task-260819 取数配置器 · 共享测试基础设施。
 *
 * 只做两件事：① 按角色建一个真实用户并通过真实 /auth/login 拿 CPQ_SESSION（不采用 mock/@TestSecurity，
 * 因为 AC-56 / AC-54 等要验证的是"后端确实在校验"，绕过登录会削弱证据力）；② 收尾清理自建用户。
 *
 * 参照既有测试的写法（不读实现源码）：
 * - 用户表字段与 INSERT 方式参照 com.cpq.changelog.ChangeLogResourceTest
 * - 密码哈希与登录校验方式参照 com.cpq.security.SecurityBackendTest（BCrypt cost 12）
 * - 登录端点与 200/401 语义参照 com.cpq.integration.PermissionTest（POST /api/cpq/auth/login，
 *   cookie 名 CPQ_SESSION）
 *
 * 🚨 2026-08-21 真跑实测发现：本测试环境（test profile）任何走真实 {@code POST /api/cpq/auth/login}
 * 的请求都稳定 500 {@code CONNECTION_CLOSED}（{@code SessionHelper} 写 Redis session 失败）。
 * 用未改动的既有基线测试 {@code com.cpq.integration.PermissionTest} 复现出完全相同的错误
 * （见 test-report），证明这是**预先存在、与本任务无关的测试环境缺陷**，不是本任务的固件或实现问题。
 * 本仓库已有 3 个先例用同一招应对：{@code Task0805ExportBindingReportTest} /
 * {@code Task0805ConsolidateScopeTest} / {@code QuotationCopyValueSnapshotInheritanceTest} T3 ——
 * 用 {@link RbacOffProfile} 局部关闭 RBAC，绕过登录墙直连 REST 端点验证业务逻辑本身
 * （不改共享配置文件，不影响其它测试类）。{@link #createUserAndLogin} / {@link #login} 仍保留，
 * 只在**必须验证真实角色 403 拦截**的用例（AC-56）里使用——那类用例在本环境下无法拿到真实结果，
 * 已在对应测试类里用 Assumptions 标记为 SKIPPED 并注明原因，不是被我隐藏的假绿。
 */
final class SemanticGraphTestSupport {

    /**
     * 局部关闭 RBAC，绕开登录墙（既有基线问题，见类头注释）。仅供不需要验证角色区分的用例使用。
     * 必须是 public 且有 public 无参构造——Quarkus 用 {@code Class.getConstructor()}（只认 public）反射实例化，
     * 首次真跑时因为漏了 public 直接报 NoSuchMethodException，教训记在这。
     */
    public static class RbacOffProfile implements QuarkusTestProfile {
        public RbacOffProfile() {
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    static final String TAG = "SQLVB-TEST-";

    private SemanticGraphTestSupport() {
    }

    /** 建一个指定角色的测试用户并登录，返回 CPQ_SESSION cookie 值。密码统一为 {@link #DEFAULT_PASSWORD}。 */
    static final String DEFAULT_PASSWORD = "Test@2026x";

    static UUID createUser(EntityManager em, UserTransaction utx, String role) throws Exception {
        UUID id = UUID.randomUUID();
        String username = (TAG + role + "-" + id).toLowerCase().replace("_", "").substring(0, 28);
        String hash = BCrypt.hashpw(DEFAULT_PASSWORD, BCrypt.gensalt(12));

        boolean joinExisting = utx.getStatus() == jakarta.transaction.Status.STATUS_ACTIVE;
        if (!joinExisting) {
            utx.begin();
            em.joinTransaction();
        }
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, status, " +
                        "is_first_login, created_at, updated_at) " +
                        "VALUES (:id, :un, :un, :un || '@sqlvb-test.local', :hash, :role, 'ACTIVE', false, NOW(), NOW())")
                .setParameter("id", id)
                .setParameter("un", username)
                .setParameter("hash", hash)
                .setParameter("role", role)
                .executeUpdate();
        if (!joinExisting) {
            utx.commit();
        }
        USERNAME_BY_ID.put(id, username);
        return id;
    }

    private static final java.util.Map<UUID, String> USERNAME_BY_ID = new java.util.concurrent.ConcurrentHashMap<>();

    /** 建用户 + 立即登录，返回 CPQ_SESSION 值。 */
    static String createUserAndLogin(EntityManager em, UserTransaction utx, String role) throws Exception {
        UUID id = createUser(em, utx, role);
        return login(USERNAME_BY_ID.get(id));
    }

    static String login(String username) {
        Response resp = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + username + "\",\"password\":\"" + DEFAULT_PASSWORD + "\"}")
                .when()
                .post("/api/cpq/auth/login");
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("登录失败 username=" + username + " status=" + resp.statusCode()
                    + " body=" + resp.getBody().asString());
        }
        String cookie = resp.getCookie("CPQ_SESSION");
        if (cookie == null || cookie.isBlank()) {
            throw new IllegalStateException("登录成功但未返回 CPQ_SESSION cookie，body=" + resp.getBody().asString());
        }
        return cookie;
    }

    /** 清理本类建的全部测试用户（按 TAG 前缀识别）。供 @AfterAll / @AfterEach 调用。 */
    static void cleanupUsers(EntityManager em, UserTransaction utx) throws Exception {
        boolean joinExisting = utx.getStatus() == jakarta.transaction.Status.STATUS_ACTIVE;
        if (!joinExisting) {
            utx.begin();
            em.joinTransaction();
        }
        em.createNativeQuery("DELETE FROM \"user\" WHERE username LIKE :p")
                .setParameter("p", TAG.toLowerCase() + "%")
                .executeUpdate();
        if (!joinExisting) {
            utx.commit();
        }
    }
}
