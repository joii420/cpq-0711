package com.cpq.task260916;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S-API 辅助用例 T-API-04 ~ T-API-07（test.md §2「S-API 的辅助用例」：不认领 AC，补后端口径的回归网）。
 * 期望值逐字取自 test.md 该表；与 AC-7 / AC-18 / AC-19 / AC-15 / AC-2 的数值同源。
 */
@QuarkusTest
@DisplayName("task-260916 · S-API · 辅助用例 T-API-04~07")
class AuxApi04to07Test extends T916ApiBase {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    // ═══════════════════ T-API-04 导入 ═══════════════════

    /**
     * T-API-04：自造源下导入 {@code 2.0000000005} → 结果行 {@code price} 与库值为 {@code 2.000000001}；
     * 同源同日再导 {@code 2.000000002} → {@code message} 为 {@code 原值 2.000000001 → 新值 2.000000002}。
     * <p>单价按真实 Excel 的数值单元格写入（double），与用户文件同形。
     */
    @Test
    @DisplayName("T-API-04：导入 2.0000000005 → 2.000000001；再导 2.000000002 → 「原值 2.000000001 → 新值 2.000000002」")
    void tApi04_importRoundsAndOverwriteMessage() throws Exception {
        Fixture fx = newCustomer("X04");
        withCleanup(List.of(fx), () -> {
            newSource(fx, "X04");
            LocalDate d = LocalDate.of(2020, 1, 20);

            Response first = importOne(fx, d, "Ni", 2.0000000005);
            JsonPath j1 = json(first);
            List<Map<String, Object>> rows1 = j1.getList("rows");
            assertNotNull(rows1, "T-API-04：首导响应无 rows");
            assertEquals(1, rows1.size(), "T-API-04：首导应 1 行结果。body=" + first.asString());
            assertEquals("CREATED", j1.getString("rows[0].result"), "T-API-04：首导应为 CREATED");
            assertNumEq("2.000000001", j1.get("rows[0].price"), "T-API-04 首导结果行 price");
            assertNumEq("2.000000001", dbPrice(fx, "Ni", d), "T-API-04 首导库值");

            Response second = importOne(fx, d, "Ni", 2.000000002);
            JsonPath j2 = json(second);
            assertEquals("UPDATED", j2.getString("rows[0].result"), "T-API-04：再导应为 UPDATED。body=" + second.asString());
            String msg = j2.getString("rows[0].message");
            System.out.println("[T-API-04] 覆盖提示原文 = 「" + msg + "」");
            assertEquals("原值 2.000000001 → 新值 2.000000002", msg, "T-API-04：覆盖提示文本");
            assertNumEq("2.000000002", dbPrice(fx, "Ni", d), "T-API-04 再导库值");
        });
    }

    /** T-API-04 附（与 AC-8 同一规则的接口层回归，AC-8 本身归 S-UI）：舍入后为 0 → FAILED「单价必须大于 0」且不落库。 */
    @Test
    @DisplayName("T-API-04 附：导入 0.0000000004 → FAILED「单价必须大于 0」，该源该日无行")
    void tApi04b_importRoundsToZeroFails() throws Exception {
        Fixture fx = newCustomer("X04Z");
        withCleanup(List.of(fx), () -> {
            newSource(fx, "X04Z");
            LocalDate d = LocalDate.of(2020, 1, 21);
            Response r = importOne(fx, d, "Cu", 0.0000000004);
            JsonPath j = json(r);
            assertEquals("FAILED", j.getString("rows[0].result"), "T-API-04 附：应 FAILED。body=" + r.asString());
            assertEquals("单价必须大于 0", j.getString("rows[0].message"), "T-API-04 附：失败说明");
            long n = count("SELECT count(*) FROM element_daily_price WHERE source_id = ?1 AND price_date = ?2",
                    fx.sourceId, d);
            System.out.println("[T-API-04 附] 该源该日行数 = " + n + "（应 0）");
            assertEquals(0L, n);
        });
    }

