package com.cpq.task261026crypto;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** AC-11 black-box execution matrix for every operation listed in api.md section 4. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.ProdProfile.class)
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
class Task261026TwentyOperationRuntimeTest {

    private static final byte[] CIPHER =
            "T261026-CRYPTO-BE-MATRIX-CIPHERTEXT".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BUSINESS_INVALID_PLAINTEXT =
            "T261026-CRYPTO-BE-MATRIX-DECRYPTED-BUT-NOT-A-WORKBOOK".getBytes(StandardCharsets.UTF_8);

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    private final UUID customerId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private final UUID excelTemplateId = UUID.randomUUID();
    private final UUID mappingTemplateId = UUID.randomUUID();
    private final UUID priceSourceId = UUID.randomUUID();
    private final UUID componentDirectoryId = UUID.randomUUID();
    private final String customerCode = "T261026-CRYPTO-BE-MATRIX-CUST-"
            + customerId.toString().substring(0, 8);
    private final String materialCode = "T261026-CRYPTO-BE-MATRIX-MAT-"
            + customerId.toString().substring(0, 8);
    private String adminSession;

    @BeforeEach
    void seedEveryPreconditionThatCouldShortCircuitBeforeCrypto() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("INSERT INTO customer "
                        + "(id, code, name, level, status, created_at, updated_at) "
                        + "VALUES (:id, :code, :name, 'STANDARD', 'ACTIVE', NOW(), NOW())")
                .setParameter("id", customerId)
                .setParameter("code", customerCode)
                .setParameter("name", customerCode)
                .executeUpdate();
        em.createNativeQuery("INSERT INTO template "
                        + "(id, template_series_id, name, status, template_kind, created_at, updated_at) "
                        + "VALUES (:id, :series, :name, 'DRAFT', 'QUOTATION', NOW(), NOW())")
                .setParameter("id", templateId)
                .setParameter("series", UUID.randomUUID())
                .setParameter("name", "T261026-CRYPTO-BE-MATRIX-TEMPLATE")
                .executeUpdate();
        em.createNativeQuery("INSERT INTO customer_excel_template "
                        + "(id, name, customer_id, header_row_index, data_start_row_index, sheet_index, "
                        + "part_no_column, excel_columns, created_at, updated_at) "
                        + "VALUES (:id, :name, :customer, 1, 2, 0, '客户料号', CAST('[]' AS jsonb), NOW(), NOW())")
                .setParameter("id", excelTemplateId)
                .setParameter("name", "T261026-CRYPTO-BE-MATRIX-EXCEL")
                .setParameter("customer", customerId)
                .executeUpdate();
        em.createNativeQuery("INSERT INTO import_mapping_template "
                        + "(id, name, excel_template_id, template_id, column_mappings, created_at, updated_at) "
                        + "VALUES (:id, :name, :excel, :template, CAST('[]' AS jsonb), NOW(), NOW())")
                .setParameter("id", mappingTemplateId)
                .setParameter("name", "T261026-CRYPTO-BE-MATRIX-MAPPING")
                .setParameter("excel", excelTemplateId)
                .setParameter("template", templateId)
                .executeUpdate();
        em.createNativeQuery("INSERT INTO element_price_source "
                        + "(id, source_name, source_type, status, created_at, updated_at) "
                        + "VALUES (:id, :name, 'MANUAL', 'ACTIVE', NOW(), NOW())")
                .setParameter("id", priceSourceId)
                .setParameter("name", "T261026-CRYPTO-BE-MATRIX-SOURCE")
                .executeUpdate();
        em.createNativeQuery("INSERT INTO component_directory(id, name, sort_order, created_at) "
                        + "VALUES (:id, :name, 0, NOW())")
                .setParameter("id", componentDirectoryId)
                .setParameter("name", "T261026-CRYPTO-BE-MATRIX-DIR")
                .executeUpdate();
        em.createNativeQuery("INSERT INTO material_recipe "
                        + "(id, code, symbol, name, recipe_type, status) "
                        + "VALUES (gen_random_uuid(), :code, 'T261026', :name, 'locked', 'ACTIVE')")
                .setParameter("code", materialCode)
                .setParameter("name", materialCode)
                .executeUpdate();
        utx.commit();

