package com.cpq.basicdata.v6.parser;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExcelParserServiceTest {

    private final ExcelParserService parser = new ExcelParserService();

    private Sheet sheetWithHeaders(XSSFWorkbook wb, String... headers) {
        Sheet s = wb.createSheet("t");
        Row h = s.createRow(0);
        for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);
        Row d = s.createRow(1);
        for (int i = 0; i < headers.length; i++) d.createCell(i).setCellValue("v" + i);
        return s;
    }

    @Test
    void duplicateHeader_throws() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = sheetWithHeaders(wb, "宏丰料号", "组装工序", "组装工序", "组装加工费");
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> parser.parseSheet(s));
            assertTrue(ex.getMessage().contains("组装工序"), "错误信息含重复列名");
        }
    }

    @Test
    void distinctHeaders_parsesAllColumns() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = sheetWithHeaders(wb, "宏丰料号", "组装工序编码", "组装工序名称");
            List<SheetRow> rows = parser.parseSheet(s);
            assertEquals(1, rows.size());
            assertEquals("v1", rows.get(0).getStr("组装工序编码"));
            assertEquals("v2", rows.get(0).getStr("组装工序名称"));
        }
    }
}
