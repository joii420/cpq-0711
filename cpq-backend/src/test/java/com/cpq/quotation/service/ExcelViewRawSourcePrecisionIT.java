package com.cpq.quotation.service;

import com.cpq.quotation.entity.QuotationLineItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TC-050/051: raw Excel sources keep their text while formulas consume exact BigDecimal values. */
@QuarkusTest
@TestProfile(ExcelViewRawSourcePrecisionIT.RbacOffProfile.class)
class ExcelViewRawSourcePrecisionIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RAW_8 = "123.12345678";
    private static final String RAW_12 = "98765431.123456789012";

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    @Inject ExcelViewService excelViewService;
    @Inject EntityManager em;

    private UUID customerId;
    private UUID templateId;
    private UUID quotationId;
    private UUID lineItemId;
    private UUID componentDataId;

    @BeforeEach
    void seedFixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            customerId = UUID.randomUUID();
            templateId = UUID.randomUUID();
            quotationId = UUID.randomUUID();
            lineItemId = UUID.randomUUID();
            componentDataId = UUID.randomUUID();
            UUID componentId = UUID.randomUUID();
            UUID salesRepId = (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1")
                .getSingleResult();

            exec("INSERT INTO customer (id,code,name) VALUES (:id,:code,'TC-050 raw source')",
                "id", customerId, "code", "TC050-" + customerId.toString().substring(0, 8));

            String columns = "[{\"col_key\":\"raw8\",\"label\":\"Raw 8\","
                + "\"source_type\":\"PRODUCT_ATTRIBUTE\",\"field_key\":\"raw8\"},"
                + "{\"col_key\":\"raw12\",\"label\":\"Raw 12\","
                + "\"source_type\":\"COMPONENT_FIELD\",\"field_key\":\"raw12\"},"
                + "{\"col_key\":\"calc12\",\"label\":\"Calculated\","
                + "\"source_type\":\"FORMULA\",\"formula\":\"[raw12]+0\"}]";
            exec("INSERT INTO template (id,template_series_id,name,status,formulas,"
                    + "template_sql_views_snapshot,excel_view_config,created_at,updated_at) "
                    + "VALUES (:id,:series,'TC-050 template','DRAFT','[]','{}',"
                    + "CAST(:columns AS jsonb),now(),now())",
                "id", templateId, "series", UUID.randomUUID(), "columns", columns);

            exec("INSERT INTO quotation (id,quotation_number,customer_id,name,sales_rep_id,status,"
                    + "total_amount,original_amount,system_discount_rate,final_discount_rate,tax_rate,"
                    + "tax_amount,created_at,updated_at) VALUES (:id,:number,:customerId,'TC-050 quote',"
                    + ":salesRepId,'DRAFT',0,0,100,100,0,0,now(),now())",
                "id", quotationId, "number", "TC050-" + quotationId,
                "customerId", customerId, "salesRepId", salesRepId);

            exec("INSERT INTO quotation_line_item (id,quotation_id,template_id,"
                    + "product_attribute_values,composite_type,subtotal,sort_order,created_at) "
                    + "VALUES (:id,:quotationId,:templateId,CAST(:attrs AS jsonb),'SIMPLE',0,0,now())",
                "id", lineItemId, "quotationId", quotationId, "templateId", templateId,
                "attrs", "{\"raw8\":\"1.23456789\"}");

            exec("INSERT INTO quotation_line_component_data (id,line_item_id,component_id,row_data,"
                    + "subtotal,sort_order,created_at) VALUES (:id,:lineItemId,:componentId,"
                    + "CAST(:rows AS jsonb),0,0,now())",
                "id", componentDataId, "lineItemId", lineItemId, "componentId", componentId,
                "rows", "[{\"raw12\":\"1.234567891234\"}]");
        });
    }

    @AfterEach
    void cleanup() {
        if (quotationId == null) return;
        QuarkusTransaction.requiringNew().run(() -> {
            exec("DELETE FROM quotation_line_component_data WHERE id=:id", "id", componentDataId);
            exec("DELETE FROM quotation_line_item WHERE id=:id", "id", lineItemId);
            exec("DELETE FROM quotation WHERE id=:id", "id", quotationId);
            exec("DELETE FROM template WHERE id=:id", "id", templateId);
            exec("DELETE FROM customer WHERE id=:id", "id", customerId);
        });
    }

    @Test
    void rawEightAndTwelveDigitStrings_surviveDisplayAndPayload_whileFormulaUsesTwelveDigitNode()
            throws Exception {
        putCell("raw8", RAW_8);
        putCell("raw12", RAW_12);

        JsonNode response = MAPPER.readTree(RestAssured.given()
            .get("/api/cpq/quotations/" + quotationId + "/excel-view")
            .then().statusCode(200).extract().asString());
        JsonNode row = response.at("/data/rows/0");
        assertEquals(RAW_8, row.path("raw8").asText(), "TC-050 raw 8-digit source response");
        assertEquals(RAW_12, row.path("raw12").asText(), "TC-050 raw 12-digit source response");
        assertEquals(RAW_12, row.path("calc12").asText(), "TC-051 formula payload value");
        assertTrue(row.path("raw8").isTextual());
        assertTrue(row.path("raw12").isTextual());
        assertTrue(row.path("calc12").isTextual());

        Object[] stored = QuarkusTransaction.requiringNew().call(() -> (Object[]) em.createNativeQuery(
                "SELECT product_attribute_values->>'raw8',"
                    + "jsonb_typeof(product_attribute_values->'raw8'),"
                    + "(SELECT row_data->0->>'raw12' FROM quotation_line_component_data WHERE id=:cdId),"
                    + "(SELECT jsonb_typeof(row_data->0->'raw12') "
                    + "FROM quotation_line_component_data WHERE id=:cdId) "
                    + "FROM quotation_line_item WHERE id=:lineId")
            .setParameter("cdId", componentDataId).setParameter("lineId", lineItemId)
            .getSingleResult());
        assertEquals(RAW_8, stored[0], "TC-050 product attribute JSON text round-trip");
        assertEquals("string", stored[1], "TC-050 product attribute DB token must remain a string");
        assertEquals(RAW_12, stored[2], "TC-050 component row JSON text round-trip");
        assertEquals("string", stored[3], "TC-050 component row DB token must remain a string");

        Object calculated = QuarkusTransaction.requiringNew().call(() -> {
            QuotationLineItem line = QuotationLineItem.findById(lineItemId);
            return excelViewService.buildLineRowData(line, templateId, customerId).get("calc12");
        });
        assertTrue(calculated instanceof BigDecimal, "TC-051 formula node must be BigDecimal: " + calculated);
        BigDecimal calculatedDecimal = (BigDecimal) calculated;
        assertEquals(0, new BigDecimal(RAW_12).compareTo(calculatedDecimal),
            "TC-051 raw text -> BigDecimal -> +0 must remain exact");
        assertEquals(12, calculatedDecimal.scale(), "TC-051 formula node must retain 12 decimal places");

        byte[] workbookBytes = QuarkusTransaction.requiringNew().call(() ->
            excelViewService.exportExcelView(quotationId));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
            var exportRow = workbook.getSheetAt(0).getRow(1);
            assertEquals(CellType.STRING, exportRow.getCell(0).getCellType());
            assertEquals(CellType.STRING, exportRow.getCell(1).getCellType());
            assertEquals(CellType.STRING, exportRow.getCell(2).getCellType());
            assertEquals(RAW_8, exportRow.getCell(0).getStringCellValue(),
                "TC-050 raw 8-digit export display");
            assertEquals(RAW_12, exportRow.getCell(1).getStringCellValue(),
                "TC-050 raw 12-digit export display");
            assertEquals("98765431.123456789", exportRow.getCell(2).getStringCellValue(),
                "computed display alone uses the 9-digit rule");
        }
    }

    private void putCell(String colKey, String value) {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body(Map.of("lineItemId", lineItemId.toString(), "colKey", colKey, "value", value))
            .put("/api/cpq/quotations/" + quotationId + "/excel-view")
            .then().statusCode(200);
    }

    private void exec(String sql, Object... nameValuePairs) {
        var query = em.createNativeQuery(sql);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            query.setParameter((String) nameValuePairs[i], nameValuePairs[i + 1]);
        }
        query.executeUpdate();
    }
}