        Response login = given()
                .contentType(JSON)
                .body("{\"username\":\"admin\",\"password\":\"Admin@2026\"}")
                .when().post("/api/cpq/auth/login");
        assertEquals(200, login.statusCode(), login.asString());
        adminSession = login.cookie("CPQ_SESSION");
        assertNotNull(adminSession, "Matrix setup must cache one valid admin session");
        assertFalse(adminSession.isBlank(), "Matrix setup received a blank admin session");
    }

    @AfterEach
    void awaitAsyncWorkAndPreciselyDeletePrivateMatrixFixtures() throws Exception {
        awaitQuotationRecordsTerminal();
        List<?> paths = em.createNativeQuery(
                        "SELECT original_file_path FROM import_record WHERE customer_id = :customer")
                .setParameter("customer", customerId)
                .getResultList();
        for (Object value : paths) {
            if (value != null && !value.toString().isBlank()) {
                Files.deleteIfExists(Path.of(value.toString()));
            }
        }

        utx.begin();
        em.joinTransaction();
        for (String table : quoteTablesWithCustomerNo()) {
            em.createNativeQuery("DELETE FROM " + table + " WHERE customer_no = :code")
                    .setParameter("code", customerCode)
                    .executeUpdate();
        }
        em.createNativeQuery("UPDATE import_record SET quotation_id = NULL WHERE customer_id = :customer")
                .setParameter("customer", customerId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM import_record WHERE customer_id = :customer")
                .setParameter("customer", customerId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM model_config WHERE subject_key = :material")
                .setParameter("material", materialCode)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN "
                        + "(SELECT id FROM component WHERE directory_id = :directory)")
                .setParameter("directory", componentDirectoryId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE directory_id = :directory")
                .setParameter("directory", componentDirectoryId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM component_directory WHERE id = :directory")
                .setParameter("directory", componentDirectoryId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM element_price_source WHERE id = :source")
                .setParameter("source", priceSourceId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM import_mapping_template WHERE id = :id")
                .setParameter("id", mappingTemplateId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM customer_excel_template WHERE id = :id")
                .setParameter("id", excelTemplateId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM template WHERE id = :id")
                .setParameter("id", templateId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM material_recipe WHERE code = :code")
                .setParameter("code", materialCode)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM customer_contact WHERE customer_id = :customer")
                .setParameter("customer", customerId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM customer WHERE id = :customer")
                .setParameter("customer", customerId)
                .executeUpdate();
        utx.commit();
    }

    @Test
    void ac11_eachOfTheTwentyDocumentedOperationsProcessesEveryFileSlot() {
        List<Operation> operations = List.of(
                one("01-process-master", "/api/cpq/v6/process-master/import", this::fileOnly),
                one("02-elements", "/api/cpq/elements/import", this::fileOnly),
                one("03-material-recipes", "/api/cpq/material-recipes/import", this::fileOnly),
                one("04-currency-all", "/api/cpq/currencies/rates/import", this::fileOnly),
                one("05-currency-code", "/api/cpq/currencies/CNY/rates/import", this::fileOnly),
                one("06-customers", "/api/cpq/customers/import", this::fileOnly),
                one("07-quotation", "/api/cpq/dataset/quote/quotation-import",
                        () -> fileOnly().multiPart("customerId", customerId.toString())),
                one("08-dataset", "/api/cpq/dataset/cost-basic/import", this::fileOnly),
                one("09-element-price", "/api/cpq/element-price/import",
                        () -> fileOnly()
                                .multiPart("sourceId", priceSourceId.toString())
                                .multiPart("priceDate", LocalDate.now().toString())
                                .multiPart("currency", "CNY")),
                one("10-excel-headers", "/api/cpq/excel-templates/parse-headers", this::fileOnly),
                one("11-customer-material-map",
                        "/api/cpq/customers/" + customerId + "/material-mappings/import", this::fileOnly),
                one("12-import-execute", "/api/cpq/imports/execute",
                        () -> fileOnly()
                                .multiPart("customerId", customerId.toString())
                                .multiPart("excelTemplateId", excelTemplateId.toString())
                                .multiPart("mappingTemplateId", mappingTemplateId.toString())),
                one("13-import-preview", "/api/cpq/imports/import-excel",
                        () -> fileOnly()
                                .multiPart("templateId", templateId.toString())
                                .multiPart("customerId", customerId.toString())),
                one("14-internal-materials", "/api/cpq/internal-materials/import", this::fileOnly),
                two("15-model-configs", "/api/cpq/model-configs",
                        () -> given()
                                .cookie("CPQ_SESSION", adminSession)
                                .multiPart("subjectType", "MATERIAL")
                                .multiPart("subjectKey", materialCode)
                                .multiPart("label", "T261026-CRYPTO-BE-MATRIX")
                                .multiPart("glbFile", "matrix.glb", CIPHER, "model/gltf-binary")
                                .multiPart("thumbnailFile", "matrix.png", CIPHER, "image/png")
                                .multiPart("setCurrent", "true")),
                one("16-products", "/api/cpq/products/import", this::fileOnly),
                one("17-users", "/api/cpq/users/import", this::fileOnly),
                one("18-template-excel-header",
                        "/api/cpq/templates/" + templateId + "/excel-view-config/parse-header", this::fileOnly),
                one("19-component-preview",
                        "/api/cpq/component-directories/" + componentDirectoryId
                                + "/import?conflictPolicy=RENAME", this::jsonFileOnly),
                one("20-component-commit",
                        "/api/cpq/component-directories/" + componentDirectoryId
                                + "/import/commit?conflictPolicy=RENAME"
                                + "&ignoreMissingDeps=false&ignoreUnboundFormulas=false", this::jsonFileOnly));

        assertEquals(20, operations.size(), "The executable matrix must contain exactly 20 rows");
        List<Result> results = new ArrayList<>();
        for (Operation operation : operations) {
            Task261026CryptoTestSupport.FakeCryptoResource.plan(
                    200, json("{\"encrypted\":true}"), 200, BUSINESS_INVALID_PLAINTEXT);
            if (operation.fileSlots() == 2) {
                Task261026CryptoTestSupport.FakeCryptoResource.decryptForFile(
                        "matrix.png", 503, json("{\"message\":\"matrix-stop-before-write\"}"));
            }

            Response response = operation.request().get().when().post(operation.path());
            if ("07-quotation".equals(operation.name()) && response.statusCode() == 200) {
                String recordId = response.jsonPath().getString("data.importRecordId");
                assertFalse(recordId == null || recordId.isBlank(), response.asString());
                awaitQuotationRecordTerminal(recordId);
            }
            List<Task261026CryptoTestSupport.CapturedRequest> captured =
                    Task261026CryptoTestSupport.FakeCryptoResource.requests();
            results.add(new Result(operation.name(), operation.path(), response.statusCode(), captured.size()));

            assertFalse(List.of(405, 415).contains(response.statusCode()),
                    operation.name() + " did not enter the documented multipart operation: " + response.asString());
            assertEquals(operation.fileSlots(),
                    Task261026CryptoTestSupport.FakeCryptoResource.count("/v1/inspect"), operation.name());
            assertEquals(operation.fileSlots(),
                    Task261026CryptoTestSupport.FakeCryptoResource.count("/v1/decrypt"), operation.name());
            assertEquals(operation.fileSlots() * 2, captured.size(), operation.name());
            for (int slot = 0; slot < operation.fileSlots(); slot++) {
                int start = slot * 2;
                assertEquals("/v1/inspect", captured.get(start).path(), operation.name());
                assertEquals("/v1/decrypt", captured.get(start + 1).path(), operation.name());
                assertEquals(captured.get(start).requestId(), captured.get(start + 1).requestId(),
                        operation.name());
            }
            if (operation.fileSlots() == 2) {
                assertNotEquals(captured.get(0).requestId(), captured.get(2).requestId(),
                        "model glbFile and thumbnailFile require independent request IDs");
            }
        }

        assertEquals(20, results.size(), "Every manifest row must produce a runtime result row");
        assertEquals(20, results.stream().map(Result::name).distinct().count(),
                "Runtime result rows must map one-to-one to operations");
    }

    private RequestSpecification fileOnly() {
        return given()
                .cookie("CPQ_SESSION", adminSession)
                .multiPart("file", "T261026-CRYPTO-BE-MATRIX.xlsx", CIPHER,
                        Task261026CryptoTestSupport.XLSX_MIME);
    }

    private RequestSpecification jsonFileOnly() {
        return given()
                .cookie("CPQ_SESSION", adminSession)
                .multiPart("file", "T261026-CRYPTO-BE-MATRIX.json", CIPHER,
                        "application/json");
    }

    private void awaitQuotationRecordTerminal(String recordId) {
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            Response response = given()
                    .cookie("CPQ_SESSION", adminSession)
                    .when()
                    .get("/api/cpq/dataset/quote/quotation-import/{id}", recordId);
            assertEquals(200, response.statusCode(), response.asString());
            String status = response.jsonPath().getString("data.status");
            if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
                return;
            }
            pauseForWorker();
        }
        throw new AssertionError("Private quotation import stayed PROCESSING for 120 seconds: " + recordId);
    }

    private void awaitQuotationRecordsTerminal() {
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            long processing = ((Number) em.createNativeQuery(
                            "SELECT count(*) FROM import_record "
                                    + "WHERE customer_id = :customer "
                                    + "AND import_status NOT IN ('SUCCESS', 'FAILED')")
                    .setParameter("customer", customerId)
                    .getSingleResult()).longValue();
            if (processing == 0) {
                return;
            }
            pauseForWorker();
        }
        throw new AssertionError("Private quotation import rows stayed PROCESSING; cleanup refused to race worker");
    }

    @SuppressWarnings("unchecked")
    private List<String> quoteTablesWithCustomerNo() {
        return em.createNativeQuery("SELECT table_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name LIKE 'ds_quote_%' "
                        + "AND column_name = 'customer_no' "
                        + "ORDER BY CASE WHEN table_name LIKE '%_history' THEN 0 ELSE 1 END, table_name")
                .getResultList();
    }

    private static void pauseForWorker() {
        try {
            Thread.sleep(500L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for private quotation import", interrupted);
        }
    }

    private static Operation one(String name, String path, Supplier<RequestSpecification> request) {
        return new Operation(name, path, 1, request);
    }

    private static Operation two(String name, String path, Supplier<RequestSpecification> request) {
        return new Operation(name, path, 2, request);
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Operation(
            String name,
            String path,
            int fileSlots,
            Supplier<RequestSpecification> request) {
    }

    private record Result(String name, String path, int status, int upstreamCalls) {
    }
}
