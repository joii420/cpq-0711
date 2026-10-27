package com.cpq.task261026crypto;

import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Black-box fixture support based only on the published customer-import template contract. */
abstract class Task261026CustomerImportBase {

    static final String PREFIX = "T261026-CRYPTO-BE-CUSTOMER-";
    static final Set<String> REPORT_KEYS =
            Set.of("totalRows", "createdCustomers", "createdContacts", "errors");

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    @AfterEach
    void deleteOnlyCustomersCreatedByThisSlice() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM customer_contact WHERE customer_id IN "
                        + "(SELECT id FROM customer WHERE code LIKE :prefix)")
                .setParameter("prefix", PREFIX + "%")
                .executeUpdate();
        em.createNativeQuery("DELETE FROM customer WHERE code LIKE :prefix")
                .setParameter("prefix", PREFIX + "%")
                .executeUpdate();
        utx.commit();
    }

    byte[] legalMinimumCustomerWorkbook(String code, String name) throws IOException {
        Response template = given().when().get("/api/cpq/customers/import/template");
        assertEquals(200, template.statusCode(), template.asString());
        byte[] templateBytes = template.asByteArray();
        assertTrue(templateBytes.length > 0, "The published customer template must be non-empty");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(templateBytes));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheet("客户");
            if (sheet == null) {
                sheet = workbook.getSheetAt(0);
            }
            Row header = sheet.getRow(0);
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (int column = 0; column < header.getLastCellNum(); column++) {
                columns.put(formatter.formatCellValue(header.getCell(column)).trim(), column);
            }
            assertTrue(columns.keySet().containsAll(
                            Set.of("客户编号", "客户名称", "联系人姓名", "联系人电话")),
                    "Downloaded template is missing a documented mandatory column: " + columns.keySet());

            for (int rowNumber = sheet.getLastRowNum(); rowNumber >= 1; rowNumber--) {
                Row row = sheet.getRow(rowNumber);
                if (row != null) {
                    sheet.removeRow(row);
                }
            }
            Row data = sheet.createRow(1);
            put(data, columns, "客户编号", code);
            put(data, columns, "客户名称", name);
            put(data, columns, "联系人姓名", "测试联系人");
            put(data, columns, "联系人电话", "13800138000");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    Response importCustomer(byte[] bytes, String name) {
        return given()
                .multiPart("file", name, bytes, Task261026CryptoTestSupport.XLSX_MIME)
                .when().post("/api/cpq/customers/import");
    }

    void assertSuccessfulReport(Response response) {
        assertEquals(200, response.statusCode(), response.asString());
        Map<String, Object> envelope = response.jsonPath().getMap("");
        assertEquals(Set.of("code", "message", "data"), envelope.keySet(), response.asString());
        Map<String, Object> data = response.jsonPath().getMap("data");
        assertEquals(REPORT_KEYS, data.keySet(), response.asString());
        assertEquals(1, ((Number) data.get("totalRows")).intValue(), response.asString());
        assertEquals(1, ((Number) data.get("createdCustomers")).intValue(), response.asString());
        assertEquals(1, ((Number) data.get("createdContacts")).intValue(), response.asString());
        assertTrue(((java.util.List<?>) data.get("errors")).isEmpty(), response.asString());
    }

    long customerCount(String code, String name) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM customer WHERE code = :code AND name = :name")
                .setParameter("code", code)
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    long contactCount(String code) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM customer_contact cc "
                        + "JOIN customer c ON c.id = cc.customer_id "
                        + "WHERE c.code = :code AND cc.name = '测试联系人'")
                .setParameter("code", code)
                .getSingleResult()).longValue();
    }

    private static void put(Row row, Map<String, Integer> columns, String header, String value) {
        row.createCell(columns.get(header)).setCellValue(value);
    }
}
