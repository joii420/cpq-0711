package com.cpq.quotation.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.*;

/**
 * P3 lazy-cardvalues(2026-06-29):钉死 {@code POST /api/cpq/quotations/{id}/ensure-card-values} 端点 ——
 * 正常(取到单飞锁)调用返回 200、{@code data} 非空、且 {@code data.cardValuesWarming} 为 false;
 * warm 在飞(未取到单飞锁)返 {@code cardValuesWarming=true} 的轻量 DTO(单线程难触发,不在此断言)。
 *
 * <p><b>RBAC</b>:{@link QuotationResource} 带类级 {@code @RoleAllowed}。测试期望 {@code cpq.security.rbac.enabled=false}
 * (见 {@code src/test/resources/application.properties}),但本工作区编译产物里主 {@code application.properties}
 * 的 {@code true} 在 classpath 上覆盖了测试值 —— 故用 {@link RbacOffProfile} 以最高优先级强制关闭 RBAC,
 * 与 {@code SystemConfigResourceTest} 等"test env rbac 关闭"的既定约定一致。
 *
 * <p><b>注意</b>:此端点是真实 HTTP 调用(无 {@code @TestTransaction}),会真正补算并 <b>COMMIT</b> rockwell
 * 基准单的缺失卡片值。这等价于"首次 warm",幂等、不损坏数据 —— 跑完后该单卡片值被合法填充(基线值)。
 */
@QuarkusTest
@TestProfile(EnsureCardValuesEndpointTest.RbacOffProfile.class)
class EnsureCardValuesEndpointTest {

    static final String QID = "8f0c37a4-8186-4f5e-a9ca-358bd2d9662d";

    /** 强制 rbac 关闭(覆盖 classpath 上主 application.properties 的 true),让带 @RoleAllowed 的端点在测试中无需登录。 */
    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    @Test
    void ensureCardValues_returns200_withData_notWarming() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .when()
                .post("/api/cpq/quotations/" + QID + "/ensure-card-values")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data", notNullValue())
                // 正常路径(取到锁)返回完整 DTO,warming 标记为 false/缺省
                .body("data.cardValuesWarming", anyOf(equalTo(false), nullValue()));
    }
}
