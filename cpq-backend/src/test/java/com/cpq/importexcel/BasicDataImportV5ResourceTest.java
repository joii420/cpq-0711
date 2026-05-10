package com.cpq.importexcel;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * BasicDataImportV5Resource REST 端点集成测试。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BasicDataImportV5ResourceTest {

    private static final String CUSTOMER_ID = "33000000-0000-0000-0000-000000000002";
    private static final UUID CUSTOMER_UUID = UUID.fromString(CUSTOMER_ID);
    private static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    @BeforeAll
    static void setupRestAssured() {
        RestAssured.baseURI = "http://localhost:8081";
    }

    @BeforeEach
    void setupTestData() throws Exception {
        utx.begin();
        em.joinTransaction();

        // 确保测试用户存在（import_record.imported_by FK）
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, status, is_first_login, created_at, updated_at) " +
                "VALUES (:id, 'v5-resource-tester', 'V5 Resource Tester', 'v5rest@test.com', 'hash', 'SYSTEM_ADMIN', 'ACTIVE', false, NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", USER_UUID).executeUpdate();

        // 确保客户存在（product_import_lock.customer_id FK）
        em.createNativeQuery(
                "INSERT INTO customer(id, name, code, level, accumulated_amount, status, created_at, updated_at) " +
                "VALUES (:id, 'V5 REST Test Customer', 'V5-REST-CUST', 'STANDARD', 0, 'ACTIVE', NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", CUSTOMER_UUID).executeUpdate();

        // 清理上次测试留下的锁
        em.createNativeQuery("DELETE FROM product_import_lock WHERE customer_id = :cid")
                .setParameter("cid", CUSTOMER_UUID).executeUpdate();

        utx.commit();
    }

    // ─── preview 端点：无 customerId → 400 ──────────────────────────────────

    @Test
    @Order(1)
    void preview_missingCustomerId_returns400() throws Exception {
        File excelFile = createTempExcel("preview-no-cid");

        given()
            .multiPart("file", excelFile, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        .when()
            .post("/api/cpq/import/basic-data/v5/preview")
        .then()
            .statusCode(400);
    }

    // ─── preview 端点：有效 Excel + customerId → 200 ────────────────────────

    @Test
    @Order(2)
    void preview_validRequest_returns200WithValidationResult() throws Exception {
        File excelFile = createTempExcel("preview-valid");

        given()
            .multiPart("customerId", CUSTOMER_UUID.toString())
            .multiPart("file", excelFile, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        .when()
            .post("/api/cpq/import/basic-data/v5/preview")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data.status", anyOf(equalTo("PREVIEW_OK"), equalTo("PREVIEW_BLOCKED")));
    }

    // ─── confirm 端点：有效 Excel → 200 ─────────────────────────────────────

    @Test
    @Order(3)
    void confirm_validRequest_returns200() throws Exception {
        File excelFile = createTempExcel("confirm-valid");

        given()
            .multiPart("customerId", CUSTOMER_UUID.toString())
            .multiPart("file", excelFile, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        .when()
            .post("/api/cpq/import/basic-data/v5/confirm")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data.status", anyOf(equalTo("SUCCESS"), equalTo("FAILED")));
    }

    // ─── D-11: 旧端点 /api/cpq/quotations/import-basic-data 已退役 ──

    @Test
    @Order(4)
    void oldEndpoint_retired_noLongerReturns200() {
        // D-11 v4 适配层已删除，旧端点不应返回 200（路径已不存在，框架可能返回 404 或 415）
        given()
        .when()
            .post("/api/cpq/quotations/import-basic-data")
        .then()
            .statusCode(not(equalTo(200)));
    }

    // ─── 帮助方法 ────────────────────────────────────────────────────────────

    private File createTempExcel(String prefix) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("料号主档");
            Row header = sheet.createRow(0);
            String[] cols = {"HF_PART_NO", "PART_NAME", "UNIT_WEIGHT", "WEIGHT_UNIT", "STATUS_CODE"};
            for (int c = 0; c < cols.length; c++) {
                header.createCell(c).setCellValue(cols[c]);
            }
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("P-REST-001");
            r.createCell(1).setCellValue("REST 测试料号");
            r.createCell(2).setCellValue(0.005);
            r.createCell(3).setCellValue("KG");
            r.createCell(4).setCellValue("Y");

            File temp = File.createTempFile(prefix + "-", ".xlsx");
            temp.deleteOnExit();
            try (FileOutputStream fos = new FileOutputStream(temp)) {
                wb.write(fos);
            }
            return temp;
        }
    }
}
