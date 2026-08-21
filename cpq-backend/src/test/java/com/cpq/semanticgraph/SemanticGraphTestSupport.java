package com.cpq.semanticgraph;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.mindrot.jbcrypt.BCrypt;

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
 */
final class SemanticGraphTestSupport {

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
