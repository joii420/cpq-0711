package com.cpq.quotation.service;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.formula.dataloader.QuotationIdContext;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.entity.QuotationViewStructure;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 报价单整份快照 Phase 1 — 报价单级 4 份结构快照 + 产品行级 4 份值快照。
 *
 * <p><b>核心职责</b>:
 * <ul>
 *   <li>{@link #ensureStructure(UUID)} — 首次加产品时固定 4 份视图结构（创建即冻，不覆盖）</li>
 *   <li>{@link #snapshotLineValues(QuotationLineItem)} — 对每行算四份初始值</li>
 * </ul>
 *
 * <p><b>设计 §1.4 收口纪律</b>:
 * <ul>
 *   <li>报价侧 {@code buildCardValues} 复用 ConfigureSnapshotService 已写入的 snapshot_rows（不双写 expand）</li>
 *   <li>核价侧 {@code buildCostingCardValues} 单独加载核价模板 driver 组件并一次 expand（核价侧无现成展开）</li>
 *   <li>Excel 值通过 {@link ExcelViewService#buildLineRowData} 计算</li>
 * </ul>
 *
 * <p><b>AP-51</b>: rowCount 权威 = expansion.rowCount > 0 ? rowCount : baseRows.length（禁 Math.max）
 * <p><b>AP-39</b>: DATA_SOURCE 字段的 {@code datasource_binding} 必须完整搬运，不能丢。
 */
@ApplicationScoped
public class CardSnapshotService {

    private static final Logger LOG = Logger.getLogger(CardSnapshotService.class);
    static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    @Inject
    ComponentDriverService componentDriverService;

    @Inject
    ExcelViewService excelViewService;

    @Inject
    FormulaCalculator formulaCalculator;

    /** 自注入：触发 REQUIRES_NEW 代理拦截器 */
    @Inject
    CardSnapshotService self;

    // =========================================================================
    // ensureStructure — 4 份结构快照（创建即冻）
    // =========================================================================

    /**
     * 若该报价单 4 份结构快照尚不存在，则从模板组装并写入；已存在则不覆盖（创建即冻）。
     * 全程 try/catch 降级，不影响加产品主流程。
     */
    @Transactional
    public void ensureStructure(UUID quotationId) {
        if (quotationId == null) return;
        try {
            Quotation q = Quotation.findById(quotationId);
            if (q == null) {
                LOG.warnf("[card-snapshot] ensureStructure: quotation not found id=%s", quotationId);
                return;
            }

            // 报价模板 → QUOTE_CARD + QUOTE_EXCEL
            if (q.customerTemplateId != null) {
                upsertStructure(quotationId, "QUOTE_CARD",
                    buildCardStructure(q.customerTemplateId, "QUOTATION"));
                upsertStructure(quotationId, "QUOTE_EXCEL",
                    buildExcelStructure(q.customerTemplateId));
            }

            // 核价模板 → COSTING_CARD + COSTING_EXCEL
            if (q.costingCardTemplateId != null) {
                upsertStructure(quotationId, "COSTING_CARD",
                    buildCardStructure(q.costingCardTemplateId, "COSTING"));
                upsertStructure(quotationId, "COSTING_EXCEL",
                    buildExcelStructure(q.costingCardTemplateId));
            }
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] ensureStructure failed for quotation=%s: %s",
                quotationId, e.getMessage());
        }
    }

    /**
     * UPSERT 结构快照：已存在则跳过（创建即冻），不存在则插入。
     */
    private void upsertStructure(UUID quotationId, String viewKind, String structureJson) {
        if (structureJson == null) return;
        QuotationViewStructure existing =
            QuotationViewStructure.findByQuotationAndKind(quotationId, viewKind);
        if (existing != null) return; // 已冻，不覆盖

        QuotationViewStructure s = new QuotationViewStructure();
        s.quotationId = quotationId;
        s.viewKind = viewKind;
        s.structure = structureJson;
        s.createdAt = OffsetDateTime.now();
        s.persist();
        LOG.debugf("[card-snapshot] created structure quotation=%s kind=%s", quotationId, viewKind);
    }

    // =========================================================================
    // buildCardStructure — 从 components_snapshot 组装卡片结构
    // =========================================================================

    /**
     * 从模板 {@code components_snapshot} 组装卡片结构 JSON（spec §3.1 形状）。
     *
     * <p>结构 = { version:1, templateId, templateKind, tabs:[{ componentId, tabName,
     * sortOrder, componentType, dataDriverPath, rowKeyFields, fields:[], formulas:[] }] }
     *
     * <p><b>AP-39</b>: DATA_SOURCE 字段的 {@code datasource_binding} 完整搬运（不丢）。
     */
    private String buildCardStructure(UUID templateId, String templateKind) {
        try {
            @SuppressWarnings("unchecked")
            var rows = em.createNativeQuery(
                "SELECT components_snapshot FROM template WHERE id = :tid")
                .setParameter("tid", templateId)
                .getResultList();
            if (rows.isEmpty() || rows.get(0) == null) return null;

            String snapshotJson = rows.get(0).toString();
            JsonNode snapshot = MAPPER.readTree(snapshotJson);
            if (!snapshot.isArray()) return null;

            ObjectNode root = MAPPER.createObjectNode();
            root.put("version", 1);
            root.put("templateId", templateId.toString());
            root.put("templateKind", templateKind);

            ArrayNode tabs = root.putArray("tabs");

            for (JsonNode tab : snapshot) {
                ObjectNode tabNode = MAPPER.createObjectNode();

                // 基础元数据
                String componentId = tab.path("componentId").asText(null);
                tabNode.put("componentId", componentId != null ? componentId : "");
                tabNode.put("tabName", tab.path("tabName").asText(""));
                tabNode.put("sortOrder", tab.path("sortOrder").asInt(0));
                tabNode.put("componentType", tab.path("componentType").asText("NORMAL"));
                tabNode.put("dataDriverPath", tab.path("data_driver_path").asText(""));

                // rowKeyFields：从 component 表读（AP-39 补充：行键冻进结构）
                if (componentId != null && !componentId.isBlank()) {
                    String rowKeyFields = loadRowKeyFields(componentId);
                    if (rowKeyFields != null) {
                        tabNode.set("rowKeyFields", MAPPER.readTree(rowKeyFields));
                    } else {
                        tabNode.putArray("rowKeyFields");
                    }
                } else {
                    tabNode.putArray("rowKeyFields");
                }

                // fields 映射（snapshot 用 field_type，结构用 fieldType；AP-39 datasource_binding 保留）
                ArrayNode fieldsNode = tabNode.putArray("fields");
                for (JsonNode f : tab.path("fields")) {
                    ObjectNode fieldNode = MAPPER.createObjectNode();
                    fieldNode.put("name", f.path("name").asText(""));
                    fieldNode.put("fieldType", f.path("field_type").asText(""));
                    fieldNode.put("label", f.path("name").asText(""));
                    fieldNode.put("sortOrder", f.path("sort_order").asInt(0));
                    fieldNode.put("isAmount", f.path("is_amount").asBoolean(false));
                    fieldNode.put("isRequired", f.path("is_required").asBoolean(false));
                    fieldNode.put("editable", isEditable(f.path("field_type").asText("")));
                    if (!f.path("content").isMissingNode()) {
                        fieldNode.put("defaultValue", f.path("content").asText(null));
                    }
                    if (!f.path("basic_data_path").isMissingNode()) {
                        fieldNode.put("basicDataPath", f.path("basic_data_path").asText(null));
                    }
                    // AP-39: DATA_SOURCE 必须完整搬运 datasource_binding
                    if ("DATA_SOURCE".equals(f.path("field_type").asText(""))) {
                        JsonNode binding = f.path("datasource_binding");
                        if (!binding.isMissingNode() && !binding.isNull()) {
                            fieldNode.set("datasourceBinding", binding);
                        }
                    }
                    fieldsNode.add(fieldNode);
                }

                // formulas 直接搬运
                JsonNode formulas = tab.path("formulas");
                if (formulas.isArray()) {
                    tabNode.set("formulas", formulas);
                } else {
                    tabNode.putArray("formulas");
                }

                tabs.add(tabNode);
            }

            return MAPPER.writeValueAsString(root);

        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildCardStructure failed templateId=%s: %s",
                templateId, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // buildExcelStructure — 从 excel_view_config 组装 Excel 列结构
    // =========================================================================

    /**
     * 从模板 {@code excel_view_config} 组装 Excel 列结构（spec §3.1 形状）。
     * 输出: { version:1, templateId, columns:[{colKey, title, sourceType, ...}] }
     */
    private String buildExcelStructure(UUID templateId) {
        try {
            @SuppressWarnings("unchecked")
            var rows = em.createNativeQuery(
                "SELECT excel_view_config, template_kind FROM template WHERE id = :tid")
                .setParameter("tid", templateId)
                .getResultList();
            if (rows.isEmpty()) return null;

            Object[] row = (Object[]) rows.get(0);
            String excelViewConfig = row[0] != null ? row[0].toString() : null;
            String templateKind = row[1] != null ? row[1].toString() : "";

            ObjectNode root = MAPPER.createObjectNode();
            root.put("version", 1);
            root.put("templateId", templateId.toString());
            root.put("templateKind", templateKind);

            if (excelViewConfig == null || excelViewConfig.isBlank()
                    || "[]".equals(excelViewConfig.trim())
                    || "{}".equals(excelViewConfig.trim())) {
                root.putArray("columns");
                return MAPPER.writeValueAsString(root);
            }

            JsonNode cols = MAPPER.readTree(excelViewConfig);
            if (!cols.isArray()) {
                root.putArray("columns");
                return MAPPER.writeValueAsString(root);
            }

            ArrayNode columns = root.putArray("columns");
            for (JsonNode col : cols) {
                ObjectNode colNode = MAPPER.createObjectNode();
                colNode.put("colKey", col.path("col_key").asText(col.path("colKey").asText("")));
                colNode.put("title", col.path("title").asText(col.path("col_name").asText("")));
                colNode.put("sourceType", col.path("source_type").asText("VARIABLE"));
                if (!col.path("variable_path").isMissingNode()) {
                    colNode.put("variablePath", col.path("variable_path").asText(null));
                }
                if (!col.path("formula").isMissingNode()) {
                    colNode.put("formula", col.path("formula").asText(null));
                }
                colNode.put("hidden", col.path("hidden").asBoolean(false));
                if (!col.path("comparison_tag").isMissingNode()) {
                    colNode.put("comparisonTag", col.path("comparison_tag").asText(null));
                }
                columns.add(colNode);
            }

            return MAPPER.writeValueAsString(root);

        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildExcelStructure failed templateId=%s: %s",
                templateId, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // snapshotLineValues — 产品行级 4 份值快照
    // =========================================================================

    /**
     * 对一行产品算四份初始值并写入 4 个值列。
     * <ul>
     *   <li>报价卡片值：复用 ConfigureSnapshotService 已写进 quotation_line_component_data.snapshot_rows（不二次 expand）</li>
     *   <li>报价 Excel 值：调 ExcelViewService.buildLineRowData 算最终列值</li>
     *   <li>核价卡片值：单独加载核价模板 driver 组件并 expand（核价侧无现成快照）</li>
     *   <li>核价 Excel 值：调 ExcelViewService.buildLineRowData（核价模板）</li>
     * </ul>
     * 各份 try/catch 降级，单份失败写 null 不连坐。
     */
    @Transactional
    public void snapshotLineValues(QuotationLineItem li) {
        if (li == null || li.id == null) return;
        try {
            // 在当前事务内重新加载，避免 "Detached entity" 错误
            QuotationLineItem managed = QuotationLineItem.findById(li.id);
            if (managed == null) return;

            Quotation q = Quotation.findById(managed.quotationId);
            if (q == null) return;

            // 报价侧：卡片值复用 snapshot_rows（Task 6 真实填充，不二次 expand）
            managed.quoteCardValues = safeCall(() ->
                buildCardValues(managed, q.customerTemplateId));

            // 报价侧：Excel 值由 ExcelViewService 计算
            managed.quoteExcelValues = safeCall(() ->
                buildExcelValues(managed, q.customerTemplateId, q.customerId));

            // 核价侧：需单独 expand（核价模板组件，无现成快照）
            if (q.costingCardTemplateId != null) {
                managed.costingCardValues = safeCall(() ->
                    buildCostingCardValues(managed, q.costingCardTemplateId,
                        q.customerId, q.id));
                managed.costingExcelValues = safeCall(() ->
                    buildExcelValues(managed, q.costingCardTemplateId, q.customerId));
            }

            managed.cardSnapshotAt = OffsetDateTime.now();
            managed.quoteValuesAt  = managed.cardSnapshotAt;
            // Panache managed entity — no explicit persist needed in active transaction
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] snapshotLineValues failed lineItem=%s: %s", li.id, e.getMessage());
        }
    }

    // =========================================================================
    // buildCardValues — 复用 snapshot_rows 组装报价卡片值（不双写 expand）
    // =========================================================================

    /**
     * 报价侧卡片值：读 quotation_line_component_data.snapshot_rows（ConfigureSnapshotService 已写），
     * 按 components_snapshot tab 顺序组装 tabs[].{baseRows, editRows, formulaResults}。
     *
     * <p>baseRows 每项 = {driverRow:{...}, basicDataValues:{...}}（直接来自 ExpandDriverResponse.Row）。
     * editRows/formulaResults Phase 1 留空（Phase 2 渲染脱钩再补）。
     * AP-51: rowCount 不做 Math.max，以 snapshot_rows 行数为准。
     */
    String buildCardValues(QuotationLineItem li, UUID templateId) {
        if (li == null || li.id == null || templateId == null) return null;
        try {
            // 1. 取模板 components_snapshot（用于 tab 顺序 + componentId）
            @SuppressWarnings("unchecked")
            var tmplRows = em.createNativeQuery(
                "SELECT components_snapshot FROM template WHERE id = :tid")
                .setParameter("tid", templateId)
                .getResultList();
            if (tmplRows.isEmpty() || tmplRows.get(0) == null) return null;

            JsonNode snapshot = MAPPER.readTree(tmplRows.get(0).toString());
            if (!snapshot.isArray()) return null;

            // 2. 取本行所有已有 snapshot_rows（key = componentId → rows JSON）
            @SuppressWarnings("unchecked")
            List<Object[]> snapData = em.createNativeQuery(
                "SELECT component_id, snapshot_rows FROM quotation_line_component_data " +
                "WHERE line_item_id = :lid AND snapshot_rows IS NOT NULL")
                .setParameter("lid", li.id)
                .getResultList();

            Map<String, String> snapByCompId = new LinkedHashMap<>();
            for (Object[] r : snapData) {
                if (r[0] != null && r[1] != null) {
                    snapByCompId.put(r[0].toString(), r[1].toString());
                }
            }

            // 3. 预构建每个组件的 baseRows（按 componentId） + rowKeyFields
            Map<String, ArrayNode> baseRowsByComp = new LinkedHashMap<>();
            for (JsonNode tab : snapshot) {
                String cid = tab.path("componentId").asText("");
                baseRowsByComp.put(cid, buildBaseRowsFromSnapshotRows(snapByCompId.get(cid), cid));
            }

            // 4. 组装 tabs（Task 3: 填 formulaResults，加产品时 editRows 恒空）
            ObjectNode root = assembleTabsWithFormulaResults(snapshot, baseRowsByComp, null);

            return MAPPER.writeValueAsString(root);

        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildCardValues failed li=%s tmpl=%s: %s",
                li.id, templateId, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // buildCostingCardValues — 核价侧单独 expand（无现成快照）
    // =========================================================================

    /**
     * 核价侧卡片值：加载核价模板 driver 组件，按报价行 partNo/compositeType 展开一次，
     * 组装 tabs[].{baseRows, editRows, formulaResults}。
     * 与报价侧 buildCardValues 相比：这里 expand 是必要的（非双写），
     * 因为 snapshotLines 只快照报价模板组件。
     */
    private String buildCostingCardValues(QuotationLineItem li, UUID costingTemplateId,
                                           UUID customerId, UUID quotationId) {
        if (li == null || li.id == null || costingTemplateId == null) return null;
        try {
            // 1. 取核价模板 components_snapshot
            @SuppressWarnings("unchecked")
            var tmplRows = em.createNativeQuery(
                "SELECT components_snapshot FROM template WHERE id = :tid")
                .setParameter("tid", costingTemplateId)
                .getResultList();
            if (tmplRows.isEmpty() || tmplRows.get(0) == null) return null;

            JsonNode snapshot = MAPPER.readTree(tmplRows.get(0).toString());
            if (!snapshot.isArray()) return null;

            // 2-4. 加载核价模板 driver 组件并 expand → baseRows（按 componentId）
            Map<String, ArrayNode> baseRowsByComp =
                expandTemplateDriverBaseRows(costingTemplateId, li, customerId, quotationId);

            // 5. 组装 tabs（Task 3: 填 formulaResults；核价侧 editRows 恒空）
            ObjectNode root = assembleTabsWithFormulaResults(snapshot, baseRowsByComp, null);

            return MAPPER.writeValueAsString(root);

        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildCostingCardValues failed li=%s tmpl=%s: %s",
                li.id, costingTemplateId, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // buildExcelValues — 调 ExcelViewService.buildLineRowData 算 Excel 行值
    // =========================================================================

    /**
     * 构建 Excel 值快照 JSON（{rows:[{colKey:value}]}）。
     * 调 {@link ExcelViewService#buildLineRowData} 计算本行各列值；模板无配置时 rows 为空数组。
     */
    String buildExcelValues(QuotationLineItem li, UUID templateId, UUID customerId) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode rowsNode = root.putArray("rows");
            if (li == null || templateId == null) return MAPPER.writeValueAsString(root);

            Map<String, Object> rowData = excelViewService.buildLineRowData(li, templateId, customerId);
            if (rowData != null && !rowData.isEmpty()) {
                rowsNode.add(MAPPER.valueToTree(rowData));
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildExcelValues failed li=%s tmpl=%s: %s",
                li != null ? li.id : "null", templateId, e.getMessage());
            try {
                ObjectNode root = MAPPER.createObjectNode();
                root.putArray("rows");
                return MAPPER.writeValueAsString(root);
            } catch (Exception ex) { return null; }
        }
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    // =========================================================================
    // Task 3 — formulaResults 填充（2 遍：先齐跨 tab componentSubtotals，再逐 tab calculate）
    // =========================================================================

    /** 从 snapshot_rows JSON 反序列化为 baseRows ArrayNode（[{driverRow,basicDataValues}]）。 */
    private ArrayNode buildBaseRowsFromSnapshotRows(String rowsJson, String componentId) {
        ArrayNode baseRows = MAPPER.createArrayNode();
        if (rowsJson == null || rowsJson.isBlank()) return baseRows;
        try {
            List<ExpandDriverResponse.Row> rows = MAPPER.readValue(
                rowsJson, new TypeReference<List<ExpandDriverResponse.Row>>() {});
            if (rows != null) {
                for (ExpandDriverResponse.Row row : rows) baseRows.add(rowToNode(row));
            }
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildBaseRows deserialize failed comp=%s: %s", componentId, e.getMessage());
        }
        return baseRows;
    }

    /** 把 expand 返回的 Row 列表转 baseRows ArrayNode。 */
    private ArrayNode buildBaseRowsFromRows(List<ExpandDriverResponse.Row> rows) {
        ArrayNode baseRows = MAPPER.createArrayNode();
        for (ExpandDriverResponse.Row row : rows) baseRows.add(rowToNode(row));
        return baseRows;
    }

    private ObjectNode rowToNode(ExpandDriverResponse.Row row) {
        ObjectNode rowNode = MAPPER.createObjectNode();
        rowNode.set("driverRow",
            row.driverRow != null ? MAPPER.valueToTree(row.driverRow) : MAPPER.createObjectNode());
        // basicDataValues（AP-39: 含 DATA_SOURCE 解析值不丢）
        rowNode.set("basicDataValues",
            row.basicDataValues != null ? MAPPER.valueToTree(row.basicDataValues) : MAPPER.createObjectNode());
        return rowNode;
    }

    /**
     * 按 snapshot tab 顺序组装 {tabs:[{componentId,tabName,baseRows,editRows,formulaResults}]}。
     *
     * <p><b>PASS 1</b>：跨 NORMAL tab（跳过 SUBTOTAL）按出现顺序算 componentSubtotals
     * （keyed by componentId / componentCode / tabName），供 component_subtotal token 引用（tab 间顺序依赖）。
     * <p><b>PASS 2</b>：逐 tab 调 {@link FormulaCalculator#calculate} 填 formulaResults；加产品/核价 editRows 恒空。
     * <p>FORMULA 重算口径与前端 computeAllFormulas / computeTabSubtotal / previous_row_subtotal 一致（防漂移）。
     */
    private ObjectNode assembleTabsWithFormulaResults(JsonNode snapshot, Map<String, ArrayNode> baseRowsByComp,
                                                      Map<String, ArrayNode> editRowsByComp) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode tabs = root.putArray("tabs");

        final ArrayNode emptyEdit = MAPPER.createArrayNode();
        // rowKeyFields 缓存（每组件一次）
        Map<String, JsonNode> rkfByComp = new LinkedHashMap<>();
        for (JsonNode tab : snapshot) {
            String cid = tab.path("componentId").asText("");
            if (!rkfByComp.containsKey(cid)) rkfByComp.put(cid, loadRowKeyFieldsNode(cid));
        }

        // 草稿重刷：旧 editRows 按 rowKey 对齐到新 baseRows，丢弃新数据中不存在的 rowKey（AP-54 业务键对齐）
        Map<String, ArrayNode> filteredEdit = filterEditRowsToNewBaseRows(
            snapshot, baseRowsByComp, editRowsByComp, rkfByComp, emptyEdit);

        // PASS 1: componentSubtotals（顺序累加，后 tab 可引用前 tab 小计；含保留的 editRows）
        Map<String, Double> componentSubtotals = new java.util.HashMap<>();
        for (JsonNode tab : snapshot) {
            if ("SUBTOTAL".equals(tab.path("componentType").asText("NORMAL"))) continue;
            String cid = tab.path("componentId").asText("");
            ArrayNode baseRows = baseRowsByComp.getOrDefault(cid, emptyEdit);
            ArrayNode editRows = filteredEdit.getOrDefault(cid, emptyEdit);
            double sub = formulaCalculator.computeTabSubtotal(
                tab.path("fields"), tab.path("formulas"), tab.path("formula_assignments"),
                rkfByComp.get(cid), baseRows, editRows, componentSubtotals).doubleValue();
            if (!cid.isBlank()) componentSubtotals.put(cid, sub);
            String code = tab.path("componentCode").asText(null);
            if (code != null && !code.isBlank()) componentSubtotals.put(code, sub);
            componentSubtotals.put(tab.path("tabName").asText(""), sub);
        }

        // PASS 2: 逐 tab 填 formulaResults
        for (JsonNode tab : snapshot) {
            String cid = tab.path("componentId").asText("");
            ObjectNode tabNode = MAPPER.createObjectNode();
            tabNode.put("componentId", cid);
            tabNode.put("tabName", tab.path("tabName").asText(""));

            ArrayNode baseRows = baseRowsByComp.getOrDefault(cid, MAPPER.createArrayNode());
            ArrayNode editRows = filteredEdit.getOrDefault(cid, MAPPER.createArrayNode());
            tabNode.set("baseRows", baseRows);
            tabNode.set("editRows", editRows); // 加产品/核价 → 空；草稿重刷 → 保留的编辑

            ArrayNode formulaResults = formulaCalculator.calculate(
                tab.path("fields"), tab.path("formulas"), tab.path("formula_assignments"),
                rkfByComp.get(cid), baseRows, editRows,
                componentSubtotals, new java.util.HashMap<>(), new java.util.HashMap<>());
            tabNode.set("formulaResults", formulaResults);

            tabs.add(tabNode);
        }
        return root;
    }

    /**
     * 草稿重刷：把旧 editRows 按 rowKey 叠加到新 baseRows 行键集合上，丢弃新数据里不存在的 rowKey。
     * editRowsByComp 为 null（加产品/核价）→ 返回空映射（editRows 恒空）。
     */
    private Map<String, ArrayNode> filterEditRowsToNewBaseRows(
            JsonNode snapshot, Map<String, ArrayNode> baseRowsByComp,
            Map<String, ArrayNode> editRowsByComp, Map<String, JsonNode> rkfByComp, ArrayNode emptyEdit) {
        Map<String, ArrayNode> filtered = new LinkedHashMap<>();
        if (editRowsByComp == null || editRowsByComp.isEmpty()) return filtered;
        for (JsonNode tab : snapshot) {
            String cid = tab.path("componentId").asText("");
            ArrayNode oldEdits = editRowsByComp.get(cid);
            if (oldEdits == null || oldEdits.size() == 0) continue;

            // 新 baseRows 的 rowKey 集合
            ArrayNode baseRows = baseRowsByComp.getOrDefault(cid, emptyEdit);
            JsonNode rkf = rkfByComp.get(cid);
            java.util.Set<String> newKeys = new java.util.HashSet<>();
            int idx = 0;
            for (JsonNode br : baseRows) {
                String rk = formulaCalculator.computeRowKey(rkf, br.path("driverRow"));
                newKeys.add(rk != null && !rk.isEmpty() ? rk : String.valueOf(idx));
                idx++;
            }

            ArrayNode kept = MAPPER.createArrayNode();
            for (JsonNode er : oldEdits) {
                if (newKeys.contains(er.path("rowKey").asText(""))) kept.add(er);
            }
            if (kept.size() > 0) filtered.put(cid, kept);
        }
        return filtered;
    }

    /**
     * 加载模板 driver 组件并按 partNo/compositeType expand 一次 → baseRows（按 componentId）。
     * 核价侧（buildCostingCardValues）与报价侧草稿重刷（refreshQuoteCardValues）共用此种子展开。
     */
    private Map<String, ArrayNode> expandTemplateDriverBaseRows(UUID templateId, QuotationLineItem li,
                                                                UUID customerId, UUID quotationId) {
        Map<String, ArrayNode> baseRowsByComp = new LinkedHashMap<>();
        // 单列查询 → List<UUID>（非 Object[]）；逐元素当 componentId
        @SuppressWarnings("unchecked")
        List<Object> driverComps = em.createNativeQuery(
            "SELECT DISTINCT c.id FROM template_component tc " +
            "JOIN component c ON c.id = tc.component_id " +
            "WHERE tc.template_id = :tid AND c.data_driver_path IS NOT NULL AND c.data_driver_path <> ''")
            .setParameter("tid", templateId)
            .getResultList();

        String partNo = li.productPartNoSnapshot;
        String compositeType = li.compositeType;
        if (partNo != null && !partNo.isBlank()) {
            QuotationIdContext.set(quotationId);
            try {
                for (Object dcObj : driverComps) {
                    if (dcObj == null) continue;
                    String cidStr = dcObj.toString();
                    UUID compId = UUID.fromString(cidStr);
                    try {
                        ExpandDriverResponse exp = componentDriverService.expand(
                            compId, customerId, partNo, null, null, null, li.id, compositeType);
                        List<ExpandDriverResponse.Row> rows =
                            (exp != null && exp.rows != null) ? exp.rows : new ArrayList<>();
                        baseRowsByComp.put(cidStr, buildBaseRowsFromRows(rows));
                    } catch (Exception e) {
                        LOG.warnf("[card-snapshot] expand comp=%s li=%s: %s", compId, li.id, e.getMessage());
                        baseRowsByComp.put(cidStr, MAPPER.createArrayNode());
                    }
                }
            } finally {
                QuotationIdContext.clear();
            }
        }
        return baseRowsByComp;
    }

    /** 从 quote_card_values JSON 提取各组件的旧 editRows（componentId → editRows 数组）。 */
    private Map<String, ArrayNode> extractEditRowsByComp(String cardValuesJson) {
        Map<String, ArrayNode> map = new LinkedHashMap<>();
        if (cardValuesJson == null || cardValuesJson.isBlank()) return map;
        try {
            JsonNode root = MAPPER.readTree(cardValuesJson);
            for (JsonNode tab : root.path("tabs")) {
                String cid = tab.path("componentId").asText("");
                JsonNode edits = tab.path("editRows");
                if (cid != null && !cid.isBlank() && edits.isArray() && edits.size() > 0) {
                    map.put(cid, (ArrayNode) edits);
                }
            }
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] extractEditRowsByComp failed: %s", e.getMessage());
        }
        return map;
    }

    private JsonNode loadComponentsSnapshot(UUID templateId) {
        try {
            @SuppressWarnings("unchecked")
            var rows = em.createNativeQuery(
                "SELECT components_snapshot FROM template WHERE id = :tid")
                .setParameter("tid", templateId).getResultList();
            if (rows.isEmpty() || rows.get(0) == null) return null;
            JsonNode snapshot = MAPPER.readTree(rows.get(0).toString());
            return snapshot.isArray() ? snapshot : null;
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] loadComponentsSnapshot failed tid=%s: %s", templateId, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // refreshQuoteCardValues — 草稿重刷（只刷报价侧，按行键保编辑，核价不动）
    // =========================================================================

    /**
     * 草稿态重刷报价侧两份值（设计 §5）：
     * <ol>
     *   <li>重查基础值：按报价模板 driver 组件 expand 种子 → 新 baseRows（实时最新数据）。</li>
     *   <li>对齐保留编辑：旧 {@code quote_card_values} 的 editRows 按 rowKey 叠加到新 baseRows；新数据无该 key 丢弃。</li>
     *   <li>重算公式：基于新 baseRows + 保留 editRows → 新 formulaResults。</li>
     *   <li>重算报价 Excel → 回写 {@code quote_excel_values}。</li>
     *   <li>更新 {@code quote_values_at}。</li>
     * </ol>
     * <p><b>核价两列物理不参与本次 UPDATE</b>（结构性隔离，核价永久冻死）。
     * <p>降级：任一步失败 → 保留上一次报价值快照，不抛、不阻断打开（与加产品同等降级）。
     */
    @Transactional
    public void refreshQuoteCardValues(QuotationLineItem li) {
        if (li == null || li.id == null) return;
        try {
            QuotationLineItem managed = QuotationLineItem.findById(li.id);
            if (managed == null) return;
            Quotation q = Quotation.findById(managed.quotationId);
            if (q == null || q.customerTemplateId == null) return;

            JsonNode snapshot = loadComponentsSnapshot(q.customerTemplateId);
            if (snapshot == null) return;

            // 1. 重查基础值（报价模板 driver 组件 expand 种子）
            Map<String, ArrayNode> baseRowsByComp =
                expandTemplateDriverBaseRows(q.customerTemplateId, managed, q.customerId, q.id);

            // 2. 旧 editRows（按 rowKey 对齐保留）
            Map<String, ArrayNode> oldEdits = extractEditRowsByComp(managed.quoteCardValues);

            // 3. 组装新 quote_card_values（保留编辑 + 重算 formulaResults）
            ObjectNode root = assembleTabsWithFormulaResults(snapshot, baseRowsByComp, oldEdits);
            managed.quoteCardValues = MAPPER.writeValueAsString(root);

            // 4. 重算报价 Excel（核价不动）
            String excel = safeCall(() -> buildExcelValues(managed, q.customerTemplateId, q.customerId));
            if (excel != null) managed.quoteExcelValues = excel;

            // 5. 更新报价侧时间戳
            managed.quoteValuesAt = OffsetDateTime.now();
            // 核价两列：物理不参与本次 UPDATE
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] refreshQuoteCardValues failed li=%s: %s", li.id, e.getMessage());
        }
    }

    /**
     * 草稿态打开时重刷整单报价侧卡片值（设计 §5 触发点）。
     * <ul>
     *   <li>仅 {@code status="DRAFT"} 执行；非 DRAFT（已提交/冻结）→ no-op 返 0。</li>
     *   <li>遍历该报价单全部 lineItems，逐行 {@link #refreshQuoteCardValues}（本方法无外层事务，每行 REQUIRED 即独立新事务，单行失败不连坐）。</li>
     * </ul>
     * @return 实际重刷的行数（非 DRAFT 返 0）。
     */
    public int refreshDraftQuoteCards(UUID quotationId) {
        if (quotationId == null) return 0;
        Quotation q = Quotation.findById(quotationId);
        if (q == null || !"DRAFT".equals(q.status)) return 0; // 非 DRAFT no-op
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", quotationId);
        int n = 0;
        for (QuotationLineItem li : lines) {
            try {
                self.refreshQuoteCardValues(li); // self → 触发 @Transactional 代理（每行独立事务）
                n++;
            } catch (Exception e) {
                LOG.warnf("[card-snapshot] refreshDraftQuoteCards line=%s failed: %s", li.id, e.getMessage());
            }
        }
        return n;
    }

    private JsonNode loadRowKeyFieldsNode(String componentId) {
        String json = loadRowKeyFields(componentId);
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String loadRowKeyFields(String componentId) {
        try {
            @SuppressWarnings("unchecked")
            var rows = em.createNativeQuery(
                "SELECT row_key_fields FROM component WHERE id = :cid")
                .setParameter("cid", UUID.fromString(componentId))
                .getResultList();
            if (rows.isEmpty() || rows.get(0) == null) return null;
            return rows.get(0).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isEditable(String fieldType) {
        return "INPUT_NUMBER".equals(fieldType)
            || "INPUT_TEXT".equals(fieldType)
            || "LIST_FORMULA".equals(fieldType);
    }

    private String safeCall(java.util.concurrent.Callable<String> c) {
        try {
            return c.call();
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] safeCall降级: %s", e.getMessage());
            return null;
        }
    }
}
