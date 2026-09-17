package com.cpq.task260916;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>AC-11（单点 · 策略保存系数与加价）</b> = T-API-03。
 *
 * <blockquote>AC-11 原文：操作 —— 对一个自造客户 {@code PUT /api/cpq/element-price/strategies/default}，
 * {@code factor} 为 {@code "1.2345678915"}、{@code premium} 为 {@code "0.0000000015"}；
 * 断言 —— 返回体与库中 {@code factor} 数值等于 {@code 1.234567892}，{@code premium} 数值等于 {@code 0.000000002}。</blockquote>
 *
 * <p>「库中」取 {@code element_price_strategy}（该客户 {@code element_code IS NULL} 的默认策略行）。
 * 另用 {@code GET /strategies?customerNo=} 回读一次，证明不是只有 PUT 响应被舍入。
 */
@QuarkusTest
@DisplayName("task-260916 · S-API · AC-11 策略系数/加价按 9 位舍入")
class Ac11StrategyWriteScaleTest extends T916ApiBase {

    @Test
    @DisplayName("AC-11：PUT default factor=\"1.2345678915\" premium=\"0.0000000015\" → 1.234567892 / 0.000000002")
    void ac11_defaultStrategyFactorPremiumRoundToScale9() throws Exception {
        Fixture fx = newCustomer("A11");
        withCleanup(List.of(fx), () -> {
            newSource(fx, "A11");

            Response r = putDefaultStrategy(fx, "LATEST", "1.2345678915", "0.0000000015");
            System.out.println("[AC-11] PUT 响应原文 = " + r.asString());
            assertStatus(r, 200, "AC-11 PUT /strategies/default");
            JsonPath j = json(r);
            assertNumEq("1.234567892", j.get("factor"), "AC-11 响应 factor");
            assertNumEq("0.000000002", j.get("premium"), "AC-11 响应 premium");

            List<Object[]> db = rows("SELECT factor, premium FROM element_price_strategy "
                    + "WHERE customer_no = ?1 AND element_code IS NULL", fx.customerNo);
            System.out.println("[AC-11] 库中默认策略行数 = " + db.size());
            assertEquals(1, db.size(), "AC-11：自造客户的默认策略应恰好 1 行");
            assertNumEq("1.234567892", db.get(0)[0], "AC-11 库中 factor");
            assertNumEq("0.000000002", db.get(0)[1], "AC-11 库中 premium");

            Response back = asAdmin().queryParam("customerNo", fx.customerNo).get(EP + "/strategies").thenReturn();
            System.out.println("[AC-11] GET 回读原文 = " + back.asString());
            assertStatus(back, 200, "AC-11 GET /strategies 回读");
            assertNumEq("1.234567892", json(back).get("default.factor"), "AC-11 回读 default.factor");
            assertNumEq("0.000000002", json(back).get("default.premium"), "AC-11 回读 default.premium");
        });
    }
}
