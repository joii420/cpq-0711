package com.cpq.task261012;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T261012 API contract checks for the read-only workbook endpoints. */
@QuarkusTest
class MaterialElementTemplateApiTest {

    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static Map<String, String> adminCookies;

    @Inject
    EntityManager em;

    @Test
    void t261012_elementImportTemplate_hasContractHeadersAndOneExampleRow() throws Exception {
        Response response = asAdmin().when()
                .get("/api/cpq/elements/import/template").thenReturn();
        assertEquals(200, response.statusCode());
        assertTrue(response.contentType().contains(XLSX));
        List<List<String>> rows = rows(response.asByteArray());
        assertEquals(List.of("元素符号", "中文名称"), rows.get(0));
        assertEquals(2, rows.size(), "模板必须有且只有一行示例");
        assertFalse(rows.get(1).get(0).isBlank());
        assertFalse(rows.get(1).get(1).isBlank());
    }

    @Test
    void t261012_materialImportTemplate_hasStrictHeadersAndOneExampleRow() throws Exception {
        Response response = asAdmin().when()
                .get("/api/cpq/material-recipes/import/template").thenReturn();
        assertEquals(200, response.statusCode());
        assertTrue(response.contentType().contains(XLSX));
        List<List<String>> rows = rows(response.asByteArray());
        assertEquals(List.of("材质", "组号", "元素符号", "含量"), rows.get(0));
        assertEquals(2, rows.size(), "模板必须有且只有一行示例");
        for (String cell : rows.get(1)) assertFalse(cell.isBlank(), "示例行不得有空单元格");
    }

    @Test
    void t261012_elementExport_hasFourColumnReadOnlyContract() throws Exception {
        Response response = asAdmin().when()
                .get("/api/cpq/elements/export").thenReturn();
        assertEquals(200, response.statusCode());
        assertTrue(response.contentType().contains(XLSX));
        String disposition = response.header("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.contains("attachment"));
        List<List<String>> rows = rows(response.asByteArray());
        assertEquals(List.of("元素编号", "元素符号", "中文名称", "状态"), rows.get(0));
        assertTrue(rows.size() > 1, "导出必须至少包含一行现存元素");
        assertEquals(4, rows.get(1).size());
    }

    @Test
    void t261012_elementImport_conflictingNames_returnsRowsAndWritesNothing() throws Exception {
        String symbol = "T261012_" + System.nanoTime();
        long before = elementCount(symbol);
        Response response = asAdmin()
                .multiPart("file", "t261012-element-conflict.xlsx", elementWorkbook(symbol), XLSX)
                .post("/api/cpq/elements/import").thenReturn();

        assertEquals(400, response.statusCode(), response.asString());
        assertEquals("ELEMENT_IMPORT_CONFLICT", response.jsonPath().getString("detail.bizCode"));
        assertEquals(symbol, response.jsonPath().getString("detail.conflicts[0].symbol"));
        assertEquals(List.of(2, 3), response.jsonPath().getList("detail.conflicts[0].rows", Integer.class));
        assertEquals(List.of("名称甲", "名称乙"), response.jsonPath().getList("detail.conflicts[0].names", String.class));
        assertEquals(before, elementCount(symbol), "异名冲突必须整批拒收且不得写入元素表");
    }

    @Test
    void t261012_materialImport_missingElement_returnsStructuredValidationAndWritesNothing() throws Exception {
        String material = "T261012_材质_" + System.nanoTime();
        String missingElement = "T261012_MISSING_" + System.nanoTime();
        long before = recipeCount(material);
        Response response = asAdmin()
                .multiPart("file", "t261012-material-missing-element.xlsx",
                        materialWorkbook(material, missingElement), XLSX)
                .post("/api/cpq/material-recipes/import").thenReturn();

        assertEquals(400, response.statusCode(), response.asString());
        assertEquals("MATERIAL_IMPORT_VALIDATION_FAILED", response.jsonPath().getString("detail.bizCode"));
        assertTrue(response.jsonPath().getList("detail.missingElements", String.class).contains(missingElement),
                "结构化错误必须列出不存在元素：" + response.asString());
        assertEquals(before, recipeCount(material), "缺失元素必须整份回滚，不得新增材质");
    }

    private RequestSpecification asAdmin() {
        return RestAssured.given().cookies(adminSession());
    }

    private static synchronized Map<String, String> adminSession() {
        if (adminCookies != null) return adminCookies;
        Response login = RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("username", "admin", "password", "Admin@2026"))
                .post("/api/cpq/auth/login").thenReturn();
        assertEquals(200, login.statusCode(), "管理员登录失败，无法执行受保护 API 测试：" + login.asString());
        adminCookies = new LinkedHashMap<>(login.getCookies());
        assertFalse(adminCookies.isEmpty(), "管理员登录 200 但未返回会话 cookie");
        Response me = RestAssured.given().cookies(adminCookies).get("/api/cpq/auth/me").thenReturn();
        assertEquals(200, me.statusCode(), "管理员会话验证失败：" + me.asString());
        assertEquals("SYSTEM_ADMIN", me.jsonPath().getString("data.role"), "测试会话必须是 SYSTEM_ADMIN");
        return adminCookies;
    }

    private long elementCount(String symbol) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM element WHERE element_code = :code")
                .setParameter("code", symbol).getSingleResult()).longValue());
    }

    private long recipeCount(String symbol) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_recipe WHERE symbol = :symbol")
                .setParameter("symbol", symbol).getSingleResult()).longValue());
    }

    private static byte[] elementWorkbook(String symbol) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("元素");
            sheet.createRow(0).createCell(0).setCellValue("元素符号");
            sheet.getRow(0).createCell(1).setCellValue("中文名称");
            sheet.createRow(1).createCell(0).setCellValue(symbol);
            sheet.getRow(1).createCell(1).setCellValue("名称甲");
            sheet.createRow(2).createCell(0).setCellValue(symbol);
            sheet.getRow(2).createCell(1).setCellValue("名称乙");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] materialWorkbook(String material, String element) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("材质");
            String[] headers = {"材质", "组号", "元素符号", "含量"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(material);
            row.createCell(1).setCellValue("1");
            row.createCell(2).setCellValue(element);
            row.createCell(3).setCellValue("1");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static List<List<String>> rows(byte[] bytes) throws Exception {
        DataFormatter formatter = new DataFormatter();
        List<List<String>> result = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(1, workbook.getNumberOfSheets(), "接口导出的工作簿必须只有一个 sheet");
            for (Row row : workbook.getSheetAt(0)) {
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < row.getLastCellNum(); i++) {
                    cells.add(formatter.formatCellValue(row.getCell(i)).trim());
                }
                while (!cells.isEmpty() && cells.get(cells.size() - 1).isBlank()) cells.remove(cells.size() - 1);
                if (!cells.isEmpty()) result.add(cells);
            }
        }
        assertFalse(result.isEmpty(), "xlsx 至少应包含表头");
        return result;
    }
}
