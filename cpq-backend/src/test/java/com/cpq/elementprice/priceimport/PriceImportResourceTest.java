package com.cpq.elementprice.priceimport;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * task-0722 · B5 价格导入 —— 🔒 部分成功事务边界专项（§11.3.2）。
 */
@QuarkusTest
@DisplayName("PriceImportResource — task-0722 · B5 价格导入")
class PriceImportResourceTest {

    private static final String BASE = "/api/cpq/element-price";

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    private String sourceId;
    private static final String ELEM_ACTIVE_1 = "TEST-EL-AG";
    private static final String ELEM_ACTIVE_2 = "TEST-EL-CU";
    private static final String ELEM_INACTIVE = "TEST-EL-INACT";

    @BeforeEach
    void setup() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM element_daily_price WHERE element_name LIKE 'TEST-EL-%'").executeUpdate();
        em.createNativeQuery("DELETE FROM element_price_source WHERE source_name = 'TEST-IMPORT-SRC'").executeUpdate();
        em.createNativeQuery("DELETE FROM element WHERE element_code LIKE 'TEST-EL-%'").executeUpdate();
        em.createNativeQuery(
                "INSERT INTO element (id, element_code, element_name, element_no, status, created_at, updated_at) VALUES " +
                "(gen_random_uuid(), :c1, '测试银', 'TESTNO-AG', 'ACTIVE', NOW(), NOW()), " +
                "(gen_random_uuid(), :c2, '测试铜', 'TESTNO-CU', 'ACTIVE', NOW(), NOW()), " +
                "(gen_random_uuid(), :c3, '测试已停用', 'TESTNO-INACT', 'INACTIVE', NOW(), NOW())")
                .setParameter("c1", ELEM_ACTIVE_1).setParameter("c2", ELEM_ACTIVE_2).setParameter("c3", ELEM_INACTIVE)
                .executeUpdate();
        utx.commit();

        sourceId = given().contentType("application/json")
                .body("{\"sourceName\":\"TEST-IMPORT-SRC\",\"sourceUrl\":\"https://test.example/imp\"}")
            .when().post("/api/cpq/element-price/sources")
            .then().statusCode(200).extract().path("id");
    }

    @Test
    @DisplayName("T1: 模板下载 → 200 + xlsx content-type")
    void downloadTemplate() {
        given()
            .when().get(BASE + "/import-template")
            .then().statusCode(200)
                .contentType(org.hamcrest.Matchers.containsString(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    @DisplayName("T2: 5 行含 1 错行导入 → 4 行成功 1 行失败，成功行确已入库（非整批回滚）")
    void partialSuccessImport() throws Exception {
        byte[] xlsx = buildXlsx(new Object[][]{
                {ELEM_ACTIVE_1, 5820.0, "CNY", "元/kg"},
                {ELEM_ACTIVE_2, 74.5, "CNY", "元/kg"},
                {"TEST-EL-NOTEXIST", 100.0, "CNY", "元/kg"},   // 元素不存在 → FAILED
                {ELEM_INACTIVE, 100.0, "CNY", "元/kg"},         // 元素已停用 → FAILED
                {ELEM_ACTIVE_1, 0.0, "CNY", "元/kg"},           // 单价非法(<=0) → FAILED（同元素 Ag 但不同行，逐行独立）
        });

        PriceImportResultDTO result = given()
                .multiPart("file", "import.xlsx", xlsx,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .multiPart("sourceId", sourceId)
                .multiPart("priceDate", LocalDate.now().toString())
            .when().post(BASE + "/import")
            .then().statusCode(200)
            .extract().as(PriceImportResultDTO.class);

        assertEquals(5, result.rows.size());
        assertEquals(2, result.createdCount, "两行合法数据应成功入库");
        assertEquals(3, result.failedCount, "三行非法数据应失败");
        assertEquals(0, result.updatedCount);

        // 数据库直查：确认成功行真的落库（不是整批回滚）
        long countAg = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM element_daily_price WHERE element_name = :c AND source_id = :s AND raw_price = 5820.0000")
                .setParameter("c", ELEM_ACTIVE_1).setParameter("s", UUID.fromString(sourceId)).getSingleResult()).longValue();
        assertEquals(1, countAg, "Ag 5820 应已入库");

        long countCu = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM element_daily_price WHERE element_name = :c AND source_id = :s")
                .setParameter("c", ELEM_ACTIVE_2).setParameter("s", UUID.fromString(sourceId)).getSingleResult()).longValue();
        assertEquals(1, countCu, "Cu 应已入库");

        long countNotExist = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM element_daily_price WHERE element_name = 'TEST-EL-NOTEXIST'")
                .getSingleResult()).longValue();
        assertEquals(0, countNotExist, "不存在的元素符号不应写入任何行");
    }

    @Test
    @DisplayName("T3: 重复导入(同 源+日期+元素) → 覆盖，回显原值→新值")
    void reimportOverwrites() throws Exception {
        LocalDate date = LocalDate.now();
        byte[] first = buildXlsx(new Object[][]{{ELEM_ACTIVE_1, 5795.0, "CNY", "元/kg"}});
        given().multiPart("file", "a.xlsx", first,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .multiPart("sourceId", sourceId).multiPart("priceDate", date.toString())
            .when().post(BASE + "/import").then().statusCode(200)
                .body("createdCount", equalTo(1));

        byte[] second = buildXlsx(new Object[][]{{ELEM_ACTIVE_1, 5820.0, "CNY", "元/kg"}});
        given().multiPart("file", "b.xlsx", second,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .multiPart("sourceId", sourceId).multiPart("priceDate", date.toString())
            .when().post(BASE + "/import").then().statusCode(200)
                .body("updatedCount", equalTo(1))
                .body("createdCount", equalTo(0))
                .body("rows[0].result", equalTo("UPDATED"))
                .body("rows[0].message", org.hamcrest.Matchers.containsString("5795"))
                .body("rows[0].message", org.hamcrest.Matchers.containsString("5820"));

        long count = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM element_daily_price WHERE element_name = :c AND source_id = :s AND price_date = :d")
                .setParameter("c", ELEM_ACTIVE_1).setParameter("s", UUID.fromString(sourceId)).setParameter("d", date)
                .getSingleResult()).longValue();
        assertEquals(1, count, "重导不应产生重复行");
    }

    @Test
    @DisplayName("T4: sourceId 非 ACTIVE → 400")
    void disabledSourceRejected() throws Exception {
        given().contentType("application/json").body("{\"status\":\"DISABLED\"}")
            .when().post("/api/cpq/element-price/sources/" + sourceId + "/status")
            .then().statusCode(200);

        byte[] xlsx = buildXlsx(new Object[][]{{ELEM_ACTIVE_1, 100.0, "CNY", "元/kg"}});
        given().multiPart("file", "c.xlsx", xlsx,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .multiPart("sourceId", sourceId).multiPart("priceDate", LocalDate.now().toString())
            .when().post(BASE + "/import")
            .then().statusCode(400);
    }

    private byte[] buildXlsx(Object[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("价格导入");
            Row h = s.createRow(0);
            String[] cols = {"元素符号*", "单价*", "货币", "计价单位"};
            for (int i = 0; i < cols.length; i++) h.createCell(i).setCellValue(cols[i]);
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r + 1);
                row.createCell(0).setCellValue((String) rows[r][0]);
                row.createCell(1).setCellValue((Double) rows[r][1]);
                row.createCell(2).setCellValue((String) rows[r][2]);
                row.createCell(3).setCellValue((String) rows[r][3]);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }
}
