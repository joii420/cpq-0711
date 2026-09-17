package com.cpq.task260916;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-21（单点 · 历史接口的快照数字为十进制字符串）</b> = T-API-09。
 *
 * <blockquote>AC-21 原文：前置 —— 自造价格源下手工新建一条日价 {@code 3.123456789} 再改为 {@code 3.123456788}；
 * 自造客户新建例外（系数 {@code 1.123456789}、加价 {@code 0.000000002}）再删除；
 * 操作 —— 调 {@code GET /api/cpq/element-price/prices/history}（按该源）与
 * {@code GET /api/cpq/element-price/strategies/history}（按该客户）；
 * 断言 —— 单价历史新增记录的 {@code snapshot.price} 是<b>字符串</b> {@code "3.123456789"}，修改记录的是字符串
 * {@code "3.123456788"}；策略历史新增记录的 {@code snapshot.factor} 是字符串 {@code "1.123456789"}、
 * {@code snapshot.premium} 是字符串 {@code "0.000000002"}（🚫 不许 {@code 2.0E-9} / {@code 2e-9}），
 * 删除记录同样为字符串；两个接口其余字段名与层级不变。</blockquote>
 *
 * <p>类型判据用 Jackson {@link JsonNode#isTextual()} 读<b>原始响应体</b>——
 * 不经 RestAssured JsonPath，避免数字 / 字符串在取值时被悄悄互转，导致「只比数值」的假绿。
 * 「字段名与层级不变」按 update-0724 api.md §5 与 task-0722 api.md §7 登记的字段做<b>存在性</b>检查
 * （文档之外若有新增字段不判红）；api.md §2 第 9 行：{@code windowNum} 仍为数字（非空时）。
 */
@QuarkusTest
@DisplayName("task-260916 · S-API · AC-21 历史快照数字为十进制字符串")
class Ac21HistorySnapshotStringTest extends T916ApiBase {

    static final ObjectMapper MAPPER = new ObjectMapper();

    static final List<String> PRICE_ENTRY_FIELDS = List.of("id", "changedAt", "changedByName", "action",
            "elementCode", "elementName", "sourceId", "sourceName", "priceDate", "targetLabel", "changes", "snapshot");
    static final List<String> PRICE_SNAPSHOT_FIELDS = List.of("price", "currency", "priceUnit", "fetchStatus");
    static final List<String> STRATEGY_ENTRY_FIELDS = List.of("id", "changedAt", "changedByName", "targetLabel",
            "elementCode", "action", "changes", "snapshot");
    static final List<String> STRATEGY_SNAPSHOT_FIELDS = List.of("sourceName", "method", "windowNum", "windowUnit",
            "factor", "premium");
    static final List<String> PAGE_FIELDS = List.of("content", "totalElements", "page", "size");

    @Test
    @DisplayName("AC-21：单价历史 snapshot.price、策略历史 snapshot.factor/premium 为精确十进制字符串，字段与层级不变")
    void ac21_historySnapshotNumbersAreDecimalStrings() throws Exception {
        Fixture fx = newCustomer("A21");
        withCleanup(List.of(fx), () -> {
            newSource(fx, "A21");
            LocalDate d = LocalDate.of(2020, 5, 5);

            // ── 前置：日价 新建 → 修改 ──
            Response c = postPrice(fx, "Cu", d, "3.123456789");
            assertStatus(c, 201, "AC-21 前置：新建日价");
            String priceId = c.jsonPath().getString("id");
            assertStatus(putPrice(priceId, "3.123456788"), 200, "AC-21 前置：修改日价");

            // ── 前置：例外 新建 → 删除 ──
            assertStatus(putDefaultStrategy(fx, "LATEST", "1", "0"), 200, "AC-21 前置：默认策略");
            Response e = postException(fx, "Zn", "1.123456789", "0.000000002");
            assertStatus(e, 200, "AC-21 前置：新建 Zn 例外");
            String excId = e.jsonPath().getString("id");
            assertStatus(asAdmin().delete(EP + "/strategies/exceptions/" + excId).thenReturn(), 204,
                    "AC-21 前置：删除 Zn 例外");

            // ── 单价历史 ──
            Response ph = asAdmin()
                    .queryParam("sourceId", fx.sourceId.toString())
                    .queryParam("from", LocalDate.now().minusDays(1).toString())
                    .queryParam("to", LocalDate.now().plusDays(1).toString())
                    .queryParam("page", 0).queryParam("size", 50)
                    .get(EP + "/prices/history").thenReturn();
            System.out.println("[AC-21] 单价历史原文 = " + ph.asString());
            assertStatus(ph, 200, "AC-21 GET /prices/history");
            JsonNode pRoot = MAPPER.readTree(ph.asString());
            assertHasFields(pRoot, PAGE_FIELDS, "单价历史分页");
            JsonNode pContent = pRoot.get("content");
            assertTrue(pContent.isArray() && pContent.size() > 0, "AC-21：自造源下单价历史为空 ⇒ 断言空跑");

            JsonNode pCreate = findOne(pContent, "CREATE", "Cu", "单价历史");
            JsonNode pUpdate = findOne(pContent, "UPDATE", "Cu", "单价历史");
            for (JsonNode n : List.of(pCreate, pUpdate)) {
                assertHasFields(n, PRICE_ENTRY_FIELDS, "单价历史条目(" + n.path("action").asText() + ")");
                assertTrue(n.get("changes").isArray(), "单价历史 changes 应为数组");
                assertTrue(n.get("snapshot").isObject(), "单价历史 snapshot 应为对象");
                assertHasFields(n.get("snapshot"), PRICE_SNAPSHOT_FIELDS, "单价历史 snapshot");
            }
            assertDecimalString(pCreate.get("snapshot").get("price"), "3.123456789", "单价历史 CREATE snapshot.price");
            assertDecimalString(pUpdate.get("snapshot").get("price"), "3.123456788", "单价历史 UPDATE snapshot.price");

            // ── 策略历史 ──
            Response sh = asAdmin()
                    .queryParam("customerNo", fx.customerNo)
                    .queryParam("page", 0).queryParam("size", 50)
                    .get(EP + "/strategies/history").thenReturn();
            System.out.println("[AC-21] 策略历史原文 = " + sh.asString());
            assertStatus(sh, 200, "AC-21 GET /strategies/history");
            JsonNode sRoot = MAPPER.readTree(sh.asString());
            assertHasFields(sRoot, PAGE_FIELDS, "策略历史分页");
            JsonNode sContent = sRoot.get("content");
            assertTrue(sContent.isArray() && sContent.size() > 0, "AC-21：自造客户策略历史为空 ⇒ 断言空跑");

            JsonNode sCreate = findOne(sContent, "CREATE", "Zn", "策略历史");
            JsonNode sDelete = findOne(sContent, "DELETE", "Zn", "策略历史");
            for (JsonNode n : List.of(sCreate, sDelete)) {
                String tag = "策略历史 " + n.path("action").asText();
                assertHasFields(n, STRATEGY_ENTRY_FIELDS, tag + " 条目");
                assertTrue(n.get("changes").isArray(), tag + " changes 应为数组");
                JsonNode snap = n.get("snapshot");
                assertTrue(snap.isObject(), tag + " snapshot 应为对象");
                assertHasFields(snap, STRATEGY_SNAPSHOT_FIELDS, tag + " snapshot");
                assertDecimalString(snap.get("factor"), "1.123456789", tag + " snapshot.factor");
                assertDecimalString(snap.get("premium"), "0.000000002", tag + " snapshot.premium");
                JsonNode wn = snap.get("windowNum");
                assertTrue(wn.isNull() || wn.isNumber(),
                        tag + " snapshot.windowNum 应仍为数字（LATEST 下为 null），实际节点类型=" + wn.getNodeType());
            }

            // 兜底：只查数字字段本身的原文（整段快照含 sourceId 等 UUID，像「2e5」会误报）
            List<JsonNode> numericNodes = List.of(
                    pCreate.get("snapshot").get("price"), pUpdate.get("snapshot").get("price"),
                    sCreate.get("snapshot").get("factor"), sCreate.get("snapshot").get("premium"),
                    sDelete.get("snapshot").get("factor"), sDelete.get("snapshot").get("premium"));
            for (JsonNode n : numericNodes) {
                assertFalse(n.toString().matches("(?s).*\\d[eE][+-]?\\d.*"),
                        "AC-21：快照数字出现科学计数法：" + n);
            }
        });
    }

    // ═══════════════════ helpers ═══════════════════

    /** 按 action + elementCode 取恰好 1 条（本片私有数据，非全局计数）。 */
    private static JsonNode findOne(JsonNode content, String action, String elementCode, String what) {
        List<JsonNode> hits = new ArrayList<>();
        for (JsonNode n : content) {
            if (action.equals(n.path("action").asText()) && elementCode.equals(n.path("elementCode").asText())) {
                hits.add(n);
            }
        }
        assertEquals(1, hits.size(), "AC-21：" + what + " 中 " + elementCode + " 的 " + action
                + " 记录应恰好 1 条，实际 " + hits.size() + "。content=" + content);
        return hits.get(0);
    }

    private static void assertHasFields(JsonNode node, List<String> fields, String what) {
        assertNotNull(node, what + "：节点缺失");
        List<String> missing = new ArrayList<>();
        for (String f : fields) {
            if (!node.has(f)) {
                missing.add(f);
            }
        }
        assertTrue(missing.isEmpty(), "AC-21「字段名与层级不变」：" + what + " 缺字段 " + missing + "。节点=" + node);
    }

    /** 节点必须是 JSON 字符串，且文本逐字等于期望（不接受 2.0E-9 / 2e-9 / 3.1234567890 等变体）。 */
    private static void assertDecimalString(JsonNode n, String expected, String what) {
        assertNotNull(n, "AC-21：" + what + " 字段缺失");
        System.out.println("[AC-21] " + what + " 节点类型=" + n.getNodeType() + " 原文=" + n);
        assertTrue(n.isTextual(), "AC-21：" + what + " 应为 JSON 字符串，实际节点类型=" + n.getNodeType()
                + " 原文=" + n);
        assertEquals(expected, n.textValue(), "AC-21：" + what + " 文本不符");
    }
}
