package com.cpq.task260916;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * S-API 辅助用例 <b>T-API-08</b>（test.md §2）：自造客户生成一次版本
 * （{@code POST /api/cpq/price-adjust/versions/generate}）→ 新版本该元素 {@code current_price} 为 9 位值。
 *
 * <p>对应需求文档 §④「波及的现有功能」第 3 行：生成时调用取价函数 ⇒ 修复后新生成的版本冻结价为 9 位。
 * 期望值：自造源 Cu 日价 {@code 101.13921}、元素策略最新一条价 ×1.2 +50 ⇒ {@code 171.367052}（改前 {@code 171.3671}）。
 *
 * <p>🚦 test.md 要求：生成前置条件若在私有数据上满足不了，<b>如实报告未覆盖</b>，🚫 不许改用共享客户。
 * 因此每个前置步骤失败都以「【T-API-08 前置不满足 ⇒ 报未覆盖】」开头，与「生成结果不对」区分开。
 *
 * <p>调价策略：{@code enabled=true}、{@code DAILY}，执行时刻设为「当前时刻 + 12 小时」（HH:mm），
 * 避免测试期间被定时任务再生成一版（即便生成了，清理也按客户号精确覆盖）。
 */
@QuarkusTest
@DisplayName("task-260916 · S-API · 辅助用例 T-API-08 价格版本生成冻结 9 位价")
class AuxApi08VersionGenerateTest extends T916ApiBase {

    static final String PA = "/api/cpq/price-adjust";
    static final String PRE = "【T-API-08 前置不满足 ⇒ 报未覆盖】";