    // ═══════════════════ T-API-05 单价变更历史 ═══════════════════

    /**
     * T-API-05：对自造日价做 {@code 101.13921 → 101.13922} 更新 → 历史里出现该单价变更条目，
     * 文本为 {@code 101.13921 → 101.13922}（改前两者 4 位文本相同，<b>不产生</b>条目 —— 本用例据此区分新旧口径）。
     */
    @Test
    @DisplayName("T-API-05：单价 101.13921 → 101.13922 产生变更条目，old/new 文本为 101.13921 / 101.13922")
    void tApi05_priceHistoryNineDigitDiff() throws Exception {
        Fixture fx = newCustomer("X05");
        withCleanup(List.of(fx), () -> {
            newSource(fx, "X05");
            LocalDate d = LocalDate.of(2020, 1, 22);
            Response c = postPrice(fx, "Cu", d, "101.13921");
            assertStatus(c, 201, "T-API-05 新建日价");
            String id = c.jsonPath().getString("id");
            assertStatus(putPrice(id, "101.13922"), 200, "T-API-05 修改日价");

            Response h = asAdmin()
                    .queryParam("sourceId", fx.sourceId.toString())
                    .queryParam("from", LocalDate.now().minusDays(1).toString())
                    .queryParam("to", LocalDate.now().plusDays(1).toString())
                    .queryParam("page", 0).queryParam("size", 50)
                    .get(EP + "/prices/history").thenReturn();
            System.out.println("[T-API-05] 历史响应原文 = " + h.asString());
            assertStatus(h, 200, "T-API-05 GET /prices/history");
            JsonPath j = json(h);
            List<Map<String, Object>> content = j.getList("content");
            assertNotNull(content);
            assertFalse(content.isEmpty(), "T-API-05：自造源下历史为空 ⇒ 断言空跑");

            List<Map<String, Object>> updates = j.getList(
                    "content.findAll { it.action == 'UPDATE' && it.elementCode == 'Cu' }");
            assertEquals(1, updates.size(), "T-API-05：本源 Cu 的 UPDATE 条目应恰好 1 条（改前口径为 0 条）");
            List<Map<String, Object>> priceChanges = j.getList(
                    "content.find { it.action == 'UPDATE' && it.elementCode == 'Cu' }.changes.findAll { it.field == 'price' }");
            assertEquals(1, priceChanges.size(), "T-API-05：UPDATE 条目里应有 1 条 price 变更");
            Map<String, Object> ch = priceChanges.get(0);
            System.out.println("[T-API-05] price 变更 = " + ch.get("oldValue") + " → " + ch.get("newValue"));
            assertEquals("101.13921", String.valueOf(ch.get("oldValue")), "T-API-05 oldValue 文本");
            assertEquals("101.13922", String.valueOf(ch.get("newValue")), "T-API-05 newValue 文本");

            // AC-18 第 4 步的接口层同源：新增记录摘要中单价为 101.13921
            Object createdSnapPrice = j.get(
                    "content.find { it.action == 'CREATE' && it.elementCode == 'Cu' }.snapshot.price");
            assertNumEq("101.13921", createdSnapPrice, "T-API-05 CREATE 快照 price");
        });
    }

    // ═══════════════════ T-API-06 策略变更历史 ═══════════════════

