package com.cpq.product.service;

import com.cpq.product.dto.ImportResult;
import com.cpq.product.entity.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProductImportService {

    private static final Logger LOG = Logger.getLogger(ProductImportService.class);
    private static final int MAX_ROWS = 5000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Transactional
    public ImportResult importFromExcel(InputStream inputStream) {
        int added = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            int rowCount = 0;

            for (Row row : sheet) {
                // Skip header row (index 0)
                if (row.getRowNum() == 0) continue;

                // Skip completely empty rows
                if (isEmptyRow(row)) continue;

                rowCount++;
                if (rowCount > MAX_ROWS) {
                    errors.add("Row limit exceeded (max " + MAX_ROWS + "), remaining rows skipped");
                    break;
                }

                int rowNum = row.getRowNum() + 1;
                try {
                    String name = getCellString(row, 0);
                    String partNo = getCellString(row, 1);
                    String category = getCellString(row, 2);
                    String specification = getCellString(row, 3);
                    String tagsRaw = getCellString(row, 4);

                    if (name == null || name.isBlank()) {
                        errors.add("Row " + rowNum + ": name is required");
                        failed++;
                        continue;
                    }
                    if (partNo == null || partNo.isBlank()) {
                        errors.add("Row " + rowNum + ": Part No is required");
                        failed++;
                        continue;
                    }
                    if (category == null || category.isBlank()) {
                        errors.add("Row " + rowNum + ": category is required");
                        failed++;
                        continue;
                    }
                    com.cpq.basicdata.entity.ProductCategory pc = resolveOrCreateCategory(category);

                    // Check part_no duplicate
                    long count = Product.count("partNo", partNo);
                    if (count > 0) {
                        skipped++;
                        continue;
                    }

                    List<String> tags = parseTags(tagsRaw);

                    Product product = new Product();
                    product.name = name;
                    product.partNo = partNo;
                    product.category = pc.name;
                    product.categoryId = pc.id;
                    product.specification = specification;
                    product.status = "ACTIVE";
                    product.tags = MAPPER.writeValueAsString(tags);
                    product.persist();
                    added++;

                } catch (Exception e) {
                    errors.add("Row " + rowNum + ": " + e.getMessage());
                    failed++;
                }
            }
        } catch (Exception e) {
            LOG.errorf("Excel import failed: %s", e.getMessage());
            errors.add("Failed to parse Excel file: " + e.getMessage());
            failed++;
        }

        LOG.infof("Excel import complete: added=%d skipped=%d failed=%d", added, skipped, failed);
        return new ImportResult(added, skipped, failed, errors);
    }

    private boolean isEmptyRow(Row row) {
        for (Cell cell : row) {
            if (cell.getCellType() != CellType.BLANK) {
                String val = getCellString(row, cell.getColumnIndex());
                if (val != null && !val.isBlank()) return false;
            }
        }
        return true;
    }

    private String getCellString(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC: return String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try { return cell.getStringCellValue().trim(); }
                catch (Exception e) { return String.valueOf(cell.getNumericCellValue()); }
            default: return null;
        }
    }

    private com.cpq.basicdata.entity.ProductCategory resolveOrCreateCategory(String input) {
        // Try by name first, then by code
        com.cpq.basicdata.entity.ProductCategory pc =
                com.cpq.basicdata.entity.ProductCategory.find("name = ?1 OR code = ?1", input).firstResult();
        if (pc != null) return pc;
        // Auto-create
        pc = new com.cpq.basicdata.entity.ProductCategory();
        pc.code = input.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        pc.name = input;
        pc.persist();
        return pc;
    }

    private List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return new ArrayList<>();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