    @Test
    @DisplayName("T-API-08：生成一次版本 → Cu current_price = 171.367052（接口与库值）")
    void tApi08_generatedVersionFreezesScale9Price() throws Exception {
        Fixture fx = newCustomer("X08");
        withCleanup(List.of(fx), () -> {
            newSource(fx, "X08");
            LocalDate priceDate = LocalDate.now().minusDays(1);

            // ── 前置 1：元素价格策略 + 日价 ──
            Response s = putDefaultStrategy(fx, "LATEST", "1.2", "50");
            assertReachedBusinessLayer(s, "T-API-08 元素策略");
            assertEquals(200, s.statusCode(), PRE + "元素默认策略保存失败：" + s.asString());
            Response p = postPrice(fx, "Cu", priceDate, "101.13921");
            assertReachedBusinessLayer(p, "T-API-08 日价");
            assertEquals(201, p.statusCode(), PRE + "Cu 日价新建失败：" + p.asString());

            // 前置自证：取价函数对该客户今天就能取到 9 位价（否则生成结果无从谈起）
            Map<String, Object> fn = customerElementPrice(fx, LocalDate.now());
            assertNotNull(fn.get("Cu"), PRE + "取价函数对自造客户取不到 Cu。实际=" + fn);

            // ── 前置 2：调价策略 + 参与元素 ──
            Map<String, Object> strategy = new LinkedHashMap<>();
            strategy.put("enabled", true);
            strategy.put("cycleType", "DAILY");
            // 服务端要求 HH:mm（首轮传 HH:mm:00 被 400「executeTime 格式非法，应为 HH:mm」拒绝）
            strategy.put("executeTime", LocalTime.now().plusHours(12).truncatedTo(ChronoUnit.MINUTES)
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
            strategy.put("materialScopeMode", "ALL");
            strategy.put("costDiffThreshold", "0");
            Response ps = asAdmin().body(strategy).put(PA + "/strategies/" + fx.customerNo).thenReturn();
            System.out.println("[T-API-08] PUT 调价策略 → " + ps.statusCode() + " " + ps.asString());
            assertReachedBusinessLayer(ps, "T-API-08 调价策略");
            assertEquals(200, ps.statusCode(), PRE + "调价策略保存失败：" + ps.asString());

            Response pe = asAdmin().body(Map.of("elementCodes", List.of("Cu"), "confirmUnselect", false))
                    .put(PA + "/strategies/" + fx.customerNo + "/elements").thenReturn();
            System.out.println("[T-API-08] PUT 参与元素 → " + pe.statusCode() + " " + pe.asString());
            assertReachedBusinessLayer(pe, "T-API-08 参与元素");
            assertEquals(200, pe.statusCode(), PRE + "参与元素保存失败：" + pe.asString());

            // ── 操作：立即生成一次 ──
            Response g = asAdmin().body(Map.of("customerNo", fx.customerNo, "confirmSupersede", false))
                    .post(PA + "/versions/generate").thenReturn();
            System.out.println("[T-API-08] POST generate → " + g.statusCode() + " " + g.asString());
            assertReachedBusinessLayer(g, "T-API-08 生成版本");
            assertEquals(201, g.statusCode(), "T-API-08：生成版本应 201（api main-api 12.X.3）。body=" + g.asString());
            String versionId = g.jsonPath().getString("versionId");
            assertNotNull(versionId, "T-API-08：生成响应无 versionId");
            waitBudgetSettled(fx, versionId);

            // ── 断言：接口 ──
            Response items = asAdmin().queryParam("page", 0).queryParam("size", 50)
                    .get(PA + "/versions/" + versionId + "/items").thenReturn();
            System.out.println("[T-API-08] 版本明细原文 = " + items.asString());
            assertStatus(items, 200, "T-API-08 GET /versions/{id}/items");
            JsonPath j = json(items);
            List<Map<String, Object>> content = j.getList("content");
            assertNotNull(content);
            assertFalse(content.isEmpty(), "T-API-08：新版本明细为空 ⇒ 断言空跑");
            Map<String, Object> cu = j.getMap("content.find { it.elementCode == 'Cu' }");
            assertNotNull(cu, "T-API-08：新版本明细里没有 Cu。content=" + content);
            assertEquals(Boolean.FALSE, cu.get("noPrice"), "T-API-08：Cu 不应为无价");
            assertEquals(Boolean.FALSE, cu.get("inheritedFromPrevious"), "T-API-08：Cu 应为实时取价而非沿用");
            assertNumEq("171.367052", cu.get("currentPrice"), "T-API-08 接口 currentPrice");

            // ── 断言：库值 ──
            Object dbCur = scalar("SELECT i.current_price FROM element_price_version_item i "
                    + "JOIN element_price_version v ON v.id = i.version_id "
                    + "WHERE v.id = ?1 AND v.customer_no = ?2 AND i.element_code = 'Cu'",
                    UUID.fromString(versionId), fx.customerNo);
            assertNumEq("171.367052", dbCur, "T-API-08 库中 current_price");
        });
    }

    /**
     * 生成会带出预算任务（响应里有 budgetJobId / budgetStatus），可能异步写库。
     * 清理前等它离开「进行中」态，最多 20 秒；等不到就打印并继续（清理后的残留自检会兜底暴露）。
     */
    private void waitBudgetSettled(Fixture fx, String versionId) throws InterruptedException {
        Set<String> running = Set.of("RUNNING", "PENDING", "QUEUED", "WAITING");
        String status = null;
        for (int i = 0; i < 20; i++) {
            Response r = asAdmin().queryParam("customerNo", fx.customerNo).queryParam("page", 0)
                    .queryParam("size", 20).get(PA + "/versions").thenReturn();
            if (r.statusCode() != 200) {
                System.out.println("[T-API-08] 版本轨迹查询 " + r.statusCode() + "，不再等待预算");
                return;
            }
            status = r.jsonPath().getString("content.find { it.versionId == '" + versionId + "' }.budgetStatus");
            if (status == null || !running.contains(status)) {
                break;
            }
            Thread.sleep(1000);
        }
        System.out.println("[T-API-08] 预算状态（等待后）= " + status);
    }
}
