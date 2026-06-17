package com.cpq.quotation.service;

import com.cpq.component.dto.BomClosureResult;
import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.service.BomClosureService;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.formula.dataloader.QuotationIdContext;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.entity.QuotationViewStructure;
import com.cpq.quotation.rowkey.DeletedRowKeys;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 核价 BOM 递归展开（P1）：算根料号闭包（partSet+spine+环清单）。 */
    @Inject
    BomClosureService bomClosureService;

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
                    // 字段列展示宽度(px)。组件 fields 中无 width 或 <=0 时存 0，前端 resolveFieldWidth 回退默认 120。
                    fieldNode.put("width", f.path("width").asInt(0));
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
                    // Plan 3a：条件公式整块搬运（AP-44 完备性，否则渲染期条件解析静默失效）
                    if (f.path("conditional_formula").isObject()) {
                        fieldNode.set("conditionalFormula", f.path("conditional_formula"));
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
                    // 单位换算（AP-44 完备性补漏 2026-06-17）：搬运 unit_source_field → unitSourceField。
                    // 前端结构脱钩路径(buildComponentDataFromStructure)据此让 applyUnitConversion/computeAllFormulas
                    // 按同行单位列归一被换算列；漏搬则前端实时重算用原值（净用量 g/pcs 未 ×0.001 → 产品小计虚高 ~1000x）。
                    JsonNode usf = f.path("unit_source_field");
                    if (!usf.isMissingNode() && !usf.isNull() && !usf.asText("").isBlank()) {
                        fieldNode.put("unitSourceField", usf.asText());
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
            // Task 3.1: 列定义统一从 EXCEL 组件解析（不再直接读 template.excel_view_config 当列数组）
            com.cpq.template.entity.Template template = com.cpq.template.entity.Template.findById(templateId);
            if (template == null) return null;
            String templateKind = template.templateKind != null ? template.templateKind : "";

            ObjectNode root = MAPPER.createObjectNode();
            root.put("version", 1);
            root.put("templateId", templateId.toString());
            root.put("templateKind", templateKind);

            List<Map<String, Object>> cols = excelViewService.getEffectiveColumns(template);
            if (cols == null || cols.isEmpty()) {
                root.putArray("columns");
                return MAPPER.writeValueAsString(root);
            }

            ArrayNode columns = root.putArray("columns");
            for (Map<String, Object> col : cols) {
                ObjectNode colNode = MAPPER.createObjectNode();
                Object colKey = col.get("col_key") != null ? col.get("col_key") : col.get("colKey");
                colNode.put("colKey", colKey != null ? colKey.toString() : "");
                Object title = col.get("title") != null ? col.get("title") : col.get("col_name");
                colNode.put("title", title != null ? title.toString() : "");
                Object st = col.get("source_type");
                colNode.put("sourceType", st != null ? st.toString() : "VARIABLE");
                if (col.containsKey("variable_path")) {
                    Object vp = col.get("variable_path");
                    colNode.put("variablePath", vp != null ? vp.toString() : null);
                }
                if (col.containsKey("formula")) {
                    Object fm = col.get("formula");
                    colNode.put("formula", fm != null ? fm.toString() : null);
                }
                Object hidden = col.get("hidden");
                colNode.put("hidden", Boolean.TRUE.equals(hidden) || "true".equals(String.valueOf(hidden)));
                if (col.containsKey("comparison_tag")) {
                    Object ct = col.get("comparison_tag");
                    colNode.put("comparisonTag", ct != null ? ct.toString() : null);
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
                    buildExcelValues(managed, q.costingCardTemplateId, q.customerId, managed.costingCardValues, true));
            }

            managed.cardSnapshotAt = OffsetDateTime.now();
            managed.quoteValuesAt  = managed.cardSnapshotAt;
            // Panache managed entity — no explicit persist needed in active transaction
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] snapshotLineValues failed lineItem=%s: %s", li.id, e.getMessage());
        }
    }

    /**
     * 核价 BOM 递归展开（P1）：重算<b>整单核价</b>卡片值 + 核价 Excel（仅 COSTING，不碰报价侧）。
     *
     * <p>用于 {@code refresh-snapshot} —— 让<b>存量核价单</b>在用户主动刷新时把核价卡片
     * 重算成整棵 BOM 树（plan 灰度口径「存量核价单下次快照重算时变整棵树」）。
     *
     * <p>安全性：核价侧 {@code editRows} 恒空（无核价编辑端点），重算不丢用户编辑；
     * 报价侧 {@code quoteCardValues}/{@code quoteExcelValues} <b>完全不动</b>（守隔离，防 AP-41）。
     */
    @Transactional
    public void refreshCostingCardValues(UUID quotationId) {
        if (quotationId == null) return;
        Quotation q = Quotation.findById(quotationId);
        if (q == null || q.costingCardTemplateId == null) return;
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", quotationId);
        for (QuotationLineItem li : lines) {
            try {
                QuotationLineItem managed = QuotationLineItem.findById(li.id);
                if (managed == null) continue;
                managed.costingCardValues = safeCall(() ->
                    buildCostingCardValues(managed, q.costingCardTemplateId, q.customerId, q.id));
                managed.costingExcelValues = safeCall(() ->
                    buildExcelValues(managed, q.costingCardTemplateId, q.customerId, managed.costingCardValues, true));
            } catch (Exception e) {
                LOG.warnf("[card-snapshot] refreshCostingCardValues li=%s: %s", li.id, e.getMessage());
            }
        }
        LOG.infof("[card-snapshot] refreshCostingCardValues done quotation=%s lines=%d", quotationId, lines.size());
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

            // 2. 一次查 snapshot_rows + deleted_row_keys（同表同条件，避免两次往返）
            @SuppressWarnings("unchecked")
            List<Object[]> compData = em.createNativeQuery(
                "SELECT component_id, snapshot_rows, deleted_row_keys " +
                "FROM quotation_line_component_data WHERE line_item_id = :lid")
                .setParameter("lid", li.id)
                .getResultList();

            Map<String, String> snapByCompId = new LinkedHashMap<>();
            Map<String, List<DeletedRowKeys.Tombstone>> delByComp = new HashMap<>();
            for (Object[] r : compData) {
                if (r[0] == null) continue;
                String cid = r[0].toString();
                if (r[1] != null) snapByCompId.put(cid, r[1].toString());
                delByComp.put(cid, DeletedRowKeys.parse(r[2] == null ? null : r[2].toString()));
            }

            // 3. 预构建每个组件的 baseRows（按 componentId）
            Map<String, ArrayNode> baseRowsByComp = new LinkedHashMap<>();
            for (JsonNode tab : snapshot) {
                String cid = tab.path("componentId").asText("");
                baseRowsByComp.put(cid, buildBaseRowsFromSnapshotRows(snapByCompId.get(cid), cid));
            }

            // 4. 组装 tabs（Task 3: 填 formulaResults，加产品时 editRows 恒空；报价侧传真实墓碑）
            ObjectNode root = assembleTabsWithFormulaResults(snapshot, baseRowsByComp, null, null, delByComp);

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

            // 核价 BOM 递归展开（P1）：先算根料号闭包（整棵 PRICING BOM 树）。
            // 闭包 partSet 喂各核价组件多值取数；spine 全节点作行主轴（缺数据补空行）。
            BomClosureResult closure = bomClosureService.compute(li.productPartNoSnapshot, java.util.Map.of());

            // 2-4. 加载核价模板 driver 组件并按 spine 全节点展开 → baseRows（按 componentId）
            Map<String, ArrayNode> baseRowsByComp =
                expandTemplateDriverBaseRows(costingTemplateId, li, customerId, quotationId, closure);

            // 5. 组装 tabs（Task 3: 填 formulaResults；核价侧 editRows 恒空）
            // 核价侧 side==COSTING 显式不传墓碑（spec §3.7 隔离）：三参签名 delegate → delByComp=null → 不过滤
            ObjectNode root = assembleTabsWithFormulaResults(snapshot, baseRowsByComp, null);

            // 6. 成环料号回传根节点（前端告警「已截断展开」）
            if (closure != null && closure.cyclePartNos != null && !closure.cyclePartNos.isEmpty()) {
                ArrayNode cyc = root.putArray("cyclePartNos");
                for (String pn : closure.cyclePartNos) cyc.add(pn);
            }

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

    /**
     * P2-B 核价 Excel 树重载：{@code costingTree=true} 时按 BOM spine 逐节点出多行（{rows:[N], treeMode:true}）；
     * 否则委托四参单行版本。仅核价侧传 true（报价 Excel 仍单行，守隔离）。
     */
    String buildExcelValues(QuotationLineItem li, UUID templateId, UUID customerId,
                            String cardValuesJson, boolean costingTree) {
        if (!costingTree) return buildExcelValues(li, templateId, customerId, cardValuesJson);
        try {
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode rowsNode = root.putArray("rows");
            if (li == null || templateId == null) return MAPPER.writeValueAsString(root);
            List<Map<String, Object>> treeRows =
                excelViewService.buildLineTreeRows(li, templateId, customerId, cardValuesJson);
            for (Map<String, Object> r : treeRows) rowsNode.add(MAPPER.valueToTree(r));
            root.put("treeMode", true);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildExcelValues(tree) failed li=%s tmpl=%s: %s",
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
        // 三参签名零破坏：delegate 到四参（rkfOverride=null）。所有既有调用走此入口，行为不变（AP-40/AP-51 纪律）。
        return assembleTabsWithFormulaResults(snapshot, baseRowsByComp, editRowsByComp, null);
    }

    /**
     * 四参重载（v6-N 草稿行键双注入）：delegate 到五参（delByComp=null，不过滤）。
     *
     * <p><b>零破坏</b>：既有所有调用（buildCostingCardValues / *ForTest 等）经此入口，行为完全不变。
     *
     * @param rkfOverride componentId → rowKeyFields 节点（覆盖该组件持久化行键）；null → 不覆盖。
     */
    private ObjectNode assembleTabsWithFormulaResults(JsonNode snapshot, Map<String, ArrayNode> baseRowsByComp,
                                                      Map<String, ArrayNode> editRowsByComp,
                                                      Map<String, JsonNode> rkfOverride) {
        // 四参签名零破坏：delegate 到五参（delByComp=null），核价侧与旧测试不经过滤路径
        return assembleTabsWithFormulaResults(snapshot, baseRowsByComp, editRowsByComp, rkfOverride, null);
    }

    /**
     * 五参重载（driver 默认行永久删除 + v6-N 草稿行键双注入）：
     *
     * <p>对每个 cid 取 {@code delByComp.get(cid)} 的墓碑列表，连同 rowKeyFieldNames 一起传入
     * {@link FormulaCalculator#calculate} 与 {@link #buildResolvedRows} 的带墓碑新重载，
     * 在唯一化之后按双命中剔除被永久删除的 driver 默认行（守头号不变量 AP-54）。
     *
     * <p><b>核价隔离（spec §3.7）</b>：buildCostingCardValues 经四参入口传 {@code delByComp=null}，
     * 核价侧绝不误伤任何行。
     *
     * <p><b>零破坏</b>：四参 → 三参 → 此方法，delByComp=null 时全程不进过滤分支。
     *
     * @param rkfOverride componentId → rowKeyFields 节点（覆盖该组件持久化行键）；null → 不覆盖。
     * @param delByComp   componentId → 墓碑列表（报价侧传真实值；核价/旧路径传 null → 不过滤）。
     */
    private ObjectNode assembleTabsWithFormulaResults(JsonNode snapshot, Map<String, ArrayNode> baseRowsByComp,
                                                      Map<String, ArrayNode> editRowsByComp,
                                                      Map<String, JsonNode> rkfOverride,
                                                      Map<String, List<DeletedRowKeys.Tombstone>> delByComp) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode tabs = root.putArray("tabs");

        final ArrayNode emptyEdit = MAPPER.createArrayNode();
        // rowKeyFields 缓存（每组件一次）
        Map<String, JsonNode> rkfByComp = new LinkedHashMap<>();
        for (JsonNode tab : snapshot) {
            String cid = tab.path("componentId").asText("");
            if (!rkfByComp.containsKey(cid)) rkfByComp.put(cid, loadRowKeyFieldsNode(cid));
        }
        // v6-N 草稿行键覆盖：装配里 rkfByComp 默认读持久化行键；试算时把宿主 cid 行键覆盖为草稿行键。
        if (rkfOverride != null) rkfByComp.putAll(rkfOverride);

        // 草稿重刷：旧 editRows 按 rowKey 对齐到新 baseRows，丢弃新数据中不存在的 rowKey（AP-54 业务键对齐）
        Map<String, ArrayNode> filteredEdit = filterEditRowsToNewBaseRows(
            snapshot, baseRowsByComp, editRowsByComp, rkfByComp, emptyEdit);

        // PASS 1: componentSubtotals（顺序累加，后 tab 可引用前 tab 小计；含保留的 editRows）
        // 报价侧：传入 deleted 以反映永久删除行后的正确小计基数（核价侧 delByComp=null → 不过滤）
        Map<String, Double> componentSubtotals = new java.util.HashMap<>();
        for (JsonNode tab : snapshot) {
            // 仅处理 NORMAL tab（跳过 SUBTOTAL 及 EXCEL —— EXCEL 非普通公式 tab，不参与小计累加）
            if (!"NORMAL".equals(tab.path("componentType").asText("NORMAL"))) continue;
            String cid = tab.path("componentId").asText("");
            ArrayNode baseRows = baseRowsByComp.getOrDefault(cid, emptyEdit);
            ArrayNode editRows = filteredEdit.getOrDefault(cid, emptyEdit);
            // 墓碑路由：报价侧取该组件墓碑；delByComp==null（核价侧/旧路径）→ deleted=null → 不过滤（spec §3.7 隔离）
            List<DeletedRowKeys.Tombstone> deleted = (delByComp == null) ? null : delByComp.get(cid);
            List<String> rkfNames = rowKeyFieldNamesOf(rkfByComp.get(cid));
            java.util.Map<String, java.math.BigDecimal> byCol = formulaCalculator.computeTabSubtotalsByColumn(
                tab.path("fields"), tab.path("formulas"), tab.path("formula_assignments"),
                rkfByComp.get(cid), baseRows, editRows, componentSubtotals, deleted, rkfNames);
            double sub = 0.0;
            for (java.math.BigDecimal v : byCol.values()) sub += v.doubleValue();
            String code = tab.path("componentCode").asText(null);
            String tabName = tab.path("tabName").asText("");
            if (!cid.isBlank()) componentSubtotals.put(cid, sub);
            if (code != null && !code.isBlank()) componentSubtotals.put(code, sub);
            componentSubtotals.put(tabName, sub);
            // Plan 2-核心：per-column 键 `${key}#${列名}`，供按列引用/显示。
            for (java.util.Map.Entry<String, java.math.BigDecimal> e : byCol.entrySet()) {
                double cv = e.getValue().doubleValue();
                if (!cid.isBlank()) componentSubtotals.put(cid + "#" + e.getKey(), cv);
                if (code != null && !code.isBlank()) componentSubtotals.put(code + "#" + e.getKey(), cv);
                componentSubtotals.put(tabName + "#" + e.getKey(), cv);
            }
        }

        // PASS 2: 按组件 cross_tab_ref 依赖拓扑序逐 tab 算（A 必须先于引用它的 B），
        //   每算完一个组件即把其"按字段名标量行"(resolvedRows) 存入 crossTabRows 供后续组件引用。
        //   输出顺序仍按原 snapshot 顺序（拓扑序只决定计算次序，不改变 UI tab 顺序）。

        // 1) 组件级拓扑序（仅 NORMAL tab；SUBTOTAL 不参与，单独在原序补算）
        // 1a) 解析表：component_code / tabName / componentId → componentId（供 component_subtotal 依赖解析）
        Map<String, String> refToCid = new HashMap<>();
        for (JsonNode tab : snapshot) {
            if (!"NORMAL".equals(tab.path("componentType").asText("NORMAL"))) continue;
            String cid = tab.path("componentId").asText("");
            if (!cid.isBlank()) refToCid.put(cid, cid);
            String code = tab.path("componentCode").asText("");
            if (!code.isBlank()) refToCid.put(code, cid);
            String tn = tab.path("tabName").asText("");
            if (!tn.isBlank()) refToCid.put(tn, cid);
        }
        List<String> compIds = new ArrayList<>();
        Map<String, Set<String>> compDeps = new LinkedHashMap<>();
        for (JsonNode tab : snapshot) {
            // 仅 NORMAL tab 进拓扑序（跳过 SUBTOTAL 及 EXCEL —— EXCEL 不参与公式计算/cross_tab_ref）
            if (!"NORMAL".equals(tab.path("componentType").asText("NORMAL"))) continue;
            String cid = tab.path("componentId").asText("");
            compIds.add(cid);
            // cross_tab_ref 源依赖（既有） + component_subtotal 跨组件依赖（QT-1743 修复，与前端对齐）
            Set<String> deps = new LinkedHashSet<>(CrossTabComponentOrder.extractSourceRefs(tab.path("formulas")));
            for (String r : CrossTabComponentOrder.extractSubtotalRefs(tab.path("formulas"))) {
                String tcid = refToCid.get(r);
                if (tcid != null && !tcid.equals(cid)) deps.add(tcid);  // 排除自引用（二阶列由 B6 两阶段处理）
            }
            compDeps.put(cid, deps);
        }
        List<String> order = CrossTabComponentOrder.topoOrder(compIds, compDeps);

        // componentId → snapshot tab（按 componentId 反查；SUBTOTAL 走原序补算时直接遍历 snapshot）
        Map<String, JsonNode> tabById = new LinkedHashMap<>();
        for (JsonNode tab : snapshot) {
            // 仅 NORMAL tab 入反查表（跳过 SUBTOTAL 及 EXCEL）
            if (!"NORMAL".equals(tab.path("componentType").asText("NORMAL"))) continue;
            tabById.put(tab.path("componentId").asText(""), tab);
        }

        // cross_tab_ref 已算行存储（组件标识 componentId/componentCode → resolvedRows）
        Map<String, List<Map<String, Object>>> crossTabRows = new java.util.HashMap<>();
        // componentId → 已组装 tabNode（最终按原 snapshot 顺序回灌）
        Map<String, ObjectNode> tabNodeById = new LinkedHashMap<>();

        // 2) 拓扑序计算 NORMAL tab
        for (String cid : order) {
            JsonNode tab = tabById.get(cid);
            if (tab == null) continue;
            ArrayNode baseRows = baseRowsByComp.getOrDefault(cid, MAPPER.createArrayNode());
            ArrayNode editRows = filteredEdit.getOrDefault(cid, MAPPER.createArrayNode());
            String code = tab.path("componentCode").asText(null);
            String tabNameStr = tab.path("tabName").asText("");

            // 墓碑路由：报价侧取该组件墓碑；delByComp==null（核价侧/旧路径）→ deleted=null → 不过滤（spec §3.7 隔离）
            List<DeletedRowKeys.Tombstone> deleted = (delByComp == null) ? null : delByComp.get(cid);
            List<String> rkfNames = rowKeyFieldNamesOf(rkfByComp.get(cid));

            // B6 两阶段：同组件内可能存在二阶列（component_subtotal 引用本组件其它 is_subtotal 列）。
            // 第 1 次 calculate：得到正确的 cross_tab_ref 列行值（crossTabRows 已有兄弟组件行）；
            //   backfill 本组件各列小计到 componentSubtotals（含一阶列 "${code}#${col}" 列小计键）。
            // 第 2 次 calculate：component_subtotal 对本组件列小计键的引用此时已就绪 → 二阶列算对。
            ArrayNode pass1Results = formulaCalculator.calculate(
                tab.path("fields"), tab.path("formulas"), tab.path("formula_assignments"),
                rkfByComp.get(cid), baseRows, editRows,
                componentSubtotals, new java.util.HashMap<>(), new java.util.HashMap<>(),
                crossTabRows, deleted, rkfNames); // 带墓碑新重载：报价侧过滤删除行；核价侧 deleted=null → 不变
            List<Map<String, Object>> pass1Resolved = buildResolvedRows(
                tab, baseRows, editRows, pass1Results, rkfByComp.get(cid), deleted, rkfNames);
            // 存入 crossTabRows（双键，供后续兄弟组件 cross_tab_ref 查询）
            // 单位换算（cross_tab 物化点）：跨组件引用方读 canonical（按同行单位列换算）。
            // 仅换喂 crossTabRows 的副本——pass1Resolved 原值留给 backfill(各自换副本) + 落库 resolvedRows。
            List<Map<String, Object>> pass1CrossTab = convertRowsForCrossTab(tab.path("fields"), pass1Resolved);
            crossTabRows.put(cid, pass1CrossTab);
            if (code != null && !code.isBlank()) crossTabRows.put(code, pass1CrossTab);
            // 第 1 次 backfill：用 pass1 的 resolved 更新本组件一阶列列小计到 componentSubtotals，
            //   关键：把 "${cid}#${col}" / "${code}#${col}" / "${tabName}#${col}" 三类键都写入，
            //   供第 2 次 calculate 的 component_subtotal token 查到本组件一阶列的正确值。
            backfillSubtotalsFromResolved(tab.path("fields"), pass1Resolved, cid, code,
                tabNameStr, componentSubtotals);

            // 第 2 次 calculate（仅在组件含 is_subtotal 列时执行，否则结果与 pass1 相同可复用）：
            //   componentSubtotals 已有正确一阶列小计键 → 二阶列 component_subtotal token 能正确求值。
            boolean hasSubtotalCols = !formulaCalculator.findSubtotalFieldNames(tab.path("fields")).isEmpty();
            ArrayNode formulaResults;
            List<Map<String, Object>> resolved;
            if (hasSubtotalCols) {
                formulaResults = formulaCalculator.calculate(
                    tab.path("fields"), tab.path("formulas"), tab.path("formula_assignments"),
                    rkfByComp.get(cid), baseRows, editRows,
                    componentSubtotals, new java.util.HashMap<>(), new java.util.HashMap<>(),
                    crossTabRows, deleted, rkfNames);
                resolved = buildResolvedRows(
                    tab, baseRows, editRows, formulaResults, rkfByComp.get(cid), deleted, rkfNames);
                // 更新 crossTabRows 为第 2 次 resolved（二阶列已算对，兄弟组件引用此组件 cross_tab_ref 时应取最终值）
                List<Map<String, Object>> resolvedCrossTab = convertRowsForCrossTab(tab.path("fields"), resolved);
                crossTabRows.put(cid, resolvedCrossTab);
                if (code != null && !code.isBlank()) crossTabRows.put(code, resolvedCrossTab);
                // 第 2 次 backfill：更新二阶列本身的列小计
                backfillSubtotalsFromResolved(tab.path("fields"), resolved, cid, code,
                    tabNameStr, componentSubtotals);
            } else {
                // 无 is_subtotal 列 → 复用 pass1 结果，零额外开销
                formulaResults = pass1Results;
                resolved = pass1Resolved;
            }

            tabNodeById.put(cid, buildTabNode(tab, cid, baseRows, editRows, formulaResults,
                resolved, componentSubtotals));
        }

        // 3) SUBTOTAL tab：不参与拓扑序，按需补算（crossTabRows 可用，但其行不并入 crossTabRows）
        //    EXCEL tab 不在此补算：EXCEL 非普通公式 tab，不参与卡片公式计算（Excel 视图渲染走独立通道，Phase 3）。
        //    SUBTOTAL tab 不过滤：SUBTOTAL 聚合全组件，不属于单一 componentId 的 driver 行，
        //    其基础行来自 NORMAL 组件已算的 componentSubtotals（token 型），不是 driver expand 行，
        //    因此 delByComp 对 SUBTOTAL tab 无意义（传 null → 不过滤）。
        for (JsonNode tab : snapshot) {
            String cid = tab.path("componentId").asText("");
            if ("EXCEL".equals(tab.path("componentType").asText("NORMAL"))) continue;
            if (tabNodeById.containsKey(cid)) continue; // 已在拓扑序里算过
            ArrayNode baseRows = baseRowsByComp.getOrDefault(cid, MAPPER.createArrayNode());
            ArrayNode editRows = filteredEdit.getOrDefault(cid, MAPPER.createArrayNode());
            ArrayNode formulaResults = formulaCalculator.calculate(
                tab.path("fields"), tab.path("formulas"), tab.path("formula_assignments"),
                rkfByComp.get(cid), baseRows, editRows,
                componentSubtotals, new java.util.HashMap<>(), new java.util.HashMap<>(),
                crossTabRows);
            List<Map<String, Object>> resolved = buildResolvedRows(
                tab, baseRows, editRows, formulaResults, rkfByComp.get(cid));
            // SUBTOTAL tab 不回填列小计：其 is_subtotal 列由组件级聚合公式(component_subtotal token)决定，
            // 不能从 resolvedRows 重算覆盖（评审 #1）。列小计回填仅针对 NORMAL 组件的 cross_tab 列。
            // SUBTOTAL 行不并入 crossTabRows（不可被 cross_tab_ref 引用）
            tabNodeById.put(cid, buildTabNode(tab, cid, baseRows, editRows, formulaResults,
                resolved, componentSubtotals));
        }

        // 4) 按原 snapshot 顺序输出 tab（拓扑序不得改变 UI tab 顺序）
        for (JsonNode tab : snapshot) {
            ObjectNode tn = tabNodeById.get(tab.path("componentId").asText(""));
            if (tn != null) tabs.add(tn);
        }
        return root;
    }

    /** 组装单个 tabNode（baseRows/editRows/formulaResults/subtotal/resolvedRows）。 */
    private ObjectNode buildTabNode(JsonNode tab, String cid, ArrayNode baseRows, ArrayNode editRows,
            ArrayNode formulaResults, List<Map<String, Object>> resolvedRows,
            Map<String, Double> componentSubtotals) {
        ObjectNode tabNode = MAPPER.createObjectNode();
        tabNode.put("componentId", cid);
        tabNode.put("tabName", tab.path("tabName").asText(""));
        tabNode.set("baseRows", baseRows);
        tabNode.set("editRows", editRows); // 加产品/核价 → 空；草稿重刷 → 保留的编辑
        tabNode.set("formulaResults", formulaResults);

        // 值快照带上本 tab 小计（供 Excel CARD_FORMULA 的 __subtotal__ 引用，见 CardEffectiveRows）
        String code = tab.path("componentCode").asText(null);
        String tabName = tab.path("tabName").asText("");
        Double sub = componentSubtotals.get(cid);
        if (sub == null && code != null) sub = componentSubtotals.get(code);
        if (sub == null) sub = componentSubtotals.get(tabName);
        if (sub != null) tabNode.put("subtotal", sub);

        // Plan 2c：per-column 小计（供 [页签.列名] 引用）。从 componentSubtotals 的
        // `${cid|code|tabName}#${列名}` 键提取（Plan 2 Task 3 已写入）。
        ObjectNode byColNode = MAPPER.createObjectNode();
        for (String prefix : new String[]{ cid, code, tabName }) {
            if (prefix == null || prefix.isBlank()) continue;
            String keyPrefix = prefix + "#";
            for (Map.Entry<String, Double> en : componentSubtotals.entrySet()) {
                if (en.getKey().startsWith(keyPrefix) && en.getValue() != null) {
                    String col = en.getKey().substring(keyPrefix.length());
                    if (!byColNode.has(col)) byColNode.put(col, en.getValue());
                }
            }
        }
        if (byColNode.size() > 0) tabNode.set("subtotalByColumn", byColNode);

        // resolvedRows 输出（与 crossTabRows 同源，DRY）
        ArrayNode resolvedRowsNode = MAPPER.createArrayNode();
        for (Map<String, Object> r : resolvedRows) resolvedRowsNode.add(MAPPER.valueToTree(r));
        tabNode.set("resolvedRows", resolvedRowsNode);
        return tabNode;
    }

    /**
     * 逐行解析成"按字段名标量行" — 供 Excel CARD_FORMULA 按字段名取数 + cross_tab_ref 兄弟组件行查询。
     *
     * <p>通用引擎 {@link FormulaCalculator#resolveRowByFieldName}，配置驱动，零硬编码字段名。
     * 值 <b>RAW 类型</b>（文本保留文本、数字保留数字），故 cross_tab_ref 文本匹配键可命中。
     *
     * <p>零破坏：旧 5 参签名 delegate 到新 7 参，传 null,null = 不过滤。
     */
    private List<Map<String, Object>> buildResolvedRows(JsonNode tab, ArrayNode baseRows,
            ArrayNode editRows, ArrayNode formulaResults, JsonNode rowKeyFields) {
        return buildResolvedRows(tab, baseRows, editRows, formulaResults, rowKeyFields, null, null);
    }

    /**
     * buildResolvedRows 新重载（带墓碑过滤）。
     *
     * <p><b>头号不变量（AP-54）</b>：uniqKeys 由完整 baseRows 唯一化所得；过滤在唯一化之后，
     * 按墓碑双命中剔除整行。迭代下标 ri 仍走完整集（命中则 continue，绝不重排）。
     * fps 用同一份完整 baseRows 的 driverRow 计算，与 keepMask 传入的 effKeys 等长。
     *
     * @param deleted          墓碑列表（null 或空 → 不过滤，旧路径零变化）
     * @param rowKeyFieldNames rowKeyFields 节点解出的字段名列表（供 rowFingerprint 提取 driverRow 键值）
     */
    private List<Map<String, Object>> buildResolvedRows(JsonNode tab, ArrayNode baseRows,
            ArrayNode editRows, ArrayNode formulaResults, JsonNode rowKeyFields,
            List<DeletedRowKeys.Tombstone> deleted, List<String> rowKeyFieldNames) {
        JsonNode fieldsDef = tab.path("fields");
        Map<String, JsonNode> frByKey = new LinkedHashMap<>();
        for (JsonNode fr : formulaResults) frByKey.put(fr.path("rowKey").asText(""), fr.path("values"));
        Map<String, JsonNode> edByKey = new LinkedHashMap<>();
        for (JsonNode er : editRows) edByKey.put(er.path("rowKey").asText(""), er.path("values"));
        // 行键唯一化预扫（撞键→#序号），与 FormulaCalculator.computeRows / 前端 / editRows 存储键一致
        List<String> rawKeys = new ArrayList<>();
        int pk = 0;
        for (JsonNode br : baseRows) {
            String rk0 = formulaCalculator.computeRowKey(rowKeyFields, fieldsDef, br.path("driverRow"), br.path("basicDataValues"));
            rawKeys.add((rk0 != null && !rk0.isEmpty()) ? rk0 : String.valueOf(pk));
            pk++;
        }
        List<String> uniqKeys = FormulaCalculator.uniquifyRowKeys(rawKeys);

        // driver 默认行永久删除：先唯一化(上方)，再按墓碑双命中过滤；fps 用完整 baseRows 计算（守头号不变量）。
        // keep==null 表示不过滤（deleted 为 null/空 → 核价侧及旧路径零影响）。
        boolean[] keep = null;
        if (deleted != null && !deleted.isEmpty()) {
            List<String> fps = new ArrayList<>(baseRows.size());
            for (JsonNode br : baseRows) fps.add(DeletedRowKeys.rowFingerprint(rowKeyFieldNames, br.path("driverRow")));
            keep = DeletedRowKeys.keepMask(uniqKeys, fps, deleted);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        int ri = 0;
        for (JsonNode br : baseRows) {
            // driver 默认行永久删除：ri 仍随完整集递增（uniqKeys.get(ri) 对齐完整集），命中则 continue（不重排）
            if (keep != null && !keep[ri]) { ri++; continue; }

            JsonNode driverRow = br.path("driverRow");
            JsonNode basicDataValues = br.path("basicDataValues");
            String rowKey = uniqKeys.get(ri);
            JsonNode editValues = edByKey.get(rowKey);
            JsonNode formulaValues = frByKey.get(rowKey);
            Map<String, Object> resolvedRow = formulaCalculator.resolveRowByFieldName(
                fieldsDef, driverRow, basicDataValues, editValues, formulaValues);
            // P2-B 核价 Excel 树：透传 spine 节点身份，供 Excel 按 __nodeId 过滤本节点有效行
            JsonNode nodeId = br.path("__nodeId");
            if (!nodeId.isMissingNode() && !nodeId.isNull()) {
                if (!(resolvedRow instanceof java.util.LinkedHashMap))
                    resolvedRow = new java.util.LinkedHashMap<>(resolvedRow);
                resolvedRow.put("__nodeId", nodeId.asText());
            }
            out.add(resolvedRow);
            ri++;
        }
        return out;
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
            JsonNode fieldsDef = tab.path("fields");
            // 行键唯一化预扫（撞键→#序号），与 buildResolvedRows / computeRows / 前端一致
            List<String> rawNewKeys = new ArrayList<>();
            int idx = 0;
            for (JsonNode br : baseRows) {
                String rk = formulaCalculator.computeRowKey(rkf, fieldsDef,
                        br.path("driverRow"), br.path("basicDataValues"));
                rawNewKeys.add(rk != null && !rk.isEmpty() ? rk : String.valueOf(idx));
                idx++;
            }
            java.util.Set<String> newKeys = new java.util.HashSet<>(
                    FormulaCalculator.uniquifyRowKeys(rawNewKeys));

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

    /**
     * 核价 BOM 递归展开（P1）专用：以闭包 {@code spine} 全节点为<b>行主轴</b>展开各 driver 组件。
     *
     * <p>用户决策（2026-06-04）：每个核价组件都按 spine 的<b>全部节点</b>出行，即使该组件对某节点
     * 无业务数据也补一空行（仅系统列），保证每个 Tab 树结构完整、行对齐、不产生孤儿节点。
     *
     * <p>流程：每个组件对 {@code closure.partSet} 一次多值展开（{@link ComponentDriverService#expandForPartSet}），
     * 得到 {@code Map<partNo, 业务行>}；再按 spine 顺序逐节点左关联：
     * <ul>
     *   <li>节点 hf_part_no 有业务行 → 每条业务行各出一行（DAG 重复子件 → 同业务数据复制到各 occurrence）；</li>
     *   <li>节点无业务行 → 补一空行（driverRow 仅 hf_part_no，业务列空）。</li>
     * </ul>
     * 每行并入系统列 {@code __nodeId/__parentId/__lvl/__hfPartNo/__parentNo/__bomVersion/__isCycle}
     * （{@code __} 前缀命名空间，杜绝与业务字段碰撞；不进 component.fields，绕开 AP-44）。
     *
     * @param closure 根料号闭包；{@code null} → 委托给无闭包的原方法（报价侧/兜底）。
     */
    private Map<String, ArrayNode> expandTemplateDriverBaseRows(UUID templateId, QuotationLineItem li,
                                                                UUID customerId, UUID quotationId,
                                                                BomClosureResult closure) {
        if (closure == null) {
            return expandTemplateDriverBaseRows(templateId, li, customerId, quotationId);
        }
        Map<String, ArrayNode> baseRowsByComp = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> driverComps = em.createNativeQuery(
            "SELECT DISTINCT c.id, c.bom_recursive_expand FROM template_component tc " +
            "JOIN component c ON c.id = tc.component_id " +
            "WHERE tc.template_id = :tid AND c.data_driver_path IS NOT NULL AND c.data_driver_path <> ''")
            .setParameter("tid", templateId)
            .getResultList();

        String compositeType = li.compositeType;
        String partNo = li.productPartNoSnapshot;   // 未勾选(false)分支：单料号普通 expand 用
        QuotationIdContext.set(quotationId);
        com.cpq.datasource.sqlview.SpineKeysContext.set(
            com.cpq.datasource.sqlview.SpineKeysContext.fromClosure(closure));
        try {
            for (Object[] dc : driverComps) {
                if (dc == null || dc[0] == null) continue;
                String cidStr = dc[0].toString();
                boolean recursive = !(dc[1] instanceof Boolean) || (Boolean) dc[1]; // 默认/非布尔 → true
                UUID compId = UUID.fromString(cidStr);
                try {
                    if (recursive) {
                        // 勾选：BOM 闭包递归 → spine 全节点行 + __* 系统列
                        Map<String, ExpandDriverResponse> byPart = componentDriverService.expandForPartSet(
                            compId, customerId, closure.partSet, li.id, compositeType);
                        baseRowsByComp.put(cidStr, buildSpineBaseRows(closure, byPart));
                    } else {
                        // 未勾选：按根料号单料号普通展开(无系统列)，等同报价侧取数
                        ExpandDriverResponse exp = componentDriverService.expand(
                            compId, customerId, partNo, null, null, null, li.id, compositeType);
                        List<ExpandDriverResponse.Row> rows =
                            (exp != null && exp.rows != null) ? exp.rows : new ArrayList<>();
                        baseRowsByComp.put(cidStr, buildBaseRowsFromRows(rows));
                    }
                } catch (Exception e) {
                    LOG.warnf("[card-snapshot] expand(recursive=%b) comp=%s li=%s: %s",
                              recursive, compId, li.id, e.getMessage());
                    baseRowsByComp.put(cidStr, MAPPER.createArrayNode());
                }
            }
        } finally {
            com.cpq.datasource.sqlview.SpineKeysContext.clear();
            QuotationIdContext.clear();
        }
        return baseRowsByComp;
    }

    /**
     * 以 spine 全节点为行主轴构建 baseRows（缺数据补空行）。
     * 行数 = Σ(每节点 max(1, 该节点业务行数))，确定性（满足 AP-51 刷新稳定）。
     */
    private ArrayNode buildSpineBaseRows(BomClosureResult closure, Map<String, ExpandDriverResponse> byPart) {
        ArrayNode out = MAPPER.createArrayNode();
        if (closure.spine == null) return out;
        for (BomClosureResult.SpineNode node : closure.spine) {
            ExpandDriverResponse resp = (node.hfPartNo == null) ? null : byPart.get(node.hfPartNo);
            List<ExpandDriverResponse.Row> bizRows =
                (resp != null && resp.rows != null) ? resp.rows : java.util.Collections.emptyList();
            if (bizRows.isEmpty()) {
                out.add(spineRowNode(node, null));                 // 缺数据补空行
            } else {
                for (ExpandDriverResponse.Row r : bizRows) out.add(spineRowNode(node, r));
            }
        }
        return out;
    }

    /** 组装单行：业务行(或空行) + 注入系统列 {@code __*}（命名空间前缀防碰撞）。 */
    private ObjectNode spineRowNode(BomClosureResult.SpineNode node, ExpandDriverResponse.Row bizRow) {
        ObjectNode rowNode;
        if (bizRow != null) {
            rowNode = rowToNode(bizRow);
        } else {
            rowNode = MAPPER.createObjectNode();
            ObjectNode dr = rowNode.putObject("driverRow");
            if (node.hfPartNo != null) { dr.put("hf_part_no", node.hfPartNo); dr.put("part_no", node.hfPartNo); }
            rowNode.putObject("basicDataValues");
        }
        rowNode.put("__nodeId", node.nodeId == null ? "" : node.nodeId);
        if (node.parentId == null) rowNode.putNull("__parentId"); else rowNode.put("__parentId", node.parentId);
        rowNode.put("__lvl", node.lvl);
        if (node.hfPartNo == null) rowNode.putNull("__hfPartNo"); else rowNode.put("__hfPartNo", node.hfPartNo);
        if (node.parentNo == null) rowNode.putNull("__parentNo"); else rowNode.put("__parentNo", node.parentNo);
        if (node.bomVersion == null) rowNode.putNull("__bomVersion"); else rowNode.put("__bomVersion", node.bomVersion);
        rowNode.put("__isCycle", node.isCycle);
        return rowNode;
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
     * 草稿态重刷报价侧两份值（设计 §5）。委托 {@link #refreshQuoteCardValues(QuotationLineItem, boolean)}，
     * {@code force=false}（默认冻结模式：已首次 bake 的行直接 no-op，防误调覆盖冻结值）。
     */
    @Transactional
    public void refreshQuoteCardValues(QuotationLineItem li) {
        refreshQuoteCardValues(li, false);
    }

    /**
     * 草稿态重刷报价侧两份值（设计 §5，带 force 参数）：
     * <ol>
     *   <li>短路判断（2026-06-18 草稿默认冻结）：{@code force=false} 且 {@code cardSnapshotAt!=null}
     *       → 直接 no-op，防草稿打开/误调覆盖已冻结的报价值；{@code force=true}（显式刷新/删恢复行）才继续。</li>
     *   <li>重查基础值：按报价模板 driver 组件 expand 种子 → 新 baseRows（实时最新数据）。</li>
     *   <li>对齐保留编辑：旧 {@code quote_card_values} 的 editRows 按 rowKey 叠加到新 baseRows；新数据无该 key 丢弃。</li>
     *   <li>重算公式：基于新 baseRows + 保留 editRows → 新 formulaResults。</li>
     *   <li>重算报价 Excel → 回写 {@code quote_excel_values}。</li>
     *   <li>更新 {@code quote_values_at}。</li>
     * </ol>
     * <p><b>核价两列物理不参与本次 UPDATE</b>（结构性隔离，核价永久冻死）。
     * <p>降级：任一步失败 → 保留上一次报价值快照，不抛、不阻断打开（与加产品同等降级）。
     *
     * @param li    报价产品行（detached 或 managed 均可）
     * @param force {@code true} = 强制重算（显式刷新 / 删除恢复行）；{@code false} = 已 bake 行 no-op
     */
    @Transactional
    public void refreshQuoteCardValues(QuotationLineItem li, boolean force) {
        if (li == null || li.id == null) return;
        try {
            QuotationLineItem managed = QuotationLineItem.findById(li.id);
            if (managed == null) return;

            // 草稿默认冻结（2026-06-18）：已首次 bake 的行非 force 调用直接 no-op，
            // 防 on-open / 误调覆盖冻结值。force=true（显式刷新/删恢复行）才重算。
            if (!force && managed.cardSnapshotAt != null) return;

            Quotation q = Quotation.findById(managed.quotationId);
            if (q == null || q.customerTemplateId == null) return;

            JsonNode snapshot = loadComponentsSnapshot(q.customerTemplateId);
            if (snapshot == null) return;

            // 1. 重查基础值（报价模板 driver 组件 expand 种子）
            Map<String, ArrayNode> baseRowsByComp =
                expandTemplateDriverBaseRows(q.customerTemplateId, managed, q.customerId, q.id);  // 报价侧:无闭包(closure=null)

            // 2. 旧 editRows（按 rowKey 对齐保留）
            Map<String, ArrayNode> oldEdits = extractEditRowsByComp(managed.quoteCardValues);

            // 2.5 (2026-06-02 修复 报价卡片 FORMULA 单元格读陈旧 formulaResults=0): 把 row_data
            //     (autosave 持久化的当前 INPUT 值, 与前端渲染 comp.rows 同源) 按 rowKey 合并进 editRows，
            //     让重算的 formulaResults 用当前 单价 等输入。否则 INPUT 仅在 editQuoteCardValue 写过的行
            //     进 editRows，autosave 写 row_data 但 editQuoteCardValue 漏的行 formulaResults 缺输入算 0，
            //     单元格(快照优先)读 0 而列小计(前端实时)正确 → 不一致。详见 RECORD 2026-06-02。
            Map<String, ArrayNode> mergedEdits =
                mergeRowDataInputsIntoEdits(snapshot, baseRowsByComp, oldEdits, managed.id);

            // 2.6. 查各组件 deleted_row_keys 墓碑（报价侧需过滤永久删除的 driver 默认行）
            Map<String, List<DeletedRowKeys.Tombstone>> delByComp = loadTombstonesByComp(managed.id);

            // 3. 组装新 quote_card_values（保留编辑 + 重算 formulaResults；报价侧传真实墓碑）
            ObjectNode root = assembleTabsWithFormulaResults(snapshot, baseRowsByComp, mergedEdits, null, delByComp);
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

            // 查各组件 deleted_row_keys 墓碑（报价侧重算时仍需过滤永久删除的 driver 默认行）
            Map<String, List<DeletedRowKeys.Tombstone>> delByComp = loadTombstonesByComp(li.id);

            // 重算（baseRows 不变 + 新 editRows；报价侧传真实墓碑，确保删除行不出现在重算结果中）
            ObjectNode root = assembleTabsWithFormulaResults(snapshot, baseRowsByComp, editRowsByComp, null, delByComp);
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

    /**
     * 从 rowKeyFields 节点提取字段名列表，供 {@link DeletedRowKeys#rowFingerprint} 使用。
     *
     * <p><b>提取口径</b>（与 computeRowKey 对齐）：rowKeyFields 是 JSON 数组，每项为字符串，
     * 直接取 {@code asText()}（= 字段名）。例如 {@code ["material_no","spec"]} → {@code ["material_no","spec"]}。
     *
     * <p><b>Task8 前端对齐说明</b>：前端 deletedRows.ts 计算 fp 时，需用同一组字段名（即 rowKeyFields 数组的
     * 每项字符串值）作为 {@code rowKeyFieldNames}，与此方法提取规则完全一致。
     *
     * @param rowKeyFieldsNode rowKeyFields 节点（可为 null、非数组 → 返回空列表）
     * @return 字段名列表，null 节点时返回空列表
     */
    private List<String> rowKeyFieldNamesOf(JsonNode rowKeyFieldsNode) {
        if (rowKeyFieldsNode == null || !rowKeyFieldsNode.isArray()) return List.of();
        List<String> names = new ArrayList<>(rowKeyFieldsNode.size());
        for (JsonNode n : rowKeyFieldsNode) {
            String name = n.asText("");
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    /**
     * 按 lineItemId 查 quotation_line_component_data.deleted_row_keys，
     * 返回 componentId → 墓碑列表的映射（供报价侧过滤永久删除的 driver 默认行）。
     *
     * <p>一次查全行，O(1) 往返，无 N+1。deleted_row_keys 为 null/空白 → 该组件对应空列表（不过滤）。
     */
    private Map<String, List<DeletedRowKeys.Tombstone>> loadTombstonesByComp(UUID lineItemId) {
        Map<String, List<DeletedRowKeys.Tombstone>> result = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                "SELECT component_id, deleted_row_keys FROM quotation_line_component_data " +
                "WHERE line_item_id = :lid")
                .setParameter("lid", lineItemId)
                .getResultList();
            for (Object[] r : rows) {
                if (r[0] != null) {
                    result.put(r[0].toString(),
                        DeletedRowKeys.parse(r[1] == null ? null : r[1].toString()));
                }
            }
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] loadTombstonesByComp failed lid=%s: %s", lineItemId, e.getMessage());
        }
        return result;
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

                JsonNode fieldsDef = tab.path("fields");
                // 行键唯一化预扫（撞键→#序号），对齐 computeRows / buildResolvedRows / 前端；
                // 须按全部 baseRows 定序号，再按下标取（保证与逐行求值的 #序号一致）。
                List<String> rawKeys = new ArrayList<>();
                for (int i = 0; i < baseRows.size(); i++) {
                    JsonNode br = baseRows.get(i);
                    String rk = formulaCalculator.computeRowKey(rkf, fieldsDef,
                            br.path("driverRow"), br.path("basicDataValues"));
                    rawKeys.add((rk != null && !rk.isEmpty()) ? rk : String.valueOf(i));
                }
                List<String> uniqKeys = FormulaCalculator.uniquifyRowKeys(rawKeys);

                int n = Math.min(baseRows.size(), rowData.size());
                for (int i = 0; i < n; i++) {
                    String rowKey = uniqKeys.get(i);
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

    // =========================================================================
    // dryRunTokenRows — token 引擎试算（草稿公式 + 草稿行键双注入，复用渲染装配）
    // =========================================================================

    /**
     * 🔴 命门0：走 token 引擎、复用真实渲染装配（{@link #assembleTabsWithFormulaResults}）的「试算预览」，
     * 使「试算逐行值 == 渲染逐行值」。旧 EXCEL 试算链（{@link ExcelViewService#dryRunTabFormula}）完全保留不动。
     *
     * <p><b>草稿双注入（v6-N/v6-O）</b>：
     * <ol>
     *   <li>读 lineItem → snapshot = {@link #loadComponentsSnapshot}(li.templateId)。</li>
     *   <li><b>草稿公式注入</b>：深拷贝 snapshot；按 componentId 定位宿主 tab（同 cid 多实例本期取首个命中，
     *       多实例 follow-up 应按 sortOrder — v6-O）；宿主 tab 的 {@code formulas} 替换为单条草稿公式
     *       {@code [{name:__dryrun__, fieldName:__dryrun__, expression: draftTokens}]} + {@code fields} 追加
     *       {@code {name:__dryrun__, field_type:FORMULA}}（{@code collectFormulaFields} 从 fields 收集 FORMULA 字段，
     *       故须同步追加字段定义）；兄弟 source 组件 formulas 保持已存版本。</li>
     *   <li><b>baseRows 展开 + editRows 合并</b>：与 {@link #refreshQuoteCardValues} 同源
     *       （{@link #expandTemplateDriverBaseRows} + {@link #extractEditRowsByComp} + {@link #mergeRowDataInputsIntoEdits}）。</li>
     *   <li><b>草稿行键覆盖（v6-N）</b>：把宿主 cid 的行键覆盖为 {@code draftSelfRowKeyFields}，通过
     *       {@link #assembleTabsWithFormulaResults} 四参重载的 {@code rkfOverride} 实现。</li>
     *   <li>取宿主 tab formulaResults 逐行，提取 {@code values["__dryrun__"]} → {@code [{rowKey, value}]}。</li>
     * </ol>
     *
     * @param hostComponentId      宿主组件 id（被编辑公式所属组件）
     * @param lineItemId           样本卡片 lineItemId
     * @param draftTokens          草稿公式 token 数组（camelCase，cross_tab_ref source/target/agg/match）
     * @param draftSelfRowKeyFields 草稿自身行键字段名列表（覆盖宿主持久化行键）；null/空 → 不覆盖（用持久化行键）
     * @return 宿主逐行 {@code [{rowKey, value}]}（value 为 token 引擎求值结果，Number/String/null）
     */
    public List<Map<String, Object>> dryRunTokenRows(String hostComponentId, UUID lineItemId,
                                                      JsonNode draftTokens, List<String> draftSelfRowKeyFields) {
        if (hostComponentId == null || lineItemId == null) {
            throw new IllegalArgumentException("hostComponentId / lineItemId 必填");
        }
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        if (li == null) throw new IllegalStateException("lineItem 不存在: " + lineItemId);
        Quotation q = Quotation.findById(li.quotationId);
        if (q == null || q.customerTemplateId == null) {
            throw new IllegalStateException("quotation / customerTemplateId 缺失 li=" + lineItemId);
        }

        JsonNode snapshot = loadComponentsSnapshot(q.customerTemplateId);
        if (snapshot == null || !snapshot.isArray()) {
            throw new IllegalStateException("模板 components_snapshot 缺失 tid=" + q.customerTemplateId);
        }

        // 1. baseRows 展开 + editRows 合并（与 refreshQuoteCardValues 同源）
        Map<String, ArrayNode> baseRowsByComp =
            expandTemplateDriverBaseRows(q.customerTemplateId, li, q.customerId, q.id);
        Map<String, ArrayNode> oldEdits = extractEditRowsByComp(li.quoteCardValues);
        Map<String, ArrayNode> mergedEdits =
            mergeRowDataInputsIntoEdits(snapshot, baseRowsByComp, oldEdits, li.id);

        return dryRunTokenRowsCore(snapshot, hostComponentId, draftTokens, draftSelfRowKeyFields,
            baseRowsByComp, mergedEdits);
    }

    /**
     * 试算核心（与 {@link #dryRunTokenRows} 共用；测试可注入 baseRows/editRows 做确定性命门0 对拍）。
     * 不读 DB、纯装配，故与 {@link #refreshQuoteCardValues} 喂同样 baseRows 时逐行可对拍。
     */
    List<Map<String, Object>> dryRunTokenRowsCore(JsonNode snapshot, String hostComponentId,
                                                  JsonNode draftTokens, List<String> draftSelfRowKeyFields,
                                                  Map<String, ArrayNode> baseRowsByComp,
                                                  Map<String, ArrayNode> editRowsByComp) {
        // 2. 草稿公式注入（深拷贝，不污染 loadComponentsSnapshot 返回的节点）
        JsonNode draftSnapshot = injectDraftFormula(snapshot, hostComponentId, draftTokens);

        // 3. 草稿行键覆盖（v6-N）：宿主 cid 行键 → draftSelfRowKeyFields（空 → 不覆盖）
        Map<String, JsonNode> rkfOverride = null;
        if (draftSelfRowKeyFields != null && !draftSelfRowKeyFields.isEmpty()) {
            ArrayNode rkf = MAPPER.createArrayNode();
            for (String f : draftSelfRowKeyFields) rkf.add(f);
            rkfOverride = new LinkedHashMap<>();
            rkfOverride.put(hostComponentId, rkf);
        }

        // 4. 复用真实渲染装配（token 引擎）
        ObjectNode assembled = assembleTabsWithFormulaResults(
            draftSnapshot, baseRowsByComp, editRowsByComp, rkfOverride);

        // 5. 取宿主 tab formulaResults 逐行 __dryrun__
        return extractHostDryRunRows(assembled, hostComponentId);
    }

    /**
     * 深拷贝 snapshot 并把宿主 tab 注入单条草稿公式 {@code __dryrun__}（formulas + fields 同步追加 FORMULA 字段）。
     * <p>同 cid 多实例本期取首个命中（v6-O follow-up：应按 sortOrder 精确匹配，参见 AP-40）。
     * 兄弟 source 组件 formulas 保持已存版本不动。
     */
    private JsonNode injectDraftFormula(JsonNode snapshot, String hostComponentId, JsonNode draftTokens) {
        ArrayNode copy = (ArrayNode) snapshot.deepCopy();
        boolean injected = false;
        for (JsonNode tabNode : copy) {
            if (!tabNode.isObject()) continue;
            if (injected) break; // 同 cid 多实例：仅首个命中（v6-O follow-up: sortOrder）
            if (!hostComponentId.equals(tabNode.path("componentId").asText(""))) continue;
            ObjectNode tab = (ObjectNode) tabNode;

            // formulas → 单条草稿公式
            ArrayNode formulas = MAPPER.createArrayNode();
            ObjectNode fm = MAPPER.createObjectNode();
            fm.put("name", DRYRUN_FIELD);
            fm.put("fieldName", DRYRUN_FIELD);
            fm.set("expression", draftTokens != null ? draftTokens.deepCopy() : MAPPER.createArrayNode());
            formulas.add(fm);
            tab.set("formulas", formulas);
            // 模板级 formula_assignments 会按字段下标绑公式名 → 清空，避免 __dryrun__ 被错绑/漏绑
            tab.putArray("formula_assignments");

            // fields 追加 FORMULA 字段定义（collectFormulaFields 从 fields 收集 FORMULA 字段）
            JsonNode fieldsNode = tab.path("fields");
            ArrayNode fields = fieldsNode.isArray() ? (ArrayNode) fieldsNode : tab.putArray("fields");
            // 去重：若已存在同名字段（理论上不会），先移除
            for (int i = fields.size() - 1; i >= 0; i--) {
                if (DRYRUN_FIELD.equals(fields.get(i).path("name").asText(""))) fields.remove(i);
            }
            ObjectNode df = MAPPER.createObjectNode();
            df.put("name", DRYRUN_FIELD);
            df.put("field_type", "FORMULA");
            fields.add(df);
            injected = true;
        }
        if (!injected) {
            throw new IllegalStateException("宿主组件不在模板 snapshot 中: " + hostComponentId);
        }
        return copy;
    }

    /** 从装配结果取宿主 tab 的 formulaResults 逐行，提取 {@code values["__dryrun__"]} → [{rowKey, value}]。 */
    private List<Map<String, Object>> extractHostDryRunRows(ObjectNode assembled, String hostComponentId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode tab : assembled.path("tabs")) {
            if (!hostComponentId.equals(tab.path("componentId").asText(""))) continue;
            for (JsonNode fr : tab.path("formulaResults")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rowKey", fr.path("rowKey").asText(""));
                JsonNode v = fr.path("values").path(DRYRUN_FIELD);
                row.put("value", jsonToScalar(v));
                out.add(row);
            }
            break;
        }
        return out;
    }

    /** JsonNode → 标量（Number/String/Boolean/null），供试算行 value 输出。 */
    private static Object jsonToScalar(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return null;
        if (v.isNumber()) return v.numberValue();
        if (v.isBoolean()) return v.booleanValue();
        return v.asText();
    }

    /** 草稿试算公式/字段固定名（命名空间前缀，杜绝与业务字段碰撞）。 */
    private static final String DRYRUN_FIELD = "__dryrun__";

    /**
     * PASS2 回填：从 PASS2 正确算出的 resolvedRows 重算 is_subtotal 列的列小计，
     * 覆盖 componentSubtotals 中对应的 {@code ${key}#${列名}} 和总小计 {@code ${key}} 键。
     *
     * <p>解决根因：PASS1（:708-733）调用 computeTabSubtotalsByColumn 时 crossTabRows 为空，
     * cross_tab_ref 列只能算出 0；PASS2（:764-803）有完整 crossTabRows 后算出正确 resolved，
     * 但 componentSubtotals 里的 per-column 值未更新，导致 buildTabNode 读到 0。
     *
     * <p>实现与前端 {@code subtotalsFromResolvedRows} 口径一致：对 is_subtotal 列的各行值
     * 做数值累加（字符串能解析为数字则累加，否则跳过），结果覆盖写入 componentSubtotals。
     *
     * @param fields           tab 字段定义数组（含 is_subtotal / isSubtotal 标记）
     * @param resolvedRows     PASS2 逐行解析后的行（按字段名键标量值）
     * @param cid              componentId
     * @param code             componentCode（可为 null）
     * @param tabName          tabName
     * @param componentSubtotals 可变 map，直接覆盖写入
     */
    private void backfillSubtotalsFromResolved(
            JsonNode fields,
            List<Map<String, Object>> resolvedRows,
            String cid,
            String code,
            String tabName,
            Map<String, Double> componentSubtotals) {
        List<String> subtotalFields = formulaCalculator.findSubtotalFieldNames(fields);
        if (subtotalFields.isEmpty()) return;

        // 单位换算（物化点5）：求和用换算后行（canonical）；resolvedRows 本身（落库）保持原值不动。
        List<Map<String, Object>> rowsForSum = new ArrayList<>(resolvedRows.size());
        for (Map<String, Object> r : resolvedRows) {
            rowsForSum.add(com.cpq.engine.unit.UnitConversion.convertObjectRow(fields, r));
        }

        double totalSum = 0.0;
        for (String col : subtotalFields) {
            double colSum = 0.0;
            for (Map<String, Object> row : rowsForSum) {
                Object val = row.get(col);
                if (val == null) continue;
                double d;
                if (val instanceof Number n) {
                    d = n.doubleValue();
                } else {
                    try { d = Double.parseDouble(val.toString()); } catch (NumberFormatException ignore) { continue; }
                }
                colSum += d;
            }
            java.math.BigDecimal rounded =
                java.math.BigDecimal.valueOf(colSum).setScale(4, java.math.RoundingMode.HALF_UP);
            double roundedDouble = rounded.doubleValue();
            // 写 per-column 键（三种 key 形式，与 PASS1 写法对称）
            if (!cid.isBlank()) componentSubtotals.put(cid + "#" + col, roundedDouble);
            if (code != null && !code.isBlank()) componentSubtotals.put(code + "#" + col, roundedDouble);
            componentSubtotals.put(tabName + "#" + col, roundedDouble);
            totalSum += roundedDouble;
        }
        // 回填总小计（= 所有 is_subtotal 列之和，与 PASS1 computeTabSubtotalsByColumn 逻辑对称）
        double roundedTotal = java.math.BigDecimal.valueOf(totalSum)
            .setScale(4, java.math.RoundingMode.HALF_UP).doubleValue();
        if (!cid.isBlank()) componentSubtotals.put(cid, roundedTotal);
        if (code != null && !code.isBlank()) componentSubtotals.put(code, roundedTotal);
        componentSubtotals.put(tabName, roundedTotal);
    }

    /**
     * 单位换算（cross_tab 物化点）：把一组 resolved 行换算成 canonical 副本喂 crossTabRows，原行不变。
     * 配 unit_source_field 的输入列按同行单位归一到 KG/PCS；未配列原样。与前端 buildCrossTabRows putCrossTab 对称。
     */
    private List<Map<String, Object>> convertRowsForCrossTab(JsonNode fields, List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(com.cpq.engine.unit.UnitConversion.convertObjectRow(fields, r));
        }
        return out;
    }
}
