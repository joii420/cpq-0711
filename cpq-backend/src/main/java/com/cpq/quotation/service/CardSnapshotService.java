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
     * Task5(2026-06-01): DRAFT 重建 4 份结构 —— 删旧后 ensureStructure 重插。
     * <p>草稿态结构应跟随当前模板（模板改了草稿即时反映）；旧报价单借此补全 v2 新增的
     * config keys（formulaName/globalVariableCode/defaultSource/listFormulaConfig）+ 顶层 productAttributes。
     * <p>仅 DRAFT 执行；提交后结构冻结（ensureStructure 的 upsert 不覆盖）不受影响。
     */
    @Transactional
    public void rebuildStructureForDraft(UUID quotationId) {
        if (quotationId == null) return;
        Quotation q = Quotation.findById(quotationId);
        if (q == null || !"DRAFT".equals(q.status)) return;
        QuotationViewStructure.delete("quotationId", quotationId);
        ensureStructure(quotationId);
    }

    /**
     * UPSERT 结构快照：已存在则跳过（创建即冻），不存在则插入。
     */
    private void upsertStructure(UUID quotationId, String viewKind, String structureJson) {
        if (structureJson == null) return;
        QuotationViewStructure existing =
            QuotationViewStructure.findByQuotationAndKind(quotationId, viewKind);
        if (existing != null) return; // 已冻，不覆盖

        // 幂等插入：并发下两线程可能都通过上面的存在性检查（TOCTOU），靠 DB 层
        // ON CONFLICT DO NOTHING 兜底，撞 uq_quotation_view_structure 不抛 23505、
        // 不污染事务（否则同事务后续核价值 UPDATE 会被 25P02 连坐失败 → costing_* 永久空）。
        persistStructureIdempotent(quotationId, viewKind, structureJson);
        LOG.debugf("[card-snapshot] created structure quotation=%s kind=%s", quotationId, viewKind);
    }

    /**
     * 幂等插入一份结构快照：撞唯一约束 (quotation_id, view_kind) 时 DO NOTHING。
     * 用原生 SQL 而非 entity.persist()，避免重复键抛 PSQLException 把整个事务标记为
     * aborted（PG 行为），从而保护同事务内后续的核价值写入不被连坐。
     * public 供 CardStructureSnapshotTest 直接验证幂等性。
     */
    public void persistStructureIdempotent(UUID quotationId, String viewKind, String structureJson) {
        em.createNativeQuery(
            "INSERT INTO quotation_view_structure (id, quotation_id, view_kind, structure, created_at) " +
            "VALUES (gen_random_uuid(), :qid, :kind, cast(:struct as jsonb), now()) " +
            "ON CONFLICT (quotation_id, view_kind) DO NOTHING")
            .setParameter("qid", quotationId)
            .setParameter("kind", viewKind)
            .setParameter("struct", structureJson)
            .executeUpdate();
    }

    // =========================================================================
    // buildCardStructure — 从 components_snapshot 组装卡片结构
    // =========================================================================

    /**
     * 从模板 {@code components_snapshot} 组装卡片结构 JSON（spec §3.1 形状）。
     *
     * <p>结构 = { version:1, templateId, templateKind, tabs:[{ componentId, tabName,
     * sortOrder, componentType, dataDriverPath, treeConfig?, rowKeyFields, fields:[], formulas:[] }] }
     *
     * <p><b>AP-39</b>: DATA_SOURCE 字段的 {@code datasource_binding} 完整搬运（不丢）。
     */
    private String buildCardStructure(UUID templateId, String templateKind) {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT components_snapshot, product_attributes FROM template WHERE id = :tid")
                .setParameter("tid", templateId)
                .getResultList();
            if (rows.isEmpty() || rows.get(0) == null || rows.get(0)[0] == null) return null;

            String snapshotJson = rows.get(0)[0].toString();
            JsonNode snapshot = MAPPER.readTree(snapshotJson);
            if (!snapshot.isArray()) return null;

            ObjectNode root = MAPPER.createObjectNode();
            // version 2 (Task5 2026-06-01): 字段补全 config keys + 顶层 productAttributes（前端旁路 enrich/loadProductAttributes 全靠它）
            root.put("version", 2);
            root.put("templateId", templateId.toString());
            root.put("templateKind", templateKind);

            // Task5: productAttributes（产品属性 schema）冻进结构，前端不再 loadProductAttributes(GET /templates)
            Object paObj = rows.get(0)[1];
            if (paObj != null) {
                try {
                    JsonNode pa = MAPPER.readTree(paObj.toString());
                    if (pa.isArray()) root.set("productAttributes", pa);
                    else root.putArray("productAttributes");
                } catch (Exception ignore) { root.putArray("productAttributes"); }
            } else {
                root.putArray("productAttributes");
            }

            ArrayNode tabs = root.putArray("tabs");

            for (JsonNode tab : snapshot) {
                ObjectNode tabNode = MAPPER.createObjectNode();

                // 基础元数据
                String componentId = tab.path("componentId").asText(null);
                tabNode.put("componentId", componentId != null ? componentId : "");
                // 2026-06-02 产品小计=0 修复: 必须搬运 componentCode（含 __impN 多实例后缀）。
                //   SUBTOTAL 组件公式按 component_code 引用各 NORMAL tab 小计（如 COMP-0020__imp1）。
                //   Task5 后前端旁路 enrich 改读本结构组装 componentData，结构若缺 componentCode →
                //   componentData.componentCode='' → evaluateExpression 的 component_subtotal token
                //   按 component_code 查 componentSubtotals 全部落空 → 产品小计恒 0。
                tabNode.put("componentCode", tab.path("componentCode").asText(""));
                tabNode.put("tabName", tab.path("tabName").asText(""));
                tabNode.put("sortOrder", tab.path("sortOrder").asInt(0));
                tabNode.put("componentType", tab.path("componentType").asText("NORMAL"));
                tabNode.put("dataDriverPath", tab.path("data_driver_path").asText(""));
                // 树表配置透传(snapshot snake_case → 结构 camelCase;缺失/NULL 不写,前端按非树表处理)
                JsonNode treeCfg = tab.path("tree_config");
                if (treeCfg != null && treeCfg.isObject()) {
                    tabNode.set("treeConfig", treeCfg.deepCopy());
                }

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
                    fieldNode.put("isSubtotal", f.path("is_subtotal").asBoolean(false));
                    fieldNode.put("editable", isEditable(f.path("field_type").asText("")));
                    if (!f.path("content").isMissingNode()) {
                        fieldNode.put("defaultValue", f.path("content").asText(null));
                    }
                    if (!f.path("basic_data_path").isMissingNode()) {
                        fieldNode.put("basicDataPath", f.path("basic_data_path").asText(null));
                    }
                    // Task5(AP-44 完备性): 前端旁路 enrich 后, componentData 结构全靠本结构 —— 必须搬运全部 config keys，
                    // 否则 LIST_FORMULA(永久加载中)/default_source placeholder/global_variable_code/累加小计 等静默失效。
                    if (!f.path("formula_name").isMissingNode() && !f.path("formula_name").isNull()) {
                        fieldNode.put("formulaName", f.path("formula_name").asText(null));
                    }
                    if (!f.path("global_variable_code").isMissingNode() && !f.path("global_variable_code").isNull()) {
                        fieldNode.put("globalVariableCode", f.path("global_variable_code").asText(null));
                    }
                    if (!f.path("default_source").isMissingNode() && !f.path("default_source").isNull()) {
                        fieldNode.set("defaultSource", f.path("default_source"));
                    }
                    if (!f.path("list_formula_config").isMissingNode() && !f.path("list_formula_config").isNull()) {
                        fieldNode.set("listFormulaConfig", f.path("list_formula_config"));
                    }
                    // datasource_binding：原仅 DATA_SOURCE 搬运；改为任意字段类型只要存在即搬（INPUT_*.default_source.* 之外的绑定亦保真）
                    JsonNode binding = f.path("datasource_binding");
                    if (!binding.isMissingNode() && !binding.isNull()) {
                        fieldNode.set("datasourceBinding", binding);
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

            // 报价侧：Excel 值由 ExcelViewService 计算，透传同侧卡片快照（CARD_FORMULA 用同侧有效行取数）
            managed.quoteExcelValues = safeCall(() ->
                buildExcelValues(managed, q.customerTemplateId, q.customerId, managed.quoteCardValues));

            // 核价侧：需单独 expand（核价模板组件，无现成快照）
            if (q.costingCardTemplateId != null) {
                managed.costingCardValues = safeCall(() ->
                    buildCostingCardValues(managed, q.costingCardTemplateId,
                        q.customerId, q.id));
                // 核价 Excel 透传同侧核价卡片快照（核价只在加产品时算，草稿重刷/编辑不碰此两列）
                managed.costingExcelValues = safeCall(() ->
                    buildExcelValues(managed, q.costingCardTemplateId, q.customerId, managed.costingCardValues));
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
     * 既有三参签名保留（无卡片快照 → 旧路径，cardValuesJson=null）。
     */
    String buildExcelValues(QuotationLineItem li, UUID templateId, UUID customerId) {
        return buildExcelValues(li, templateId, customerId, null);
    }

    /**
     * 新重载：把同侧卡片值快照透传给 {@link ExcelViewService#buildLineRowData}，
     * CARD_FORMULA 用同侧有效行取数。{@code cardValuesJson} 为 null 时走旧路径。
     */
    String buildExcelValues(QuotationLineItem li, UUID templateId, UUID customerId, String cardValuesJson) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode rowsNode = root.putArray("rows");
            if (li == null || templateId == null) return MAPPER.writeValueAsString(root);

            Map<String, Object> rowData = excelViewService.buildLineRowData(li, templateId, customerId, cardValuesJson);
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

            // 值快照带上本 tab 小计（供 Excel CARD_FORMULA 的 __subtotal__ 引用，见 CardEffectiveRows）
            String code = tab.path("componentCode").asText(null);
            Double sub = componentSubtotals.get(cid);
            if (sub == null && code != null) sub = componentSubtotals.get(code);
            if (sub == null) sub = componentSubtotals.get(tab.path("tabName").asText(""));
            if (sub != null) tabNode.put("subtotal", sub);

            // 逐行解析成"按字段名标量行"(resolvedRows)，供 Excel CARD_FORMULA 直接按字段名取数。
            // 通用引擎 resolveRowByFieldName，配置驱动，零硬编码字段名。
            JsonNode fieldsDef = tab.path("fields");
            Map<String, JsonNode> frByKey = new LinkedHashMap<>();
            for (JsonNode fr : formulaResults) frByKey.put(fr.path("rowKey").asText(""), fr.path("values"));
            Map<String, JsonNode> edByKey = new LinkedHashMap<>();
            for (JsonNode er : editRows) edByKey.put(er.path("rowKey").asText(""), er.path("values"));
            JsonNode rkf = rkfByComp.get(cid);
            ArrayNode resolvedRows = MAPPER.createArrayNode();
            int ri = 0;
            for (JsonNode br : baseRows) {
                JsonNode driverRow = br.path("driverRow");
                JsonNode basicDataValues = br.path("basicDataValues");
                String rk = formulaCalculator.computeRowKey(rkf, driverRow);
                String rowKey = (rk != null && !rk.isEmpty()) ? rk : String.valueOf(ri);
                JsonNode editValues = edByKey.get(rowKey);
                JsonNode formulaValues = frByKey.get(rowKey);
                Map<String, Object> resolved = formulaCalculator.resolveRowByFieldName(
                    fieldsDef, driverRow, basicDataValues, editValues, formulaValues);
                resolvedRows.add(MAPPER.valueToTree(resolved));
                ri++;
            }
            tabNode.set("resolvedRows", resolvedRows);

            tabs.add(tabNode);
        }
        return root;
    }

    /** 仅供单测：暴露 assembleTabsWithFormulaResults 的 JSON 结果。 */
    String assembleTabsWithFormulaResultsForTest(JsonNode snapshot,
            java.util.Map<String, com.fasterxml.jackson.databind.node.ArrayNode> baseRowsByComp,
            java.util.Map<String, com.fasterxml.jackson.databind.node.ArrayNode> editRowsByComp) throws Exception {
        return MAPPER.writeValueAsString(
            assembleTabsWithFormulaResults(snapshot, baseRowsByComp, editRowsByComp));
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

    /** 从 quote_card_values JSON 提取各组件的 baseRows（componentId → baseRows 数组）。 */
    private Map<String, ArrayNode> extractBaseRowsByComp(String cardValuesJson) {
        Map<String, ArrayNode> map = new LinkedHashMap<>();
        if (cardValuesJson == null || cardValuesJson.isBlank()) return map;
        try {
            JsonNode root = MAPPER.readTree(cardValuesJson);
            for (JsonNode tab : root.path("tabs")) {
                String cid = tab.path("componentId").asText("");
                JsonNode base = tab.path("baseRows");
                if (cid != null && !cid.isBlank()) {
                    map.put(cid, base.isArray() ? (ArrayNode) base : MAPPER.createArrayNode());
                }
            }
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] extractBaseRowsByComp failed: %s", e.getMessage());
        }
        return map;
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

            // 2.5 (2026-06-02 修复 报价卡片 FORMULA 单元格读陈旧 formulaResults=0): 把 row_data
            //     (autosave 持久化的当前 INPUT 值, 与前端渲染 comp.rows 同源) 按 rowKey 合并进 editRows，
            //     让重算的 formulaResults 用当前 单价 等输入。否则 INPUT 仅在 editQuoteCardValue 写过的行
            //     进 editRows，autosave 写 row_data 但 editQuoteCardValue 漏的行 formulaResults 缺输入算 0，
            //     单元格(快照优先)读 0 而列小计(前端实时)正确 → 不一致。详见 RECORD 2026-06-02。
            Map<String, ArrayNode> mergedEdits =
                mergeRowDataInputsIntoEdits(snapshot, baseRowsByComp, oldEdits, managed.id);

            // 3. 组装新 quote_card_values（保留编辑 + 重算 formulaResults）
            ObjectNode root = assembleTabsWithFormulaResults(snapshot, baseRowsByComp, mergedEdits);
            managed.quoteCardValues = MAPPER.writeValueAsString(root);

            // 4. 重算报价 Excel（核价不动），透传刚算好的新 quoteCardValues（CARD_FORMULA 同侧取数）
            String excel = safeCall(() ->
                buildExcelValues(managed, q.customerTemplateId, q.customerId, managed.quoteCardValues));
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
        // Task5(2026-06-01): DRAFT 打开重刷时一并重建结构（草稿跟随当前模板 + 旧单补全 v2 config keys/productAttributes；提交后冻结不动）。
        try { self.rebuildStructureForDraft(quotationId); }
        catch (Exception e) { LOG.warnf("[card-snapshot] rebuildStructureForDraft failed q=%s: %s", quotationId, e.getMessage()); }
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", quotationId);
        int n = 0;
        for (QuotationLineItem li : lines) {
            try {
                // 2026-06-02 修复(草稿打开刷不出后台改的基础数据): 草稿重刷是用户"重查最新 SQL"的显式动作，
                //   先定向清掉本行 driver 展开缓存（30s TTL）。否则后台直接改库（未走 app 导入 → 未调 evictAll）
                //   时缓存命中旧值，refreshQuoteCardValues 重 expand 仍拿陈旧数据 → baseRows/含量 刷不出新值。
                if (li.id != null) componentDriverService.evictForLineItem(li.id);
                self.refreshQuoteCardValues(li); // self → 触发 @Transactional 代理（每行独立事务）
                n++;
            } catch (Exception e) {
                LOG.warnf("[card-snapshot] refreshDraftQuoteCards line=%s failed: %s", li.id, e.getMessage());
            }
        }
        return n;
    }

    // =========================================================================
    // editCardValue — 用户编辑报价卡片单元格（写 editRows + 重算，核价不动）
    // =========================================================================

    /**
     * 草稿态用户编辑报价卡片可编辑字段（设计 §6，替代旧 autosave 写 row_data）：
     * <ol>
     *   <li>把编辑值写入 {@code quote_card_values.tabs[componentId].editRows}（按 rowKey 索引）。</li>
     *   <li>基于<b>已存 baseRows</b>（不重新 expand）+ 全部 editRows 重算 FORMULA → 更新 formulaResults。</li>
     *   <li>重算报价 Excel → 回写 {@code quote_excel_values}。</li>
     *   <li>更新 {@code quote_values_at}；<b>核价两列物理不参与本次 UPDATE</b>。</li>
     * </ol>
     * <p>仅 {@code DRAFT} 可编辑；非 DRAFT → 返回 null（端点据此拒绝）。
     *
     * @return {@code {quoteCardValues, quoteExcelValues, quoteValuesAt}}（供前端就地更新 formulaResults/excel，AP-50）；
     *         非 DRAFT / 数据缺失 → null。
     */
    @Transactional
    public Map<String, Object> editCardValue(UUID lineItemId, String componentId, String rowKey,
                                             String fieldName, Object value) {
        if (lineItemId == null || componentId == null || rowKey == null || fieldName == null) return null;
        try {
            QuotationLineItem li = QuotationLineItem.findById(lineItemId);
            if (li == null) return null;
            Quotation q = Quotation.findById(li.quotationId);
            if (q == null || q.customerTemplateId == null) return null;
            if (!"DRAFT".equals(q.status)) return null; // 仅草稿态可编辑

            JsonNode snapshot = loadComponentsSnapshot(q.customerTemplateId);
            if (snapshot == null) return null;

            // 从已存快照重建 baseRows + editRows（不重新 expand，编辑只动 editRows）
            Map<String, ArrayNode> baseRowsByComp = extractBaseRowsByComp(li.quoteCardValues);
            Map<String, ArrayNode> editRowsByComp = extractEditRowsByComp(li.quoteCardValues);

            // 应用本次编辑：定位/新建 componentId 的 editRows 中 rowKey 项，写 values[fieldName]=value
            ArrayNode edits = editRowsByComp.get(componentId);
            if (edits == null) {
                edits = MAPPER.createArrayNode();
                editRowsByComp.put(componentId, edits);
            }
            ObjectNode target = null;
            for (JsonNode er : edits) {
                if (rowKey.equals(er.path("rowKey").asText(""))) { target = (ObjectNode) er; break; }
            }
            if (target == null) {
                target = MAPPER.createObjectNode();
                target.put("rowKey", rowKey);
                target.putObject("values");
                edits.add(target);
            }
            JsonNode valuesNode = target.path("values");
            if (!valuesNode.isObject()) valuesNode = target.putObject("values");
            ((ObjectNode) valuesNode).set(fieldName, MAPPER.valueToTree(value));

            // 重算（baseRows 不变 + 新 editRows）
            ObjectNode root = assembleTabsWithFormulaResults(snapshot, baseRowsByComp, editRowsByComp);
            li.quoteCardValues = MAPPER.writeValueAsString(root);

            // 重算报价 Excel（核价不动），透传刚算好的新 quoteCardValues（CARD_FORMULA 同侧取数）
            String excel = safeCall(() ->
                buildExcelValues(li, q.customerTemplateId, q.customerId, li.quoteCardValues));
            if (excel != null) li.quoteExcelValues = excel;

            li.quoteValuesAt = OffsetDateTime.now();
            // 核价两列：物理不参与本次 UPDATE

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("quoteCardValues", li.quoteCardValues);
            resp.put("quoteExcelValues", li.quoteExcelValues);
            resp.put("quoteValuesAt", li.quoteValuesAt != null ? li.quoteValuesAt.toString() : null);
            return resp;
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] editCardValue failed li=%s comp=%s rowKey=%s field=%s: %s",
                lineItemId, componentId, rowKey, fieldName, e.getMessage());
            return null;
        }
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

    /**
     * 2026-06-02 修复: 把 quotation_line_component_data.row_data（autosave 持久化的当前 INPUT 值，
     * 与前端渲染 comp.rows 同源）按 rowKey 合并进 editRows，供草稿打开重刷 formulaResults 用当前输入重算。
     * <ul>
     *   <li>仅取 {@code INPUT_NUMBER/INPUT_TEXT} 用户输入字段；不取 driver/FORMULA/LIST_FORMULA（由 baseRows/重算提供）。</li>
     *   <li>row_data[i] 与 baseRows[i] 同序（同 driver 展开）；rowKey 用 baseRows[i].driverRow 计算（与 filter 对齐），空 rkf → 位置下标。</li>
     *   <li>row_data 是当前权威输入 → 覆盖 editRows 同字段；row_data 缺该字段则保留旧 editRows 值。</li>
     *   <li>任一步失败 → 降级返回原 editRows，不阻断打开。</li>
     * </ul>
     */
    private Map<String, ArrayNode> mergeRowDataInputsIntoEdits(
            JsonNode snapshot, Map<String, ArrayNode> baseRowsByComp,
            Map<String, ArrayNode> oldEdits, UUID lineItemId) {
        try {
            // 基底：复制旧 editRows（不改原引用）
            Map<String, ArrayNode> merged = new LinkedHashMap<>();
            if (oldEdits != null) {
                for (Map.Entry<String, ArrayNode> e : oldEdits.entrySet()) {
                    merged.put(e.getKey(), e.getValue() != null ? e.getValue().deepCopy() : MAPPER.createArrayNode());
                }
            }
            // 加载本行各组件 row_data
            @SuppressWarnings("unchecked")
            List<Object[]> rd = em.createNativeQuery(
                "SELECT component_id, row_data FROM quotation_line_component_data " +
                "WHERE line_item_id = :lid AND row_data IS NOT NULL")
                .setParameter("lid", lineItemId)
                .getResultList();
            Map<String, JsonNode> rowDataByComp = new LinkedHashMap<>();
            for (Object[] r : rd) {
                if (r[0] != null && r[1] != null) {
                    JsonNode arr = MAPPER.readTree(r[1].toString());
                    if (arr.isArray()) rowDataByComp.put(r[0].toString(), arr);
                }
            }
            if (rowDataByComp.isEmpty()) return oldEdits;

            for (JsonNode tab : snapshot) {
                String cid = tab.path("componentId").asText("");
                if (cid.isBlank()) continue;
                JsonNode rowData = rowDataByComp.get(cid);
                if (rowData == null || !rowData.isArray() || rowData.size() == 0) continue;

                // INPUT 字段名集合（仅用户输入，不含 FORMULA/LIST_FORMULA/driver）
                List<String> inputFields = new ArrayList<>();
                for (JsonNode f : tab.path("fields")) {
                    String ft = f.path("field_type").asText("");
                    if ("INPUT_NUMBER".equals(ft) || "INPUT_TEXT".equals(ft)) {
                        String n = f.path("name").asText("");
                        if (!n.isEmpty()) inputFields.add(n);
                    }
                }
                if (inputFields.isEmpty()) continue;

                ArrayNode baseRows = baseRowsByComp.getOrDefault(cid, MAPPER.createArrayNode());
                JsonNode rkf = loadRowKeyFieldsNode(cid);
                ArrayNode edits = merged.computeIfAbsent(cid, k -> MAPPER.createArrayNode());
                Map<String, ObjectNode> editByKey = new LinkedHashMap<>();
                for (JsonNode er : edits) {
                    if (er.isObject()) editByKey.put(er.path("rowKey").asText(""), (ObjectNode) er);
                }

                int n = Math.min(baseRows.size(), rowData.size());
                for (int i = 0; i < n; i++) {
                    JsonNode driverRow = baseRows.get(i).path("driverRow");
                    String rk = formulaCalculator.computeRowKey(rkf, driverRow);
                    String rowKey = (rk != null && !rk.isEmpty()) ? rk : String.valueOf(i);
                    JsonNode rdRow = rowData.get(i);

                    ObjectNode editRow = editByKey.get(rowKey);
                    if (editRow == null) {
                        editRow = MAPPER.createObjectNode();
                        editRow.put("rowKey", rowKey);
                        editRow.putObject("values");
                        edits.add(editRow);
                        editByKey.put(rowKey, editRow);
                    }
                    ObjectNode vals = editRow.path("values").isObject()
                        ? (ObjectNode) editRow.path("values") : editRow.putObject("values");
                    for (String fld : inputFields) {
                        JsonNode v = rdRow.path(fld);
                        if (!v.isMissingNode() && !v.isNull()) vals.set(fld, v); // 当前权威输入覆盖
                    }
                }
            }
            return merged;
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] mergeRowDataInputsIntoEdits 降级 li=%s: %s", lineItemId, e.getMessage());
            return oldEdits; // 降级用原 editRows
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
