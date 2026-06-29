package com.cpq.quotation.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;

/**
 * P3 lazy-cardvalues(2026-06-29):钉死 {@code POST /api/cpq/quotations/{id}/ensure-card-values} 端点已正确接线 ——
 * 端点存在、受类级 {@code @RoleAllowed} 保护(非 404/500);鉴权放行的正常路径返回 200、{@code data} 非空、
 * {@code data.cardValuesWarming} 为 false。
 *
 * <p><b>RBAC / 401 容忍</b>:{@link QuotationResource} 带类级 {@code @RoleAllowed},鉴权由自定义
 * {@code RoleFilter}/{@code SessionHelper} 处理;测试环境 {@code cpq.security.rbac.enabled=false} 仅关角色校验,
 * 无 session 时仍可能 401。故本测试与 {@code SystemConfigResourceTest} 等同类 RBAC 测试一致 —— <b>捕获状态码、
 * 容忍 401</b>(401 = 端点已接线且受保护、只是无 session;200 = 鉴权放行的 happy path),不用 {@code @TestProfile}。
 * 端点驱动的 {@code ensureCardValues} 业务逻辑由服务级 {@code EnsureCardValuesTest} 充分覆盖,本测试只验接线。
 *
 * <p><b>注意</b>:200 路径是真实 HTTP 调用(无 {@code @TestTransaction}),会真正补算并 <b>COMMIT</b> rockwell
 * 基准单的缺失卡片值 —— 等价"首次 warm",幂等、不损坏数据。
 */
@QuarkusTest
class EnsureCardValuesEndpointTest {

    static final String QID = "8f0c37a4-8186-4f5e-a9ca-358bd2d9662d";

    @Test
    void ensureCardValues_endpointWired_200orSecured401() {
        int status = RestAssured.given()
                .contentType(ContentType.JSON)
                .when()
                    .post("/api/cpq/quotations/" + QID + "/ensure-card-values")
                .then()
                    .extract().statusCode();

        // 端点必须已接线且受保护:200(鉴权放行)或 401(无 session)。绝不应是 404(未接线)/500(异常)。
        org.junit.jupiter.api.Assertions.assertTrue(
                status == 200 || status == 401,
                "ensure-card-values 端点应已接线且受保护,实得 status=" + status);

        // 鉴权放行时,断言完整 DTO:data 非空、warming 标记恒为 false(取到锁的正常路径)。
        if (status == 200) {
            RestAssured.given()
                    .contentType(ContentType.JSON)
                    .when()
                        .post("/api/cpq/quotations/" + QID + "/ensure-card-values")
                    .then()
                        .statusCode(200)
                        .body("code", equalTo(200))
                        .body("data", notNullValue())
                        .body("data.cardValuesWarming", equalTo(false));
        }
    }
}
