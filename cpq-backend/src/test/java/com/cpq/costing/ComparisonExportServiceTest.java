package com.cpq.costing;

import com.cpq.costing.dto.ComparisonExportRequest;
import com.cpq.costing.service.ComparisonExportService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
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
        cell.quote = 10; cell.costing = 11; cell.highlighted = true;

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
            assertEquals(10.0, reportRow.getCell(2).getNumericCellValue(), 1e-9);
            assertEquals(11.0, costingRow.getCell(2).getNumericCellValue(), 1e-9);
        }
    }

    @Test
    void highlightedCellsHaveFill() throws Exception {
        byte[] bytes = new ComparisonExportService().export(sampleRequest());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Cell quoteCell = sheet.getRow(1).getCell(2);
            assertEquals(FillPatternType.SOLID_FOREGROUND, quoteCell.getCellStyle().getFillPattern(),
                    "差异格应有实心填充");
        }
    }
}
