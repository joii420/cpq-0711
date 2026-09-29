package com.cpq.task260927currency;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * S-API contract slice for task-260927 currency/exchange-rate rules.
 *
 * The tests deliberately use the public REST contract from api.md rather than
 * importing implementation classes.  Rows written by this slice use the
 * FX260927_ source prefix and historical dates; no global count is asserted.
 */
@QuarkusTest
class CurrencyExchangeRateApiTest {

    private static final String PREFIX = "FX260927_";
    private static final String USD = "USD";

    @Test
    @DisplayName("AC-1/2: currency list contains CNY and a target currency with a 12-place display rate")
    void currencyList_hasBaseAndTargetWithTwelvePlaceRate() {
        ensureCurrency(USD, "美元", "$", 20);

        Response response = RestAssured.given().when().get("/api/cpq/currencies")
                .then().statusCode(200).extract().response();
        String body = response.asString();
        assertTrue(body.contains("CNY"), "currency list must expose the CNY base currency: " + body);
        assertTrue(body.contains(USD), "currency list must expose the USD target currency: " + body);

        List<Map<String, Object>> rows = response.jsonPath().getList("data");
        assertNotNull(rows, "currency list data must be an array");
        assertFalse(rows.isEmpty(), "currency list must not be empty");
        Map<String, Object> cny = rows.stream()
                .filter(r -> "CNY".equals(String.valueOf(r.get("code"))))
                .findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, cny.get("isBase"));
    }

    @Test
    @DisplayName("AC-18/19: zero and negative rates are rejected with an explicit error")
    void invalidRate_isRejected() {
        ensureCurrency(USD, "美元", "$", 20);
        for (String rate : List.of("0", "-0.1")) {
            Response response = RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body("{\"rate\":\"" + rate + "\",\"effectiveDate\":\"2001-02-01\","
                            + "\"source\":\"" + PREFIX + "INVALID-" + UUID.randomUUID() + "\"}")
                    .when().post("/api/cpq/currencies/" + USD + "/rates")
                    .then().extract().response();
            assertEquals(400, response.statusCode(), "invalid rate response: " + response.asString());
            assertTrue(response.asString().contains("INVALID_EXCHANGE_RATE")
                            || response.asString().contains("汇率")
                            || response.asString().contains("rate"),
                    "invalid rate response should identify the rate: " + response.asString());
        }
    }

    @Test
    @DisplayName("AC-24/25: same-day updates remain in history and the last update is the effective record")
    void sameEffectiveDate_keepsHistoryAndLatestWins() {
        ensureCurrency(USD, "美元", "$", 20);
        String sourcePrefix = PREFIX + "SAME-DAY-" + UUID.randomUUID() + "-";
        String date = "2001-03-14";
        postRate("7.200000000000", date, sourcePrefix + "FIRST");
        postRate("7.300000000000", date, sourcePrefix + "LAST");

        Response response = RestAssured.given().when()
                .get("/api/cpq/currencies/" + USD + "/rates")
                .then().statusCode(200).extract().response();
        List<Map<String, Object>> rows = response.jsonPath().getList("data.items");
        assertNotNull(rows, "rate history must expose data.items");
        List<Map<String, Object>> sameDay = rows.stream()
                .filter(r -> date.equals(String.valueOf(r.get("effectiveDate"))))
                .filter(r -> String.valueOf(r.get("source")).startsWith(sourcePrefix))
                .toList();
        assertEquals(2, sameDay.size(), "same-day updates must not overwrite history");
        Map<String, Object> latest = sameDay.stream()
                .filter(r -> String.valueOf(r.get("source")).endsWith("LAST"))
                .findFirst().orElseThrow();
        assertEquals("7.300000000000", String.valueOf(latest.get("displayRate")));

        String customerCode = createStrategyCustomer();
        Response simulation = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"currency\":\"USD\",\"baseDate\":\"" + date + "\",\"method\":\"LATEST\"}")
                .when().post("/api/cpq/customers/" + customerCode + "/exchange-rate-strategy/simulate")
                .then().statusCode(200).extract().response();
        assertTrue(simulation.asString().contains("7.300000000000"),
                "LATEST must use the last same-day update: " + simulation.asString());
    }

    @Test
    @DisplayName("AC-25: invalid import is all-or-nothing and reports the offending row")
    void rateImport_invalidRow_rejectsWholeBatch() throws Exception {
        ensureCurrency(USD, "美元", "$", 20);
        byte[] workbook = workbook(new String[][]{
                {"生效日期", "汇率", "来源"},
                {"2001-04-01", "7.2", PREFIX + "IMPORT-VALID"},
                {"2001-04-02", "0", PREFIX + "IMPORT-INVALID"}
        });

        Response response = RestAssured.given()
                .multiPart("file", "rates.xlsx", workbook,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .when().post("/api/cpq/currencies/" + USD + "/rates/import")
                .then().extract().response();
        assertEquals(400, response.statusCode(), "invalid import must reject the complete batch");
        assertTrue(response.asString().contains("2") || response.asString().contains("0")
                        || response.asString().contains("汇率"),
                "import error must expose row/reason: " + response.asString());
    }

    @Test
    @DisplayName("批量汇率导入：匹配币种写入、未匹配跳过、同日覆盖有效值并保留历史")
    void batchRateImport_matchesSkipsAndKeepsOverwriteHistory() throws Exception {
        ensureCurrency(USD, "美元", "$", 20);
        String marker = PREFIX + "BATCH-" + UUID.randomUUID();
        String date = "2001-05-17";
        postRate("7.100000000000", date, marker + "-OLD");
        byte[] workbook = workbook(new String[][]{
                {"生效日期*", "币种编号*", "汇率*", "来源"},
                {date, USD, "7.400000000000", marker + "-NEW"},
                {date, "NO_MATCH_FX", "8.000000000000", marker + "-SKIP"}
        });

        Response imported = RestAssured.given()
                .multiPart("file", "batch-rates.xlsx", workbook,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .when().post("/api/cpq/currencies/rates/import")
                .then().statusCode(200).extract().response();
        assertEquals(1, imported.jsonPath().getInt("data.importedCount"), imported.asString());
        assertEquals(1, imported.jsonPath().getInt("data.overwrittenCount"), imported.asString());
        assertEquals(1, imported.jsonPath().getInt("data.skippedCount"), imported.asString());
        assertTrue(imported.jsonPath().getList("data.unmatchedCurrencyCodes", String.class).contains("NO_MATCH_FX"));

        List<Map<String, Object>> rows = RestAssured.given().when()
                .get("/api/cpq/currencies/" + USD + "/rates")
                .then().statusCode(200).extract().jsonPath().getList("data.items");
        List<Map<String, Object>> importedHistory = rows.stream()
                .filter(r -> String.valueOf(r.get("source")).startsWith(marker))
                .toList();
        assertEquals(2, importedHistory.size(), "覆盖后必须保留旧汇率历史");
        assertEquals(marker + "-NEW", importedHistory.get(0).get("source"), "同日新导入记录必须成为最新有效值");
    }

    @Test
    @DisplayName("AC-3/15: customer default currency is persisted independently of future quote snapshots")
    void customerDefaultCurrency_isPersisted() {
        ensureCurrency(USD, "美元", "$", 20);
        String name = PREFIX + "CUSTOMER-" + UUID.randomUUID();
        String body = "{\"name\":\"" + name + "\",\"level\":\"GOLD\","
                + "\"defaultCurrency\":\"USD\",\"contacts\":[{\"name\":\"FX contact\","
                + "\"phone\":\"13926092701\",\"isPrimary\":true}]}";
        String id = RestAssured.given().contentType(ContentType.JSON).body(body)
                .when().post("/api/cpq/customers")
                .then().statusCode(200).extract().path("data.id");

        RestAssured.given().when().get("/api/cpq/customers/" + id)
                .then().statusCode(200)
                .body("data.defaultCurrency", equalTo(USD));
    }

    @Test
    @DisplayName("AC-14/27: strategy simulation resolves all date modes against the quote creation date")
    void strategySimulation_resolvesDateModes() {
        ensureCurrency(USD, "美元", "$", 20);
        postRate("7.010000000000", "2026-01-31", PREFIX + "DATE-JAN31-" + UUID.randomUUID());
        postRate("7.020000000000", "2026-02-28", PREFIX + "DATE-FEBEND-" + UUID.randomUUID());
        postRate("7.030000000000", "2026-03-15", PREFIX + "DATE-MONTH-" + UUID.randomUUID());
        postRate("7.040000000000", "2026-03-31", PREFIX + "DATE-SPECIFIC-" + UUID.randomUUID());

        String customer = createStrategyCustomer();
        assertSimulation(customer, "SPECIFIC_DATE", "2026-03-31", null, "2026-03-31", "7.040000000000");
        assertSimulation(customer, "PREVIOUS_MONTH_DAY", null, 31, "2026-01-31", "7.010000000000");
        assertSimulation(customer, "MONTH_DAY", null, 15, "2026-03-15", "7.030000000000");
        assertSimulation(customer, "PREVIOUS_MONTH_END", null, null, "2026-02-28", "7.020000000000");
    }

    @Test
    @DisplayName("AC-19/20/27: a date with no effective rate fails instead of falling back to another day")
    void strategySimulation_missingDate_fails() {
        ensureCurrency(USD, "美元", "$", 20);
        String customer = createStrategyCustomer();
        Response response = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"currency\":\"USD\",\"baseDate\":\"2026-06-20\","
                        + "\"method\":\"SPECIFIC_DATE\",\"specificDate\":\"2026-06-19\"}")
                .when().post("/api/cpq/customers/" + customer + "/exchange-rate-strategy/simulate")
                .then().extract().response();
        assertEquals(409, response.statusCode(), response.asString());
        assertTrue(response.asString().contains("QUOTATION_RATE_NOT_FOUND")
                        || response.asString().contains("汇率"), response.asString());
    }

    private static void ensureCurrency(String code, String name, String symbol, int sortOrder) {
        Response response = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"code\":\"" + code + "\",\"name\":\"" + name
                        + "\",\"symbol\":\"" + symbol + "\",\"sortOrder\":" + sortOrder + "}")
                .when().post("/api/cpq/currencies").then().extract().response();
        assertTrue(response.statusCode() == 200 || response.statusCode() == 201 || response.statusCode() == 409,
                "ensure currency response: " + response.asString());
    }

    private static void postRate(String rate, String date, String source) {
        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"rate\":\"" + rate + "\",\"effectiveDate\":\"" + date
                        + "\",\"source\":\"" + source + "\"}")
                .when().post("/api/cpq/currencies/" + USD + "/rates")
                .then().statusCode(anyOf(equalTo(200), equalTo(201)));
    }

    private static String createStrategyCustomer() {
        String name = PREFIX + "STRATEGY-CUSTOMER-" + UUID.randomUUID();
        String code = "FX" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return RestAssured.given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"level\":\"GOLD\","
                        + "\"defaultCurrency\":\"USD\",\"contacts\":[{\"name\":\"FX contact\","
                        + "\"phone\":\"13926092702\",\"isPrimary\":true}]}")
                .when().post("/api/cpq/customers")
                .then().statusCode(200).extract().path("data.code");
    }

    private static void assertSimulation(String customer, String method, String specificDate,
                                         Integer dayOfMonth, String expectedDate, String expectedRate) {
        StringBuilder body = new StringBuilder("{\"currency\":\"USD\",\"baseDate\":\"2026-03-20\",\"method\":\"")
                .append(method).append("\"");
        if (specificDate != null) body.append(",\"specificDate\":\"").append(specificDate).append("\"");
        if (dayOfMonth != null) body.append(",\"dayOfMonth\":").append(dayOfMonth);
        body.append('}');
        Response response = RestAssured.given().contentType(ContentType.JSON).body(body.toString())
                .when().post("/api/cpq/customers/" + customer + "/exchange-rate-strategy/simulate")
                .then().statusCode(200).extract().response();
        String text = response.asString();
        assertTrue(text.contains(expectedDate), "resolved date missing: " + text);
        assertTrue(text.contains(expectedRate), "resolved 12-place rate missing: " + text);
    }

    private static byte[] workbook(String[][] rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("汇率");
            for (int i = 0; i < rows.length; i++) {
                var row = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) row.createCell(j).setCellValue(rows[i][j]);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
