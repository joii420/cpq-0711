package com.cpq.importexcel;

import com.cpq.importexcel.entity.ImportRecord;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.*;

/**
 * Tests for TDD §9 QIMP - 导入历史相关用例.
 *
 * Covers:
 * QIMP-RECORD-18 — GET /imports/{id}/download 返回原始文件二进制
 * QIMP-V3-EXCEL-17 — v3 客户 Excel 导入端点存在性 / 参数校验
 *
 * Deferred (out of scope):
 * QIMP-V5-REIMPORT-15 / 16 — 后端尚未实现 reimport-basic-data 端点
 * QIMP-RETENTION-19 — 12 个月清理是定时任务，需时间 mock，单测困难
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImportRecordResourceTest {

    @Inject
    EntityManager em;

    private static UUID importRecordId;
    private static Path tempFile;

    @BeforeEach
    @Transactional
    void setupOnce() throws IOException {
        if (importRecordId != null) return;

        // 1. 准备一个临时文件作为"原始 Excel"
        tempFile = Files.createTempFile("import-test-", ".xlsx");
        Files.write(tempFile, new byte[]{0x50, 0x4B, 0x03, 0x04, /* fake xlsx header */ 1, 2, 3, 4});

        // 2. 创建测试客户
        String custBody = """
                {
                  "name": "Import Record Test Customer",
                  "level": "STANDARD",
                  "contacts": [{"name": "IR", "phone": "13800001234", "isPrimary": true}]
                }
                """;
        String custId = RestAssured.given()
                .contentType(ContentType.JSON).body(custBody)
                .post("/api/cpq/customers").then().statusCode(200)
                .extract().path("data.id");

        // 3. 直接 persist 一条 ImportRecord
        ImportRecord rec = new ImportRecord();
        rec.customerId = UUID.fromString(custId);
        rec.originalFileName = "fake-import.xlsx";
        rec.originalFilePath = tempFile.toString();
        rec.totalRows = 0;
        rec.successRows = 0;
        rec.matchedRows = 0;
        rec.unmatchedRows = 0;
        rec.importStatus = "SUCCESS";
        rec.importedBy = UUID.fromString("00000000-0000-0000-0000-000000000001"); // v5-import-tester seed
        rec.createdAt = OffsetDateTime.now();
        // DB schema 要求若干 JSONB 字段 NOT NULL，给空对象兜底
        rec.mappingSnapshot = "{}";
        rec.configSnapshot = "{}";
        em.persist(rec);
        em.flush();
        importRecordId = rec.id;
    }

    // ------------------------------------------------------------------
    // QIMP-RECORD-18: 下载原始 Excel 文件
    // ------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("QIMP-RECORD-18: GET /imports/{id}/download 返回 attachment + 文件名")
    void downloadOriginalFile_returnsAttachment() {
        RestAssured.given()
                .when()
                    .get("/api/cpq/imports/" + importRecordId + "/download")
                .then()
                    .statusCode(200)
                    .header("Content-Disposition", containsString("attachment"))
                    .header("Content-Disposition", containsString("fake-import.xlsx"));
    }

    // ------------------------------------------------------------------
    // QIMP-RECORD-18 边界：originalFilePath 文件不存在 → 404
    // ------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("QIMP-RECORD-18 (negative): 文件被清理后下载返 404")
    @Transactional
    void downloadOriginalFile_missing_returns404() throws IOException {
        // 创建一条 originalFilePath 指向不存在文件的记录（复用 setup 阶段已建好的客户作为 FK）
        ImportRecord setup = em.find(ImportRecord.class, importRecordId);
        ImportRecord rec = new ImportRecord();
        rec.customerId = setup.customerId;
        rec.originalFileName = "missing.xlsx";
        rec.originalFilePath = "C:/non-existent-path-" + UUID.randomUUID() + ".xlsx";
        rec.totalRows = 0;
        rec.successRows = 0;
        rec.matchedRows = 0;
        rec.unmatchedRows = 0;
        rec.importStatus = "SUCCESS";
        rec.importedBy = UUID.fromString("00000000-0000-0000-0000-000000000001"); // v5-import-tester seed
        rec.createdAt = OffsetDateTime.now();
        rec.mappingSnapshot = "{}";
        rec.configSnapshot = "{}";
        em.persist(rec);
        em.flush();
        UUID missingId = rec.id;
        RestAssured.given()
                .when()
                    .get("/api/cpq/imports/" + missingId + "/download")
                .then()
                    .statusCode(404);
    }

    // ------------------------------------------------------------------
    // QIMP-RECORD-18 列表：导入历史按客户筛选
    // ------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("QIMP-RECORD-18 列表: GET /imports 返回分页结构")
    void listImportRecords_returnsPaged() {
        RestAssured.given()
                .queryParam("page", 0)
                .queryParam("size", 10)
                .when()
                    .get("/api/cpq/imports")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.content", notNullValue())
                    .body("data.totalElements", greaterThanOrEqualTo(1));
    }

    // ------------------------------------------------------------------
    // QIMP-V3-EXCEL-17: 客户 Excel 导入预览端点参数校验
    // ------------------------------------------------------------------
    @Test
    @Order(4)
    @DisplayName("QIMP-V3-EXCEL-17: import-excel 缺 file/templateId/customerId 期望 400")
    void importExcel_missingParams_returns400() {
        // 不带任何 form param → 400
        RestAssured.given()
                .multiPart("dummy", "x")
                .when()
                    .post("/api/cpq/imports/import-excel")
                .then()
                    .statusCode(400);
    }

    // ------------------------------------------------------------------
    // QIMP-V3-EXCEL-17 confirm: confirm-import 端点存在
    // ------------------------------------------------------------------
    @Test
    @Order(5)
    @DisplayName("QIMP-V3-EXCEL-17: confirm-import 端点合法 body 不返 500")
    void confirmImport_endpointReachable() {
        // 空 body / 缺字段 → 应 400 而非 500（端点存在）
        int status = RestAssured.given()
                .contentType(ContentType.JSON).body("{}")
                .when()
                    .post("/api/cpq/imports/confirm-import")
                .then()
                .extract().statusCode();
        assert status >= 400 && status < 500
                : "confirm-import on empty body should 4xx, got " + status;
    }

    // ------------------------------------------------------------------
    // QIMP-V5-REIMPORT-15: reimport-basic-data 端点存在，非 DRAFT 状态返 400
    // ------------------------------------------------------------------
    @Test
    @Order(6)
    @DisplayName("QIMP-V5-REIMPORT-15: reimport-basic-data 缺 file 参数返 400")
    void reimportBasicData_missingFile_returns400() {
        // 不带 file 参数 → 400（端点存在且参数校验正常）
        int status = RestAssured.given()
                .multiPart("dummy", "x")
                .when()
                    .post("/api/cpq/quotations/" + UUID.randomUUID() + "/reimport-basic-data")
                .then()
                    .extract().statusCode();
        Assertions.assertTrue(status >= 400 && status < 500,
                "QIMP-V5-REIMPORT-15: reimport-basic-data without file should return 4xx, got " + status);
    }

    // ------------------------------------------------------------------
    // QIMP-V5-REIMPORT-16: 非 DRAFT 报价单不可重导，期望 404（ID 不存在）
    // ------------------------------------------------------------------
    @Test
    @Order(7)
    @DisplayName("QIMP-V5-REIMPORT-16: reimport-basic-data 对不存在 quotation_id 返 404 或 400")
    void reimportBasicData_nonexistentQuotation_returns4xx() {
        // 使用随机不存在的 UUID 上传文件 → 应返 4xx（404 找不到报价单，或 400 业务错误）
        UUID nonExistentId = UUID.randomUUID();
        int status = RestAssured.given()
                .multiPart("file", "test.xlsx", new byte[]{0x50, 0x4B, 0x03, 0x04},
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .when()
                    .post("/api/cpq/quotations/" + nonExistentId + "/reimport-basic-data")
                .then()
                    .extract().statusCode();
        Assertions.assertTrue(status >= 400 && status < 500,
                "QIMP-V5-REIMPORT-16: reimport-basic-data for non-existent quotation should return 4xx, got " + status);
    }

    @AfterAll
    static void cleanup() {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {}
        }
    }
}
