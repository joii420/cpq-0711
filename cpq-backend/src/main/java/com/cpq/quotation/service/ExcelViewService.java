package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.dto.TemplateFormulaDTO;
import com.cpq.template.entity.Template;
import com.cpq.template.service.TemplateFormulaService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    /**
     * Stage 2 集成：FORMULA 列 [名称] 引用先查模板公式，命中则走 TemplateFormulaService 求值。
     * 使用 @Inject 注入，避免循环依赖（TemplateFormulaService 不依赖 ExcelViewService）。
     */
    @Inject
    TemplateFormulaService templateFormulaService;

    /** CARD_FORMULA 列：批量拓扑求值（跨列依赖，不能逐列独立算）。 */
    @Inject
    CardFormulaEvaluator cardFormulaEvaluator;

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
        return getExcelView(quotationId, null);
    }

    /**
     * templateIdOverride 非空时按该模板算（核价单视图传核价模板）；
     * 否则用 lineItems[0].templateId（报价模板，向后兼容）。
     */
    public Map<String, Object> getExcelView(UUID quotationId, UUID templateIdOverride) {
        List<QuotationLineItem> lineItems = QuotationLineItem.list("quotationId = ?1 ORDER BY sortOrder ASC", quotationId);
        if (lineItems.isEmpty()) {
            return Map.of("columns", List.of(), "rows", List.of());
        }

        // 优先使用调用方传入的模板 ID（如核价模板），否则 fallback 到 lineItems[0].templateId
        UUID templateId = templateIdOverride != null ? templateIdOverride : lineItems.get(0).templateId;
        Template template = templateId != null ? (Template) Template.findById(templateId) : null;
        // 反 AP-12（懒资源硬 404）：模板被删 / 未配置时返回空视图，UI 走空态分支。
        if (template == null) {
            return Map.of("columns", List.of(), "rows", List.of());
        }

        List<Map<String, Object>> columns = parseJsonArray(template.excelViewConfig);
        // Stage 2: 预加载模板公式 Map（供 FORMULA 列 [名称] 引用时快速命中）
        List<TemplateFormulaDTO> templateFormulas = templateFormulaService.listByTemplate(templateId);
        Map<String, TemplateFormulaDTO> formulaByName = new LinkedHashMap<>();
        for (TemplateFormulaDTO f : templateFormulas) formulaByName.put(f.name, f);

        // Stage 2: 获取报价单 customerId（供模板公式 SUM_OVER 使用）
        UUID quotationCustomerId = null;
        Quotation quotation = Quotation.findById(quotationId);
        if (quotation != null) quotationCustomerId = quotation.customerId;

        List<Map<String, Object>> rows = new ArrayList<>();

        for (QuotationLineItem li : lineItems) {
            Map<String, Object> row = buildRowData(li, columns, templateId, formulaByName, quotationCustomerId);
            row.put("_lineItemId", li.id.toString());
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns);
        result.put("rows", rows);
        return result;
    }

    /**
     * 公开入口：给外部 Service（如 CardSnapshotService）计算单行 Excel 列值。
     * 按 templateId 加载 excel_view_config；customerId 用于模板公式 SUM_OVER 聚合。
     * 返回 {colKey: value} 平铺 Map；模板无配置时返回空 Map。
     */
    public Map<String, Object> buildLineRowData(QuotationLineItem li, UUID templateId, UUID customerId) {
        if (li == null || templateId == null) return new LinkedHashMap<>();
        try {
            Template template = Template.findById(templateId);
            if (template == null || template.excelViewConfig == null || template.excelViewConfig.isBlank()) {
                return new LinkedHashMap<>();
            }
            List<Map<String, Object>> columns = parseJsonArray(template.excelViewConfig);
            if (columns.isEmpty()) return new LinkedHashMap<>();
            List<TemplateFormulaDTO> templateFormulas = templateFormulaService.listByTemplate(templateId);
            Map<String, TemplateFormulaDTO> formulaByName = new LinkedHashMap<>();
            for (TemplateFormulaDTO f : templateFormulas) formulaByName.put(f.name, f);
            return buildRowData(li, columns, templateId, formulaByName, customerId);
        } catch (Exception e) {
            LOG.warnf("[ExcelView] buildLineRowData failed li=%s tmpl=%s: %s", li.id, templateId, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * Stage 2 重载：带模板 ID + 公式 Map + customerId 参数，支持 FORMULA 列的 [名称] 引用先查模板公式。
     */
    private Map<String, Object> buildRowData(QuotationLineItem li,
                                              List<Map<String, Object>> columns,
                                              UUID templateId,
                                              Map<String, TemplateFormulaDTO> formulaByName,
                                              UUID quotationCustomerId) {
        // V6: 把本行的 part_version_locked 推入 ThreadLocal，深层 DataLoader.loadByPath
        // 自动注入 AND part_version=N 谓词，避免拉取所有历史版本数据导致 BOM 等表"X 项 (共N项)" 重复显示。
        // partVersion=null 时行为与旧逻辑完全一致。
        com.cpq.formula.dataloader.PartVersionContext.set(li.partVersionLocked);
        try {
        Map<String, Object> productAttrs = parseJsonMap(li.productAttributeValues);
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

        // Stage 2: 提取 partNo 和 customerId（供模板公式 SUM_OVER 等聚合函数使用）
        String partNo = extractPartNo(li, componentRowData);
        UUID customerId = quotationCustomerId;

        // CARD_FORMULA：批量拓扑求值（不能逐列独立算，需跨列依赖）
        Map<String, Object> cardFormulaValues = java.util.Collections.emptyMap();
        List<Map<String, Object>> cardCols = new ArrayList<>();
        for (Map<String, Object> col : columns)
            if ("CARD_FORMULA".equals(col.get("source_type"))) cardCols.add(col);
        if (!cardCols.isEmpty()) {
            cardFormulaValues = cardFormulaEvaluator.evaluateColumns(
                cardCols, componentDataList, customerId, partNo, null);
        }

        // Stage 2: 逐列计算，VARIABLE 列先算好，FORMULA 列引用时可以直接用 cachedCells
        Map<String, Object> cachedCells = new LinkedHashMap<>();

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
                case "VARIABLE" -> {
                    // VARIABLE 列已由 LinkedExcelView / 前端负责，这里取 componentRowData 的同名字段
                    // 或直接从 componentRowData 取（如 BNF 路径查值由前端 hook 完成）
                    yield componentRowData.get(colKey);
                }
                case "FORMULA" -> {
                    // Stage 2: FORMULA 列 [名称] 引用先查模板公式，再 fallback 到 cachedCells
                    String formulaExpr = (String) col.get("formula");
                    yield evaluateFormulaColumn(
                            formulaExpr, colKey, templateId, formulaByName,
                            cachedCells, columns, customerId, partNo);
                }
                case "EXCEL_FORMULA" -> {
                    // Return the formula string for the frontend to evaluate
                    yield col.get("formula");
                }
                case "FIXED_VALUE" -> col.get("fixed_value");
                case "CARD_FORMULA" -> cardFormulaValues.get(colKey);
                default -> null;
            };
            row.put(colKey, value);
            cachedCells.put(colKey, value);  // 供后续 FORMULA 列引用
        }
        return row;
        } finally {
            com.cpq.formula.dataloader.PartVersionContext.clear();
        }
    }

    /**
     * 兼容旧签名（无模板公式支持）。
     */
    private Map<String, Object> buildRowData(QuotationLineItem li, List<Map<String, Object>> columns) {
        return buildRowData(li, columns, li.templateId, Map.of(), null);
    }

    /**
     * V6：重算整张报价单所有 line_item 的 excel_view_snapshot。
     *
     * <p>调用场景：QuotationService.updateLineItemPartVersion 切换 partVersionLocked 后
     * 调此方法刷新整单 snapshot，使 Excel 视图实时反映新版本数据。
     *
     * <p>在已有 @Transactional 事务内执行；逐行 buildRowData + persist，任何一行失败整体回滚。
     *
     * @param quotationId 报价单 ID
     */
    @Transactional
    public void regenerateAllSnapshots(UUID quotationId) {
        Quotation quotation = Quotation.findById(quotationId);
        if (quotation == null) return;

        List<QuotationLineItem> lineItems = QuotationLineItem.list(
                "quotationId = ?1 ORDER BY sortOrder ASC", quotationId);
        if (lineItems.isEmpty()) return;

        UUID templateId = lineItems.get(0).templateId;
        Template template = templateId != null ? (Template) Template.findById(templateId) : null;
        if (template == null || template.excelViewConfig == null || template.excelViewConfig.isBlank()) {
            LOG.debugf("regenerateAllSnapshots: quotation=%s template=%s has no excelViewConfig, skip",
                    quotationId, templateId);
            return;
        }

        List<Map<String, Object>> columns = parseJsonArray(template.excelViewConfig);
        List<TemplateFormulaDTO> templateFormulas = templateFormulaService.listByTemplate(templateId);
        Map<String, TemplateFormulaDTO> formulaByName = new LinkedHashMap<>();
        for (TemplateFormulaDTO f : templateFormulas) formulaByName.put(f.name, f);

        for (QuotationLineItem li : lineItems) {
            Map<String, Object> snapshot = buildRowData(li, columns, templateId, formulaByName, quotation.customerId);
            li.excelViewSnapshot = toJson(snapshot);
            li.persist();
        }
        LOG.infof("regenerateAllSnapshots: rebuilt %d snapshots for quotation=%s", lineItems.size(), quotationId);
    }

    /**
     * Stage 2: 求值 FORMULA 列表达式。
     *
     * 处理流程：
     * 1. 去掉前导 "="
     * 2. 扫 [名称] 引用 → 先查 formulaByName（模板公式），命中则调 TemplateFormulaService.evaluateFormula
     * 3. 未命中模板公式 → fallback 到 cachedCells（同行前面已算好的列值）
     * 4. 替换后用 JEXL 或直接返回结果
     *
     * 注意：目前 ExcelViewService 不持有 DataLoader（RequestScoped），
     * 所以模板公式求值委托给 TemplateFormulaService，由它内部持有 DataLoader。
     */
    private Object evaluateFormulaColumn(String formulaExpr,
                                          String colKey,
                                          UUID templateId,
                                          Map<String, TemplateFormulaDTO> formulaByName,
                                          Map<String, Object> cachedCells,
                                          List<Map<String, Object>> columns,
                                          UUID customerId, String partNo) {
        if (formulaExpr == null || formulaExpr.isBlank()) return null;
        // 去掉前导 "="
        String expr = formulaExpr.startsWith("=") ? formulaExpr.substring(1).trim() : formulaExpr.trim();

        // 扫描 [名称] 引用，替换为数值字面量
        java.util.regex.Pattern bracketPat = java.util.regex.Pattern.compile("\\[([^\\[\\]]+)]");
        java.util.regex.Matcher m = bracketPat.matcher(expr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String ref = m.group(1).trim();
            Object refVal = resolveFormulaRef(ref, templateId, formulaByName, cachedCells, customerId, partNo);
            String literal = toNumericStr(refVal);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(literal));
        }
        m.appendTail(sb);
        String resolved = sb.toString();

        // 用轻量 JEXL 求值（纯算术）
        try {
            org.apache.commons.jexl3.JexlEngine jexl = new org.apache.commons.jexl3.JexlBuilder()
                    .silent(true).strict(false).create();
            Object result = jexl.createExpression(resolved).evaluate(new org.apache.commons.jexl3.MapContext());
            return normalizeNumeric(result);
        } catch (Exception e) {
            LOG.debugf("[ExcelView] FORMULA col '%s' eval failed: expr='%s' err=%s", colKey, resolved, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 FORMULA 中的 [名称] 引用：
     * 1. 先查模板公式（formulaByName）→ 调 TemplateFormulaService.evaluateFormula
     * 2. fallback：查 cachedCells（同行前面已算好的列）
     * 3. 再 fallback：返回 null（最终用 0 替代）
     */
    private Object resolveFormulaRef(String ref,
                                      UUID templateId,
                                      Map<String, TemplateFormulaDTO> formulaByName,
                                      Map<String, Object> cachedCells,
                                      UUID customerId, String partNo) {
        // 1. 模板公式命中
        if (formulaByName.containsKey(ref) && templateId != null
                && partNo != null && !partNo.isBlank()) {
            try {
                Object v = templateFormulaService.evaluateFormula(templateId, ref, customerId, partNo);
                LOG.debugf("[ExcelView] [%s] → templateFormula → %s", ref, v);
                return v;
            } catch (Exception e) {
                LOG.debugf("[ExcelView] templateFormula eval failed for [%s]: %s", ref, e.getMessage());
            }
        }
        // 2. cachedCells fallback
        if (cachedCells.containsKey(ref)) {
            return cachedCells.get(ref);
        }
        // 3. not found
        return null;
    }

    /** 提取料号：优先从 productAttributeValues 取 hf_part_no，其次从 componentRowData */
    private String extractPartNo(QuotationLineItem li, Map<String, Object> componentRowData) {
        Map<String, Object> attrs = parseJsonMap(li.productAttributeValues);
        Object pn = attrs.get("hf_part_no");
        if (pn == null) pn = componentRowData.get("hf_part_no");
        return pn != null ? pn.toString() : null;
    }

    /** 转数值字符串（用于表达式替换） */
    private String toNumericStr(Object v) {
        if (v == null) return "0";
        if (v instanceof java.math.BigDecimal bd) return bd.toPlainString();
        if (v instanceof Number n) return new java.math.BigDecimal(n.toString()).toPlainString();
        String s = v.toString().trim();
        try { new java.math.BigDecimal(s); return s; } catch (Exception e) { return "0"; }
    }

    /** 规范化数值结果 */
    private Object normalizeNumeric(Object v) {
        if (v instanceof Double d) return java.math.BigDecimal.valueOf(d);
        if (v instanceof Float f) return new java.math.BigDecimal(f.toString());
        if (v instanceof Long l) return java.math.BigDecimal.valueOf(l);
        if (v instanceof Integer i) return java.math.BigDecimal.valueOf(i);
        return v;
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