    /**
     * T-API-06：自造客户例外系数 {@code 1.123456789 → 1.123456788} → 变更文本为 {@code 1.123456789 → 1.123456788}。
     * 顺带（AC-19 接口层同源）：新建例外 factor/premium 按 9 位舍入；删除记录快照中 factor 为 {@code 1.123456788}。
     */
    @Test
    @DisplayName("T-API-06：例外系数 1.123456789 → 1.123456788，历史 old/new 文本逐字一致")
    void tApi06_strategyHistoryNineDigitDiff() throws Exception {
        Fixture fx = newCustomer("X06");
        withCleanup(List.of(fx), () -> {
            newSource(fx, "X06");
            assertStatus(putDefaultStrategy(fx, "LATEST", "1", "0"), 200, "T-API-06 前置：默认策略");

            Response created = postException(fx, "Zn", "1.1234567891", "0.0000000015");
            System.out.println("[T-API-06] 新建例外响应 = " + created.asString());
            assertStatus(created, 200, "T-API-06 新建 Zn 例外");
            String excId = created.jsonPath().getString("id");
            assertNumEq("1.123456789", json(created).get("factor"), "T-API-06 新建例外 factor");
            assertNumEq("0.000000002", json(created).get("premium"), "T-API-06 新建例外 premium");

            Response updated = putException(fx, excId, "Zn", "1.123456788", "0.000000002");
            assertStatus(updated, 200, "T-API-06 修改 Zn 例外");
            assertNumEq("1.123456788", json(updated).get("factor"), "T-API-06 修改后 factor");

            Response h = historyOf(fx, "Zn");
            JsonPath j = json(h);
            List<Map<String, Object>> content = j.getList("content");
            assertNotNull(content);
            assertFalse(content.isEmpty(), "T-API-06：自造客户 Zn 例外历史为空 ⇒ 断言空跑");
            assertEquals("UPDATE", j.getString("content[0].action"), "T-API-06：最新一条应为 UPDATE");
            List<Map<String, Object>> factorChanges = j.getList("content[0].changes.findAll { it.field == 'factor' }");
            assertEquals(1, factorChanges.size(),
                    "T-API-06：最新 UPDATE 应含 1 条 factor 变更（改前 2 位文本相同 ⇒ 可能 0 条）。changes="
                            + j.getList("content[0].changes"));
            Map<String, Object> ch = factorChanges.get(0);
            System.out.println("[T-API-06] factor 变更 = " + ch.get("oldValue") + " → " + ch.get("newValue"));
            assertEquals("1.123456789", String.valueOf(ch.get("oldValue")), "T-API-06 oldValue 文本");
            assertEquals("1.123456788", String.valueOf(ch.get("newValue")), "T-API-06 newValue 文本");
            List<Map<String, Object>> premiumChanges = j.getList("content[0].changes.findAll { it.field == 'premium' }");
            assertTrue(premiumChanges.isEmpty(), "T-API-06：premium 未变，不应出现在 changes。实际=" + premiumChanges);

            Object createSnapFactor = j.get("content.find { it.action == 'CREATE' }.snapshot.factor");
            Object createSnapPremium = j.get("content.find { it.action == 'CREATE' }.snapshot.premium");
            assertNumEq("1.123456789", createSnapFactor, "T-API-06 CREATE 快照 factor");
            assertNumEq("0.000000002", createSnapPremium, "T-API-06 CREATE 快照 premium");

            // 删除 → DELETE 快照 factor = 1.123456788
            Response del = asAdmin().delete(EP + "/strategies/exceptions/" + excId).thenReturn();
            assertStatus(del, 204, "T-API-06 删除 Zn 例外");
            JsonPath j2 = json(historyOf(fx, "Zn"));
            assertEquals("DELETE", j2.getString("content[0].action"), "T-API-06：删除后最新一条应为 DELETE");
            assertNumEq("1.123456788", j2.get("content[0].snapshot.factor"), "T-API-06 DELETE 快照 factor");
        });
    }

    // ═══════════════════ T-API-07 试算 ═══════════════════

