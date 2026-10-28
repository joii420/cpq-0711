package com.cpq.quotation.service;

import com.cpq.quotation.dto.QuotationDTO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuotationExportRolePrecisionTest {

    @Test
    void salesExcelCapsComputedAmountsButKeepsRatesAndRawJsonUnchanged() throws Exception {
        UUID quotationId = UUID.randomUUID();
        QuotationDTO quotation = quotationWithPrecisionSentinels();
        QuotationService quotationService = mock(QuotationService.class);
        when(quotationService.getById(quotationId)).thenReturn(quotation);

        QuotationExportService service = new QuotationExportService();
        service.quotationService = quotationService;

        byte[] bytes = service.exportExcel(quotationId, true, true, 4);
        List<String> strings = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING) {
                            strings.add(cell.getStringCellValue());
                        }
                    }
                }
            }
        }

        assertTrue(strings.contains("1.2346"), strings.toString());
        assertTrue(strings.contains("88.123456789"), strings.toString());
        assertTrue(strings.contains("13.123456789"), strings.toString());
        assertTrue(strings.contains("{\"raw\":\"1.2345678915\"}"), strings.toString());
        assertFalse(strings.contains("1.234567892"), strings.toString());
    }

    private static QuotationDTO quotationWithPrecisionSentinels() {
        BigDecimal computed = new BigDecimal("1.2345678915");
        QuotationDTO quotation = new QuotationDTO();
        quotation.quotationNumber = "T261027_RDP";
        quotation.originalAmount = computed;
        quotation.totalAmount = computed;
        quotation.finalDiscountRate = new BigDecimal("88.123456789123");

        QuotationDTO.LineItemDTO line = new QuotationDTO.LineItemDTO();
        line.id = UUID.randomUUID();
        line.subtotal = computed;
        line.discountRateApplied = new BigDecimal("88.123456789123");
        line.lineUnitPrice = computed;
        line.lineDiscountAmount = computed;
        line.lineFinalPrice = computed;
        line.lineTotalAmount = computed;
        line.vatRate = new BigDecimal("13.123456789123");
        line.vatRateRatio = BigDecimal.ZERO;

        QuotationDTO.ComponentDataDTO component = new QuotationDTO.ComponentDataDTO();
        component.tabName = "物料";
        component.subtotal = computed;
        component.rowData = "{\"raw\":\"1.2345678915\"}";
        line.componentData = List.of(component);
        quotation.lineItems = List.of(line);
        return quotation;
    }
}
