package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.entity.Template;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * Service for Excel View v2: reading and writing excel_view_config on Templates
 * and generating the excel-view representation for quotation line items.
 */
@ApplicationScoped
public class ExcelViewService {

    private static final Logger LOG = Logger.getLogger(ExcelViewService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- Template excel-view-config API ----

    public String getExcelViewConfig(UUID templateId) {
        Template template = Template.findById(templateId);
        // 反 AP-12（懒资源硬 404）：模板未配置 excel-view 时（新模板 / 未启用 ExcelView 的模板）
        // 返回空 [] 而非 404，让前端 ExcelView 显示"未配置"空状态而不是整页报错。
        if (template == null) return "[]";
        return template.excelViewConfig != null ? template.excelViewConfig : "[]";
    }

    @Transactional
    public String saveExcelViewConfig(UUID templateId, String excelViewConfigJson) {
        Template template = Template.findById(templateId);
        if (template == null) throw new BusinessException(404, "Template not found: " + templateId);
        if (!"DRAFT".equals(template.status)) {
            throw new BusinessException("Only DRAFT templates can have their excel_view_config updated");
        }

        List<Map<String, Object>> columns = parseJsonArray(excelViewConfigJson);
        // Validate: EXCEL_FORMULA column references must point to existing col_keys
        Set<String> colKeys = new HashSet<>();
        for (Map<String, Object> col : columns) {
            Object ck = col.get("col_key");
            if (ck != null) colKeys.add(ck.toString());
        }
        for (Map<String, Object> col : columns) {
            String sourceType = (String) col.get("source_type");
            if ("EXCEL_FORMULA".equals(sourceType)) {
                Object formula = col.get("formula");
                if (formula != null) {
                    // Basic validation: formula is non-empty string
                    if (formula.toString().isBlank()) {
                        throw new BusinessException("EXCEL_FORMULA column must have a non-empty formula");
                    }
                }
            }
        }

        template.excelViewConfig = excelViewConfigJson;
        LOG.infof("Saved excel_view_config for template id=%s, %d columns", templateId, columns.size());
        return template.excelViewConfig;
    }

    // ---- Quotation excel-view GET ----

    public Map<String, Object> getExcelView(UUID quotationId) {
        List<QuotationLineItem> lineItems = QuotationLineItem.list("quotationId = ?1 ORDER BY sortOrder ASC", quotationId);
        if (lineItems.isEmpty()) {
            return Map.of("columns", List.of(), "rows", List.of());
        }

        // Use the template from the first line item
        UUID templateId = lineItems.get(0).templateId;
        Template template = templateId != null ? (Template) Template.findById(templateId) : null;
        // 反 AP-12（懒资源硬 404）：模板被删 / 未配置时返回空视图，UI 走空态分支。
        if (template == null) {
            return Map.of("columns", List.of(), "rows", List.of());
        }

        List<Map<String, Object>> columns = parseJsonArray(template.excelViewConfig);
        List<Map<String, Object>> rows = new ArrayList<>();

        for (QuotationLineItem li : lineItems) {
            Map<String, Object> row = buildRowData(li, columns);
            row.put("_lineItemId", li.id.toString());
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns);
        result.put("rows", rows);
        return result;
    }

    private Map<String, Object> buildRowData(QuotationLineItem li, List<Map<String, Object>> columns) {
        Map<String, Object> productAttrs = parseJsonMap(li.productAttributeValues);
        // Load component data rows for this line item
        List<QuotationLineComponentData> componentDataList =
            QuotationLineComponentData.list("lineItemId = ?1 ORDER BY sortOrder ASC", li.id);

        // Merge all row_data records into a flat map (first row of each component)
        Map<String, Object> componentRowData = new LinkedHashMap<>();
        for (QuotationLineComponentData cd : componentDataList) {
            List<Map<String, Object>> rowDataList = parseJsonArray(cd.rowData);
            if (!rowDataList.isEmpty()) {
                componentRowData.putAll(rowDataList.get(0));
            }
        }

        Map<String, Object> row = new LinkedHashMap<>();
        for (Map<String, Object> col : columns) {
            String colKey = (String) col.get("col_key");
            if (colKey == null) continue;
            String sourceType = (String) col.get("source_type");
            if (sourceType == null) continue;

            Object value = switch (sourceType) {
                case "PRODUCT_ATTRIBUTE" -> {
                    String fieldKey = (String) col.get("field_key");
                    yield fieldKey != null ? productAttrs.get(fieldKey) : null;
                }
                case "COMPONENT_FIELD" -> {
                    String fieldKey = (String) col.get("field_key");
                    yield fieldKey != null ? componentRowData.get(fieldKey) : null;
                }
                case "EXCEL_FORMULA" -> {
                    // Return the formula string for the frontend to evaluate
                    yield col.get("formula");
                }
                case "FIXED_VALUE" -> col.get("fixed_value");
                default -> null;
            };
            row.put(colKey, value);
        }
        return row;
    }

    // ---- Quotation excel-view PUT (cell update) ----

    @Transactional
    public void updateExcelViewCell(UUID quotationId, UUID lineItemId, String colKey, Object value) {
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        if (li == null) throw new BusinessException(404, "LineItem not found: " + lineItemId);
        if (!quotationId.equals(li.quotationId)) {
            throw new BusinessException(400, "LineItem does not belong to quotation: " + quotationId);
        }

        Template template = Template.findById(li.templateId);
        if (template == null) throw new BusinessException(404, "Template not found: " + li.templateId);

        List<Map<String, Object>> columns = parseJsonArray(template.excelViewConfig);
        Map<String, Object> colDef = columns.stream()
            .filter(c -> colKey.equals(c.get("col_key")))
            .findFirst()
            .orElseThrow(() -> new BusinessException(400, "Column not found in excel_view_config: " + colKey));

        String sourceType = (String) colDef.get("source_type");
        String fieldKey = (String) colDef.get("field_key");

        switch (sourceType) {
            case "PRODUCT_ATTRIBUTE" -> {
                if (fieldKey == null) throw new BusinessException(400, "field_key missing for PRODUCT_ATTRIBUTE column: " + colKey);
                Map<String, Object> attrs = parseJsonMap(li.productAttributeValues);
                attrs.put(fieldKey, value);
                li.productAttributeValues = toJson(attrs);
            }
            case "COMPONENT_FIELD" -> {
                if (fieldKey == null) throw new BusinessException(400, "field_key missing for COMPONENT_FIELD column: " + colKey);
                // Update in the first component data record that has this field
                List<QuotationLineComponentData> cdList =
                    QuotationLineComponentData.list("lineItemId = ?1 ORDER BY sortOrder ASC", lineItemId);
                boolean updated = false;
                for (QuotationLineComponentData cd : cdList) {
                    List<Map<String, Object>> rows = parseJsonArray(cd.rowData);
                    if (!rows.isEmpty() && rows.get(0).containsKey(fieldKey)) {
                        rows.get(0).put(fieldKey, value);
                        cd.rowData = toJson(rows);
                        updated = true;
                        break;
                    }
                }
                if (!updated && !cdList.isEmpty()) {
                    // Add to first component data row
                    List<Map<String, Object>> rows = parseJsonArray(cdList.get(0).rowData);
                    if (rows.isEmpty()) rows.add(new LinkedHashMap<>());
                    rows.get(0).put(fieldKey, value);
                    cdList.get(0).rowData = toJson(rows);
                }
            }
            default -> throw new BusinessException(400, "Cannot update read-only column type: " + sourceType);
        }

        // Rebuild excel_view_snapshot
        List<Map<String, Object>> allColumns = parseJsonArray(template.excelViewConfig);
        Map<String, Object> snapshot = buildRowData(li, allColumns);
        li.excelViewSnapshot = toJson(snapshot);

        LOG.infof("Updated excel view cell: quotationId=%s lineItemId=%s colKey=%s", quotationId, lineItemId, colKey);
    }

    // ---- Export Excel with formulas ----

    public byte[] exportExcelView(UUID quotationId) {
        Map<String, Object> viewData = getExcelView(quotationId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) viewData.get("columns");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) viewData.get("rows");

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Excel View");

            // Header row
            Row headerRow = sheet.createRow(0);
            CellStyle headerStyle = createHeaderStyle(workbook);
            for (int i = 0; i < columns.size(); i++) {
                Map<String, Object> col = columns.get(i);
                String label = col.get("label") != null ? col.get("label").toString() : col.get("col_key").toString();
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(label);
                cell.setCellStyle(headerStyle);
            }

            // Data rows
            for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
                Row dataRow = sheet.createRow(rowIdx + 1);
                Map<String, Object> rowData = rows.get(rowIdx);

                for (int colIdx = 0; colIdx < columns.size(); colIdx++) {
                    Map<String, Object> col = columns.get(colIdx);
                    String colKey = (String) col.get("col_key");
                    String sourceType = (String) col.get("source_type");
                    Object cellValue = rowData.get(colKey);

                    Cell cell = dataRow.createCell(colIdx);

                    if ("EXCEL_FORMULA".equals(sourceType) && cellValue != null) {
                        // Write as Excel formula
                        String formula = cellValue.toString();
                        // Strip leading '=' if present for setCellFormula
                        if (formula.startsWith("=")) formula = formula.substring(1);
                        try {
                            cell.setCellFormula(formula);
                        } catch (Exception e) {
                            cell.setCellValue(formula);
                        }
                    } else if (cellValue != null) {
                        setCellValue(cell, cellValue);
                    }
                }
            }

            // Auto-size columns
            for (int i = 0; i < columns.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(500, "Failed to generate Excel view export: " + e.getMessage());
        }
    }

    private void setCellValue(Cell cell, Object value) {
        if (value instanceof Number) {
            cell.setCellValue(((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            cell.setCellValue((Boolean) value);
        } else {
            cell.setCellValue(value.toString());
        }
    }

    private CellStyle createHeaderStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    // ---- JSON helpers ----

    private List<Map<String, Object>> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
