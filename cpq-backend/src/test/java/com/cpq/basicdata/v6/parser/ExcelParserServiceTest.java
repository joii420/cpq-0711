package com.cpq.basicdata.v6.parser;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExcelParserServiceTest {

    private final ExcelParserService parser = new ExcelParserService();

    private Sheet sheetWith(XSSFWorkbook wb, String[] headers, String[] values) {
        Sheet s = wb.createSheet("t");
        Row h = s.createRow(0);
        for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);
        Row d = s.createRow(1);
        for (int i = 0; i < values.length; i++) d.createCell(i).setCellValue(values[i]);
        return s;
    }

    @Test
    void duplicateHeader_noThrow_resolvedByPosition() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            // 模拟组成件BOM: 宏丰料号 | 项次 | 工序编号 | 组装工序 | 项次 | 组成件料号
            Sheet s = sheetWith(wb,
                new String[]{"宏丰料号", "项次", "工序编号", "组装工序", "项次", "组成件料号"},
                new String[]{"MAT1", "1", "OP1", "Z350", "5", "C9"});
            List<SheetRow> rows = parser.parseSheet(s);   // 不抛异常
            assertEquals(1, rows.size());
            SheetRow r = rows.get(0);
            assertEquals("1", r.getStr("项次"), "首现优先=第一个项次");
            assertEquals(Integer.valueOf(1), r.getIntNth("项次", 1));
            assertEquals(Integer.valueOf(5), r.getIntNth("项次", 2), "第二个项次=5");
            assertNull(r.getIntNth("项次", 3), "只有两个项次,第3个为 null");
            assertEquals("Z350", r.getStr("组装工序"));
        }
    }

    @Test
    void duplicateAssemblyProcess_firstWins_isCode() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            // 两列都叫"组装工序": 编码在前(Z350), 名称在后(焊接)
            Sheet s = sheetWith(wb,
                new String[]{"宏丰料号", "组装工序", "组装工序", "组装加工费"},
                new String[]{"MAT1", "Z350", "焊接", "20"});
            List<SheetRow> rows = parser.parseSheet(s);
            assertEquals("Z350", rows.get(0).getStr("组装工序"), "首现=编码列");
            assertEquals("焊接", rows.get(0).getStrNth("组装工序", 2), "第2个=名称列");
        }
    }

    @Test
    void distinctHeaders_parsesAllColumns() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = sheetWith(wb,
                new String[]{"宏丰料号", "组装工序编码", "组装工序名称"},
                new String[]{"MAT1", "Z350", "焊接"});
            List<SheetRow> rows = parser.parseSheet(s);
            assertEquals("Z350", rows.get(0).getStr("组装工序编码"));
            assertEquals("焊接", rows.get(0).getStr("组装工序名称"));
        }
    }
}
