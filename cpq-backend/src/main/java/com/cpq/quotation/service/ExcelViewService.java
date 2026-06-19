package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.entity.Component;
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

    /** P2-B 核价 Excel 树：算根料号 BOM 闭包 spine（按节点出行）。 */
    @Inject
    com.cpq.component.service.BomClosureService bomClosureService;

    /** TAB_JOIN_FORMULA 列：跨页签连表聚合求值。 */
    @Inject
    com.cpq.quotation.service.tabjoin.TabJoinPlanEvaluator tabJoinPlanEvaluator;

    /** TAB_JOIN 有效行重算：从持久化 componentData 现算（含列小计 + SUBTOTAL 公式总计）。 */
    @Inject
    FormulaCalculator formulaCalculator;

    // ---- Task 3.1: Excel column resolver (config 归属迁移到 EXCEL 组件) ----

    /** 委托给 {@link ExcelColumnResolver}：所有"列定义读取"站点统一走 getEffectiveColumns。 */
    @Inject
    ExcelColumnResolver excelColumnResolver;

    /** 统一列定义解析入口（委托 resolver）。 */
    public List<Map<String, Object>> getEffectiveColumns(Template t) {
        return excelColumnResolver.getEffectiveColumns(t);
    }

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

        // Task 3.1: 先持久化（resolver 解析 EXCEL 组件需要读到最新 config），再用有效列做校验。
        template.excelViewConfig = excelViewConfigJson;
        template.persist();

        // 校验对象：新形状 {excel_component_id, column_overrides, import_settings} 解析为合并列；
        // 旧裸数组形状原样解析。两者都走 getEffectiveColumns 统一口径。
        List<Map<String, Object>> columns = getEffectiveColumns(template);

        // Validate: EXCEL_FORMULA columns must have a non-empty formula
        for (Map<String, Object> col : columns) {
            String sourceType = (String) col.get("source_type");
            if ("EXCEL_FORMULA".equals(sourceType)) {
                Object formula = col.get("formula");
                if (formula != null && formula.toString().isBlank()) {
                    throw new BusinessException("EXCEL_FORMULA column must have a non-empty formula");
                }
            }
        }

        validateTabJoinConfig(columns);

        LOG.infof("Saved excel_view_config for template id=%s, %d effective columns", templateId, columns.size());
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

        List<Map<String, Object>> columns = getEffectiveColumns(template);
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
     * 试算：用传入 columns（临时配置，不读模板/不落库）按该报价单逐行算，返回 {columns, rows}。
     * CARD_FORMULA 分支由 buildRowData 内部 cardFormulaEvaluator 处理，求值异常被吞为 null，不会 500。
     */
    public Map<String, Object> dryRun(UUID quotationId, List<Map<String, Object>> columns, UUID templateId) {
        List<QuotationLineItem> lineItems = QuotationLineItem.list(
                "quotationId = ?1 ORDER BY sortOrder ASC", quotationId);
        if (lineItems.isEmpty() || columns == null || columns.isEmpty()) {
            return Map.of("columns", columns == null ? List.of() : columns, "rows", List.of());
        }

        Quotation quotation = Quotation.findById(quotationId);
        UUID customerId = quotation != null ? quotation.customerId : null;

        List<TemplateFormulaDTO> tfs = templateId != null
                ? templateFormulaService.listByTemplate(templateId)
                : List.of();
        Map<String, TemplateFormulaDTO> byName = new LinkedHashMap<>();
        for (TemplateFormulaDTO t : tfs) byName.put(t.name, t);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (QuotationLineItem li : lineItems) {
            Map<String, Object> row = buildRowData(li, columns, templateId, byName, customerId);
            row.put("_lineItemId", li.id.toString());
            rows.add(row);
        }
        return Map.of("columns", columns, "rows", rows);
    }

    /**
     * 公开入口：给外部 Service（如 CardSnapshotService）计算单行 Excel 列值。
     * 按 templateId 加载 excel_view_config；customerId 用于模板公式 SUM_OVER 聚合。
     * 返回 {colKey: value} 平铺 Map；模板无配置时返回空 Map。
     * （cardValues 缺省→旧路径）
     */
    public Map<String, Object> buildLineRowData(QuotationLineItem li, UUID templateId, UUID customerId) {
        return buildLineRowData(li, templateId, customerId, (String) null);
    }

    /**
     * 新重载：传同侧卡片值快照 JSON → 解析有效行 → CARD_FORMULA 用它取数；空/失败→降级旧路径。
     */
    public Map<String, Object> buildLineRowData(QuotationLineItem li, UUID templateId,
                                                UUID customerId, String cardValuesJson) {
        if (li == null || templateId == null) return new LinkedHashMap<>();
        try {
            Template template = Template.findById(templateId);
            if (template == null || template.excelViewConfig == null || template.excelViewConfig.isBlank()) {
                return new LinkedHashMap<>();
            }
            List<Map<String, Object>> columns = getEffectiveColumns(template);
            if (columns.isEmpty()) return new LinkedHashMap<>();
            List<TemplateFormulaDTO> templateFormulas = templateFormulaService.listByTemplate(templateId);
            Map<String, TemplateFormulaDTO> formulaByName = new LinkedHashMap<>();
            for (TemplateFormulaDTO f : templateFormulas) formulaByName.put(f.name, f);

            Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows> eff =
                parseEffectiveRows(cardValuesJson, templateId);
            return buildRowData(li, columns, templateId, formulaByName, customerId, eff);
        } catch (Exception e) {
            LOG.warnf("[ExcelView] buildLineRowData failed li=%s tmpl=%s: %s", li.id, templateId, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * P2-B 核价 Excel 树：按根料号 BOM 闭包 spine 逐节点出行（每行注入 料号/父料号/版本 + 树元数据），
     * 各列用「过滤到本节点 {@code __nodeId}」的有效行求值（CARD_FORMULA 按节点聚合；空节点列 → null）。
     *
     * <p>仅核价侧调用（{@code cardValuesJson} = costingCardValues，其 baseRows/resolvedRows 含 spine {@code __nodeId}）。
     * 返回 N 行（= spine occurrence 数）；失败返回空列表（调用方降级，不抛）。
     */
    public List<Map<String, Object>> buildLineTreeRows(QuotationLineItem li, UUID templateId,
                                                       UUID customerId, String cardValuesJson) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (li == null || templateId == null) return rows;
        try {
            Template template = Template.findById(templateId);
            if (template == null || template.excelViewConfig == null || template.excelViewConfig.isBlank())
                return rows;
            List<Map<String, Object>> columns = getEffectiveColumns(template);
            if (columns.isEmpty()) return rows;
            List<TemplateFormulaDTO> templateFormulas = templateFormulaService.listByTemplate(templateId);
            Map<String, TemplateFormulaDTO> formulaByName = new LinkedHashMap<>();
            for (TemplateFormulaDTO f : templateFormulas) formulaByName.put(f.name, f);

            Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows> eff =
                parseEffectiveRows(cardValuesJson, templateId);
            com.cpq.component.dto.BomClosureResult closure =
                bomClosureService.compute(li.productPartNoSnapshot, java.util.Map.of());

            for (com.cpq.component.dto.BomClosureResult.SpineNode node : closure.spine) {
                Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows> effN =
                    com.cpq.quotation.service.card.CardEffectiveRows.filterByNodeId(eff, node.nodeId);
                Map<String, Object> row = buildRowData(li, columns, templateId, formulaByName, customerId, effN);
                row.put("__hfPartNo", node.hfPartNo);
                row.put("__parentNo", node.parentNo);
                row.put("__bomVersion", node.bomVersion);
                row.put("__nodeId", node.nodeId == null ? "" : node.nodeId);
                row.put("__parentId", node.parentId);
                row.put("__lvl", node.lvl);
                row.put("_lineItemId", li.id != null ? li.id.toString() : null);
                rows.add(row);
            }
        } catch (Exception e) {
            LOG.warnf("[ExcelView] buildLineTreeRows failed li=%s tmpl=%s: %s",
                li != null ? li.id : null, templateId, e.getMessage());
        }
        return rows;
    }

    /**
     * 解析卡片值快照 → 有效行 Map；空/异常 → null（降级旧路径）。
     */
    private Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows>
            parseEffectiveRows(String cardValuesJson, UUID templateId) {
        if (cardValuesJson == null || cardValuesJson.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode cardValues = MAPPER.readTree(cardValuesJson);
            Template t = Template.findById(templateId);
            com.fasterxml.jackson.databind.JsonNode componentsSnapshot =
                (t != null && t.componentsSnapshot != null)
                    ? MAPPER.readTree(t.componentsSnapshot) : null;
            java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> fieldsByCid = new java.util.HashMap<>();
            if (componentsSnapshot != null && componentsSnapshot.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode c : componentsSnapshot) {
                    String cid = c.path("componentId").asText("");
                    if (!cid.isBlank()) fieldsByCid.put(cid, c.path("fields"));
                }
            }
            return com.cpq.quotation.service.card.CardEffectiveRows.parse(
                cardValues, componentsSnapshot, (cid) -> null, fieldsByCid::get);
        } catch (Exception e) {
            LOG.debugf("[ExcelView] parseEffectiveRows failed tmpl=%s: %s", templateId, e.getMessage());
            return null;
        }
    }

    /**
     * Stage 2 重载：带模板 ID + 公式 Map + customerId 参数，支持 FORMULA 列的 [名称] 引用先查模板公式。
     * effectiveRows 非空时 CARD_FORMULA 列走 CardDataProvider.fromEffectiveRows 精确命中；
     * 为空时降级回旧路径（从持久化 componentData 构造 provider），保持向后兼容。
     */
    private Map<String, Object> buildRowData(QuotationLineItem li,
                                              List<Map<String, Object>> columns,
                                              UUID templateId,
                                              Map<String, TemplateFormulaDTO> formulaByName,
                                              UUID quotationCustomerId) {
        return buildRowData(li, columns, templateId, formulaByName, quotationCustomerId, null);
    }

    /**
     * 五参+effectiveRows 重载：CARD_FORMULA 有效行精确取数 vs 降级旧路径。
     */
    private Map<String, Object> buildRowData(QuotationLineItem li,
                                              List<Map<String, Object>> columns,
                                              UUID templateId,
                                              Map<String, TemplateFormulaDTO> formulaByName,
                                              UUID quotationCustomerId,
                                              Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows> effectiveRows) {
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
        // effectiveRows 非空 → 用同侧卡片有效行精确命中；null → 降级旧路径（持久化 componentData）
        Map<String, Object> cardFormulaValues = java.util.Collections.emptyMap();
        List<Map<String, Object>> cardCols = new ArrayList<>();
        for (Map<String, Object> col : columns)
            if ("CARD_FORMULA".equals(col.get("source_type"))) cardCols.add(col);
        if (!cardCols.isEmpty()) {
            if (effectiveRows != null) {
                com.cpq.quotation.service.card.CardDataProvider provider =
                    com.cpq.quotation.service.card.CardDataProvider.fromEffectiveRows(effectiveRows);
                cardFormulaValues = cardFormulaEvaluator.evaluateColumns(
                    cardCols, provider, customerId, partNo, null, componentRowData);
            } else {
                cardFormulaValues = cardFormulaEvaluator.evaluateColumns(
                    cardCols, componentDataList, customerId, partNo, null, componentRowData);
            }
        }

        // TAB_JOIN_FORMULA：构造 provider。
        // effectiveRows!=null（预览/试算）→ 用传入有效行（原行为）。
        // effectiveRows==null（报价单渲染）→ 仅当存在 TAB_JOIN 列时，从持久化 componentData 现算有效行
        //   （含列小计 + SUBTOTAL 公式），以裸 componentId 键命中 Excel 列 tabKey；修复明细列取数 0 与小计引用 0。
        //   无 TAB_JOIN 列时给空 provider，避免对每行做无谓的 Component.findById（性能守卫）。
        boolean hasTabJoin = columns.stream()
            .anyMatch(c -> "TAB_JOIN_FORMULA".equals(c.get("source_type")));
        com.cpq.quotation.service.card.CardDataProvider tabJoinProvider =
            (effectiveRows != null)
                ? com.cpq.quotation.service.card.CardDataProvider.fromEffectiveRows(effectiveRows)
                : com.cpq.quotation.service.card.CardDataProvider.fromEffectiveRows(
                      hasTabJoin ? buildTabJoinEffectiveRows(componentDataList) : java.util.Map.of());

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
                case "TAB_JOIN_FORMULA" -> {
                    try { yield tabJoinPlanEvaluator.evaluateColumn(col, tabJoinProvider); }
                    catch (Exception e) {
                        LOG.warnf("[ExcelView] TAB_JOIN_FORMULA col '%s' eval failed: %s", colKey, e.getMessage());
                        yield null;
                    }
                }
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
     * 读时：从持久化 componentData 现算 TAB_JOIN 用的有效行（含列小计 + SUBTOTAL 公式总计）。
     * 加载各页签 Component 元数据（code/name/type/formulas）后委托纯计算器
     * {@link com.cpq.quotation.service.card.ComponentDataEffectiveRows}。
     */
    private Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows>
            buildTabJoinEffectiveRows(java.util.List<com.cpq.quotation.entity.QuotationLineComponentData> cdList) {
        java.util.Map<java.util.UUID, com.cpq.quotation.service.card.ComponentDataEffectiveRows.Meta> metaById =
            new java.util.HashMap<>();
        for (com.cpq.quotation.entity.QuotationLineComponentData cd : cdList) {
            if (cd.componentId == null || metaById.containsKey(cd.componentId)) continue;
            com.cpq.component.entity.Component c =
                com.cpq.component.entity.Component.findById(cd.componentId);
            if (c == null) continue;
            com.fasterxml.jackson.databind.JsonNode formulas = null;
            try {
                if (c.formulas != null && !c.formulas.isBlank()) {
                    formulas = MAPPER.readTree(c.formulas);
                }
            } catch (Exception ignore) { /* 公式坏 → 当无公式，subtotal 退回持久化值 */ }
            metaById.put(cd.componentId,
                new com.cpq.quotation.service.card.ComponentDataEffectiveRows.Meta(
                    c.code, c.name, c.componentType, formulas));
        }
        return com.cpq.quotation.service.card.ComponentDataEffectiveRows.compute(
            cdList, metaById, formulaCalculator);
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

        List<Map<String, Object>> columns = getEffectiveColumns(template);
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

        List<Map<String, Object>> columns = getEffectiveColumns(template);
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
        List<Map<String, Object>> allColumns = getEffectiveColumns(template);
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

    // ---- TAB_JOIN_FORMULA builder support endpoints ----

    /**
     * 试算：给样本 lineItem + 列配置（TAB_JOIN_FORMULA col Map），返回单值。
     * 走持久化 componentData 路径（cardValuesJson 可空，忽略）。
     * 返回 {"value": BigDecimal|null, "errors": []}。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> dryRunTabFormula(UUID lineItemId, Map<String, Object> column, String cardValuesJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            QuotationLineItem li = QuotationLineItem.findById(lineItemId);
            if (li == null) {
                out.put("value", null);
                out.put("errors", List.of("样本卡片不存在: " + lineItemId));
                return out;
            }
            // 优先尝试 cardValuesJson 解析路径；失败或为空时降级持久化路径
            com.cpq.quotation.service.card.CardDataProvider provider;
            Map<String, com.cpq.quotation.service.card.CardEffectiveRows.TabRows> eff =
                parseEffectiveRows(cardValuesJson, li.templateId);
            if (eff != null && !eff.isEmpty()) {
                provider = com.cpq.quotation.service.card.CardDataProvider.fromEffectiveRows(eff);
            } else {
                List<QuotationLineComponentData> cdList =
                    QuotationLineComponentData.list("lineItemId = ?1 ORDER BY sortOrder ASC", li.id);
                provider = new com.cpq.quotation.service.card.CardDataProvider(cdList);
            }
            java.math.BigDecimal v = tabJoinPlanEvaluator.evaluateColumn(column, provider);
            out.put("value", v);
            out.put("errors", List.of());
        } catch (Exception e) {
            out.put("value", null);
            out.put("errors", List.of(e.getMessage() == null ? "求值异常" : e.getMessage()));
        }
        return out;
    }

    /**
     * 模板页签定义：从 componentsSnapshot 解析出构建器所需的页签元信息列表。
     * 每个条目：alias(=tabName 或 componentName), tabKey(=componentId:sortOrder),
     * rowKeyFields([...]), detailFields([字段名...]), subtotalCols([is_subtotal 字段名...])。
     * rowKeyFields 从 Component.rowKeyFields JSONB 字段取，componentsSnapshot 不含此字段。
     */
    public List<Map<String, Object>> tabDefsOfTemplate(UUID templateId) {
        Template t = Template.findById(templateId);
        if (t == null) return List.of();
        // PUBLISHED 用冻结的 componentsSnapshot；DRAFT 期 snapshot 尚未冻结(发布时才生成)，
        // 从实时 template_component 关联构建——否则草稿配公式时 tab-defs 永远返空。
        List<Map<String, Object>> snapshotList;
        if (t.componentsSnapshot != null && !t.componentsSnapshot.isBlank()) {
            try {
                snapshotList = MAPPER.readValue(t.componentsSnapshot,
                    new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                LOG.warnf("[ExcelView] tabDefsOfTemplate failed to parse componentsSnapshot tmpl=%s: %s",
                    templateId, e.getMessage());
                return List.of();
            }
        } else {
            snapshotList = buildLiveComponentsList(templateId);
        }
        if (snapshotList.isEmpty()) return List.of();
        // 收集 componentId → rowKeyFields（从 Component 表）
        Map<String, List<String>> rkfByCompId = new LinkedHashMap<>();
        for (Map<String, Object> entry : snapshotList) {
            String cid = (String) entry.get("componentId");
            if (cid == null || rkfByCompId.containsKey(cid)) continue;
            try {
                Component comp = Component.findById(UUID.fromString(cid));
                if (comp != null && comp.rowKeyFields != null && !comp.rowKeyFields.isBlank()) {
                    List<String> rkf = MAPPER.readValue(comp.rowKeyFields,
                        new TypeReference<List<String>>() {});
                    rkfByCompId.put(cid, rkf);
                } else {
                    rkfByCompId.put(cid, List.of());
                }
            } catch (Exception ignore) {
                rkfByCompId.put(cid, List.of());
            }
        }
        return parseTabDefs(snapshotList, rkfByCompId);
    }

    /**
     * 草稿模板 componentsSnapshot 尚未冻结(发布时才生成)，从实时 template_component + component 关联
     * 构建 parseTabDefs 所需的 List（componentId/componentName/componentType/tabName/sortOrder/fields）。
     * fields 走 template_component.fieldsOverride 优先（与发布冻结口径一致）。
     */
    private List<Map<String, Object>> buildLiveComponentsList(UUID templateId) {
        List<com.cpq.template.entity.TemplateComponent> tcs =
            com.cpq.template.entity.TemplateComponent.list("templateId = ?1 ORDER BY sortOrder ASC", templateId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (com.cpq.template.entity.TemplateComponent tc : tcs) {
            Component comp = Component.findById(tc.componentId);
            if (comp == null) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("componentId", comp.id.toString());
            entry.put("componentName", comp.name);
            entry.put("componentType", comp.componentType);
            entry.put("tabName", tc.tabName);
            entry.put("sortOrder", tc.sortOrder);
            String effectiveFields = (tc.fieldsOverride != null && !tc.fieldsOverride.isBlank())
                ? tc.fieldsOverride : comp.fields;
            entry.put("fields", parseJsonArray(effectiveFields));
            list.add(entry);
        }
        return list;
    }

    /**
     * 纯函数：把已解析好的 componentsSnapshot List + rowKeyFields 映射 → tabDefs List。
     * 不依赖 DB，便于单测。
     *
     * @param snapshotList   componentsSnapshot 已解析的 List（每元素含 componentId/tabName/componentName/sortOrder/fields/componentType）
     * @param rkfByCompId    componentId → rowKeyFields 字段名列表（由调用方从 DB 预查）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<Map<String, Object>> parseTabDefs(
            List<Map<String, Object>> snapshotList,
            Map<String, List<String>> rkfByCompId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> entry : snapshotList) {
            String componentId = (String) entry.get("componentId");
            String tabName     = (String) entry.get("tabName");
            String componentName = (String) entry.get("componentName");
            Object sortOrderObj = entry.get("sortOrder");
            int sortOrder = sortOrderObj instanceof Number ? ((Number) sortOrderObj).intValue() : 0;

            // alias: tabName 非空优先，否则用 componentName
            String alias = (tabName != null && !tabName.isBlank()) ? tabName : componentName;
            String tabKey = componentId + ":" + sortOrder;

            List<String> rowKeyFields = rkfByCompId != null
                ? rkfByCompId.getOrDefault(componentId, List.of())
                : List.of();

            // fields: 遍历取字段名列表 + 收集 is_subtotal 列
            List<String> detailFields = new ArrayList<>();
            List<String> subtotalCols = new ArrayList<>();
            Object fieldsObj = entry.get("fields");
            if (fieldsObj instanceof List) {
                for (Object f : (List<?>) fieldsObj) {
                    if (!(f instanceof Map)) continue;
                    Map<?, ?> fm = (Map<?, ?>) f;
                    Object nameObj = fm.get("name");
                    if (nameObj == null) continue;
                    String fieldName = nameObj.toString();
                    detailFields.add(fieldName);
                    Object isSubtotal = fm.get("is_subtotal");
                    if (Boolean.TRUE.equals(isSubtotal) || "true".equals(String.valueOf(isSubtotal))) {
                        subtotalCols.add(fieldName);
                    }
                }
            }

            Map<String, Object> def = new LinkedHashMap<>();
            def.put("alias", alias);
            def.put("tabKey", tabKey);
            def.put("componentId", componentId);
            def.put("componentName", componentName);
            def.put("componentType", entry.get("componentType"));
            def.put("sortOrder", sortOrder);
            def.put("rowKeyFields", rowKeyFields);
            def.put("detailFields", detailFields);
            def.put("subtotalCols", subtotalCols);
            result.add(def);
        }
        return result;
    }

    /**
     * 样本卡片：引用该 templateId 的 QuotationLineItem 列表，供前端选样本做试算。
     * 返回 [{quotationId, quotationNo, lineItemId, cardName}]，最多 50 条，按创建时间倒序。
     */
    public List<Map<String, Object>> sampleCardsOfTemplate(UUID templateId) {
        // 按 templateId 查 line_item，最多 50 条（按 line_item.created_at 倒序）
        List<QuotationLineItem> items = QuotationLineItem.list(
            "templateId = ?1 ORDER BY createdAt DESC", templateId);

        List<Map<String, Object>> result = new ArrayList<>();
        // 缓存已查过的 Quotation，避免重复查库
        Map<UUID, Quotation> quotationCache = new LinkedHashMap<>();
        int limit = 50;
        for (QuotationLineItem li : items) {
            if (result.size() >= limit) break;
            Quotation q = quotationCache.computeIfAbsent(li.quotationId,
                qid -> Quotation.findById(qid));
            String quotationNo = q != null ? q.quotationNumber : null;
            // 卡片名：productNameSnapshot 优先，否则 productPartNoSnapshot
            String cardName = li.productNameSnapshot != null && !li.productNameSnapshot.isBlank()
                ? li.productNameSnapshot
                : li.productPartNoSnapshot;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("quotationId", li.quotationId != null ? li.quotationId.toString() : null);
            entry.put("quotationNo", quotationNo);
            entry.put("lineItemId", li.id.toString());
            entry.put("cardName", cardName);
            result.add(entry);
        }
        return result;
    }

    // ---- TAB_JOIN_FORMULA config validation ----

    /**
     * TAB_JOIN_FORMULA 列配置期校验：expression 非空；引用的页签 alias 须在 tabs 声明；
     * 裸明细字段引用的页签 rowKeyFields 必须同一行键类(只有同行键页签的明细能一起逐行运算)。
     */
    @SuppressWarnings("unchecked")
    public static void validateTabJoinConfig(List<Map<String, Object>> columns) {
        java.util.regex.Pattern TOK = java.util.regex.Pattern.compile("\\[([^\\[\\]]+)]");
        for (Map<String, Object> col : columns) {
            if (!"TAB_JOIN_FORMULA".equals(col.get("source_type"))) continue;
            String expr = (String) col.getOrDefault("expression", "");
            if (expr == null || expr.isBlank())
                throw new BusinessException(400, "页签连表公式列 " + col.get("col_key") + " 表达式不能为空");
            List<Map<String, Object>> tabs = (List<Map<String, Object>>) col.getOrDefault("tabs", List.of());
            Map<String, List<String>> rkfOf = new HashMap<>();
            for (Map<String, Object> t : tabs)
                rkfOf.put((String) t.get("alias"), (List<String>) t.getOrDefault("rowKeyFields", List.of()));
            String detailClass = null;
            java.util.regex.Matcher m = TOK.matcher(expr);
            while (m.find()) {
                String tok = m.group(1).trim();
                boolean total = tok.endsWith("(总计)");
                String body = total ? tok.substring(0, tok.length() - "(总计)".length()) : tok;
                String alias = body.contains(".") ? body.substring(0, body.indexOf('.')) : body;
                // 声明校验（明细/总计都要求 alias 已声明）
                if (!rkfOf.containsKey(alias))
                    throw new BusinessException(400, "页签连表公式列 " + col.get("col_key")
                        + " 引用了未声明的页签: " + alias);
                // 裸明细跨行键类校验
                if (!total) {
                    String sig = String.join("+", rkfOf.getOrDefault(alias, List.of()));
                    if (detailClass == null) detailClass = sig;
                    else if (!detailClass.equals(sig))
                        throw new BusinessException(400, "页签连表公式列 " + col.get("col_key")
                            + " 的明细字段跨了不同行键类(只能同行键页签的明细一起运算): " + detailClass + " ≠ " + sig);
                }
            }
        }
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
