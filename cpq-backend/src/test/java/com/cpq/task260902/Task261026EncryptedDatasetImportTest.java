package com.cpq.task260902;

import com.cpq.task261026crypto.Task261026CryptoTestSupport;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AC-3 encrypted dataset import through the existing legal cost-basic fixture. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.ProdProfile.class)
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
class Task261026EncryptedDatasetImportTest extends DatasetAcTestBase {

    private static final String PREFIX = "T261026-CRYPTO-BE-DATASET-";
    private static final byte[] CIPHER =
            "T261026-DATASET-CIPHERTEXT-MUST-NOT-BE-PARSED".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void resetCryptoObserver() {
        Task261026CryptoTestSupport.FakeCryptoResource.reset();
    }

    @AfterEach
    void deleteOnlyThisSliceDatasetRows() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (FieldMatrixSpec.TableSpec spec : SPECS) {
                if (spec.versioned) {
                    deletePrefix(spec.historyTableName(), spec.axisField);
                }
                deletePrefix(spec.tableName, prefixColumn(spec));
            }
        });
        assertEquals(0, totalPrefixRows(), "Dataset prefix cleanup must be exact and complete");
    }

    @Test
    void ac3_encryptedDatasetImportDecryptsBeforeParsingAndWritesOnlyPlaintextAxes() throws Exception {
        String runPrefix = PREFIX + UUID.randomUUID().toString().substring(0, 8) + "-";
        DatasetFixtureBuilder builder = Fixtures.costBasic(probe());
        byte[] plaintext;
        try {
            // The repository template predates pricing_unit in the current registry.
            // Keep one real unversioned write and remove unrelated cross-sheet axes.
            if (!builder.hasColumn("物料", "计价单位")) {
                builder.insertColumnAt("物料", 6, "计价单位", "PCS");
            }
            for (int rowNo : builder.dataRowNumbers("物料", false)) {
                builder.setText("物料", rowNo, "类型", "零件");
            }
            for (String sheet : Fixtures.COST_BASIC_VERSIONED_SHEETS) {
                builder.clearDataRows(sheet, true);
            }
            plaintext = replaceFixturePrefix(builder.toBytes(), P, runPrefix);
        } finally {
            builder.close();
        }
        Task261026CryptoTestSupport.FakeCryptoResource.plan(
                200, "{\"encrypted\":true}".getBytes(StandardCharsets.UTF_8), 200, plaintext);

        Response response = DatasetApi.importFile(
                adminSession(),
                DatasetApi.COST_BASIC,
                CIPHER,
                "T261026-encrypted-cost-basic.xlsx");

        assertEquals(200, response.statusCode(), response.asString());
        List<?> summary = response.jsonPath().getList("data.summary");
        assertNotNull(summary, "A real import response must contain data.summary");
        assertFalse(summary.isEmpty(), "A real import must produce a non-empty per-sheet summary");
        assertEquals(Fixtures.COST_BASIC_SHEETS.size(), summary.size(), response.asString());
        assertEquals(List.of("/v1/inspect", "/v1/decrypt"),
                Task261026CryptoTestSupport.FakeCryptoResource.requests().stream()
                        .map(Task261026CryptoTestSupport.CapturedRequest::path).toList());
        assertTrue(totalPrefixRows() > 0, "Decrypted unique axes must be present in dataset tables");
        assertEquals(0, totalRowsLike("%T261026-DATASET-CIPHERTEXT%"),
                "Ciphertext marker must not be persisted in any dataset axis/primary column");
    }

    private byte[] replaceFixturePrefix(byte[] bytes, String oldPrefix, String newPrefix) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING
                                && cell.getStringCellValue().contains(oldPrefix)) {
                            cell.setCellValue(cell.getStringCellValue().replace(oldPrefix, newPrefix));
                        }
                    }
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void deletePrefix(String table, String column) {
        if (column == null || !tableExists(table) || !columnExists(table, column)) {
            return;
        }
        em.createNativeQuery("DELETE FROM " + table + " WHERE " + column + " LIKE :prefix")
                .setParameter("prefix", PREFIX + "%")
                .executeUpdate();
    }

    private long totalPrefixRows() {
        return totalRowsLike(PREFIX + "%");
    }

    private long totalRowsLike(String pattern) {
        long total = 0;
        for (FieldMatrixSpec.TableSpec spec : SPECS) {
            String column = prefixColumn(spec);
            total += countTableLike(spec.tableName, column, pattern);
            if (spec.versioned) {
                total += countTableLike(spec.historyTableName(), spec.axisField, pattern);
            }
        }
        return total;
    }

    private long countTableLike(String table, String column, String pattern) {
        if (column == null || !tableExists(table) || !columnExists(table, column)) {
            return 0;
        }
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM " + table + " WHERE " + column + " LIKE :pattern")
                .setParameter("pattern", pattern)
                .getSingleResult()).longValue();
    }

    private static String prefixColumn(FieldMatrixSpec.TableSpec spec) {
        if (spec.axisField != null) {
            return spec.axisField;
        }
        for (String candidate : List.of(
                "material_no", "production_no", "customer_product_no", "scheme_no")) {
            if (spec.builtColumns.contains(candidate)) {
                return candidate;
            }
        }
        return spec.builtColumns.isEmpty() ? null : spec.builtColumns.get(0);
    }
}
