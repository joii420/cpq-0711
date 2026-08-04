package com.cpq.component.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.*;

/**
 * repair-0803 T-38：未登录调用受影响端点（组件保存，含公式环）→ 401，不泄漏任何 cycles 信息。
 *
 * <p><b>为什么需要 {@link TestProfile}</b>：全仓测试 profile 统一设
 * {@code cpq.security.rbac.enabled=false}（见 {@code application-test.properties}），
 * 目的是让其它业务测试不必逐个模拟登录态。但这也意味着默认测试 profile 下
 * {@code RoleFilter} 整体是 no-op —— {@code POST /api/cpq/components} 在默认测试 profile
 * 里<b>不会</b>返回 401（会直接放行到业务层，命中环校验返回 400，见
 * {@code ComponentServiceFormulaCycleStructuredTest} 的等价单元覆盖）。真要验证「未登录 401」
 * 这个安全属性，必须为本类单独打开 RBAC —— 这是 Quarkus 官方支持的每测试类配置覆盖机制，
 * 不影响其它测试类（各自独立的应用上下文）。
 *
 * <p>本仓库此前没有 {@code @TestProfile} 先例（其它 RBAC 相关测试如
 * {@code EnsureCardValuesEndpointTest} 选择在默认 profile 下「容忍 200 或 401」），
 * 这是本次为验证 T-38 新增的第一个用例；如果后续证明每类独立重启 Quarkus 拖慢整体测试时长，
 * 可考虑改回「容忍」写法，两种写法的取舍在测试报告中已注明。
 */
@QuarkusTest
@TestProfile(ComponentCycleUnauthenticated401Test.RbacOnProfile.class)
class ComponentCycleUnauthenticated401Test {

    public static class RbacOnProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "true");
        }
    }

    /** 含字段级循环引用的组件负载（A↔B 互相引用）——即便鉴权放行也会被环校验拦下 400；重点是鉴权应先一步拦成 401。 */
    private static final String CYCLIC_BODY = """
        {
          "name": "T38 未登录环测试组件",
          "fields": [
            {"name": "A", "field_type": "FORMULA"},
            {"name": "B", "field_type": "FORMULA"}
          ],
          "formulas": [
            {"name": "A", "expression": [{"type": "field", "value": "B"}]},
            {"name": "B", "expression": [{"type": "field", "value": "A"}]}
          ]
        }
        """;

    @Test void createComponent_withoutSession_returns401_notLeakingCycleInfo() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(CYCLIC_BODY)
                .when()
                    .post("/api/cpq/components")
                .then()
                    .statusCode(401)
                    .body("code", equalTo(401))
                    .body("data", nullValue())
                    .body("$", not(hasKey("cycles")))
                    .body("message", not(containsStringIgnoringCase("errorType")));
    }

    @Test void updateComponent_withoutSession_returns401() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(CYCLIC_BODY)
                .when()
                    .put("/api/cpq/components/00000000-0000-0000-0000-000000000000")
                .then()
                    .statusCode(401)
                    .body("data", nullValue());
    }
}
