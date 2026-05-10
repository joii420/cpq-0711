package com.cpq.material;

import com.cpq.importexcel.entity.CustomerMaterialMapping;
import com.cpq.importexcel.entity.InternalMaterial;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge-case tests for InternalMaterial:
 *   MAT-IMPORT-08 : bulk Excel import 500-row performance SLA (v1 simplified, representative of 5000-row intent)
 *   MAT-DELETE-09 : DELETE blocked when customer_material_mapping references the material
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InternalMaterialEdgeTest {

    @Inject
    EntityManager em;

    // ---- shared state across ordered tests ----
    private static String createdMaterialId;

    @BeforeEach
    @Transactional
    void cleanupTestData() {
        // Remove mappings created by these tests first (FK dependency)
        em.createNativeQuery(
                "DELETE FROM customer_material_mapping WHERE customer_part_no LIKE 'EDGE-DEL-MAP-%'")
                .executeUpdate();
        // Remove materials created by these tests
        em.createQuery("DELETE FROM InternalMaterial m WHERE m.materialNo LIKE 'EDGE-%'")
                .executeUpdate();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // MAT-IMPORT-08
    // PRD intent: batch import 5000 rows < 30 s.
    // v1 performance simplified: 500 rows via multipart upload, must succeed in
    // < 30 000 ms and return {"imported": 500}.
    // Full 5000-row SLA can be validated once CI hardware baseline is established.
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    @DisplayName("MAT-IMPORT-08 [v1 性能简化版] 500 行 Excel 批量导入 < 30s, successCount=500")
    void matImport08_bulkImport500Rows_withinSLA() throws Exception {
        final int ROW_COUNT = 500;
        byte[] xlsxBytes = buildImportExcel(ROW_COUNT, "EDGE-IMP-");

        long startMs = System.currentTimeMillis();

        int imported = RestAssured.given()
                .multiPart("file", "bulk_import.xlsx", xlsxBytes,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .when()
                    .post("/api/cpq/internal-materials/import")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .body("data.imported", equalTo(ROW_COUNT))
                    .extract()
                    .path("data.imported");

        long elapsedMs = System.currentTimeMillis() - startMs;

        assertEquals(ROW_COUNT, imported, "importFromExcel should return exactly " + ROW_COUNT + " imported rows");
        assertTrue(elapsedMs < 30_000,
                "Import of " + ROW_COUNT + " rows took " + elapsedMs + " ms, expected < 30 000 ms");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // MAT-DELETE-09
    // PRD: deleting an internal material that is referenced by
    // customer_material_mapping must be rejected with HTTP 400 and a message
    // indicating the constraint (contains "mappings" or "reference").
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @Order(2)
    @DisplayName("MAT-DELETE-09 内部料号被 customer_material_mapping 引用时 DELETE 返回 400")
    void matDelete09_referencedMaterial_returns400() {
        // Step 1: create the material via API
        String body = """
                {
                  "materialNo": "EDGE-DEL-MAT-001",
                  "name": "Edge Delete Test Material",
                  "specification": "test-spec",
                  "statusCode": "Y"
                }
                """;
        String materialId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                    .post("/api/cpq/internal-materials")
                .then()
                    .statusCode(200)
                    .body("code", equalTo(200))
                    .extract()
                    .path("data.id");

        assertNotNull(materialId, "Material should be created successfully");

        // Step 2: insert a CustomerMaterialMapping row referencing the material
        insertMapping(UUID.fromString(materialId));

        // Step 3: attempt DELETE — must be rejected
        RestAssured.given()
                .when()
                    .delete("/api/cpq/internal-materials/" + materialId)
                .then()
                    .statusCode(400)
                    .body("message", anyOf(
                            containsStringIgnoringCase("mappings"),
                            containsStringIgnoringCase("reference"),
                            containsStringIgnoringCase("mapping")));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Builds an in-memory XLSX with a header row followed by {@code rowCount} data rows.
     * Columns: materialNo | name | specification | size | statusCode
     * (matches InternalMaterialService.importFromExcel column index 0-4)
     */
    private byte[] buildImportExcel(int rowCount, String partNoPrefix) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");

            // Header row
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("MATERIAL_NO");
            header.createCell(1).setCellValue("NAME");
            header.createCell(2).setCellValue("SPECIFICATION");
            header.createCell(3).setCellValue("SIZE");
            header.createCell(4).setCellValue("STATUS_CODE");

            // Data rows
            for (int i = 1; i <= rowCount; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue(partNoPrefix + String.format("%06d", i));
                row.createCell(1).setCellValue("Material " + i);
                row.createCell(2).setCellValue("Spec-" + i);
                row.createCell(3).setCellValue("10x" + i);
                row.createCell(4).setCellValue("Y");
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Inserts a test customer (if not exists) and a CustomerMaterialMapping row
     * referencing the given materialId, to simulate a FK-locked delete scenario.
     */
    @Transactional(jakarta.transaction.Transactional.TxType.REQUIRES_NEW)
    void insertMapping(UUID materialId) {
        UUID testCustomerId = UUID.fromString("56000000-0000-0000-0000-000000000001");
        // Ensure the customer exists (seed data; ON CONFLICT is a no-op if already present)
        em.createNativeQuery(
                "INSERT INTO customer(id, name, code, level, accumulated_amount, status, created_at, updated_at) " +
                "VALUES (:id, 'Edge Test Customer', 'EDGE-CUST-001', 'STANDARD', 0, 'ACTIVE', NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", testCustomerId)
                .executeUpdate();

        em.createNativeQuery(
                "INSERT INTO customer_material_mapping" +
                "(id, customer_id, customer_part_no, material_id, created_at) " +
                "VALUES (:id, :cid, :cpn, :mid, NOW())")
                .setParameter("id", UUID.randomUUID())
                .setParameter("cid", testCustomerId)
                .setParameter("cpn", "EDGE-DEL-MAP-" + materialId)
                .setParameter("mid", materialId)
                .executeUpdate();
    }
}
