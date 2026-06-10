package com.cpq.importexcel.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.customer.entity.Customer;
import com.cpq.importexcel.dto.ConfirmImportRequest;
import com.cpq.importexcel.dto.ImportPreviewDTO;
import com.cpq.importexcel.dto.ImportRecordDTO;
import com.cpq.importexcel.dto.InternalMaterialDTO;
import com.cpq.importexcel.entity.CustomerExcelTemplate;
import com.cpq.importexcel.entity.ImportMappingTemplate;
import com.cpq.importexcel.entity.ImportRecord;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.entity.Template;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.*;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@ApplicationScoped
public class ImportExecutionService {

    private static final Logger LOG = Logger.getLogger(ImportExecutionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    CustomerMaterialMappingService customerMaterialMappingService;

    @Inject
    EntityManager em;

    /** 报价单整份快照 Phase 1 — Excel 导入路径接入 */
    @Inject
    com.cpq.quotation.service.CardSnapshotService cardSnapshotService;

    /** Task 3.1: 列定义统一从 EXCEL 组件解析（import_settings 仍读 excelViewConfig 不变）。 */
    @Inject
    com.cpq.quotation.service.ExcelColumnResolver excelColumnResolver;

    @Transactional
    public ImportRecordDTO executeImport(
            UUID customerId,
            UUID excelTemplateId,
            UUID mappingTemplateId,
            InputStream excelFile,
            String fileName,
            UUID userId) {

        CustomerExcelTemplate excelTemplate = CustomerExcelTemplate.findById(excelTemplateId);
        if (excelTemplate == null) throw new BusinessException(404, "CustomerExcelTemplate not found: " + excelTemplateId);

        ImportMappingTemplate mappingTemplate = ImportMappingTemplate.findById(mappingTemplateId);
        if (mappingTemplate == null) throw new BusinessException(404, "ImportMappingTemplate not found: " + mappingTemplateId);

        Customer customer = Customer.findById(customerId);
        if (customer == null) throw new BusinessException(404, "Customer not found: " + customerId);

        // Save original file
        String relPath = saveFile(excelFile, customerId, fileName);

        // Re-open from saved location for processing (we consumed the stream for saving)
        // Instead: read bytes first, then process
        // Since we already saved, parse again from saved file
        int totalRows = 0;
        int successRows = 0;
        int matchedRows = 0;
        int unmatchedRows = 0;
        List<Map<String, Object>> errors = new ArrayList<>();

        UUID quotationId = null;

        try {
            File savedFile = new File(relPath);
            try (Workbook wb = WorkbookFactory.create(savedFile)) {
                // Convert 1-based user indices to 0-based POI indices
                Sheet sheet = wb.getSheetAt(Math.max(0, excelTemplate.sheetIndex - 1));
                int headerRowIdx = Math.max(0, excelTemplate.headerRowIndex - 1);
                int dataStartIdx = Math.max(0, excelTemplate.dataStartRowIndex - 1);

                // Validate headers
                Row headerRow = sheet.getRow(headerRowIdx);
                if (headerRow == null) throw new BusinessException(400, "Header row not found at row " + excelTemplate.headerRowIndex);

                Map<String, Integer> headerIndex = new HashMap<>();
                for (Cell cell : headerRow) {
                    String val = getCellStringValue(cell);
                    if (val != null && !val.isBlank()) {
                        headerIndex.put(val.trim(), cell.getColumnIndex());
                    }
                }

                // Create quotation
                Quotation quotation = new Quotation();
                quotation.quotationNumber = generateQuotationNumber();
                quotation.customerId = customerId;
                quotation.name = "Excel Import - " + fileName;
                quotation.salesRepId = userId;
                quotation.status = "DRAFT";
                quotation.snapshotCustomerName = customer.name;
                quotation.snapshotCustomerLevel = customer.level;
                quotation.snapshotCustomerRegion = customer.region;
                quotation.snapshotCustomerIndustry = customer.industry;
                quotation.snapshotCustomerAddress = customer.address;
                quotation.expiryDate = LocalDate.now().plusDays(30);
                quotation.persist();
                quotationId = quotation.id;

                // Parse v2 column_mappings: [{ "excel_column": "...", "target_view_column": "A" }, ...]
                List<Map<String, Object>> v2Mappings = parseJsonArray(mappingTemplate.columnMappings);

                // Task 3.1: 列定义从 EXCEL 组件解析，得到 source_type/field_key
                Template tmpl = Template.findById(mappingTemplate.templateId);
                List<Map<String, Object>> viewColumns = excelColumnResolver.getEffectiveColumns(tmpl);

                // Build lookup: col_key -> column definition
                Map<String, Map<String, Object>> viewColByKey = new LinkedHashMap<>();
                for (Map<String, Object> col : viewColumns) {
                    Object ck = col.get("col_key");
                    if (ck != null) viewColByKey.put(ck.toString(), col);
                }

                // Process data rows
                for (int rowNum = dataStartIdx; rowNum <= sheet.getLastRowNum(); rowNum++) {
                    Row row = sheet.getRow(rowNum);
                    if (row == null) continue;

                    totalRows++;
                    try {
                        // Get customer part number
                        String partNoColName = excelTemplate.partNoColumn;
                        Integer partNoColIdx = headerIndex.get(partNoColName);
                        String customerPartNo = null;
                        if (partNoColIdx != null) {
                            customerPartNo = getCellStringValue(row.getCell(partNoColIdx));
                        }

                        if (customerPartNo == null || customerPartNo.isBlank()) {
                            totalRows--; // skip empty rows
                            continue;
                        }

                        // Match part number
                        InternalMaterialDTO material = customerMaterialMappingService.matchPartNo(customerId, customerPartNo);
                        boolean matched = material != null;
                        if (matched) matchedRows++;
                        else unmatchedRows++;

                        // Apply v2 mappings: separate PRODUCT_ATTRIBUTE from COMPONENT_FIELD
                        Map<String, Object> productAttrValues = new LinkedHashMap<>();
                        Map<String, Object> componentFieldValues = new LinkedHashMap<>();

                        for (Map<String, Object> mapping : v2Mappings) {
                            Object excelColObj = mapping.get("excel_column");
                            Object targetColObj = mapping.get("target_view_column");
                            if (excelColObj == null || targetColObj == null) continue;

                            String excelCol = excelColObj.toString();
                            String targetCol = targetColObj.toString();

                            // Read value from Excel
                            Integer colIdx = headerIndex.get(excelCol);
                            if (colIdx == null) continue;
                            String cellVal = getCellStringValue(row.getCell(colIdx));
                            if (cellVal == null) continue;

                            // Look up the view column definition
                            Map<String, Object> colDef = viewColByKey.get(targetCol);
                            if (colDef == null) continue;

                            String sourceType = (String) colDef.get("source_type");
                            String fieldKey = (String) colDef.get("field_key");
                            if (fieldKey == null) fieldKey = targetCol;

                            if ("PRODUCT_ATTRIBUTE".equals(sourceType)) {
                                productAttrValues.put(fieldKey, cellVal);
                            } else if ("COMPONENT_FIELD".equals(sourceType)) {
                                componentFieldValues.put(fieldKey, cellVal);
                            }
                        }

                        // Create line item
                        QuotationLineItem li = new QuotationLineItem();
                        li.quotationId = quotationId;
                        li.templateId = mappingTemplate.templateId;
                        li.productId = mappingTemplate.templateId; // placeholder - no product linked directly
                        li.productAttributeValues = productAttrValues.isEmpty() ? "{}" : toJsonMap(productAttrValues);
                        li.sortOrder = rowNum - dataStartIdx;
                        li.customerPartNo = customerPartNo;
                        li.persist();
                        // 报价单整份快照 Phase 1（降级）
                        try {
                            cardSnapshotService.ensureStructure(quotationId);
                            cardSnapshotService.snapshotLineValues(li);
                        } catch (Exception ignored) { /* 尽力而为 */ }

                        // Include material info + all excel row values in component data row
                        Map<String, Object> rowDataMap = new LinkedHashMap<>();
                        rowDataMap.put("customerPartNo", customerPartNo);
                        if (material != null) {
                            rowDataMap.put("materialNo", material.materialNo);
                            rowDataMap.put("materialName", material.name);
                        }
                        rowDataMap.putAll(componentFieldValues);
                        // Also add all raw header values for reference
                        for (Map.Entry<String, Integer> entry : headerIndex.entrySet()) {
                            Cell cell = row.getCell(entry.getValue());
                            String val = getCellStringValue(cell);
                            if (val != null) rowDataMap.putIfAbsent(entry.getKey(), val);
                        }

                        // Create component data record
                        QuotationLineComponentData cd = new QuotationLineComponentData();
                        cd.lineItemId = li.id;
                        cd.componentId = null;
                        cd.tabName = "Import";
                        cd.rowData = "[" + toJsonMap(rowDataMap) + "]";
                        cd.sortOrder = 0;
                        cd.persist();

                        // Build excel_view_snapshot from view columns
                        Map<String, Object> snapshot = new LinkedHashMap<>();
                        for (Map<String, Object> col : viewColumns) {
                            String colKey = (String) col.get("col_key");
                            String sourceType = (String) col.get("source_type");
                            if (colKey == null || sourceType == null) continue;
                            String fieldKey = (String) col.get("field_key");
                            if (fieldKey == null) fieldKey = colKey;
                            Object val = switch (sourceType) {
                                case "PRODUCT_ATTRIBUTE" -> productAttrValues.get(fieldKey);
                                case "COMPONENT_FIELD" -> componentFieldValues.get(fieldKey);
                                case "EXCEL_FORMULA" -> col.get("formula");
                                case "FIXED_VALUE" -> col.get("fixed_value");
                                default -> null;
                            };
                            snapshot.put(colKey, val);
                        }
                        li.excelViewSnapshot = toJsonMap(snapshot);

                        successRows++;
                    } catch (Exception rowEx) {
                        Map<String, Object> err = new HashMap<>();
                        err.put("row", rowNum + 1);
                        err.put("error", rowEx.getMessage());
                        errors.add(err);
                    }
                }
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(500, "Import processing failed: " + e.getMessage());
        }

        // Determine status
        String importStatus;
        if (successRows == 0) {
            importStatus = "FAILED";
        } else if (successRows < totalRows || !errors.isEmpty()) {
            importStatus = "PARTIAL";
        } else {
            importStatus = "SUCCESS";
        }

        // Save import record
        ImportRecord record = new ImportRecord();
        record.quotationId = quotationId;
        record.customerId = customerId;
        record.excelTemplateId = excelTemplateId;
        record.mappingTemplateId = mappingTemplateId;
        record.mappingSnapshot = mappingTemplate.columnMappings;
        record.originalFileName = fileName;
        record.originalFilePath = relPath;
        record.totalRows = totalRows;
        record.successRows = successRows;
        record.matchedRows = matchedRows;
        record.unmatchedRows = unmatchedRows;
        record.importStatus = importStatus;
        record.errorDetail = errors.isEmpty() ? "[]" : toJson(errors);
        record.importedBy = userId;
        record.persist();

        LOG.infof("Import complete: file=%s total=%d success=%d matched=%d status=%s",
                fileName, totalRows, successRows, matchedRows, importStatus);

        ImportRecordDTO dto = ImportRecordDTO.from(record);
        dto.customerName = customer.name;
        return dto;
    }

    private String saveFile(InputStream inputStream, UUID customerId, String fileName) {
        try {
            String month = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            String dirPath = "data/imports/" + customerId + "/" + month;
            File dir = new File(dirPath);
            if (!dir.exists()) dir.mkdirs();

            String uniqueName = UUID.randomUUID() + "_" + fileName;
            File file = new File(dir, uniqueName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                inputStream.transferTo(fos);
            }
            return file.getAbsolutePath();
        } catch (Exception e) {
            throw new BusinessException(500, "Failed to save uploaded file: " + e.getMessage());
        }
    }

    private String generateQuotationNumber() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Long seq = (Long) em.createNativeQuery("SELECT nextval('quotation_number_seq')").getSingleResult();
        return String.format("QT-%s-%04d", dateStr, seq);
    }

    private List<Map<String, Object>> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String toJsonMap(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield String.valueOf(cell.getNumericCellValue()); }
                catch (Exception e) { yield cell.getStringCellValue(); }
            }
            default -> null;
        };
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // ===================================================================
    // v3: preview + confirm flow — works directly from Template.excel_view_config
    // ===================================================================

    /**
     * Parse an Excel file using the template's excel_view_config import_settings and return
     * a preview (no DB writes).
     */
    public ImportPreviewDTO previewImport(UUID customerId, UUID templateId, InputStream excelFile, String fileName) {
        Template template = Template.findById(templateId);
        if (template == null) throw new BusinessException(404, "Template not found: " + templateId);
        if (template.excelViewConfig == null || template.excelViewConfig.isBlank()) {
            throw new BusinessException(400, "Template has no excel_view_config");
        }

        Map<String, Object> config = parseJsonObject(template.excelViewConfig);
        Map<String, Object> importSettings = getMap(config, "import_settings");
        // Task 3.1: 列定义从 EXCEL 组件解析；import_settings 仍读 excelViewConfig（不变）
        List<Map<String, Object>> viewColumns = excelColumnResolver.getEffectiveColumns(template);

        int sheetIndex = getInt(importSettings, "sheet_index", 1);
        int headerRowIndex = getInt(importSettings, "header_row_index", 2);
        int dataStartRowIndex = getInt(importSettings, "data_start_row_index", 3);
        String partNoColKey = getString(importSettings, "part_no_column_key", null);

        // Save file to disk so we can re-open it if needed
        String savedPath = saveFile(excelFile, customerId, fileName);

        ImportPreviewDTO preview = new ImportPreviewDTO();
        preview.rows = new ArrayList<>();
        preview.matchResults = new ArrayList<>();
        preview.errors = new ArrayList<>();

        try {
            File savedFile = new File(savedPath);
            try (Workbook wb = WorkbookFactory.create(savedFile)) {
                Sheet sheet = wb.getSheetAt(Math.max(0, sheetIndex - 1));
                int headerRowIdx = Math.max(0, headerRowIndex - 1);
                int dataStartIdx = Math.max(0, dataStartRowIndex - 1);

                Row headerRow = sheet.getRow(headerRowIdx);
                if (headerRow == null) throw new BusinessException(400, "Header row not found at row " + headerRowIndex);

                // Build header title -> column index
                Map<String, Integer> headerIndex = new HashMap<>();
                for (Cell cell : headerRow) {
                    String val = getCellStringValue(cell);
                    if (val != null && !val.isBlank()) {
                        headerIndex.put(val.trim(), cell.getColumnIndex());
                    }
                }

                // Build col_key -> column def map (only PRODUCT_ATTRIBUTE + COMPONENT_FIELD)
                Map<String, Map<String, Object>> colByKey = new LinkedHashMap<>();
                for (Map<String, Object> col : viewColumns) {
                    Object ck = col.get("col_key");
                    if (ck != null) colByKey.put(ck.toString(), col);
                }

                // Resolve part-no column title from col_key
                String partNoTitle = null;
                if (partNoColKey != null && colByKey.containsKey(partNoColKey)) {
                    Map<String, Object> partNoCol = colByKey.get(partNoColKey);
                    partNoTitle = (String) partNoCol.get("label");
                }

                int matchedRows = 0;
                int unmatchedRows = 0;

                for (int rowNum = dataStartIdx; rowNum <= sheet.getLastRowNum(); rowNum++) {
                    Row row = sheet.getRow(rowNum);
                    if (row == null) continue;

                    try {
                        // Check if row is entirely empty
                        String partNo = null;
                        if (partNoTitle != null) {
                            Integer partNoColIdx = headerIndex.get(partNoTitle);
                            if (partNoColIdx != null) {
                                partNo = getCellStringValue(row.getCell(partNoColIdx));
                            }
                        }

                        // Skip blank rows (no part number and row is effectively empty)
                        boolean rowHasData = false;
                        for (Cell cell : row) {
                            String v = getCellStringValue(cell);
                            if (v != null && !v.isBlank()) { rowHasData = true; break; }
                        }
                        if (!rowHasData) continue;

                        // Build row preview: col_key -> value (for importable columns)
                        Map<String, Object> rowData = new LinkedHashMap<>();
                        for (Map<String, Object> col : viewColumns) {
                            String colKey = (String) col.get("col_key");
                            String sourceType = (String) col.get("source_type");
                            String label = (String) col.get("label");
                            if (colKey == null || label == null) continue;
                            if (!"PRODUCT_ATTRIBUTE".equals(sourceType) && !"COMPONENT_FIELD".equals(sourceType)) {
                                // Include FIXED_VALUE and EXCEL_FORMULA as literals
                                if ("FIXED_VALUE".equals(sourceType)) rowData.put(colKey, col.get("fixed_value"));
                                else if ("EXCEL_FORMULA".equals(sourceType)) rowData.put(colKey, col.get("formula"));
                                continue;
                            }
                            Integer colIdx = headerIndex.get(label);
                            if (colIdx != null) {
                                String val = getCellStringValue(row.getCell(colIdx));
                                rowData.put(colKey, val);
                            }
                        }
                        preview.rows.add(rowData);

                        // Match part number
                        Map<String, Object> matchResult = new LinkedHashMap<>();
                        matchResult.put("rowIndex", preview.rows.size() - 1);
                        matchResult.put("customerPartNo", partNo);
                        if (partNo != null && !partNo.isBlank()) {
                            InternalMaterialDTO material = customerMaterialMappingService.matchPartNo(customerId, partNo);
                            matchResult.put("matched", material != null);
                            if (material != null) {
                                matchResult.put("materialNo", material.materialNo);
                                matchResult.put("materialName", material.name);
                                matchedRows++;
                            } else {
                                unmatchedRows++;
                            }
                        } else {
                            matchResult.put("matched", false);
                            unmatchedRows++;
                        }
                        preview.matchResults.add(matchResult);

                    } catch (Exception rowEx) {
                        Map<String, String> err = new HashMap<>();
                        err.put("row", String.valueOf(rowNum + 1));
                        err.put("error", rowEx.getMessage());
                        preview.errors.add(err);
                    }
                }

                preview.totalRows = preview.rows.size();
                preview.matchedRows = matchedRows;
                preview.unmatchedRows = unmatchedRows;
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(500, "Preview failed: " + e.getMessage());
        }

        // Store saved path in a temp field so confirm can reuse it
        // (we embed it in the preview result via a synthetic key)
        // The caller (resource) will include savedPath in ConfirmImportRequest
        // We attach it here via a special entry in errors list... cleaner: add to preview DTO
        // We'll just return the rows - the resource will set it on the ConfirmImportRequest
        // Store in a side-channel via a system-level entry (see resource for usage)
        preview.errors.add(buildSavedPathHint(savedPath));
        return preview;
    }

    private Map<String, String> buildSavedPathHint(String savedPath) {
        Map<String, String> hint = new HashMap<>();
        hint.put("__savedPath__", savedPath);
        return hint;
    }

    /**
     * Confirm import: create quotation + line items + import record from pre-parsed preview data.
     */
    @Transactional
    public ImportRecordDTO confirmImport(ConfirmImportRequest request, UUID userId) {
        Template template = Template.findById(request.templateId);
        if (template == null) throw new BusinessException(404, "Template not found: " + request.templateId);

        Customer customer = Customer.findById(request.customerId);
        if (customer == null) throw new BusinessException(404, "Customer not found: " + request.customerId);

        Map<String, Object> config = parseJsonObject(template.excelViewConfig != null ? template.excelViewConfig : "{}");
        // Task 3.1: 列定义从 EXCEL 组件解析；import_settings 仍读 excelViewConfig（不变）
        List<Map<String, Object>> viewColumns = excelColumnResolver.getEffectiveColumns(template);
        Map<String, Object> importSettings = getMap(config, "import_settings");
        String partNoColKey = getString(importSettings, "part_no_column_key", null);

        // Build lookup: col_key -> col def
        Map<String, Map<String, Object>> colByKey = new LinkedHashMap<>();
        for (Map<String, Object> col : viewColumns) {
            Object ck = col.get("col_key");
            if (ck != null) colByKey.put(ck.toString(), col);
        }

        // Create DRAFT quotation
        Quotation quotation = new Quotation();
        quotation.quotationNumber = generateQuotationNumber();
        quotation.customerId = request.customerId;
        quotation.name = "Excel Import - " + (request.fileName != null ? request.fileName : "");
        quotation.salesRepId = userId;
        quotation.status = "DRAFT";
        quotation.snapshotCustomerName = customer.name;
        quotation.snapshotCustomerLevel = customer.level;
        quotation.snapshotCustomerRegion = customer.region;
        quotation.snapshotCustomerIndustry = customer.industry;
        quotation.snapshotCustomerAddress = customer.address;
        quotation.expiryDate = java.time.LocalDate.now().plusDays(30);
        quotation.persist();

        List<Map<String, Object>> rows = request.rows != null ? request.rows : List.of();
        List<Map<String, Object>> matchResults = request.matchResults != null ? request.matchResults : List.of();

        // Build match lookup by rowIndex
        Map<Integer, Map<String, Object>> matchByIndex = new HashMap<>();
        for (Map<String, Object> mr : matchResults) {
            Object ri = mr.get("rowIndex");
            if (ri != null) matchByIndex.put(((Number) ri).intValue(), mr);
        }

        int successRows = 0;
        int matchedRows = 0;
        int unmatchedRows = 0;

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> rowData = rows.get(i);

            Map<String, Object> productAttrValues = new LinkedHashMap<>();
            Map<String, Object> componentFieldValues = new LinkedHashMap<>();

            for (Map.Entry<String, Object> entry : rowData.entrySet()) {
                String colKey = entry.getKey();
                Object val = entry.getValue();
                if (val == null) continue;
                Map<String, Object> colDef = colByKey.get(colKey);
                if (colDef == null) continue;
                String sourceType = (String) colDef.get("source_type");
                String fieldKey = (String) colDef.get("field_key");
                if (fieldKey == null) fieldKey = colKey;
                if ("PRODUCT_ATTRIBUTE".equals(sourceType)) {
                    productAttrValues.put(fieldKey, val);
                } else if ("COMPONENT_FIELD".equals(sourceType)) {
                    componentFieldValues.put(fieldKey, val);
                }
            }

            // Resolve customer part no
            String customerPartNo = null;
            if (partNoColKey != null) {
                Object pnv = rowData.get(partNoColKey);
                if (pnv != null) customerPartNo = pnv.toString();
            }

            // Check match status
            Map<String, Object> mr = matchByIndex.get(i);
            boolean matched = mr != null && Boolean.TRUE.equals(mr.get("matched"));
            if (matched) matchedRows++;
            else unmatchedRows++;

            // Create line item
            QuotationLineItem li = new QuotationLineItem();
            li.quotationId = quotation.id;
            li.templateId = request.templateId;
            li.productId = request.templateId; // placeholder
            li.productAttributeValues = productAttrValues.isEmpty() ? "{}" : toJsonMap(productAttrValues);
            li.sortOrder = i;
            li.customerPartNo = customerPartNo;
            li.persist();
            // 报价单整份快照 Phase 1（降级）
            try {
                cardSnapshotService.ensureStructure(quotation.id);
                cardSnapshotService.snapshotLineValues(li);
            } catch (Exception ignored) { /* 尽力而为 */ }

            // Build component data row
            Map<String, Object> cdRowData = new LinkedHashMap<>();
            if (customerPartNo != null) cdRowData.put("customerPartNo", customerPartNo);
            if (mr != null && matched) {
                if (mr.get("materialNo") != null) cdRowData.put("materialNo", mr.get("materialNo"));
                if (mr.get("materialName") != null) cdRowData.put("materialName", mr.get("materialName"));
            }
            cdRowData.putAll(componentFieldValues);

            QuotationLineComponentData cd = new QuotationLineComponentData();
            cd.lineItemId = li.id;
            cd.componentId = null;
            cd.tabName = "Import";
            cd.rowData = "[" + toJsonMap(cdRowData) + "]";
            cd.sortOrder = 0;
            cd.persist();

            // Build excel_view_snapshot
            Map<String, Object> snapshot = new LinkedHashMap<>();
            for (Map<String, Object> col : viewColumns) {
                String colKey = (String) col.get("col_key");
                String sourceType = (String) col.get("source_type");
                if (colKey == null || sourceType == null) continue;
                String fieldKey = (String) col.get("field_key");
                if (fieldKey == null) fieldKey = colKey;
                Object val = switch (sourceType) {
                    case "PRODUCT_ATTRIBUTE" -> productAttrValues.get(fieldKey);
                    case "COMPONENT_FIELD" -> componentFieldValues.get(fieldKey);
                    case "EXCEL_FORMULA" -> col.get("formula");
                    case "FIXED_VALUE" -> col.get("fixed_value");
                    default -> null;
                };
                snapshot.put(colKey, val);
            }
            li.excelViewSnapshot = toJsonMap(snapshot);

            successRows++;
        }

        // Determine status
        String importStatus;
        if (successRows == 0) importStatus = "FAILED";
        else if (matchedRows < rows.size()) importStatus = "PARTIAL";
        else importStatus = "SUCCESS";

        // Save import record
        ImportRecord record = new ImportRecord();
        record.quotationId = quotation.id;
        record.customerId = request.customerId;
        record.templateId = request.templateId;
        record.configSnapshot = template.excelViewConfig;
        record.originalFileName = request.fileName != null ? request.fileName : "";
        record.originalFilePath = request.savedFilePath != null ? request.savedFilePath : "";
        record.totalRows = rows.size();
        record.successRows = successRows;
        record.matchedRows = matchedRows;
        record.unmatchedRows = unmatchedRows;
        record.importStatus = importStatus;
        record.errorDetail = "[]";
        record.importedBy = userId;
        record.persist();

        LOG.infof("ImportV3 confirmed: template=%s total=%d success=%d matched=%d status=%s",
                request.templateId, rows.size(), successRows, matchedRows, importStatus);

        ImportRecordDTO dto = ImportRecordDTO.from(record);
        dto.customerName = customer.name;
        dto.templateName = template.name;
        dto.quotationId = quotation.id;
        return dto;
    }

    // ===================================================================
    // helpers
    // ===================================================================

    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Map) return (Map<String, Object>) v;
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List) return (List<Map<String, Object>>) v;
        return new ArrayList<>();
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return defaultValue;
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        if (v instanceof String) return (String) v;
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private String toJson(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{");
            Map<String, Object> map = list.get(i);
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(entry.getKey()).append("\":\"").append(escapeJson(String.valueOf(entry.getValue()))).append("\"");
                first = false;
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }
}
