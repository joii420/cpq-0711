package com.cpq.costing;

import com.cpq.costing.dto.ComparisonExportRequest;
import com.cpq.costing.service.ComparisonExportService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComparisonExportServiceTest {

    private ComparisonExportRequest sampleRequest() {
        ComparisonExportRequest req = new ComparisonExportRequest();

        ComparisonExportRequest.Column c = new ComparisonExportRequest.Column();
        c.tag = "MATERIAL"; c.label = "材料费"; c.groupName = "成本";
        req.columns = List.of(c);

        ComparisonExportRequest.Cell cell = new ComparisonExportRequest.Cell();
        cell.quote = new BigDecimal("98765431.123456789012");
        cell.costing = new BigDecimal("-0.000000000500");
        cell.highlighted = true;

        ComparisonExportRequest.Row row = new ComparisonExportRequest.Row();
        row.partNo = "P1"; row.presence = "BOTH";
        row.cells = Map.of("MATERIAL", cell);

        req.rows = List.of(row);
        return req;
    }

    @Test
    void writesTwoRowsPerPartWithValues() throws Exception {
        byte[] bytes = new ComparisonExportService().export(sampleRequest());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row reportRow = sheet.getRow(1);
            Row costingRow = sheet.getRow(2);
            assertEquals("报价", reportRow.getCell(1).getStringCellValue());
            assertEquals("核价", costingRow.getCell(1).getStringCellValue());
            assertEquals(CellType.STRING, reportRow.getCell(2).getCellType());
            assertEquals("98765431.123456789", reportRow.getCell(2).getStringCellValue());
            assertEquals(CellType.STRING, costingRow.getCell(2).getCellType());
            assertEquals("-0.000000001", costingRow.getCell(2).getStringCellValue());
        }
    }

    @Test
    void highlightedCellsHaveFill() throws Exception {
        byte[] bytes = new ComparisonExportService().export(sampleRequest());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Cell quoteCell = sheet.getRow(1).getCell(2);
            assertEquals(FillPatternType.SOLID_FOREGROUND, quoteCell.getCellStyle().getFillPattern(),
                    "报价行差异格应有实心填充");
            Cell costingCell = sheet.getRow(2).getCell(2);
            assertEquals(FillPatternType.SOLID_FOREGROUND, costingCell.getCellStyle().getFillPattern(),
                    "核价行差异格也应有实心填充");
        }
    }
}