    /**
     * T-API-07：自造客户（最新一条价 ×1.2 +50）、自造源日价 {@code 101.13921} → 该元素 {@code finalPrice} 为 {@code 171.367052}。
     * 顺带：同一数据下取价函数 Cu = {@code 171.367052}（AC-2 的私有数据同构版；AC-2 本身归 S-UI）。
     */
    @Test
    @DisplayName("T-API-07：试算 101.13921 × 1.2 + 50 → finalPrice 171.367052（及取价函数同值）")
    void tApi07_simulateFinalPriceScale9() throws Exception {
        Fixture fx = newCustomer("X07");
        withCleanup(List.of(fx), () -> {
            newSource(fx, "X07");
            LocalDate d = LocalDate.of(2020, 1, 23);
            assertStatus(putDefaultStrategy(fx, "LATEST", "1.2", "50"), 200, "T-API-07 前置：默认策略 ×1.2+50");
            assertStatus(postPrice(fx, "Cu", d, "101.13921"), 201, "T-API-07 前置：Cu 日价");

            Response r = asAdmin()
                    .body(Map.of("customerNo", fx.customerNo, "baseDate", d.toString()))
                    .post(EP + "/strategies/simulate").thenReturn();
            System.out.println("[T-API-07] 试算响应原文 = " + r.asString());
            assertStatus(r, 200, "T-API-07 POST /strategies/simulate");
            JsonPath j = json(r);
            List<Map<String, Object>> all = j.getList("$");
            assertNotNull(all);
            assertFalse(all.isEmpty(), "T-API-07：试算返回空数组 ⇒ 断言空跑");
            Map<String, Object> cu = j.getMap("find { it.elementCode == 'Cu' }");
            assertNotNull(cu, "T-API-07：试算结果里没有 Cu 行");
            assertEquals(Boolean.TRUE, cu.get("hasPrice"), "T-API-07：Cu 应有价");
            assertNumEq("101.13921", cu.get("rawValue"), "T-API-07 rawValue");
            assertNumEq("1.2", cu.get("factor"), "T-API-07 factor");
            assertNumEq("50", cu.get("premium"), "T-API-07 premium");
            assertNumEq("171.367052", cu.get("finalPrice"), "T-API-07 finalPrice");

            Map<String, Object> fn = customerElementPrice(fx, d);
            assertTrue(fn.containsKey("Cu"), "T-API-07：取价函数结果无 Cu。实际=" + fn);
            assertNumEq("171.367052", fn.get("Cu"), "T-API-07 取价函数 Cu");
        });
    }

    // ═══════════════════ helpers ═══════════════════

    private Response importOne(Fixture fx, LocalDate d, String elementCode, double price) throws Exception {
        byte[] xlsx = buildXlsx(elementCode, price);
        Response r = asAdminMultipart()
                .multiPart("file", "t916-import.xlsx", xlsx, XLSX)
                .multiPart("sourceId", fx.sourceId.toString())
                .multiPart("priceDate", d.toString())
                .post(EP + "/import").thenReturn();
        System.out.println("[S-API 导入] " + elementCode + "=" + price + " → " + r.statusCode() + " " + r.asString());
        assertStatus(r, 200, "POST /import");
        return r;
    }

    private Object dbPrice(Fixture fx, String elementCode, LocalDate d) {
        Object v = scalar("SELECT raw_price FROM element_daily_price WHERE source_id = ?1 "
                + "AND element_name = ?2 AND price_date = ?3", fx.sourceId, elementCode, d);
        assertNotNull(v, "库中无 " + elementCode + "@" + d + " 日价行");
        return v;
    }

    private Response historyOf(Fixture fx, String elementCode) {
        Response h = asAdmin()
                .queryParam("customerNo", fx.customerNo)
                .queryParam("elementCode", elementCode)
                .queryParam("page", 0).queryParam("size", 50)
                .get(EP + "/strategies/history").thenReturn();
        System.out.println("[S-API] 策略历史(" + elementCode + ") = " + h.asString());
        assertStatus(h, 200, "GET /strategies/history");
        return h;
    }

    /** 表头与模板一致：元素符号* / 单价* / 货币 / 计价单位；货币、单位留空（同 AC-7 口径）。 */
    private static byte[] buildXlsx(String elementCode, double price) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("价格导入");
            Row h = s.createRow(0);
            String[] cols = {"元素符号*", "单价*", "货币", "计价单位"};
            for (int i = 0; i < cols.length; i++) {
                h.createCell(i).setCellValue(cols[i]);
            }
            Row row = s.createRow(1);
            row.createCell(0).setCellValue(elementCode);
            row.createCell(1).setCellValue(price);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }
}
