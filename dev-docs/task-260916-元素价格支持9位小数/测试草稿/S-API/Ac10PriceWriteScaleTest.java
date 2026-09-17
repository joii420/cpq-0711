package com.cpq.task260916;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * <b>AC-10（边界 · 绕过前端直接调接口也按 9 位存）</b> = T-API-02。
 *
 * <blockquote>AC-10 原文：操作 —— {@code POST /api/cpq/element-price/prices}，请求体 {@code price} 为字符串
 * {@code "3.1234567895"}（其余字段合法）；再对该行 {@code PUT /api/cpq/element-price/prices/{id}}，
 * {@code price} 为 {@code "4.0000000004"}；
 * 断言 —— 新建后返回体与库中单价数值等于 {@code 3.12345679}；更新后等于 {@code 4}。</blockquote>
 *
 * <p>另附一条<b>非 AC 的契约回归</b>（api.md §2 第 2 行）：POST 舍入后为 0 的单价 → 400，且不落库。
 * 它不认领 AC-8（AC-8 是导入路径，归 S-UI），失败时单列，不影响 AC-10 结论。
 */
@QuarkusTest
@DisplayName("task-260916 · S-API · AC-10 价格写接口按 9 位舍入")
class Ac10PriceWriteScaleTest extends T916ApiBase {

    static final LocalDate D = LocalDate.of(2020, 4, 4);

    @Test
    @DisplayName("AC-10：POST price=\"3.1234567895\" → 3.12345679；PUT price=\"4.0000000004\" → 4（响应与库值）")
    void ac10_createAndUpdateRoundToScale9() throws Exception {
        Fixture fx = newCustomer("A10");
        withCleanup(List.of(fx), () -> {
            newSource(fx, "A10");

            // ── 新建 ──
            Response created = postPrice(fx, "Cu", D, "3.1234567895");
            System.out.println("[AC-10] POST 响应原文 = " + created.asString());
            assertStatus(created, 201, "AC-10 POST /prices");
            JsonPath cj = json(created);
            String id = cj.getString("id");
            assertNotNull(id, "AC-10：POST 响应无 id ⇒ 无法继续 PUT。body=" + created.asString());
            assertEquals("Cu", cj.getString("elementCode"), "AC-10：响应 elementCode 不符");
            assertNumEq("3.12345679", cj.get("price"), "AC-10 POST 响应 price");
            assertNumEq("3.12345679", rawPrice(id), "AC-10 POST 后库中 raw_price");

            // ── 更新 ──
            Response updated = putPrice(id, "4.0000000004");
            System.out.println("[AC-10] PUT 响应原文 = " + updated.asString());
            assertStatus(updated, 200, "AC-10 PUT /prices/{id}");
            assertEquals(id, json(updated).getString("id"), "AC-10：PUT 响应 id 与目标行不一致");
            assertNumEq("4", json(updated).get("price"), "AC-10 PUT 响应 price");
            assertNumEq("4", rawPrice(id), "AC-10 PUT 后库中 raw_price");
        });
    }

    @Test
    @DisplayName("契约回归（非 AC，api.md §2 #2）：POST price=\"0.0000000004\"（舍入后为 0）→ 400 且该源该日无行")
    void contract_createRoundsToZero_rejected() throws Exception {
        Fixture fx = newCustomer("A10Z");
        withCleanup(List.of(fx), () -> {
            newSource(fx, "A10Z");
            Response r = postPrice(fx, "Cu", D, "0.0000000004");
            System.out.println("[AC-10 附] POST 0.0000000004 响应 = " + r.statusCode() + " " + r.asString());
            assertStatus(r, 400, "api.md §2 #2：舍入后为 0 应 400");
            long n = count("SELECT count(*) FROM element_daily_price WHERE source_id = ?1 AND price_date = ?2",
                    fx.sourceId, D);
            System.out.println("[AC-10 附] 该源该日行数 = " + n + "（应 0）");
            assertEquals(0L, n, "舍入后为 0 被拒后不应落库");
        });
    }

    private Object rawPrice(String id) {
        Object v = scalar("SELECT raw_price FROM element_daily_price WHERE id = ?1", UUID.fromString(id));
        assertNotNull(v, "库中找不到 id=" + id + " 的日价行");
        return v;
    }
}
