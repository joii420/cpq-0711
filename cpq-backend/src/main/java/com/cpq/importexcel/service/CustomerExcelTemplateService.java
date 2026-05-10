package com.cpq.importexcel.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.importexcel.dto.CreateCustomerExcelTemplateRequest;
import com.cpq.importexcel.dto.CustomerExcelTemplateDTO;
import com.cpq.importexcel.entity.CustomerExcelTemplate;
import com.cpq.importexcel.entity.ImportMappingTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.*;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class CustomerExcelTemplateService {

    private static final Logger LOG = Logger.getLogger(CustomerExcelTemplateService.class);

    public List<CustomerExcelTemplateDTO> listByCustomer(UUID customerId) {
        List<CustomerExcelTemplate> list;
        if (customerId != null) {
            list = CustomerExcelTemplate.find("customerId = ?1 ORDER BY createdAt DESC", customerId).list();
        } else {
            list = CustomerExcelTemplate.find("ORDER BY createdAt DESC").list();
        }
        return list.stream()
                .map(CustomerExcelTemplateDTO::from)
                .collect(Collectors.toList());
    }

    public CustomerExcelTemplateDTO getById(UUID id) {
        CustomerExcelTemplate t = CustomerExcelTemplate.findById(id);
        if (t == null) throw new BusinessException(404, "CustomerExcelTemplate not found: " + id);
        return CustomerExcelTemplateDTO.from(t);
    }

    @Transactional
    public CustomerExcelTemplateDTO create(CreateCustomerExcelTemplateRequest request, UUID createdBy) {
        CustomerExcelTemplate t = new CustomerExcelTemplate();
        t.name = request.name;
        t.customerId = request.customerId;
        t.description = request.description;
        t.headerRowIndex = request.headerRowIndex;
        t.dataStartRowIndex = request.dataStartRowIndex;
        t.sheetIndex = request.sheetIndex;
        t.partNoColumn = request.partNoColumn;
        if (request.excelColumns != null) {
            t.excelColumns = toJsonArray(request.excelColumns);
        }
        t.createdBy = createdBy;
        t.persist();

        LOG.infof("Created CustomerExcelTemplate id=%s name=%s", t.id, t.name);
        return CustomerExcelTemplateDTO.from(t);
    }

    @Transactional
    public CustomerExcelTemplateDTO update(UUID id, CreateCustomerExcelTemplateRequest request) {
        CustomerExcelTemplate t = CustomerExcelTemplate.findById(id);
        if (t == null) throw new BusinessException(404, "CustomerExcelTemplate not found: " + id);

        if (request.name != null) t.name = request.name;
        if (request.description != null) t.description = request.description;
        t.headerRowIndex = request.headerRowIndex;
        t.dataStartRowIndex = request.dataStartRowIndex;
        t.sheetIndex = request.sheetIndex;
        if (request.partNoColumn != null) t.partNoColumn = request.partNoColumn;
        if (request.excelColumns != null) t.excelColumns = toJsonArray(request.excelColumns);

        return CustomerExcelTemplateDTO.from(t);
    }

    @Transactional
    public void delete(UUID id) {
        CustomerExcelTemplate t = CustomerExcelTemplate.findById(id);
        if (t == null) throw new BusinessException(404, "CustomerExcelTemplate not found: " + id);

        long refs = ImportMappingTemplate.count("excelTemplateId = ?1", id);
        if (refs > 0) throw new BusinessException(400, "Cannot delete: template has mapping templates");

        t.delete();
        LOG.infof("Deleted CustomerExcelTemplate id=%s", id);
    }

    /**
     * Parse Excel header row.
     * @param sheetIndex 0-based sheet index (UI shows as 1-based, frontend subtracts 1)
     * @param headerRowIndex 1-based row number (user perspective: "第2行" = 2, converted to 0-based here)
     */
    public List<String> parseExcelHeaders(InputStream inputStream, int sheetIndex, int headerRowIndex) {
        try (Workbook wb = WorkbookFactory.create(inputStream)) {
            // Convert 1-based user inputs to 0-based POI indices
            Sheet sheet = wb.getSheetAt(Math.max(0, sheetIndex - 1));
            int rowIdx = Math.max(0, headerRowIndex - 1);
            Row headerRow = sheet.getRow(rowIdx);
            if (headerRow == null) return Collections.emptyList();

            // Find the true last column: max of header row, data rows, and merged regions
            int lastCol = headerRow.getLastCellNum();
            // Also check the row above (merged headers might define wider range)
            if (rowIdx > 0) {
                Row aboveRow = sheet.getRow(rowIdx - 1);
                if (aboveRow != null) {
                    lastCol = Math.max(lastCol, aboveRow.getLastCellNum());
                }
            }
            // Also check first data row
            Row dataRow = sheet.getRow(rowIdx + 1);
            if (dataRow != null) {
                lastCol = Math.max(lastCol, dataRow.getLastCellNum());
            }
            // Also check merged regions for max column
            for (var mr : sheet.getMergedRegions()) {
                if (mr.getFirstRow() <= rowIdx && mr.getLastRow() >= rowIdx) {
                    lastCol = Math.max(lastCol, mr.getLastColumn() + 1);
                }
            }

            List<String> headers = new ArrayList<>();
            for (int colIdx = 0; colIdx < lastCol; colIdx++) {
                Cell cell = headerRow.getCell(colIdx);
                String val = getCellStringValue(cell);

                // If cell is empty, check if it's part of a merged region
                if (val == null || val.isBlank()) {
                    val = getMergedCellValue(sheet, rowIdx, colIdx);
                }

                if (val == null || val.isBlank()) {
                    headers.add("");
                } else {
                    headers.add(val.trim());
                }
            }

            // Remove trailing empty entries
            while (!headers.isEmpty() && headers.get(headers.size() - 1).isEmpty()) {
                headers.remove(headers.size() - 1);
            }

            // Filter out empty entries
            return headers.stream().filter(h -> !h.isEmpty()).collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            throw new BusinessException(400, "Failed to parse Excel headers: " + e.getMessage());
        }
    }

    /**
     * For a cell that's part of a merged region, find the top-left cell's value.
     */
    private String getMergedCellValue(Sheet sheet, int row, int col) {
        for (var mergedRegion : sheet.getMergedRegions()) {
            if (mergedRegion.isInRange(row, col)) {
                Row topRow = sheet.getRow(mergedRegion.getFirstRow());
                if (topRow != null) {
                    Cell topCell = topRow.getCell(mergedRegion.getFirstColumn());
                    return getCellStringValue(topCell);
                }
            }
        }
        return null;
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private String toJsonArray(List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(items.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
