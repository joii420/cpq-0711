package com.cpq.quotation.service;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.common.exception.BusinessException;
import com.cpq.configure.service.ConfigureSnapshotService;
import com.cpq.datasource.sqlview.QuotePendingScope;
import com.cpq.datasource.sqlview.TemplateRenderScope;
import com.cpq.formula.dataloader.QuotationIdContext;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.entity.QuotationViewStructure;
import com.cpq.quotation.rowkey.DeletedRowKeys;
import com.cpq.template.exception.TemplateNotFrozenException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
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
    static final ObjectMapper MAPPER = com.cpq.common.DecimalJacksonCustomizer.newMapper();

    private enum RefreshOutcome {
        UPDATED,
        NO_OP
    }

    private enum LineFailurePolicy {
        BEST_EFFORT,
        PROPAGATE_UNRECOVERABLE
    }

    private enum DraftRefreshPolicy {
        HTTP_ATOMIC,
        MIGRATION
    }

    private record ExistingQuoteCardState(
            Map<String, ArrayNode> baseRowsByComp,
            Map<String, ArrayNode> editRowsByComp) {
    }

    private static final class HistoricalCardValuesException extends RuntimeException {
        private HistoricalCardValuesException(String message, Throwable cause) {
            super(message, cause);
        }

        private HistoricalCardValuesException(String message) {
            super(message);
        }
    }

    /** 卡片值 build 确定性失败时落库的非 NULL 哨兵（防前端「全有或全无」gate 把整侧打回实时风暴）。 */
    public static final String CARD_VALUE_FAILED_SENTINEL = "{\"tabs\":[],\"__cardValueFailed\":true}";

    private static final String QUOTATION_CALCULATION_LOCK_KEY_SQL =
        "('x'||substr(md5(:q),1,16))::bit(64)::bigint";

    private static String orSentinel(String built) {
        return (built == null || built.isBlank()) ? CARD_VALUE_FAILED_SENTINEL : built;
    }

    /**
     * BL-0030:带错误原文的失败哨兵。核价树整单渲染失败(递归 SQL / 页签 $view 报错、无生效配置等)时落库,
     * 让前端显式提示「核价渲染失败: 原文」而非无限「加载中…」或静默空白。{@code errMsg} 为空回退通用哨兵。
     */
    private static String failedSentinelWithError(String errMsg) {
        if (errMsg == null || errMsg.isBlank()) return CARD_VALUE_FAILED_SENTINEL;
        try {
            ObjectNode n = MAPPER.createObjectNode();
            n.putArray("tabs");
            n.put("__cardValueFailed", true);
            n.put("__errorMsg", errMsg);
            return MAPPER.writeValueAsString(n);
        } catch (Exception e) {
            return CARD_VALUE_FAILED_SENTINEL;
        }
    }

    @Inject
    EntityManager em;

    @Inject
    ComponentDriverService componentDriverService;

    @Inject
    ExcelViewService excelViewService;

    @Inject
    FormulaCalculator formulaCalculator;

    /** 失焦同步：用真实公式引擎物化被编辑组件的 row_data（与配置态同款 materializer）。 */
    @Inject
    RowDataMaterializer rowDataMaterializer;

    /** 失焦同步：复用 ConfigureSnapshotService.writeRowData（REQUIRES_NEW UPSERT）持久化物化结果。 */
    @Inject
    ConfigureSnapshotService configureSnapshotService;

    /** 核价树渲染重构（Task 3.1）：整单一次递归+分组，替代旧引擎逐 li closure+expand（仅含树页签模板走此路）。 */
    @Inject
    BomTreeRenderService bomTreeRenderService;

    /** 自注入：触发 REQUIRES_NEW 代理拦截器 */
    @Inject
    CardSnapshotService self;

    @Inject
    CardSnapshotConcurrencyProbe concurrencyProbe;

    /**
     * task-0806 B8：渲染期「这个模板有哪些页签/driver 组件/树页签」的问题统一改问它——
     * 不再各自手写 {@code JOIN component} 直读活表。批量接口，SQL 条数与页签数无关。
     */
    @Inject
    com.cpq.template.service.PublishedTemplateReader publishedTemplateReader;

    /**
     * repair-260829 B-1b：③步进入计算前的第二层防线（第一层是 B-1 的落库前产物自检，见
     * {@link #isEarlySkeletonRender}）。{@link #isEarlySkeletonRender} 只能兜住"①步写了一部分"
     * 的时序窗口（此时 comp_data 已有部分行、条件②能命中）；兜不住"①步一行都还没写"的窗口——
     * 此时 comp_data 整体为空，条件②天然为 false，判据不拦，会把全空骨架值当"合法空结果"放行
     * 落库。本字段用于在 {@link #ensureCardValuesDetailed} 真正开始计算前先问一句"这个报价单
     * 的建单后置物化是否还在飞"，命中则直接不算，把这个窗口也堵上。
     */
    @Inject
    com.cpq.basicdata.v6.service.MaterializeRegistry materializeRegistry;

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
            if (!"DRAFT".equals(q.status)) return;

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
     *
     * <p>task-0729 §1.10a（2026-08-06）：可见性从 private 提升为 public，供
     * {@code ComparisonViewService#getMetaByTemplates}（比对列配置屏的页签目录 meta）复用
     * ——<b>只提可见性，不新写一份</b>。它是「模板 {@code components_snapshot}（<b>数组</b>、
     * snake_case {@code field_type/is_subtotal}、无 {@code label}）→ 卡片结构（<b>对象</b>
     * {@code {tabs:[…]}}、camelCase {@code fieldType/isSubtotal}、{@code label} 由 name 派生）」
     * 的<b>唯一适配器</b>；比对列 meta 需要的正是后一种形状，另抄一份必然与冻结结构漂移。
     *
     * <p>🔒 本方法<b>纯只读</b>（只 SELECT template 与 component），落库由调用方
     * {@link #upsertStructure} 负责——比对列 meta 那条路径刻意<b>不</b>落库，见该方法注释。
     */
    public String buildCardStructure(UUID templateId, String templateKind) {
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

            // task-0806 B8（bonus fix）：rowKeyFields / element*Field 此前在下方 per-tab 循环里
            // 各自两次直读活 component 表（N+1，且是「渲染期活穿透」的一种未被 backtask.md 10 点
            // 清单收录的形态——这两个字段此前根本不进 components_snapshot jsonb，只能靠活读兜底）。
            // 改为整单一次经 PublishedTemplateReader 取冻结快照，按 componentId 建 map 供下方内存查找。
            Map<String, com.cpq.template.entity.TemplateComponentSnapshot> tcsByComponentId = new HashMap<>();
            for (com.cpq.template.entity.TemplateComponentSnapshot s : publishedTemplateReader.allTabsOf(templateId)) {
                tcsByComponentId.putIfAbsent(s.componentId.toString(), s);
            }

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

                // rowKeyFields / element*Field：task-0806 B8 起改从冻结快照（PublishedTemplateReader
                // 整单一次预取的 tcsByComponentId）取值，不再逐 tab 直读活 component 表（AP-39 补充：行键冻进结构）。
                com.cpq.template.entity.TemplateComponentSnapshot tcs =
                        (componentId != null && !componentId.isBlank()) ? tcsByComponentId.get(componentId) : null;
                if (tcs != null && tcs.rowKeyFields != null && !tcs.rowKeyFields.isBlank()) {
                    tabNode.set("rowKeyFields", MAPPER.readTree(tcs.rowKeyFields));
                } else {
                    tabNode.putArray("rowKeyFields");
                }
                // task-0729 B10（标记透传第2条）：元素编码列/元素单价列/货币列冻进结构，与
                // rowKeyFields 同一模式（组件级角色字段）。前端手动行"请先填写元素"占位分支
                // 要靠这两个字段名判断当前手动行是否命中价格承载列。
                if (tcs != null) {
                    if (tcs.elementCodeField != null) tabNode.put("elementCodeField", tcs.elementCodeField);
                    if (tcs.elementPriceField != null) tabNode.put("elementPriceField", tcs.elementPriceField);
                    if (tcs.elementCurrencyField != null) tabNode.put("elementCurrencyField", tcs.elementCurrencyField);
                }

                // formula_assignments：公式指派（列名 → 公式名）。buildCardValues 走冻结结构算值时
                // FormulaCalculator.calculate 的第 3 参直接读它，缺了则该 tab 所有指派公式失效（列恒空）。
                // 键名保持 snake_case 与模板 snapshot 一致——FormulaCalculator 按此名读取，不做双读。
                JsonNode fa = tab.path("formula_assignments");
                if (fa != null && fa.isObject()) {
                    tabNode.set("formula_assignments", fa.deepCopy());
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
                    // BL-0098（AP-44 完备性）：公式稳定 id 必须一并搬运 —— 本方法是**白名单逐键映射**，
                    // 漏搬则此后新建的每一张报价单冻结结构都拿不到 id，求值永久退回按名字/位置猜，
                    // 本次修复对新单形同虚设（且静默：不报错，只是绑定又变回可漂移的）。
                    if (!f.path("formula_id").isMissingNode() && !f.path("formula_id").isNull()) {
                        fieldNode.put("formulaId", f.path("formula_id").asText(null));
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
        snapshotLineValuesWithUnion(li, null);
    }

    /**
     * P2-C4: 单行四份值快照,核价侧可注入整单 {@code unionByComp}(saveDraft 首存在 N-行循环外一次预取后传入,
     * 把核价 driver 远程查从 N×M_rec 压到 M_rec);null=逐行旧路径(加产品/单行刷新)。报价侧恒逐行不变。
     */
    @Transactional
    public void snapshotLineValuesWithUnion(QuotationLineItem li,
                                            Map<UUID, Map<String, ExpandDriverResponse>> unionByComp) {
        snapshotLineValuesWithUnion(li, unionByComp, null);
    }

    /** B2 重载：透传 {@code prefetch}（saveDraft 首存循环外一次预取的模板 snapshot + 整单 compdata）；null=逐行旧路径。 */
    @Transactional
    public void snapshotLineValuesWithUnion(QuotationLineItem li,
                                            Map<UUID, Map<String, ExpandDriverResponse>> unionByComp,
                                            CardValuesPrefetch prefetch) {
        snapshotLineValuesWithUnion(li, unionByComp, prefetch, true);
    }

    /**
     * P3(2026-06-26 lazy-excel)重载：{@code computeExcel=false} 时跳过两侧 buildExcelValues(报价/核价 Excel 值),
     * 只算卡片值。首存路径传 false —— Excel 快照(7.5s 占 S3 大头、且只在开 Excel 视图/导出时才用)改为懒算
     * (见 {@link #ensureExcelValues})。{@code true}=原行为(加产品/刷新/提交前 ensure 等仍同步算)。
     */
    @Transactional
    public void snapshotLineValuesWithUnion(QuotationLineItem li,
                                            Map<UUID, Map<String, ExpandDriverResponse>> unionByComp,
                                            CardValuesPrefetch prefetch,
                                            boolean computeExcel) {
        if (li == null || li.id == null) return;
        // 在当前事务内重新加载，避免 "Detached entity" 错误
        QuotationLineItem managed = QuotationLineItem.findById(li.id);
        if (managed == null) return;
        Quotation q = Quotation.findById(managed.quotationId);
        if (q == null) return;
        snapshotQuoteSideOnly(managed, q, prefetch, computeExcel);                  // 报价侧逐行不变
        snapshotCostingSideOnly(managed, q, unionByComp, prefetch, computeExcel);   // 核价侧可 union
    }

    /**
     * FIX 2(2026-06-26)首存卡片值<b>集合化落库</b>:打破「77 行 × 一次 @Transactional = 77 个独立事务」。
     *
     * <p>背景([s3-detail] 实测):S3 逐行卡片值 4.1s,其中真算值仅 0.23s,~3.9s 是 77 个独立事务的
     * begin/commit + 逐行冷 findById×2 + 逐行 JSONB UPDATE。本方法在<b>一个事务</b>内:1 次取 Quotation +
     * 1 次 IN 装载全部新行(托管,省逐行 findById)+ 内存算值 + commit 单次 flush(P1 JDBC batch 合并 N 条 UPDATE)。
     *
     * <p><b>两遍 build-then-assign(评审强制)</b>:Pass1 只把卡片值字符串 build 到内存(只读 li、不赋字段,
     * 脏窗口为空 → 即便 prefetch/union miss 时 build 内部 fallback 的 em 查 mid-loop 跑也 flush 空、无害);
     * Pass2 一次性给托管实体赋 4 字段 + 整批同一时间戳(中间零查询)→ commit 时单次 flush。build 只读不写 li,
     * 两遍拆分零成本,且彻底把「批处理是否生效」与「prefetch 覆盖率」解耦。
     *
     * <p>等价:buildCardValues/buildCostingCardValues 逐行输入与逐行路径完全相同 → 落库卡片值逐位等价
     * (CardValuesBatchPersistEquivTest + GoldenCardValuesEquivTest 守)。时间戳整批同一 now(不入 md5)。
     * 只算卡片值(Excel 仍懒算,见 ensureExcelValues)。
     *
     * <p><b>task-260825 D-4（B-11）</b>：注解从默认 {@code @Transactional}(REQUIRED) 改为
     * {@code REQUIRES_NEW}——本方法是 {@link #ensureCardValues} 唯一的调用点（已 grep 确认无其它
     * 生产调用方），{@link #ensureCardValues} 按 chunk 把 {@code newLineIds} 切成若干批、每批调一次
     * 本方法；{@code REQUIRES_NEW} 让每批各自独立提交，批间不共享事务，解除「单事务包住全部 N 行」
     * 撑向 Narayana 60s 上限的隐患。调用方必须经 {@code self.} 代理调用（直接 {@code this.} 调用会
     * 绕开 CDI 拦截器，注解失效，退回原「并入外层事务」行为）。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void snapshotNewLinesCardValues(UUID quotationId, List<UUID> newLineIds,
                                           Map<UUID, Map<String, ExpandDriverResponse>> union,
                                           CardValuesPrefetch prefetch) {
        if (quotationId == null || newLineIds == null || newLineIds.isEmpty()) return;
        Quotation q = Quotation.findById(quotationId);
        if (q == null) return;
        // 1 次 IN 装载全部新行(托管实体,赋字段即脏;省现状逐行 findById 重载)
        List<QuotationLineItem> lines = QuotationLineItem.list("id IN ?1", newLineIds);
        if (lines.isEmpty()) return;
        // task-260825 B-16：4 参重载保留老行为——本方法体内部整单渲染一次（未接
        // ensureCardValues 批量分层的调用方，如 CardValuesBatchPersistEquivTest，零改动、
        // 逐位不变）。ensureCardValues 的批循环改走下方 6 参 snapshotNewLinesCardValuesBatch，
        // 由调用方在循环外整单渲染一次后按批切片传入，避免 render 随 chunk 数重复执行。
        Map<UUID, Map<String, ArrayNode>> treeBaseRowsByLine = java.util.Collections.emptyMap();
        String costingRenderError = null;   // BL-0030:整单核价树渲染失败原文 → 落带消息失败哨兵,前端显式提示
        if (q.costingCardTemplateId != null && templateHasTreeTab(q.costingCardTemplateId)) {
            try {
                treeBaseRowsByLine = bomTreeRenderService.render(q.costingCardTemplateId, lines);
            } catch (Exception e) {
                // 不上抛(否则整单快照 500 + 全 NULL → 前端无限「加载中…」);逐 li 落带原文的失败哨兵。
                costingRenderError = "核价渲染失败: " + e.getMessage();
                LOG.errorf("[costing-tree-render] 整单渲染失败 quotation=%s → 落错误哨兵透出前端: %s",
                        quotationId, e.getMessage());
            }
        }
        snapshotNewLinesCardValuesCore(q, lines, newLineIds, union, prefetch, treeBaseRowsByLine, costingRenderError);
        // B-2（repair-260828）：Core 不再承担 Pass2c 整单级工作——本方法是"单行入口"（无分批循环），
        // 调完 Core 后自己负责收一次尾。直接调用（非 self.）：此刻仍在本方法自身的 REQUIRES_NEW
        // 事务内，recomputeDraftHeaderTotals 声明 @Transactional(REQUIRED) 直接加入当前活跃事务
        // 即可，不需要新开事务；直接调用可读到 Core 刚写入的最新 subtotal（同一事务、同一连接）。
        recomputeDraftHeaderTotals(quotationId);
    }

    /**
     * task-260825 B-16（D-4 追加返修，2026-08-25 A/B 实测驱动，用户裁决）：
     * {@link #snapshotNewLinesCardValues} 4 参重载的批量分层版——{@code treeBaseRowsByLine} /
     * {@code costingRenderError} 由调用方（{@link #ensureCardValues}）传入，不在本方法内部
     * 再调 {@code bomTreeRenderService.render}。
     *
     * <p><b>为什么需要这个重载</b>：D-4（B-11~B-14）把 {@code ensureCardValues} 从「单事务包住
     * 全部 N 行」改成按 chunk 分批、每批调一次 {@code snapshotNewLinesCardValues}。但树页签渲染
     * {@code bomTreeRenderService.render} 原先<b>藏在</b> {@code snapshotNewLinesCardValues}
     * 方法体内部——4 参重载每次被调都会重新 render 一次，于是 chunk 越小、批数越多，render 就被
     * 重复执行越多次。A/B 实测：chunk=2000（1 批）③ 总耗时 51,250ms；chunk=300（7 批）③ 总耗时
     * 92,376ms（+80%），核心原因就是 render 被多算了 6 次。这与 B-12（prefetch/union 必须留在
     * 分批循环外）是<b>同一个模式</b>——只是这次「整单级工作」藏在被调方内部，而不是摆在调用方的
     * 局部变量里，排查时容易漏。
     *
     * <p><b>失败语义对所有批次一致生效</b>：{@code costingRenderError} 由 {@link #ensureCardValues}
     * 整单渲染一次后<b>原样透传</b>给每一批调用——它是<b>同一个字符串值</b>（渲染失败时非 null，
     * 成功时恒 null），不是每批各自判定，因此不会出现"前几批命中错误哨兵、后几批又重试一次拿到
     * 不同结果"的分叉；下方 Pass2 落哨兵的逻辑与 4 参重载完全一致，只是取值来源从「本方法内部算的
     * 局部变量」换成了「入参」。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void snapshotNewLinesCardValuesBatch(UUID quotationId, List<UUID> newLineIds,
                                           Map<UUID, Map<String, ExpandDriverResponse>> union,
                                           CardValuesPrefetch prefetch,
                                           Map<UUID, Map<String, ArrayNode>> treeBaseRowsByLine,
                                           String costingRenderError) {
        if (quotationId == null || newLineIds == null || newLineIds.isEmpty()) return;
        // task-260825 B-28：批事务锁等待上限——本方法是 REQUIRES_NEW 事务根（经 self. 代理调用时
        // 由拦截器在方法体执行前开启一个全新 JTA 事务并把物理连接从 Agroal 池取出、enlist 到该事务），
        // 本条 SET LOCAL 是本事务发出的第一条 SQL，強制立刻获取并绑定物理连接；PostgreSQL 的
        // SET LOCAL 语义天然是"仅对当前事务生效，COMMIT/ROLLBACK 时自动复原"（PG 文档原文），
        // 不需要手工 RESET，也不会泄漏进连接池归还后的下一次复用。此后本事务内的每一条 SQL
        // （包括下面 Pass2 对 quotation_line_item 的 UPDATE）都共享同一条物理连接，故都受本次
        // lock_timeout 约束——PG 官方文档："Abort any statement that waits longer than the
        // specified amount of time while attempting to acquire a lock on a table, index, row,
        // or other database object." 即等待行锁同样计入。超时命中时 PG 报 SQLSTATE 55P03
        // （canceling statement due to lock timeout），JDBC 层转成 PSQLException 被 Hibernate
        // 包成运行时异常，从本方法（含 self. 代理层）冒泡给 {@link #ensureCardValues} 循环体的
        // try/catch（早于默认 60s Narayana reaper 生效，不放宽 Narayana 本身）。
        em.createNativeQuery("SET LOCAL lock_timeout = '10s'").executeUpdate();
        Quotation q = Quotation.findById(quotationId);
        if (q == null) return;
        List<QuotationLineItem> lines = QuotationLineItem.list("id IN ?1", newLineIds);
        if (lines.isEmpty()) return;
        snapshotNewLinesCardValuesCore(q, lines, newLineIds, union, prefetch,
            treeBaseRowsByLine != null ? treeBaseRowsByLine : java.util.Collections.emptyMap(),
            costingRenderError);
    }

    /**
     * {@link #snapshotNewLinesCardValues} / {@link #snapshotNewLinesCardValuesBatch} 共享核心：
     * Pass1 build → Pass1.5 预载 componentData → Pass2 赋值落库 → Pass2c 单头总额跟随。
     * 与改动前逐位相同，只是 {@code treeBaseRowsByLine}/{@code costingRenderError} 从「方法体内部
     * 现算」变成「入参」——两个公开入口各自负责把这两样东西准备好再传进来（B-16）。
     */
    private void snapshotNewLinesCardValuesCore(Quotation q, List<QuotationLineItem> lines, List<UUID> newLineIds,
                                           Map<UUID, Map<String, ExpandDriverResponse>> union,
                                           CardValuesPrefetch prefetch,
                                           Map<UUID, Map<String, ArrayNode>> treeBaseRowsByLine,
                                           String costingRenderError) {
        UUID quotationId = q.id;
        com.cpq.formula.dataloader.QuotationIdContext.set(quotationId);
        try {
            // ── Pass1:只 build 字符串到内存(只读 li,不赋字段 → 脏窗口为空,任何 fallback em 查此刻 flush 空)──
            Map<UUID, String> quoteVals = new HashMap<>();
            Map<UUID, String> costingVals = new HashMap<>();
            // repair-0803 B1：逐行收集报价侧渲染失败原文 → 下方落带原文哨兵（对齐核价侧 BL-0030 能力）
            Map<UUID, String> quoteErrors = new HashMap<>();
            for (QuotationLineItem li : lines) {
                final String[] errOut = new String[1];
                quoteVals.put(li.id, safeCall(() -> buildCardValues(li, q.customerTemplateId, prefetch, errOut)));
                if (errOut[0] != null) quoteErrors.put(li.id, errOut[0]);
                if (q.costingCardTemplateId != null) {
                    if (costingRenderError != null) {
                        costingVals.put(li.id, null);   // 整单渲染已失败 → 不逐 li 重试(S1 兜底会再抛),下方落带原文哨兵
                    } else {
                        Map<String, ArrayNode> precomputed = treeBaseRowsByLine.get(li.id);
                        costingVals.put(li.id, safeCall(() ->
                            buildCostingCardValues(li, q.costingCardTemplateId, q.customerId, q.id, union, prefetch,
                                precomputed)));
                    }
                }
            }
            // ── Pass1.5(方向3 T1,2026-08-06):整批一次 IN 预载 componentData(供 Pass2b 覆盖页签小计) ──
            // 🔒 位置纪律:必须夹在 Pass1 与 Pass2 之间。此刻实体仍全部干净(Pass1 只读不赋值),
            //    这条 IN 查询不会触发 Hibernate 的 flush-before-query,Pass2「中间零查询 → commit
            //    单次 flush → P1 JDBC batch 合并 N 条 UPDATE」的既有不变量原样保住(放到 Pass2 之后
            //    就会把 li 的脏值提前 flush 掉,拆成两批)。
            Map<UUID, List<com.cpq.quotation.entity.QuotationLineComponentData>> cdByLine =
                preloadComponentDataByLine(newLineIds);
            // 🔒 B-4（repair-260828，根因 C 修复）detach 纪律——必须在下方 Pass2 赋值【之前】把
            // 本批全部 QuotationLineItem + QuotationLineComponentData 实体 em.detach()：赋值随后
            // 落在游离对象上，不触发 Hibernate 脏检查，commit 时 Hibernate 对这些实体零 UPDATE，
            // 落库完全交给 Pass2 之后的 writeCardValuesBatchNative 原生批量 UPDATE 负责。
            // 🚫 不许改成"先赋托管实体、写完再 em.clear()"——clear 之前任何查询触发
            // flush-before-query，就会先发出 N 条 UPDATE，本改动完全失效且不报错。
            for (QuotationLineItem li0 : lines) {
                em.detach(li0);
            }
            for (List<com.cpq.quotation.entity.QuotationLineComponentData> cds0 : cdByLine.values()) {
                for (com.cpq.quotation.entity.QuotationLineComponentData cd0 : cds0) {
                    em.detach(cd0);
                }
            }
            // ── Pass2:一次性赋游离实体 4 字段(中间零查询)→ 随后 writeCardValuesBatchNative 原生批量落库 ──
            OffsetDateTime now = OffsetDateTime.now();
            SubtotalOverrideCounter counter = new SubtotalOverrideCounter();
            for (QuotationLineItem li : lines) {
                // build 确定性失败(null)→ 落非 NULL 哨兵,而非 NULL：前端「全有或全无」gate 不被打回实时风暴,
                // 且 ensureCardValues 的 IS NULL 谓词下次不再重选该行(自愈、不无限重算)。失败非静默——warn 记下哪侧。
                if (quoteVals.get(li.id) == null)
                    LOG.warnf("[cardvalues-sentinel] quote build 失败 line=%s → 落失败哨兵", li.id);
                if (costingVals.containsKey(li.id) && costingVals.get(li.id) == null)
                    LOG.warnf("[cardvalues-sentinel] costing build 失败 line=%s → 落失败哨兵", li.id);
                // repair-0803 B1：有失败原文 → 落带原文哨兵，前端显示具体错误而非通用「待重算」
                // ── Pass2b(方向3 T1):赋卡片值的同一个动作里覆盖 li.subtotal + 各页签 cd.subtotal ──
                // 🔒 走与其余 4 条写路径同一个收敛点 assignQuoteCardValues(见其注释);cd 用 Pass1.5
                //    预载的整批结果 → 本循环仍是「中间零查询」,批处理不变量不破。
                // 🔒 同事务是硬要求(设计点 1):分开事务会留下「卡片值已更新、总价还没跟上」的窗口,
                //    那正是本次故障(QT-20260806-0082,前端 59.58 vs 后端 37.33)的成因形态。
                //
                // repair-260829 B-1：落库前产物自检——只在 build 未抛异常(quoteVals 非 null 且不在
                // quoteErrors 里)时才可能判定"算早了"，与既有失败哨兵语义(上两行 warn)互不重叠(E-7)。
                // 命中 isEarlySkeletonRender 则跳过本次 assignQuoteCardValues：li.quoteCardValues
                // 保持其从 DB 读入时的原值(本方法只处理 IS NULL 谓词选中的行，通常即为 NULL)，
                // 不落一次性写死的骨架值——留给下次 ensureCardValues 的 IS NULL 判据重算自愈(AC-3)。
                List<com.cpq.quotation.entity.QuotationLineComponentData> cdsForLine = cdByLine.get(li.id);
                boolean quoteBuiltOk = !quoteErrors.containsKey(li.id) && quoteVals.get(li.id) != null;
                boolean earlySkeleton = quoteBuiltOk && isEarlySkeletonRender(quoteVals.get(li.id), cdsForLine);
                if (earlySkeleton) {
                    LOG.warnf("[cardvalues-early-skeleton] quotation=%s line=%s 算早了：所有页签 baseRows " +
                            "合计为0但 snapshot_rows 非空，判定为①步(driver展开)未写完时被提前渲染，" +
                            "跳过本次落库、quote_card_values 保持原值不变，留给下次 ensureCardValues 的 " +
                            "IS NULL 判据自愈",
                        quotationId, li.id);
                } else {
                    assignQuoteCardValues(li,
                        quoteErrors.containsKey(li.id)
                            ? failedSentinelWithError(quoteErrors.get(li.id))
                            : orSentinel(quoteVals.get(li.id)),
                        cdsForLine, counter);
                    li.quoteValuesAt = now;
                }
                if (costingVals.containsKey(li.id))
                    li.costingCardValues = costingRenderError != null
                        ? failedSentinelWithError(costingRenderError)   // BL-0030:带原文,前端显式提示
                        : orSentinel(costingVals.get(li.id));
                li.cardSnapshotAt = now;
            }
            // ── Pass2d(B-4,repair-260828):原生批量 UPDATE 落库(游离实体不触发脏检查) ──
            writeCardValuesBatchNative(quotationId, lines, cdByLine);
            // B-2（repair-260828，根因 B 修复）：Pass2c「单头总额跟随行总价」不再留在本方法内部
            // 调用——本方法(Core)会被 D-4 的分批循环每批调一次，`recomputeDraftHeaderTotals`
            // 每次都要额外聚合/加载一次，chunk 越小、批数越多，这份「整单级工作」就被重复得越多次
            // （与 B-16 处理 bomTreeRenderService.render 是同一个模式）。改由两个公开入口各自负责
            // 在自己的调用粒度上只收一次尾：{@link #snapshotNewLinesCardValues}（单行入口）调完
            // 本方法后自己调一次；{@link #ensureCardValuesDetailed}（批量入口）在分批循环【结束后】
            // 才调一次。Core 本身不再承担这份整单级工作。
            counter.log(quotationId);
        } finally {
            com.cpq.formula.dataloader.QuotationIdContext.clear();
        }
        LOG.debugf("[cardvalues-batch] quotation=%s 集合化落库 %d 行(单事务)", quotationId, lines.size());
    }

    /**
     * B-4（repair-260828，根因 C 修复）：③ 段落库改原生批量 UPDATE，替代 Hibernate 脏检查落库。
     *
     * <p><b>前置条件（调用方必须已满足）</b>：本方法入参 {@code lines}/{@code cdByLine} 涉及的全部
     * 实体在调用前已 {@code em.detach(...)}，随后在游离态上完成字段赋值——本方法只负责把内存里
     * 算好的值原样写回 DB，不依赖、也不会触发 Hibernate flush。
     *
     * <p><b>固定列集</b>（问题说明 §5.3，语义与 Hibernate 全列/动态列 UPDATE 逐位等价，只是从
     * "运行时按脏位推断"换成"写死一份固定列集"——未被业务改动的列会被写回其读入时的原值，
     * 是值不变的空操作 UPDATE，不产生任何数据差异，AC-8 逐位比对覆盖此点）：
     * <ul>
     *   <li>{@code quotation_line_item}：quote_card_values(jsonb) / costing_card_values(jsonb) /
     *       subtotal / quote_values_at / card_snapshot_at</li>
     *   <li>{@code quotation_line_component_data}：subtotal（第二条批量语句，本批全部行都没有
     *       componentData 时 —— 即 {@code allCds} 为空 —— 不发这条语句，省一次 JDBC 往返）</li>
     * </ul>
     *
     * <p>JSONB 用 {@code ?::jsonb} 参数化绑定（{@code setString}），不拼字符串，无注入面。
     *
     * <p><b>B-6 埋点</b>：{@code rows}=本批行数；{@code batches}=两条语句 {@code addBatch()} 调用
     * 总次数（≈行数 + cd 行数，代表"排进 JDBC 批的语句总数"）；{@code updates}={@code executeBatch()}
     * 调用次数（1=只写了 quotation_line_item，2=还写了 quotation_line_component_data）——这是
     * AC-5「UPDATE 语句往返次数」的唯一可复核依据（{@code pg_stat_statements} 未装，已实测确认）。
     */
    private void writeCardValuesBatchNative(UUID quotationId, List<QuotationLineItem> lines,
            Map<UUID, List<com.cpq.quotation.entity.QuotationLineComponentData>> cdByLine) {
        if (lines == null || lines.isEmpty()) return;
        List<com.cpq.quotation.entity.QuotationLineComponentData> allCds = new ArrayList<>();
        if (cdByLine != null) {
            for (QuotationLineItem li : lines) {
                List<com.cpq.quotation.entity.QuotationLineComponentData> cds = cdByLine.get(li.id);
                if (cds != null && !cds.isEmpty()) allCds.addAll(cds);
            }
        }
        int[] addBatchCount = {0};
        int[] executeBatchCalls = {0};
        org.hibernate.Session session = em.unwrap(org.hibernate.Session.class);
        session.doWork(conn -> {
            try (java.sql.PreparedStatement liStmt = conn.prepareStatement(
                    "UPDATE quotation_line_item SET quote_card_values = ?::jsonb, " +
                    "costing_card_values = ?::jsonb, subtotal = ?, quote_values_at = ?, " +
                    "card_snapshot_at = ? WHERE id = ?")) {
                for (QuotationLineItem li : lines) {
                    liStmt.setString(1, li.quoteCardValues);
                    liStmt.setString(2, li.costingCardValues);
                    if (li.subtotal != null) {
                        liStmt.setBigDecimal(3, li.subtotal);
                    } else {
                        liStmt.setNull(3, java.sql.Types.NUMERIC);
                    }
                    if (li.quoteValuesAt != null) {
                        liStmt.setObject(4, li.quoteValuesAt);
                    } else {
                        liStmt.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                    }
                    if (li.cardSnapshotAt != null) {
                        liStmt.setObject(5, li.cardSnapshotAt);
                    } else {
                        liStmt.setNull(5, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                    }
                    liStmt.setObject(6, li.id);
                    liStmt.addBatch();
                    addBatchCount[0]++;
                }
                liStmt.executeBatch();
                executeBatchCalls[0]++;
            }
            if (!allCds.isEmpty()) {
                try (java.sql.PreparedStatement cdStmt = conn.prepareStatement(
                        "UPDATE quotation_line_component_data SET subtotal = ? WHERE id = ?")) {
                    for (com.cpq.quotation.entity.QuotationLineComponentData cd : allCds) {
                        if (cd.subtotal != null) {
                            cdStmt.setBigDecimal(1, cd.subtotal);
                        } else {
                            cdStmt.setNull(1, java.sql.Types.NUMERIC);
                        }
                        cdStmt.setObject(2, cd.id);
                        cdStmt.addBatch();
                        addBatchCount[0]++;
                    }
                    cdStmt.executeBatch();
                    executeBatchCalls[0]++;
                }
            }
        });
        LOG.infof("[perf] ensure-cardvalues-write quotation=%s rows=%d batches=%d updates=%d",
            quotationId, lines.size(), addBatchCount[0], executeBatchCalls[0]);
    }

    // =========================================================================
    // 方向 3 · 总价单一来源改造（2026-08-06）
    // =========================================================================

    /** 方向 3 T1：覆盖生效计数（供防空转日志）。批量路径整批一条，单行路径逐次一条。 */
    private static final class SubtotalOverrideCounter {
        int liChanged;
        int cdChanged;
        void log(UUID quotationId) {
            if (liChanged > 0 || cdChanged > 0) {
                LOG.infof("[subtotal-single-source] quotation=%s 覆盖生效：行总价 %d 处、页签小计 %d 处与前端提交值不同",
                    quotationId, liChanged, cdChanged);
            }
        }
    }

    /**
     * 方向 3 T1 · <b>报价侧卡片值的唯一写入口（收敛点）</b>。
     *
     * <p><b>为什么必须收敛</b>：{@code quoteCardValues} 全工程有 5 条写路径（懒算批量 / 加产品·导入
     * 逐行 / 刷新基础数据 / 单元格失焦 / 树删除重灌），改造前它们各自裸赋值
     * {@code li.quoteCardValues = json}。而 {@code li.subtotal} 与各页签 {@code cd.subtotal} 是
     * 这份 JSON 的<b>派生量</b> —— 卡片值变了、派生量就必须跟着变，本来就该是同一个动作。任何一条
     * 路径漏挂，那条路径就会重新产出「卡片值已更新、总价还停在旧值」的分叉，也就是本次故障
     * （{@code QT-20260806-0082}，前端 59.58 vs 后端 37.33）的成因形态。
     *
     * <p><b>不是改成派生字段</b>：两个列保留、类型不变、非空性不变，保存路径仍写前端提交值
     * （兜底，无空窗期，后端 44 / 前端 257 处读取方一处都不用改）；只是卡片值算完后由后端权威值
     * <b>覆盖</b>掉。
     *
     * <p>🔒 <b>取数口径唯一</b>（设计点 2）：走 {@link CostingSubtotalUtil}，与 L3 守卫
     * （{@code MaterialVersionUpgradeService} S0）和 S6 写回用的<b>是同一个方法</b>。绝不新写
     * 第二套提取 —— 否则守卫比较的两边又变成两套口径，本次的 bug 会换个形式回来。
     *
     * <p><b>不覆盖的三种情况</b>（一律保留兜底值，绝不抹成 0）：
     * <ol>
     *   <li>卡片值是失败哨兵或 null/空 —— 哨兵 JSON 的 {@code tabs} 是空数组，提取必得 0，
     *       覆盖下去就是把总价写坏；</li>
     *   <li>模板没有 SUBTOTAL 页签 —— 本就没有「产品行总价」这个概念；</li>
     *   <li>页签算出的 {@code subtotal} 字段缺失 —— 没算出来 ≠ 算出来是 0。</li>
     * </ol>
     *
     * <p><b>无反馈环</b>（已核实）：{@code cd.subtotal} 不参与 {@code quoteCardValues} 的生成
     * （{@code assembleTabsWithFormulaResults} 的 {@code componentSubtotals} 全部由
     * baseRows/editRows/computeRows 在内存算出，无一处读 {@code cd.subtotal}），故覆盖不会改变
     * 下一轮自己的输入，结构上不可能自激。但它<b>会</b>被
     * {@code ComponentDataEffectiveRows#computeScaled}（NORMAL 页签「沿用持久化值」）读走，
     * 进而影响 Excel 列的 {@code [页签(总计)]} / {@code __subtotal__} token —— 那是单向传播、
     * 不回流卡片值，且改造前这些 token 一直读到前端提交的 0。
     *
     * @param json        本次要落库的卡片值（可为 null / 哨兵，赋值语义与改造前逐字相同）
     * @param preloadedCd 该行的 componentData（已托管）。传 {@code null} = 由本方法自行查一次；
     *                    批量路径务必预载后传入，否则会退化成逐行查库
     */
    void assignQuoteCardValues(QuotationLineItem li, String json,
                               List<com.cpq.quotation.entity.QuotationLineComponentData> preloadedCd,
                               SubtotalOverrideCounter counter) {
        if (li == null) return;
        li.quoteCardValues = json;                       // 原赋值语义逐字不变（含 null / 哨兵）
        if (!isAuthoritativeCardValues(json)) return;    // 情况 1：保留兜底值，不覆盖

        java.math.BigDecimal unit = CostingSubtotalUtil.extractUnitSubtotalOrNull(json);
        if (unit != null) {                              // 情况 2/3：取不到 → 不覆盖
            java.math.BigDecimal rounded = com.cpq.common.PrecisionPolicy.roundForCalculation(unit);
            if (li.subtotal == null || li.subtotal.compareTo(rounded) != 0) counter.liChanged++;
            li.subtotal = rounded;
        }

        Map<String, java.math.BigDecimal> tabs = CostingSubtotalUtil.extractTabSubtotalsByComponentId(json);
        if (tabs.isEmpty()) return;
        List<com.cpq.quotation.entity.QuotationLineComponentData> cds = preloadedCd != null
            ? preloadedCd
            : com.cpq.quotation.entity.QuotationLineComponentData.list("lineItemId", li.id);
        for (com.cpq.quotation.entity.QuotationLineComponentData cd : cds) {
            if (cd.componentId == null) continue;         // 历史脏数据 / 非组件页签 → 保留兜底值
            java.math.BigDecimal v = tabs.get(cd.componentId.toString());
            if (v == null) continue;                      // 该页签没算出 subtotal → 保留兜底值
            java.math.BigDecimal rounded = com.cpq.common.PrecisionPolicy.roundForCalculation(v);
            if (cd.subtotal == null || cd.subtotal.compareTo(rounded) != 0) counter.cdChanged++;
            cd.subtotal = rounded;
        }
    }

    /** 单行写路径便捷重载：自查 componentData + 自己打计数日志。 */
    void assignQuoteCardValues(QuotationLineItem li, String json, UUID quotationId) {
        SubtotalOverrideCounter c = new SubtotalOverrideCounter();
        assignQuoteCardValues(li, json, null, c);
        c.log(quotationId);
    }

    /**
     * 收敛点的<b>「延后覆盖」变体</b>：卡片值已由调用方自行赋好，本方法只按它覆盖派生小计。
     *
     * <p>🔒 <b>为什么必须有这个变体</b>：{@code editCardValue} / {@code materializeAndProject} 两条
     * 写路径在赋完卡片值之后、同一事务里还要调
     * {@code materializeWholeLineRowData}（<b>{@code REQUIRES_NEW} + 原生 SQL</b>，写
     * {@code quotation_line_component_data.row_data}）。若在它之前就覆盖 {@code cd.subtotal}，
     * Hibernate 会在覆盖时的那次查询处 <b>auto-flush</b>，让<b>外层</b>事务先拿到这些 cd 行的写锁；
     * 紧接着内层 {@code REQUIRES_NEW} 另开事务去 UPDATE 同一批行 → 外层等内层返回、内层等外层放锁
     * → 自锁，直到 JTA 60s 超时把整个请求打成 500（已实测：放前面必 500 且耗时恒 60.1s，
     * 放到 flush/clear 之后 200）。
     *
     * <p>所以这两条路径的顺序必须是：赋卡片值 → 跑完 {@code REQUIRES_NEW} 物化 →
     * {@code em.flush()/clear()} → <b>再</b>调本方法覆盖派生小计。
     */
    void applySubtotalsFromCardValues(QuotationLineItem li, UUID quotationId) {
        if (li == null) return;
        SubtotalOverrideCounter c = new SubtotalOverrideCounter();
        assignQuoteCardValues(li, li.quoteCardValues, null, c);   // 同一份覆盖逻辑，json 取自实体现值
        c.log(quotationId);
    }

    /**
     * 卡片值是否「权威可用」—— 非 null / 非空 / 非失败哨兵。
     * 哨兵两种形态（{@link #CARD_VALUE_FAILED_SENTINEL} 与 {@link #failedSentinelWithError}）
     * 都带 {@code __cardValueFailed} 标记，用它统一判定。
     */
    private static boolean isAuthoritativeCardValues(String json) {
        return json != null && !json.isBlank() && !json.contains("__cardValueFailed");
    }

    /**
     * 方向 3 T1：<b>单头总额跟随行总价</b>（{@code quotation.original_amount} / {@code total_amount}）。
     *
     * <p>覆盖了 {@code li.subtotal} 却不动单头，只会把分叉从「行」搬到「单」—— 列表页金额列与
     * 详情页总价当场对不上。所以覆盖完必须在<b>同一事务</b>里把单头一起算过。
     *
     * <p>🔒 <b>只在 DRAFT 执行</b>。全工程存在<b>两套互不相同</b>的单头口径，用错会静默改语义：
     * <table border="1">
     *   <tr><th>口径</th><th>公式</th><th>PART 子件</th><th>写入点</th></tr>
     *   <tr><td>草稿</td><td>{@code Σ li.subtotal × finalDiscountRate/100}</td><td><b>计入</b></td>
     *       <td>{@code QuotationService} saveDraft:667 / recalculate:2064 / :2508</td></tr>
     *   <tr><td>提交</td><td>{@code Σ li.lineTotalAmount}（已含行折扣 × 年用量）</td><td><b>排除</b></td>
     *       <td>{@code QuotationService.submit:858}</td></tr>
     * </table>
     * 懒算发生在草稿期 → 镜像草稿口径。而 {@code ensureCardValues} 也会被非草稿单触发
     * （{@code ComparisonViewService} / {@code CostingFreezeService.createForSubmission}），
     * 那时单头已由 submit 用<b>另一套口径</b>算定，若不加这道 status 闸门就会被本方法当场覆写成
     * 草稿口径 —— 那是把已提交单据的金额语义改掉，比原 bug 更严重。
     *
     * <p>🔒 <b>不复用 {@code QuotationService.recalculate}</b>：那个方法里这段聚合只占 6 行，其余是
     * {@code derivedAttributeCalculatorV5.calculate} 逐行派生属性重算 —— 另一件事，而且很贵，
     * 不能顺带跑。这里只抽那 6 行。
     *
     * <p><b>B-3（repair-260828，根因 B 修复）：为什么现在可以改聚合</b>——本段原注释描述的是
     * 旧调用位置（{@code snapshotNewLinesCardValuesCore} 的 Pass2c，<b>批循环内部</b>，每批调一次）
     * 下的约束：那时必须靠 {@code QuotationLineItem.list()} 对本批次内的行按 Hibernate 一级缓存
     * <b>身份</b>返回刚被本批 Pass2 改脏的同一实例，才能不 flush 就读到覆盖后的 {@code subtotal}——
     * 改聚合会绕开一级缓存踩坑读到旧值。<b>该前提在新调用位置已不成立</b>：B-2 把调用位置挪到了
     * 批循环【结束后】（{@link #ensureCardValuesDetailed} 分批循环结束后一次 / 单行入口
     * {@link #snapshotNewLinesCardValues} 调完 Core 后一次）——此时各批均已通过各自独立的
     * {@code REQUIRES_NEW} 事务提交，DB 里已是权威新值；PostgreSQL 默认 READ COMMITTED，本方法
     * 此刻执行的聚合查询能看到所有已提交的写入，不再依赖一级缓存身份，可以安全改用
     * {@code SELECT sum(subtotal)} 聚合。原写法「整单加载全部行完整实体（含
     * {@code quote_card_values}/{@code costing_card_values} 两个大 JSONB 列，1845 行实测
     * ≈2912 kB）只为把 {@code subtotal} 相加求和」的开销随之消除——这与 B-24 已经修过的
     * {@link #ensureCardValuesDetailed} 内那处同型（同一个类、同一个反模式的第二个实例）。
     * ⚠️ <b>不要把这处聚合改回逐行加载再累加</b>：那就是把 B-3 刚修掉的开销原样加回来。
     */
    @Transactional
    public void recomputeDraftHeaderTotals(UUID quotationId) {
        if (quotationId == null) return;
        // 🔒 必须在本方法（= 本事务）内 findById 取托管实体，不能由调用方把 Quotation 传进来：
        //    refreshDraftQuoteCards 本身没有 @Transactional（它逐行 self.refreshQuoteCardValues 各开各的事务），
        //    调用方在事务外 findById 拿到的是【游离态】实体，赋值不会被脏检查落库——静默丢写。
        //    调用方已在事务内时，这次 findById 命中一级缓存，零额外查询。
        Quotation q = Quotation.findById(quotationId);
        if (q == null || !"DRAFT".equals(q.status)) return;
        // B-3：聚合查询取代「整单加载完整实体 + 内存累加」。草稿口径：不排除 PART，与 :667/:2055
        // 一致——同一张表、同一个 WHERE quotation_id 谓词，只是不再 SELECT * 再实例化整行实体。
        Object rawSum = em.createNativeQuery(
                "SELECT COALESCE(SUM(subtotal), 0) FROM quotation_line_item WHERE quotation_id = :q")
            .setParameter("q", quotationId)
            .getSingleResult();
        java.math.BigDecimal total = rawSum instanceof java.math.BigDecimal
            ? (java.math.BigDecimal) rawSum
            : new java.math.BigDecimal(rawSum.toString());
        q.originalAmount = com.cpq.common.PrecisionPolicy.roundQuotationTotal(total);
        // finalDiscountRate 实体默认 100（非空），但 :2063 仍做了空防御，此处对齐更保守的那一处。
        q.totalAmount = (q.finalDiscountRate != null)
            ? com.cpq.common.PrecisionPolicy.roundQuotationTotal(total.multiply(q.finalDiscountRate)
                .divide(new java.math.BigDecimal("100"),
                        com.cpq.common.PrecisionPolicy.DIVISION_SCALE, java.math.RoundingMode.HALF_UP))
            : com.cpq.common.PrecisionPolicy.roundQuotationTotal(total);
    }

    /**
     * 方向 3 T1：整批一次 IN 预载 componentData（按 lineItemId 分组）；空输入返回空 map。
     *
     * <p><b>B-1（repair-260828，根因 A 修复）</b>：{@code groupingBy} 只为「有 componentData 的行」
     * 建 key —— 对没有任何 componentData 的行（本单实测 1845/1845 都属此类），返回的 Map 里
     * <b>不存在</b>该行的 key，调用方 {@code cdByLine.get(li.id)} 拿到的是 {@code null}，被
     * {@link #assignQuoteCardValues} 的 {@code preloadedCd != null ? … : …list(…)} 三元
     * 误判成「没预载过」，退化成每行一条必定查回空结果的 SQL（教科书 N+1）。修法：对入参
     * {@code lineIds} 里未出现在 {@code groupingBy} 结果中的每个 id，补一个空列表——
     * 「预载过但没有 componentData」与「查回空结果」在下游 {@code for (cd : cds)} 里逐位等价
     * （空列表直接跳过循环体，语义不变），但把契约（javadoc 早已写明「批量路径务必预载后
     * 传入，否则会退化成逐行查库」）在源头兑现，而不是指望每个调用方自己记得 getOrDefault。
     */
    private Map<UUID, List<com.cpq.quotation.entity.QuotationLineComponentData>>
            preloadComponentDataByLine(java.util.Collection<UUID> lineIds) {
        if (lineIds == null || lineIds.isEmpty()) return Map.of();
        Map<UUID, List<com.cpq.quotation.entity.QuotationLineComponentData>> byLine =
            com.cpq.quotation.entity.QuotationLineComponentData
                .<com.cpq.quotation.entity.QuotationLineComponentData>list("lineItemId IN ?1", new ArrayList<>(lineIds))
                .stream().collect(java.util.stream.Collectors.groupingBy(cd -> cd.lineItemId));
        for (UUID id : lineIds) {
            byLine.putIfAbsent(id, List.of());
        }
        return byLine;
    }

    /**
     * P2-C4 拆分:报价侧快照(quoteCardValues + quoteExcelValues)。导入两遍循环里留在 pass1 原位。
     * 与改动前报价两段逐字相同;输入不含本行 cd(导入路径不写 snapshot_rows、cd componentId=null 被
     * buildCardValues 跳过)→ 产出与 cd persist 时序无关,二次循环重排对报价侧零影响。
     */
    @Transactional
    public void snapshotQuoteSideOnly(QuotationLineItem managed, Quotation q) {
        snapshotQuoteSideOnly(managed, q, null);
    }

    /** B2 重载：buildCardValues 传 {@code prefetch}（命中则复用预取模板 snapshot + 整单 compdata）。 */
    @Transactional
    public void snapshotQuoteSideOnly(QuotationLineItem managed, Quotation q, CardValuesPrefetch prefetch) {
        snapshotQuoteSideOnly(managed, q, prefetch, true);
    }

    /** P3 lazy-excel 重载：{@code computeExcel=false} 跳过报价 Excel bootstrap(留 NULL,懒算)。 */
    @Transactional
    public void snapshotQuoteSideOnly(QuotationLineItem managed, Quotation q, CardValuesPrefetch prefetch, boolean computeExcel) {
        if (managed == null || q == null) return;
        try {
            // 报价侧：卡片值复用 snapshot_rows（Task 6 真实填充，不二次 expand）
            // 方向3 T1 写路径 2/5（加产品 + 导入逐行）：走收敛点。本路径被 ImportExecutionService
            // 在 N-行循环里逐行调用（且 prefetch 恒 null），收敛点自查 componentData = 每行 +1 条
            // 索引查询 —— 该增量已实测（见交付报告「路径 2 性能闸门」），相对本方法既有的逐行
            // buildCardValues 开销可忽略。
            assignQuoteCardValues(managed,
                safeCall(() -> buildCardValues(managed, q.customerTemplateId, prefetch)), q.id);
            // 报价侧 Excel 值：前端权威（saveDraft）；仅从未 saveDraft 的新行 bootstrap 一次。
            // P3:computeExcel=false(首存)时跳过 bootstrap,留 NULL → ensureExcelValues 懒算。
            if (computeExcel && managed.quoteExcelValues == null) {
                managed.quoteExcelValues = safeCall(() ->
                    buildExcelValues(managed, q.customerTemplateId, q.customerId, managed.quoteCardValues));
            }
            managed.quoteValuesAt = OffsetDateTime.now();
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] snapshotQuoteSideOnly failed lineItem=%s: %s", managed.id, e.getMessage());
        }
    }

    /**
     * P2-C4 拆分:核价侧快照(costingCardValues + costingExcelValues)。导入挪到 pass2,可注入整单
     * {@code unionByComp}(跨行 partSet 并集一次多值查的结果);null=逐行旧路径。核价侧 editRows 恒空、
     * 与报价侧物理隔离,延后到 pass2 安全。{@code cardSnapshotAt} 恒设(守 saveDraft 新行判定,与改动前一致)。
     */
    @Transactional
    public void snapshotCostingSideOnly(QuotationLineItem managed, Quotation q,
                                        Map<UUID, Map<String, ExpandDriverResponse>> unionByComp) {
        snapshotCostingSideOnly(managed, q, unionByComp, null);
    }

    /** B2 重载：buildCostingCardValues 传 {@code prefetch}（命中则复用预取核价模板 snapshot）。 */
    @Transactional
    public void snapshotCostingSideOnly(QuotationLineItem managed, Quotation q,
                                        Map<UUID, Map<String, ExpandDriverResponse>> unionByComp,
                                        CardValuesPrefetch prefetch) {
        snapshotCostingSideOnly(managed, q, unionByComp, prefetch, true);
    }

    /** P3 lazy-excel 重载：{@code computeExcel=false} 跳过核价 Excel(留 NULL,懒算);卡片值仍算。 */
    @Transactional
    public void snapshotCostingSideOnly(QuotationLineItem managed, Quotation q,
                                        Map<UUID, Map<String, ExpandDriverResponse>> unionByComp,
                                        CardValuesPrefetch prefetch, boolean computeExcel) {
        if (managed == null || q == null) return;
        try {
            if (q.costingCardTemplateId != null) {
                // S1 修复（2026-07）：单行路径（加产品 ConfigureProductResource → snapshotLineValues →
                // snapshotLineValuesWithUnion → 本方法）原先未判 templateHasTreeTab，恒走六参重载
                // （precomputedBaseRows 恒 null）→ 含树页签模板会走旧引擎，与批量路径（
                // snapshotNewLinesCardValues / importexcel 批量路径）算出的 nodeId 不一致，且 Task 5.2
                // 删旧引擎后本路径会直接崩。与批量层同款接法：整单（此处单元素列表）先调
                // BomTreeRenderService.render 拿 precomputedBaseRows，再传给七参重载。
                Map<String, ArrayNode> precomputed = null;
                if (templateHasTreeTab(q.costingCardTemplateId)) {
                    precomputed = bomTreeRenderService
                        .render(q.costingCardTemplateId, java.util.List.of(managed))
                        .get(managed.id);
                }
                Map<String, ArrayNode> precomputedFinal = precomputed;
                managed.costingCardValues = safeCall(() ->
                    buildCostingCardValues(managed, q.costingCardTemplateId, q.customerId, q.id, unionByComp,
                        prefetch, precomputedFinal));
                // P3:computeExcel=false(首存)时跳过核价 Excel,留 NULL → ensureExcelValues 懒算。
                if (computeExcel) {
                    managed.costingExcelValues = safeCall(() ->
                        buildExcelValues(managed, q.costingCardTemplateId, q.customerId, managed.costingCardValues, true));
                }
            }
            managed.cardSnapshotAt = OffsetDateTime.now();
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] snapshotCostingSideOnly failed lineItem=%s: %s", managed.id, e.getMessage());
        }
    }

    /**
     * P3 lazy-excel(2026-06-26):懒算整单 Excel 值。首存只算卡片值、Excel 值留 NULL;开 Excel 视图 / 导出 /
     * 提交前调本方法补算缺失行的 {@code quoteExcelValues}/{@code costingExcelValues} 并落库。
     *
     * <p><b>幂等</b>:仅对 NULL 的侧/行计算,已算的跳过 → 反复调安全、第二次零开销。计算走与同步路径
     * <b>同款</b> {@link #buildExcelValues}(同 cardValues 输入)→ 与"首存就算"逐位等价(golden 卡口)。
     *
     * <p><b>task-260825 B-29</b>：薄包装，转调 {@link #ensureExcelValuesDetailed(UUID)}。
     *
     * @return 本次识别出的"需要补算"行数（与 {@link #ensureCardValues(UUID, boolean)} 同一口径，
     * <b>不是</b>"成功落库"行数——批失败时本值不扣减，见 {@link EnsureResult} 类注释）;
     * 0=全部已就绪(无需算)。要判断本次是否有批失败，请改调
     * {@link #ensureExcelValuesDetailed(UUID)} 看 {@code failedBatches}/{@code failedRows}。
     */
    @Transactional
    public int ensureExcelValues(UUID quotationId) {
        return ensureExcelValuesDetailed(quotationId).computed;
    }

    /**
     * task-260825 B-29（2026-08-28，照搬 B-28/{@link #ensureCardValuesDetailed} 手法）：
     * {@link #ensureExcelValues(UUID)} 的批失败信息透出版——原方法体单个 {@code @Transactional}
     * 包住全部行（1845 行实测 33~41s，Narayana 60s 预算余量仅 31~44%；受控实验持锁后被
     * reaper 砍整步回滚，Excel 值 0/1845），改按 chunk 分批、每批走
     * {@link #ensureExcelValuesBatch} 的独立 {@code REQUIRES_NEW} 事务，批内首条 SQL
     * {@code SET LOCAL lock_timeout = '10s'}；某批失败只记日志+计入失败汇总，不阻断后续批。
     *
     * <p><b>{@code computed} 口径与 {@link #ensureCardValuesDetailed(UUID, boolean)} 完全一致</b>
     * ——都是 {@code missing.size()}（本次识别出需要补算的行数），<b>不</b>随批失败扣减，也不是
     * "实际落库行数"。两个方法的 {@code computed} 语义必须保持一致，避免"同一份返回值载体在
     * 两处含义不同"这种更坑人的不一致（2026-08-28 用户裁决，纠正了此前"实际补算(落库)行数"的
     * 错误描述——那是基于对 ③ 的错误推断，非实测）。
     */
    @Transactional
    public EnsureResult ensureExcelValuesDetailed(UUID quotationId) {
        if (quotationId == null) return new EnsureResult(0, 0, 0);
        Quotation q = Quotation.findById(quotationId);
        if (q == null) return new EnsureResult(0, 0, 0);
        if (!"DRAFT".equals(q.status)) return new EnsureResult(0, 0, 0);
        boolean hasQuoteTpl = q.customerTemplateId != null;
        boolean hasCostingTpl = q.costingCardTemplateId != null;
        if (!hasQuoteTpl && !hasCostingTpl) return new EnsureResult(0, 0, 0);

        // 缺失谓词与原逐行判断（managed.quoteExcelValues == null / managed.costingExcelValues == null，
        // 各自受对应模板是否配置门控）等价，只是从"整单加载全部行 + 逐行内存判断"改成 SQL 端过滤，
        // 幂等语义不变：已两侧都算好的行不会被选中（C-5）。
        StringBuilder cond = new StringBuilder();
        if (hasQuoteTpl) cond.append("quote_excel_values IS NULL");
        if (hasCostingTpl) {
            if (cond.length() > 0) cond.append(" OR ");
            cond.append("costing_excel_values IS NULL");
        }
        // task-260825 B-30：加确定性排序（与 ensureCardValuesDetailed 同一套 ORDER BY sort_order
        // NULLS LAST, id），理由见该方法同款注释——批边界必须在 ③/④ 之间保持一致，否则受控实验
        // 持锁时两者废掉的批不对齐（B-29 受控验收实测：③ 只连带 300 行，④ 却连带 600 行）。
        String sql = "SELECT id FROM quotation_line_item WHERE quotation_id = :q AND (" + cond + ")"
            + " ORDER BY sort_order NULLS LAST, id";
        @SuppressWarnings("unchecked")
        java.util.List<Object> rawIds = em.createNativeQuery(sql)
            .setParameter("q", quotationId).getResultList();
        java.util.List<UUID> missing = new java.util.ArrayList<>();
        for (Object o : rawIds) { UUID u = asUuid(o); if (u != null) missing.add(u); }
        if (missing.isEmpty()) return new EnsureResult(0, 0, 0);

        int chunkSize = ensureExcelValuesChunkSize();
        int totalBatches = (int) Math.ceil(missing.size() / (double) chunkSize);
        long allBatchesStart = System.currentTimeMillis();
        int batchNo = 0;
        int failedBatches = 0;
        int failedRows = 0;
        UUID customerTemplateId = q.customerTemplateId;
        UUID costingCardTemplateId = q.costingCardTemplateId;
        UUID customerId = q.customerId;
        String status = q.status;
        for (int start = 0; start < missing.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, missing.size());
            List<UUID> batch = missing.subList(start, end);
            batchNo++;
            long batchStart = System.currentTimeMillis();
            // self. 调用——REQUIRES_NEW 注解只在经 CDI 代理调用时生效，直接 this. 调用会绕开
            // 拦截器退化为并入外层事务（与 ensureCardValuesDetailed 同款纪律，见其 B-12 注释）。
            try {
                self.ensureExcelValuesBatch(quotationId, batch,
                    customerTemplateId, costingCardTemplateId, customerId, status);
                long batchElapsed = System.currentTimeMillis() - batchStart;
                LOG.infof("[ensure-excel-values-batch] quotation=%s batch=%d/%d rows=%d elapsed=%dms chunkSize=%d",
                    quotationId, batchNo, totalBatches, batch.size(), batchElapsed, chunkSize);
            } catch (Exception e) {
                long batchElapsed = System.currentTimeMillis() - batchStart;
                failedBatches++;
                failedRows += batch.size();
                // 已提交的前 batchNo-1 批各自独立 REQUIRES_NEW 事务、早已 commit，不受本批异常
                // 影响；本批未处理行仍为 NULL，靠本方法开头的 IS NULL 谓词下次重跑自愈。
                LOG.errorf(e, "[ensure-excel-values-batch-failed] quotation=%s batch=%d/%d rows=%d " +
                        "idxRange=[%d,%d) elapsed=%dms chunkSize=%d 原因=%s",
                    quotationId, batchNo, totalBatches, batch.size(), start, end, batchElapsed,
                    chunkSize, e.getMessage());
            }
        }
        LOG.infof("[lazy-excel] ensureExcelValues quotation=%s 补算 %d 行（分 %d 批，chunk=%d，总耗时=%dms，失败 %d 批/%d 行）",
            quotationId, missing.size(), totalBatches, chunkSize,
            System.currentTimeMillis() - allBatchesStart, failedBatches, failedRows);
        return new EnsureResult(missing.size(), failedBatches, failedRows);
    }

    /**
     * task-260825 B-29：{@link #ensureExcelValuesDetailed(UUID)} 分批循环体的批处理方法——独立
     * {@code REQUIRES_NEW} 事务，批内首条 SQL 即 {@code SET LOCAL lock_timeout = '10s'}（与
     * {@link #snapshotNewLinesCardValuesBatch} 同款纪律，理由见该方法 javadoc：本条 SQL 强制
     * 立刻绑定物理连接，此后本事务内每条 SQL 都受该 lock_timeout 约束，超时命中早于 Narayana
     * 60s reaper 生效，冒泡给调用方 {@code self.} 代理层的 try/catch）。返回 {@code void}——与
     * {@link #snapshotNewLinesCardValuesBatch} 同款：调用方 {@link #ensureExcelValuesDetailed}
     * 的 {@code EnsureResult.computed} 口径是 {@code missing.size()}，不依赖本方法的返回值累加
     * 实际落库行数（见 {@link #ensureExcelValuesDetailed} javadoc 关于 computed 口径的说明）。
     *
     * <p>入参 {@code customerTemplateId}/{@code costingCardTemplateId}/{@code customerId}/
     * {@code status} 由调用方在分批循环<b>之外</b>从整单 {@code Quotation} 一次性取出后原样传入
     * ——不在本方法内部重新 {@code Quotation.findById}（那样每批都要多一次查询，且 REQUIRES_NEW
     * 新开事务里重新加载的 {@code Quotation} 实体与外层已判定过的字段值理应逐位相同，没必要
     * 重复查）。
     *
     * <p>按批 IN 预取 componentData（C-4）：不是整单一次预取，也不是逐行查库——按<b>本批</b>
     * {@code lineIds} 一次 IN 查询，与 {@link #snapshotNewLinesCardValuesBatch} 的
     * {@code preloadComponentDataByLine} 同一手法，只是本方法需要保留原 {@code ORDER BY
     * lineItemId, sortOrder, id}（与改动前 {@code ensureExcelValues} 逐位相同,供 buildRowData
     * 按 sortOrder 顺序读取）。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void ensureExcelValuesBatch(UUID quotationId, List<UUID> lineIds,
                                       UUID customerTemplateId, UUID costingCardTemplateId,
                                       UUID customerId, String status) {
        if (quotationId == null || lineIds == null || lineIds.isEmpty()) return;
        // task-260825 B-29：批事务锁等待上限，理由同 snapshotNewLinesCardValuesBatch（B-28）。
        em.createNativeQuery("SET LOCAL lock_timeout = '10s'").executeUpdate();
        java.util.List<QuotationLineItem> lines = QuotationLineItem.list("id IN ?1", lineIds);
        if (lines.isEmpty()) return;
        // C-4：按本批 IN 预取 compData（不是整单一次、也不是逐行查），供 buildRowData 读内存。
        java.util.Map<UUID, java.util.List<com.cpq.quotation.entity.QuotationLineComponentData>> cdByLine =
            com.cpq.quotation.entity.QuotationLineComponentData
                .<com.cpq.quotation.entity.QuotationLineComponentData>list(
                    "lineItemId IN ?1 ORDER BY lineItemId, sortOrder, id", lineIds)
                .stream().collect(java.util.stream.Collectors.groupingBy(cd -> cd.lineItemId));
        // C-3：ThreadLocal 上下文每批（每个 REQUIRES_NEW 事务）都要重新 set/clear，不能只在
        // 外层设一次——批方法在新事务里拿不到外层设的上下文。
        com.cpq.formula.dataloader.ExcelCompDataContext.set(cdByLine);
        com.cpq.formula.dataloader.QuotationIdContext.set(quotationId);
        // 🔒 B-5（repair-260828，根因 C 修复）detach 纪律：赋值【之前】把本批 QuotationLineItem
        // 全部 em.detach()，随后的字段赋值落在游离对象上，不触发 Hibernate 脏检查，落库改由下方
        // writeExcelValuesBatchNative 原生批量 UPDATE 负责。cdByLine 里的 componentData 只作为
        // ExcelCompDataContext 供 buildExcelValues 只读查表，本方法不写它们，不需要 detach。
        for (QuotationLineItem li0 : lines) {
            em.detach(li0);
        }
        List<QuotationLineItem> changedLines = new ArrayList<>();
        try {
            for (QuotationLineItem managed : lines) {
                boolean changed = false;
                if (managed.quoteExcelValues == null && customerTemplateId != null) {
                    // C-3：QuotePendingScope 只在报价分支内 open/restore，不得整方法/整批循环
                    // 包裹，否则核价分支（costingCardTemplateId）会被污染（破 AC-17）——与改动前
                    // ensureExcelValues 同一条不变式，只是循环体从"整单 lines"换成"本批 lines"。
                    UUID _pqPrev = QuotePendingScope.open(quotationId, status);
                    try {
                        managed.quoteExcelValues = safeCall(() ->
                            buildExcelValues(managed, customerTemplateId, customerId, managed.quoteCardValues));
                    } finally {
                        QuotePendingScope.restore(_pqPrev);
                    }
                    changed = true;
                }
                if (managed.costingExcelValues == null && costingCardTemplateId != null) {
                    managed.costingExcelValues = safeCall(() ->
                        buildExcelValues(managed, costingCardTemplateId, customerId, managed.costingCardValues, true));
                    changed = true;
                }
                if (changed) { changedLines.add(managed); }
            }
        } finally {
            com.cpq.formula.dataloader.ExcelCompDataContext.clear();
            com.cpq.formula.dataloader.QuotationIdContext.clear();
        }
        writeExcelValuesBatchNative(quotationId, changedLines);
    }

    /**
     * B-5（repair-260828，根因 C 修复）：④ 段落库改原生批量 UPDATE，与 {@link #writeCardValuesBatchNative}
     * 同款手法。调用前 {@code changedLines} 里的实体已 {@code em.detach(...)}，字段赋值发生在游离态上，
     * 本方法只负责把内存里算好的值原样写回 DB。
     *
     * <p>固定列集：{@code quotation_line_item} 的 {@code quote_excel_values}(jsonb) /
     * {@code costing_excel_values}(jsonb)。只针对<b>本批实际发生计算</b>的行（{@code changedLines}，
     * 与改动前 {@code if (changed) managed.persist();} 同一判据）——两侧都已算好、幂等跳过的行
     * 不在其中，零 UPDATE，保住 {@link #ensureExcelValuesDetailed} javadoc 描述的幂等契约。
     *
     * <p>B-6 埋点：{@code rows}={@code updates}=本次实际写入的行数（一条 UPDATE 语句、一次
     * {@code executeBatch()}，两者对 ④ 恒相等，与 ③ 因存在第二条 cd 语句而可能不同一致地各自
     * 反映自己的真实语义）；{@code batches}={@code addBatch()} 调用次数。
     */
    private void writeExcelValuesBatchNative(UUID quotationId, List<QuotationLineItem> changedLines) {
        if (changedLines == null || changedLines.isEmpty()) {
            LOG.infof("[perf] ensure-excel-write quotation=%s rows=%d batches=%d updates=%d",
                quotationId, 0, 0, 0);
            return;
        }
        int[] addBatchCount = {0};
        int[] executeBatchCalls = {0};
        org.hibernate.Session session = em.unwrap(org.hibernate.Session.class);
        session.doWork(conn -> {
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE quotation_line_item SET quote_excel_values = ?::jsonb, " +
                    "costing_excel_values = ?::jsonb WHERE id = ?")) {
                for (QuotationLineItem li : changedLines) {
                    stmt.setString(1, li.quoteExcelValues);
                    stmt.setString(2, li.costingExcelValues);
                    stmt.setObject(3, li.id);
                    stmt.addBatch();
                    addBatchCount[0]++;
                }
                stmt.executeBatch();
                executeBatchCalls[0]++;
            }
        });
        LOG.infof("[perf] ensure-excel-write quotation=%s rows=%d batches=%d updates=%d",
            quotationId, changedLines.size(), addBatchCount[0], executeBatchCalls[0]);
    }

    /**
     * task-260825 B-29：{@link #ensureExcelValuesDetailed} 分批 chunk 大小，默认 300（与
     * {@link #ensureCardValuesChunkSize} 对齐）。可配：{@code -Dcpq.ensure-excel-values-chunk-size=N}
     * 或环境变量 {@code CPQ_ENSURE_EXCEL_VALUES_CHUNK_SIZE}。非法值（非数字 / ≤0）静默回退默认值。
     */
    private static int ensureExcelValuesChunkSize() {
        String v = System.getProperty("cpq.ensure-excel-values-chunk-size",
            System.getenv().getOrDefault("CPQ_ENSURE_EXCEL_VALUES_CHUNK_SIZE", "300"));
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 ? n : 300;
        } catch (Exception e) {
            return 300;
        }
    }

    /** ensureCardValues 返回值：未取到单飞锁（另一 warm 在飞），调用方应返回轻量 warming 状态。 */
    public static final int WARMING_IN_PROGRESS = -1;

    /**
     * P3 lazy-cardvalues(2026-06-29):懒算整单卡片值。首存只算卡片值的快路径下,新行可能留 NULL 卡片值;
     * 打开报价单 / 切版本 / 提交前调本方法补算缺失行的 {@code quoteCardValues}/{@code costingCardValues} 并落库。
     *
     * <p><b>单飞(single-flight)纪律</b>:进方法<b>第一件事</b>就 {@code pg_try_advisory_xact_lock}(key=quotationId
     * 的 md5 前 16 hex → bigint)。拿不到锁=另一并发 warm 正在飞 → 直接返回 {@code -1}(warming-in-progress),
     * 由调用方决定等待/重试,<b>不重复补算</b>。<b>加锁必须早于</b>下面"缺失行 SELECT" —— 否则两事务都读到 NULL
     * 会双重补算(这是顺序正确性约束,非可调换)。
     *
     * <p><b>缺失谓词</b>:仅用 {@code IS NULL}(列是 jsonb,{@code btrim(jsonb)} 会抛错——原始 bug);核价侧仅当
     * 该单挂了核价模板({@code hasCostingTpl})时才纳入判断。复用 {@link #precomputeCostingDriverUnion} +
     * {@link #precomputeCardValuesPrefetch} + {@link #snapshotNewLinesCardValues}(与"首存就算"同款 build → 逐位等价)。
     *
     * @return 本次识别出的"需要补算"行数（{@code missing.size()}，不是"成功补算"行数——批失败时
     * 本值不扣减，见 {@link EnsureResult} 类注释）;0=全部已就绪(无需算);
     * {@link #WARMING_IN_PROGRESS}(-1)=另一并发 warm 在飞(本次未补算)。
     */
    @Transactional
    public int ensureCardValues(UUID quotationId) {
        return ensureCardValues(quotationId, false);
    }

    /**
     * 方向3 修法①（2026-08-06）：{@code forceRecomputeAll=true} 时<b>无视 {@code IS NULL} 谓词，
     * 强制重算整单全部行</b>。仅提交路径使用。
     *
     * <p><b>要解决的静默错值</b>（实测 4/4 复现，判据 {@code 加工费 83.825536→999.999}
     * ⇒ {@code li.subtotal 37.330516→38.246716}）：
     * <pre>
     *   t0  autoSave 的 warm 起飞，读到【编辑前】的数据开始算
     *   t1  用户点提交 → saveDraft 置 NULL + 写入【编辑后】的 row_data，先 commit
     *   t2  warm 后 commit，把【编辑前】算出的卡片值写回 → 覆盖了 t1 的 NULL
     *   t3  submit → ensureCardValues 的 IS NULL 选不中 → 不重算 → 提交【编辑前】的金额
     * </pre>
     * 用户改的值确实存进了 {@code row_data}，但提交出去的是旧价，<b>且没有任何报错</b>。
     *
     * <p>🔒 <b>为什么提交路径必须 force</b>：提交路径上「卡片值非 NULL」这个状态<b>本身就是异常的</b>
     * —— 紧邻的 {@code saveDraft(skipWarm)} 刚把它们全置 NULL，此刻还非 NULL 只可能是被在飞 warm
     * 用旧数据填回来的。与其信任一个异常状态，不如显式重建。
     *
     * <p>🔒 <b>为什么不是「先置 NULL 再算」</b>：那样要多一条 UPDATE，凭空拉长 {@code quotation_line_item}
     * 的行锁持有窗口，扩大与并发 saveDraft 的 ABBA 死锁面（该死锁已实测到，见 BACKLOG）。
     * 改选行谓词不写任何额外的行 —— 要重算的行本来就要被 UPDATE，<b>死锁面零增量</b>。
     *
     * <p>🔒 <b>成本≈0</b>：提交路径上 {@code saveDraft(skipWarm)} 刚把全单置 NULL，正常情况下
     * {@code missing} 本就等于全集；force 只在「被 warm 填回」这个异常态下才真的多算 —— 那正是要修的场景。
     *
     * <p>⚠️ <b>本方法只治提交金额，不治别的列</b>。根因是 Hibernate 全列 UPDATE 把 warm 的陈旧
     * 内存快照整行写回（{@code annual_volume} / {@code discount_*} 等同样被覆盖），那条由
     * {@code QuotationLineItem} 上的 {@code @DynamicUpdate} 治（修法②）。两者治不同的面，缺一不可。
     *
     * <p><b>task-260825 B-15 教训（曾短暂在此加过 {@code @TransactionConfiguration(timeout=600)}，
     * 已撤销）</b>：本方法有 6 个生产调用点，其中 {@code QuotationService#submit} 内两处调用发生在
     * {@code submit} 自身已开启的事务<b>内部</b>（并入外层事务，非事务根）。Quarkus 对「方法已处于
     * 外层活跃事务中、又声明了 {@code @TransactionConfiguration}」的组合<b>直接抛异常</b>
     * （{@code TransactionalInterceptorBase.checkConfiguration}），而非静默不生效——
     * 若在此加超时配置注解会让任何需要补算卡片值的报价单<b>提交时抛 RuntimeException</b>。
     * 大单量建单场景下延长外层事务超时的真实需求，改在唯一目标调用点
     * （{@link com.cpq.basicdata.v6.service.CreateQuotationMaterializer#materialize}）用
     * {@code io.quarkus.narayana.jta.QuarkusTransaction.run(...)} 显式包一层事务解决——
     * 这样「例外只用于建单物化路径」是结构上的事实，不会牵连本方法的其它 5 个调用点。
     * 本方法自身<b>不再</b>携带任何事务超时配置注解。
     */
    @Transactional
    public int ensureCardValues(UUID quotationId, boolean forceRecomputeAll) {
        return ensureCardValuesDetailed(quotationId, forceRecomputeAll).computed;
    }

    /**
     * task-260825 B-28（2026-08-28，用户裁决方案甲）：批失败信息载体。{@code computed} 与既有
     * {@link #ensureCardValues(UUID, boolean)} 返回值语义逐位相同（本次识别出的"需要补算"行数，
     * 不是"成功补算"行数——这一点改动前后未变）；{@code failedBatches}/{@code failedRows} 是新增的
     * 批失败汇总，仅供 {@link com.cpq.basicdata.v6.service.CreateQuotationMaterializer#materialize}
     * 拼装 {@code warnings} 用，其余 5 个既有调用点（{@code ensureCardValues} 的 int 重载）不受影响。
     *
     * <p><b>task-260825 B-29</b>：本类型同时被 {@link #ensureExcelValuesDetailed(UUID)} 复用，
     * {@code computed} 在那里口径与本方法<b>完全一致</b>——同为 {@code missing.size()}（本次识别出
     * 需要补算的行数），批失败时不扣减。{@link #ensureExcelValues(UUID)} 薄包装的返回值契约随之
     * 与本方法（{@link #ensureCardValues(UUID, boolean)}）同款，不是"实际落库行数"（2026-08-28
     * 用户裁决更正：此前认为 ensureExcelValues 原有返回值契约是"实际落库行数"是基于错误推断，
     * 未实测；改动后两条调用链统一按 {@code missing.size()} 口径，避免"同一份返回值载体在两处
     * 含义不同"这种更坑人的不一致）。
     */
    public static final class EnsureResult {
        public final int computed;
        public final int failedBatches;
        public final int failedRows;
        EnsureResult(int computed, int failedBatches, int failedRows) {
            this.computed = computed;
            this.failedBatches = failedBatches;
            this.failedRows = failedRows;
        }
    }

    /**
     * task-260825 B-28：{@link #ensureCardValues(UUID, boolean)} 的批失败信息透出版——方法体与
     * 改动前逐位相同，唯一差异是分批循环里的 {@code self.snapshotNewLinesCardValuesBatch(...)}
     * 调用改为 try/catch（见循环体内注释），不再让单批异常整体中止方法执行。
     */
    @Transactional
    public EnsureResult ensureCardValuesDetailed(UUID quotationId, boolean forceRecomputeAll) {
        return ensureCardValuesDetailed(quotationId, forceRecomputeAll, false);
    }

    /**
     * repair-260829 B-1b：3 参重载——{@code skipInProgressGuard=true} 专供
     * {@link com.cpq.basicdata.v6.service.CreateQuotationMaterializer#materialize} 自身③步调用。
     *
     * <p><b>为什么需要这个开关</b>：{@code MaterializeRegistry} 的 in-progress 标志覆盖①~④全程
     * （{@code materialize} 方法开头 {@code begin}、finally {@code end}）——若不加区分地一律
     * 拦截，③步调用本方法时标志必然是 {@code true}（就是它自己打上的），会把自己拦死、
     * 物化永远算不出东西。本重载让"物化任务自身"绕过这层守卫，其余 4 个生产调用点
     * （{@link #ensureCardValues(UUID)} / {@link #ensureCardValues(UUID, boolean)} /
     * {@code CostingFreezeService} / {@code QuotationService#submit}）一律走 2 参重载，
     * {@code skipInProgressGuard} 恒为 {@code false}，守卫正常生效。
     *
     * <p>两个重载都标 {@code @Transactional}（默认 REQUIRED）：2 参重载被外部经 CDI 代理调用时
     * 由拦截器开启事务，其内部对 3 参重载的调用是同类内 {@code this.} 直调（不经代理、不重复
     * 触发拦截器），但此时事务已经活跃，3 参重载的方法体在这个已活跃的事务里执行，语义与
     * "两次都触发拦截器"逐位等价（REQUIRED 语义本就是"有就加入、没有就开"，不依赖拦截器
     * 触发次数）。{@code CreateQuotationMaterializer} 经注入的 {@code cardSnapshotService} 代理
     * 直接调 3 参重载，走的是真实代理调用，拦截器正常触发。
     */
    @Transactional
    public EnsureResult ensureCardValuesDetailed(UUID quotationId, boolean forceRecomputeAll,
                                                  boolean skipInProgressGuard) {
        if (quotationId == null) return new EnsureResult(0, 0, 0);
        // repair-260829 B-1b：真正开始算之前先问一句"建单后置物化是否还在飞"——命中则本次
        // 直接不算、不落库(不改变任何行现状)，留给下次调用重试(IS NULL 判据保证会重跑，
        // MaterializeRegistry 是内存态且 begin/end 在 finally 里保证不会永久悬挂，见其类注释)。
        // 🚨 与 B-1 的落库前产物自检是两层独立防线：B-1 兜"①步写了一部分"(comp_data 已有部分
        // 行，B-1 条件②能命中)；本检查兜"①步一行都还没写"(comp_data 整体为空，B-1 条件②
        // 天然为 false、不拦，会把全空骨架值当"合法空结果"放行落库)——见问题说明.md ⑤ B-1b。
        // 放在单飞锁之前：不在意此刻是否已有别的 warm 在飞，只要物化任务本身还在跑就直接
        // 让路，不占用/不判断单飞锁状态，语义更单纯。
        //
        // 🔴 返回值必须复用 WARMING_IN_PROGRESS（主线 2026-08-29 复审抓到）：本条件与下面
        // tryQuotationCalculationLock 失败是同一种语义（"有人在算，本次让路，稍后重试"），
        // 但若返回 computed=0/failedBatches=0，QuotationService:900 submit 前置的两个 409
        // 判断（warmResult.computed == WARMING_IN_PROGRESS ／ warmResult.failedBatches > 0）
        // 都不会触发 —— 会被误判成"补算完成、无失败"而放行到 lineDiscountService.recompute
        // 用陈旧/缺失的卡片值算金额并冻结，且没有任何报错。这是金额路径，必须响亮失败，
        // 不能像 materialize 本身那样容错静默——同一个返回值经三个消费方（本端点/submit
        // 前重试循环/submit 本身）复用，全部按"在算中，重试"处理，契约不变（AC-9）。
        if (!skipInProgressGuard && materializeRegistry.isInProgress(quotationId)) {
            LOG.warnf("[ensure-cardvalues-materializing] quotation=%s 建单后置物化仍在进行中，" +
                    "本次计算请求跳过(不落库、不改变现状)，留给下次调用重试", quotationId);
            return new EnsureResult(WARMING_IN_PROGRESS, 0, 0);
        }
        // 单飞:加锁必须早于缺失行 SELECT,否则两事务都读 NULL → 双重补算
        if (!tryQuotationCalculationLock(quotationId)) return new EnsureResult(WARMING_IN_PROGRESS, 0, 0);   // warm 在飞

        Quotation q = Quotation.findById(quotationId);
        if (q == null) return new EnsureResult(0, 0, 0);
        if (!"DRAFT".equals(q.status)) return new EnsureResult(0, 0, 0);
        boolean hasCostingTpl = q.costingCardTemplateId != null;

        // task-260825 B-30（2026-08-28，用户受控验收实测驱动）：加确定性排序——原写法两处
        // （本处 missing 查询 + ensureExcelValuesDetailed 的 missing 查询）都没有 ORDER BY，行序
        // 由 PG 物理堆顺序决定，且③会先 UPDATE 这批行（改变其堆位置）再轮到④重新 SELECT，
        // 于是同样锁住的行在③、④两处被切进不同批（实测③只废 300 行，④却废 600 行）——不是
        // 正确性 bug（IS NULL 谓词保证重跑自愈），但批边界不确定导致连带损失不可预测、故障
        // 难复现。用 sort_order（业务行序）NULLS LAST + id（唯一列兜底，防 sort_order 重复/为空
        // 时并列顺序仍不确定）做全序，③④两处必须用同一套排序，否则批边界依旧对不上。
        String sql = forceRecomputeAll
            ? "SELECT id FROM quotation_line_item WHERE quotation_id = :q" +
              " ORDER BY sort_order NULLS LAST, id"
            : "SELECT id FROM quotation_line_item WHERE quotation_id = :q " +
              "AND ( quote_card_values IS NULL" +
              (hasCostingTpl ? " OR costing_card_values IS NULL" : "") + " )" +
              " ORDER BY sort_order NULLS LAST, id";
        @SuppressWarnings("unchecked")
        java.util.List<Object> rawIds = em.createNativeQuery(sql)
            .setParameter("q", quotationId).getResultList();
        java.util.List<UUID> missing = new java.util.ArrayList<>();
        for (Object o : rawIds) { UUID u = asUuid(o); if (u != null) missing.add(u); }
        if (missing.isEmpty()) return new EnsureResult(0, 0, 0);

        // task-0806 B21：渲染前置门禁——真正需要补算时（missing 非空）才校验模板是否已冻结，
        // 直接调用 PublishedTemplateReader#allTabsOf，让 TemplateNotFrozenException（D17/409）
        // 或 BusinessException（D19 快照损坏/500）不经任何 catch 冒泡到 HTTP 层。
        //
        // 🔒 放在 missing.isEmpty() 判断之后：整单已全部 warm 好、本次纯粹 no-op 的既有快路径
        // 不读任何结构，没有"读不到"这回事，不该报错——早于此处校验会让"打开一张老数据都齐全
        // 但模板恰好还没重新发布"的报价单也报 409，扩大本次改造的破坏面。
        //
        // 🔒 不得挪到下面 snapshotNewLinesCardValues 内部：那条路径按行走 safeCall()（:3759），
        // 会把这个异常降级吞成静默的 __cardValueFailed 哨兵（HTTP 仍 200）——实测验证过，
        // 这正是本次要根治的"HTTP 200 + N 个空壳页签"（buildCardValues 的"1. 配置源"逻辑
        // 完全绕开 PublishedTemplateReader，只原生 SQL 直读 template.components_snapshot，
        // 见 :1567 一带，null 时静默 return null，从不触发本类的冻结校验）。
        publishedTemplateReader.allTabsOf(q.customerTemplateId);
        if (hasCostingTpl) publishedTemplateReader.allTabsOf(q.costingCardTemplateId);

        // task-260825 B-24：原 QuotationLineItem.list("quotationId", quotationId) 会把整单全部
        // 完整实体（含 quote_card_values/costing_card_values 等大 JSONB 列，1845 行 TOAST 后单表
        // 实测 ≈2.7MB）连表带列一并加载，只为取出一串 UUID id——卡片值已算好时该 SELECT 实测
        // 耗时 20~90s。改投影查询：与上面 :1140-1147 同款 native SQL，只选 id 列，不实例化实体。
        // 与原写法等价：同一张表、同一个 WHERE quotation_id = ? 谓词，双方都未加 ORDER BY，
        // 顺序均由数据库自然返回；下游唯一消费方 precomputeCardValuesPrefetch 把它当
        // Collection<UUID> 仅用于 SQL IN 子句成员判断，不依赖顺序。
        @SuppressWarnings("unchecked")
        java.util.List<Object> allIdRows = em.createNativeQuery(
                "SELECT id FROM quotation_line_item WHERE quotation_id = :q")
            .setParameter("q", quotationId).getResultList();
        java.util.List<UUID> allIds = new java.util.ArrayList<>(allIdRows.size());
        for (Object o : allIdRows) { UUID u = asUuid(o); if (u != null) allIds.add(u); }
        // task-260825 D-4（B-12）：union/prefetch 在分批循环之外算一次——两者都是只读预取的纯内存
        // 数据（Map/DTO，不含托管实体引用），REQUIRES_NEW 批事务只是各自开关一次持久化上下文，
        // 不影响这两份数据的可用性，可安全跨批复用。⚠️ 不要把这两行挪进下面的分批循环：那样会把
        // D-3 刚修好的「整单查一次」（尤其 prefetch.frozenQuoteTabs）重新打回「每批查一次」。
        var union = precomputeCostingDriverUnion(quotationId);
        var prefetch = precomputeCardValuesPrefetch(quotationId, allIds);

        // task-260825 B-16（2026-08-25 A/B 实测驱动，用户裁决）：核价树页签渲染
        // bomTreeRenderService.render 原先藏在 snapshotNewLinesCardValues 方法体内部——D-4 分批后
        // 每批都会调一次该方法，于是 render 也被重复执行 chunk 次。A/B 实测：chunk=2000(1 批)
        // ③=51,250ms；chunk=300(7 批) ③=92,376ms（+80%）。与 B-12（prefetch/union 必须留在循环外）
        // 是同一个模式：整单级工作不能留在被多次调用的方法体内部。改法：对全部 missing 行整单
        // render 一次，批循环内按本批行 id 切片后传入 snapshotNewLinesCardValuesBatch（新方法）。
        List<QuotationLineItem> missingLines = QuotationLineItem.list("id IN ?1", missing);
        Map<UUID, Map<String, ArrayNode>> treeBaseRowsByLine = java.util.Collections.emptyMap();
        String costingRenderError = null;   // BL-0030：渲染失败原文，下方原样透传给每一批
        if (hasCostingTpl && templateHasTreeTab(q.costingCardTemplateId)) {
            try {
                // task-260825 B-27：必须经 self. 代理调用 renderCostingTreeBaseRows（独立
                // REQUIRES_NEW + @ActivateRequestContext 边界），不能直接调
                // bomTreeRenderService.render——见该方法 javadoc 的实测根因。
                treeBaseRowsByLine = self.renderCostingTreeBaseRows(q.costingCardTemplateId, missingLines);
            } catch (Exception e) {
                // 不上抛(否则整单快照失败 → 前端无限"加载中…")；costingRenderError 是同一个
                // 字符串值，下方每一批都原样传入 → 失败语义对全部批次一致生效，不会出现
                // "前几批命中错误哨兵、后几批又重试一次拿到不同结果"的分叉（B-16 要求）。
                costingRenderError = "核价渲染失败: " + e.getMessage();
                LOG.errorf("[costing-tree-render] 整单渲染失败 quotation=%s → 落错误哨兵透出前端: %s",
                        quotationId, e.getMessage());
            }
        }

        // task-260825 D-4（B-11/B-13）：按 chunk 分批、每批走 self.snapshotNewLinesCardValuesBatch 的
        // REQUIRES_NEW 独立事务，解除「单事务包住全部 N 行」撑向 Narayana 60s 上限的隐患。
        // 实测基线（1845 行改动前 ③=58679ms，每行 31.8ms）：chunk=300 → 单批 ≈9.5s，余量 84%。
        int chunkSize = ensureCardValuesChunkSize();
        int totalBatches = (int) Math.ceil(missing.size() / (double) chunkSize);
        long allBatchesStart = System.currentTimeMillis();
        int batchNo = 0;
        int failedBatches = 0;
        int failedRows = 0;
        for (int start = 0; start < missing.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, missing.size());
            List<UUID> batch = missing.subList(start, end);
            batchNo++;
            long batchStart = System.currentTimeMillis();
            // B-16：按本批行 id 切片 treeBaseRowsByLine，不把整单 Map 原样传全量进每批。
            Map<UUID, Map<String, ArrayNode>> batchTreeBaseRowsByLine =
                sliceTreeBaseRowsByLine(treeBaseRowsByLine, batch);
            // task-260825 B-28（用户裁决方案甲，2026-08-28，取代旧 B-14 注释里"本方法不 catch"
            // 的行为）：批与批是各自独立的 REQUIRES_NEW 事务——前面已提交的批（commit 早已落库）
            // 不该因为后面某一批被行锁堵住而陪葬。改为按批 try/catch：某一批抛异常（含上面新加的
            // 10s lock_timeout 命中）只记日志、计入失败汇总，不 rethrow，循环继续处理下一批。
            // B-12：self. 调用——REQUIRES_NEW 注解只在经 CDI 代理调用时生效，直接 this. 调用会
            // 绕开拦截器退化为并入外层事务（与改动前同一个坑，本方法内其它 self. 调用同款纪律）。
            try {
                self.snapshotNewLinesCardValuesBatch(quotationId, batch, union, prefetch,
                    batchTreeBaseRowsByLine, costingRenderError);
                long batchElapsed = System.currentTimeMillis() - batchStart;
                LOG.infof("[ensure-cardvalues-batch] quotation=%s batch=%d/%d rows=%d elapsed=%dms chunkSize=%d",
                    quotationId, batchNo, totalBatches, batch.size(), batchElapsed, chunkSize);
            } catch (Exception e) {
                long batchElapsed = System.currentTimeMillis() - batchStart;
                failedBatches++;
                failedRows += batch.size();
                // 已提交的前 batchNo-1 批因走独立 REQUIRES_NEW 事务、早已各自 commit，不受本批
                // 异常影响（REQUIRES_NEW 的语义保证）——本批未处理行仍为 NULL，靠本方法开头的
                // IS NULL 谓词下次重跑自愈（不重算已完成行）。行区间用 missing 列表内下标
                // [start,end) 标识（missing 无 sortOrder 排序保证，行数即可定位规模）。
                LOG.errorf(e, "[ensure-cardvalues-batch-failed] quotation=%s batch=%d/%d rows=%d " +
                        "idxRange=[%d,%d) elapsed=%dms chunkSize=%d 原因=%s → 本批行仍为 NULL，" +
                        "下次打开/轮询触发 ensureCardValues 时按 IS NULL 谓词自愈补算，不影响其它批",
                    quotationId, batchNo, totalBatches, batch.size(), start, end, batchElapsed,
                    chunkSize, e.getMessage());
            }
        }
        // B-2（repair-260828，根因 B 修复）：Core 不再在每批内部调 recomputeDraftHeaderTotals——
        // 本方法（批量入口）在分批循环【结束后】只收一次尾。此刻各批均已通过各自的 REQUIRES_NEW
        // 事务独立提交，DB 里的 subtotal 已是权威新值；直接调用（非 self.）即可加入本方法自身
        // 活跃的外层事务，聚合读到的就是这份权威值（PostgreSQL 默认 READ COMMITTED，本事务在
        // 此刻执行的语句总能看到此前已提交的数据，不依赖一级缓存身份）。
        recomputeDraftHeaderTotals(quotationId);
        concurrencyProbe.afterEnsureValuesBuilt(quotationId);
        LOG.infof("[ensure-cardvalues] quotation=%s 补算 %d 行（分 %d 批，chunk=%d，总耗时=%dms，失败 %d 批/%d 行）",
            quotationId, missing.size(), totalBatches, chunkSize,
            System.currentTimeMillis() - allBatchesStart, failedBatches, failedRows);
        return new EnsureResult(missing.size(), failedBatches, failedRows);
    }

    /**
     * task-260825 B-27（D-5 异步化续修，2026-08-26 实测定位）：核价树整单渲染
     * {@link BomTreeRenderService#render} 必须经由本方法（独立 {@code @Transactional(REQUIRES_NEW)}
     * + {@code @ActivateRequestContext}）调用，不能在 {@link #ensureCardValues} 方法体内直接调
     * {@code bomTreeRenderService.render(...)}。
     *
     * <p><b>实测根因</b>（真实 1845 行建单跑的后端日志）：{@code CreateQuotationMaterializer
     * #materialize}（后台线程，{@code managedExecutor.runAsync} + 自身的
     * {@code @ActivateRequestContext}）异步任务启动后 <b>52ms 内</b>，{@link BomTreeRenderService}
     * 循环里对全部 4 个树驱动组件的 {@code componentDriverService.expandUncached(...)} 调用
     * <b>100% 抛 {@code ContextNotActiveException}</b>（"RequestScoped context was not active
     * ... DataLoader"）——发生在改造前的直接调用处：{@code render()} 只是 {@link #ensureCardValues}
     * 方法体内的一句普通方法调用，join 的是 {@code materialize()} 里
     * {@code QuarkusTransaction.run(...)} 开的事务，本身<b>不经过任何 CDI {@code @Transactional}
     * 代理拦截</b>。<b>同一次运行、紧随其后的 {@code self.snapshotNewLinesCardValuesBatch}
     * （真正经代理调用的 {@code @Transactional(REQUIRES_NEW)}）7 批全部零异常</b>——两者除了
     * "是否经过一次真实的 CDI 代理 + REQUIRES_NEW 事务边界调用"外，处在同一线程、同一
     * {@code materialize()} 调用栈内，无其它结构性差异。
     *
     * <p>与本项目已有的两次同型事故结论完全一致（task-0729 B0 {@code executeItem} / repair-260807
     * {@code PriceAdjustBudgetService#processMaterial}+{@code #runDryRunSnapshot}，见
     * {@code docs/RECORD.md} 对应条目）：只在最外层方法挂 {@code @ActivateRequestContext} 不足以让
     * "经代理调用的独立事务边界"内的 request-scoped bean（这里是
     * {@link com.cpq.formula.dataloader.DataLoader}）保持可解析——必须让
     * {@code @ActivateRequestContext} 直接挂在真正触发 CDI 拦截器链、开启全新事务的那个方法本身。
     *
     * <p>只加这一处：本类另外几个 {@code bomTreeRenderService.render} 调用点（
     * {@code snapshotNewLinesCardValues} / {@code refreshCostingCardValues} /
     * {@code refreshCostingCardValuesForLine} 等）均由正常同步 HTTP 请求线程调用，请求作用域天然
     * 真实存在，未观测到同类异常，本次不动（最小改动面，只治 D-5 异步化引入的这条路径）。
     *
     * <p>REQUIRES_NEW 默认 60s 超时未做特殊放宽：本方法只读、不落任何写，是从
     * {@link #ensureCardValues} 已持有的外层长事务（600s，B-15，由
     * {@code CreateQuotationMaterializer} 用 {@code QuarkusTransaction.run(...)} 包一层）中
     * <b>挂起</b>再开一个新的短事务；若未来大单场景下渲染本身就超 60s，比照 B-15 改用
     * {@code QuarkusTransaction.run(...)} 扩展超时，<b>不要</b>加 {@code @TransactionConfiguration}
     * ——B-15 记录的"外层活跃事务 + 方法自带 {@code @TransactionConfiguration} 组合直接抛异常"禁忌
     * 在此同样适用。
     *
     * <p>self. 调用（不是 this.）：REQUIRES_NEW 只在经 CDI 代理调用时生效，与本类既有 self. 调用
     * 纪律一致（见 {@link #ensureCardValues} 内 B-12 注释）；方法保持 {@code public}，非 private
     * ——CDI 代理对 private 方法不生效，会让本次修复整体失效。
     */
    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Map<UUID, Map<String, ArrayNode>> renderCostingTreeBaseRows(UUID costingTemplateId,
            List<QuotationLineItem> missingLines) {
        return bomTreeRenderService.render(costingTemplateId, missingLines);
    }

    /**
     * task-260825 D-4（B-11）：{@link #ensureCardValues} 分批 chunk 大小，默认 300
     * （按实测 31.8ms/行 → 单批 ≈9.5s，距 Narayana 60s 上限余量 84%）。
     * 可配：{@code -Dcpq.ensure-card-values-chunk-size=N} 或环境变量
     * {@code CPQ_ENSURE_CARD_VALUES_CHUNK_SIZE}。非法值（非数字 / ≤0）静默回退默认值。
     */
    private static int ensureCardValuesChunkSize() {
        String v = System.getProperty("cpq.ensure-card-values-chunk-size",
            System.getenv().getOrDefault("CPQ_ENSURE_CARD_VALUES_CHUNK_SIZE", "300"));
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 ? n : 300;
        } catch (Exception e) {
            return 300;
        }
    }

    /**
     * task-260825 B-16：把整单一次 render 出的 {@code treeBaseRowsByLine} 按本批行 id 切片，
     * 供 {@link #snapshotNewLinesCardValuesBatch} 使用——不把整单 Map 原样传给每一批（虽然
     * {@code Map.get(li.id)} 命中逻辑本身对多余 key 无害，但显式切片让每批的输入边界清晰，
     * 避免日后有人在 core 方法里误遍历这个 Map 而不是遍历 {@code lines}）。
     */
    private static Map<UUID, Map<String, ArrayNode>> sliceTreeBaseRowsByLine(
            Map<UUID, Map<String, ArrayNode>> whole, List<UUID> batchIds) {
        if (whole == null || whole.isEmpty()) return java.util.Collections.emptyMap();
        Map<UUID, Map<String, ArrayNode>> out = new HashMap<>();
        for (UUID id : batchIds) {
            Map<String, ArrayNode> v = whole.get(id);
            if (v != null) out.put(id, v);
        }
        return out;
    }

    /** Same-quotation transaction lock shared by lazy ensure and interactive card edits. */
    private boolean tryQuotationCalculationLock(UUID quotationId) {
        Boolean locked = (Boolean) em.createNativeQuery(
                "SELECT pg_try_advisory_xact_lock(" + QUOTATION_CALCULATION_LOCK_KEY_SQL + ")")
            .setParameter("q", quotationId.toString()).getSingleResult();
        return Boolean.TRUE.equals(locked);
    }

    /** Waits for the current ensure/edit transaction on this quotation to finish. */
    private void awaitQuotationCalculationLock(UUID quotationId) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(" + QUOTATION_CALCULATION_LOCK_KEY_SQL + ")")
            .setParameter("q", quotationId.toString()).getSingleResult();
    }

    /**
     * task-260825 B-22（D-5 再返修，2026-08-26 亲验抓到竞态后用户裁决）：纯只读物化状态统计，
     * 供轮询用的只读端点消费。<b>不拿单飞锁、不触发任何计算、不写任何数据</b>——只是一条
     * {@code SELECT count(*)}，供前端区分"在等"和"已完成"，不再需要把 {@link #ensureCardValues}
     * 当轮询主循环（那正是 B-22 要根治的竞态：轮询一旦抢到单飞锁就会自己变成几十秒的计算工人，
     * 且该锁架子事务默认只有 60s，不像 {@code materialize} 路径那样被 {@code QuarkusTransaction.run}
     * 包了 600s——轮询抢锁时撞上这堵 60s 墙，会把已经在飞的批次腰斩，写丢数据）。
     *
     * <p>🔒 <b>核价侧计数口径与 {@link #ensureCardValues} 的选行谓词强制保持一致</b>——两处共享同一个
     * "是否含核价模板"判定（{@code q.costingCardTemplateId != null}），不各写一份。若不一致会导致
     * 报价单没配核价模板时 {@code done} 永远算不出 true（核价侧恒判"未就绪"）。
     */
    public MaterializeStatus materializeStatus(UUID quotationId) {
        if (quotationId == null) return new MaterializeStatus(0, 0);
        Quotation q = Quotation.findById(quotationId);
        if (q == null) return new MaterializeStatus(0, 0);
        boolean hasCostingTpl = q.costingCardTemplateId != null;
        // "ready" 谓词是 ensureCardValues "missing" 谓词的取反——同一份判定条件，只是这里统计
        // 计数而不是选 id、不做任何后续写入。
        String sql = "SELECT count(*) AS total, " +
            "count(*) FILTER (WHERE NOT (quote_card_values IS NULL" +
            (hasCostingTpl ? " OR costing_card_values IS NULL" : "") + ")) AS ready " +
            "FROM quotation_line_item WHERE quotation_id = :q";
        Object[] row = (Object[]) em.createNativeQuery(sql).setParameter("q", quotationId).getSingleResult();
        long total = ((Number) row[0]).longValue();
        long ready = ((Number) row[1]).longValue();
        return new MaterializeStatus(total, ready);
    }

    /** B-22 只读统计结果：{@code pending}/{@code done} 由 {@code total}/{@code ready} 派生，不单独存储字段防止两者失步。 */
    public static final class MaterializeStatus {
        public final long total;
        public final long ready;
        public MaterializeStatus(long total, long ready) {
            this.total = total;
            this.ready = ready;
        }
        public long getPending() { return total - ready; }
        /** total=0（尚无明细行）也算 done——没有行可等，不应显示"进行中"。 */
        public boolean isDone() { return ready == total; }
    }

    /**
     * task-260825 B-23（同上，用户裁决）：只读判定该报价单的物化单飞锁当前是否被<b>某个活跃会话</b>
     * 持有——供前端区分"后台确实在算"（继续等）与"后台死了/从没起过"（该由用户重试或提示异常）。
     *
     * <p>🚫 <b>绝不可用 {@code pg_try_advisory_*} 去试探</b>——那是"尝试获取"，会把锁真的拿走，
     * 在并发下与后台任务抢锁，重演 B-22 要根治的那个竞态。本方法<b>只读</b> {@code pg_locks}
     * 系统目录视图，不发起任何加锁请求。
     *
     * <p><b>可行性已实测验证</b>（非纯理论）：{@code pg_try_advisory_xact_lock(bigint)} 单参数形式
     * 加的锁，在 {@code pg_locks} 里以 {@code locktype='advisory'}、{@code objsubid=1} 记录，
     * 原始 64 位 key 被拆成 {@code classid}（高 32 位）+ {@code objid}（低 32 位）两个 {@code int4}
     * 列存储——用 {@code (classid::bigint << 32) | (objid::bigint & 4294967295)} 可精确重建回原始
     * bigint（含负数取值，两次独立会话持锁 + 查询交叉验证，重建值与原始 key 逐位相等）。
     */
    public boolean isMaterializeInFlight(UUID quotationId) {
        if (quotationId == null) return false;
        Boolean inFlight = (Boolean) em.createNativeQuery(
                "SELECT EXISTS (" +
                "  SELECT 1 FROM pg_locks" +
                "  WHERE locktype = 'advisory'" +
                "    AND objsubid = 1" +
                "    AND granted = true" +
                "    AND ((classid::bigint << 32) | (objid::bigint & 4294967295)) = " +
                     QUOTATION_CALCULATION_LOCK_KEY_SQL +
                ")")
            .setParameter("q", quotationId.toString()).getSingleResult();
        return Boolean.TRUE.equals(inFlight);
    }

    /** native SELECT 返回的 id 列(可能 UUID 或 String)归一化为 UUID;不可解析返 null。 */
    private static UUID asUuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }

    /**
     * P2-C4 整单核价 driver union 预取:对该报价单全部核价行,把 <b>非递归无行维度</b>(见
     * {@link ComponentDriverService#eligibleForNonRecursiveCostingBucket})组件 <b>一次</b>
     * {@code expandForPartSet(union)} → {@code Map<componentId, Map<partNo, resp>>}。
     * 把核价侧 driver 远程查从 N×M 压到 M(÷N)。不 eligible 的组件不进返回 Map → 调用方逐行回落。
     *
     * <p><b>硬切换清理（Task 5.2）</b>：原 recursive 分支({@code eligibleForBomUnion} + BOM 闭包 union)
     * 与 spineKeys-flat 分支({@code eligibleForSpineKeysFlatBucket}）已删除——recursive 组件所在的
     * 核价模板必含树页签，{@code buildCostingCardValues} 对这类模板恒用
     * {@link BomTreeRenderService} 的 {@code precomputedBaseRows}、完全不读本方法产出的
     * {@code unionByComp}（见该方法 precomputedBaseRows!=null 分支），故原 recursive 分支的计算结果
     * 100% 从未被下游消费；spineKeys 分支依赖的 {@code SpineKeysContext.get()} 在
     * {@link com.cpq.datasource.sqlview.SqlViewExecutor} 侧从未被读取（Task 1.1 起注入已断开），
     * 亦是纯 no-op。两者皆确认为死代码后一并清理，{@code unionList} 改为直接取本单各行根料号
     * （非递归组件按 {@code partNo} 精确匹配，不需要 BOM 闭包展开的子孙料号超集）。
     */
    @Transactional
    public Map<UUID, Map<String, ExpandDriverResponse>> precomputeCostingDriverUnion(UUID quotationId) {
        Map<UUID, Map<String, ExpandDriverResponse>> unionByComp = new LinkedHashMap<>();
        if (quotationId == null) return unionByComp;
        Quotation q = Quotation.findById(quotationId);
        if (q == null || q.costingCardTemplateId == null) return unionByComp;

        // 树模板恒由 BomTreeRenderService 整单渲染(见类注释),完全不消费本方法产出的 unionByComp;
        // 且其非树 driver 组件(如「元素」)的新契约 $view 输出 material_no、不含 hf_part_no,用旧
        // expandForPartSet(外层注入 hf_part_no = ANY(:hfPartNos))预取会撞
        // "column inner_q.hf_part_no does not exist" 抛错 → 中止整个 ensureCardValues → 核价快照
        // 永远建不出来。故树模板直接跳过这段对它无用且会崩的死预取。
        if (templateHasTreeTab(q.costingCardTemplateId)) return unionByComp;

        // 核价模板的全部 driver 组件清单(整单一次)。Phase 2-2'：非递归无行维度组件(COMP-0021/22/23 类)
        // 是唯一还会命中合桶的类别(递归组件恒由 BomTreeRenderService 整单渲染,见类注释)。
        // task-0806 B8：改走 PublishedTemplateReader（冻结快照），不再直读活 component 表。
        List<com.cpq.template.entity.TemplateComponentSnapshot> driverComps =
            publishedTemplateReader.driverCompsOf(q.costingCardTemplateId);
        if (driverComps.isEmpty()) return unionByComp;

        List<UUID> eligible = new ArrayList<>();          // partNo 合桶(无行维度)
        for (com.cpq.template.entity.TemplateComponentSnapshot dc : driverComps) {
            UUID compId = dc.componentId;
            if (componentDriverService.eligibleForNonRecursiveCostingBucket(compId)) {
                eligible.add(compId);
            }
        }
        if (eligible.isEmpty()) return unionByComp;

        // 全核价行根料号去重集合（非递归组件按 partNo 精确匹配，取代原 BOM 闭包 partSet 超集）。
        // task-260825 B-24：这里循环体只读 li.productPartNoSnapshot 一个标量列，原
        // QuotationLineItem.list(...) 却把整单实体（含 quote_card_values/costing_card_values
        // 等大 JSONB 列）连表带列全加载。改投影查询只选 product_part_no_snapshot 一列；过滤逻辑
        // （null 判断 + isBlank()）原样保留在 Java 侧，与原写法逐条等价，不用 SQL TRIM 近似替代
        // （isBlank() 按 Unicode 空白判定，与 SQL TRIM 只认空格语义不完全相同，避免引入偏差）。
        @SuppressWarnings("unchecked")
        java.util.List<Object> partNoRows = em.createNativeQuery(
                "SELECT product_part_no_snapshot FROM quotation_line_item WHERE quotation_id = :q")
            .setParameter("q", quotationId).getResultList();
        java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>();
        for (Object o : partNoRows) {
            String pn = (o == null) ? null : o.toString();
            if (pn != null && !pn.isBlank()) union.add(pn);
        }
        if (union.isEmpty()) return unionByComp;
        List<String> unionList = new ArrayList<>(union);

        QuotationIdContext.set(quotationId);
        // task-0806 B17-a：expandForPartSet 内部经 expandMulti 走 ComponentDriverService.setNested，
        // 打开模板渲染域让 templateId 真实传播（原恒 null），使 SQL 视图 snapshot fallback 生效。
        UUID _tplPrev = TemplateRenderScope.open(q.costingCardTemplateId);
        try {
            for (UUID compId : eligible) {
                unionByComp.put(compId,
                    componentDriverService.expandForPartSet(compId, q.customerId, unionList, null, null));
            }
        } finally {
            TemplateRenderScope.restore(_tplPrev);
            QuotationIdContext.clear();
        }
        return unionByComp;
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
        // P2-C4: 整单一次 union 预取(把核价 driver 远程查从 N×M_rec 压到 M_rec);null/空=逐行兜底。
        Map<UUID, Map<String, ExpandDriverResponse>> unionByComp = precomputeCostingDriverUnion(quotationId);
        // Task 3.1 事项B：含树页签 → 整单一次调 BomTreeRenderService.render；不含 → 恒空 map，逐 li 走老路径。
        Map<UUID, Map<String, ArrayNode>> treeBaseRowsByLine = java.util.Collections.emptyMap();
        // task-0729 debug（2026-08-03）真根因修复 #2 的连带调整：render() 现在对 expand 异常整体抛出
        // （不再悄悄吞成 0 行冒充成功，见 BomTreeRenderService §④）。本方法原先对 render() 调用零保护，
        // 若不接住会让整单批量刷新因为一个组件的偶发异常直接 500、其余行也跟着刷新失败。同款
        // costingRenderError 哨兵处理（BL-0030 既有模式，见 snapshotNewLinesCardValues:516-523）：
        // 渲染失败 → 逐行落带原文的失败哨兵，不上抛、不影响报价侧。
        String costingRenderError = null;
        if (templateHasTreeTab(q.costingCardTemplateId)) {
            try {
                treeBaseRowsByLine = bomTreeRenderService.render(q.costingCardTemplateId, lines);
            } catch (Exception e) {
                costingRenderError = "核价渲染失败: " + e.getMessage();
                LOG.errorf("[costing-tree-render] 整单渲染失败 quotation=%s → 落错误哨兵透出前端: %s",
                        quotationId, e.getMessage());
            }
        }
        final String _costingRenderError = costingRenderError;
        for (QuotationLineItem li : lines) {
            try {
                QuotationLineItem managed = QuotationLineItem.findById(li.id);
                if (managed == null) continue;
                if (_costingRenderError != null) {
                    managed.costingCardValues = failedSentinelWithError(_costingRenderError);
                    continue;
                }
                Map<String, ArrayNode> precomputed = treeBaseRowsByLine.get(li.id);
                managed.costingCardValues = safeCall(() ->
                    buildCostingCardValues(managed, q.costingCardTemplateId, q.customerId, q.id, unionByComp, null,
                        precomputed));
                managed.costingExcelValues = safeCall(() ->
                    buildExcelValues(managed, q.costingCardTemplateId, q.customerId, managed.costingCardValues, true));
            } catch (Exception e) {
                LOG.warnf("[card-snapshot] refreshCostingCardValues li=%s: %s", li.id, e.getMessage());
            }
        }
        LOG.infof("[card-snapshot] refreshCostingCardValues done quotation=%s lines=%d", quotationId, lines.size());
    }

    /**
     * task-0729 B0 · S5（核价侧）：单行核价卡片值重算。
     *
     * <p>🔒 <b>不能直接复用 {@link #refreshCostingCardValues(UUID)}</b> —— 那是整单批量版本，
     * 会重算该报价单下**全部** line item，违反「只对被升版的料号行执行重算，不碰其他行」的隔离纪律
     * （硬约束 1 / 验收 #14 双向断言：通过料号 A 后，料号 B 的 costing_card_values 必须逐字节不变）。
     * 本方法是同一段单行循环体的独立抽出，只写传入的这一个 {@code lineItemId}。
     *
     * <p>🔒 <b>刻意不接住 {@code render()} 的异常</b>（与 {@link #refreshCostingCardValues} /
     * {@link #snapshotNewLinesCardValues} 的 costingRenderError 哨兵模式不同）——本方法唯一的
     * 生产调用方是 {@code MaterialVersionUpgradeService.upgrade()} 的 S5，异常必须原样上抛，
     * 让 {@code PriceAdjustJobExecutionService.executeJob} 的既有 catch 把该 job item 标记
     * {@code FAILED} 并整体事务回滚（不留残缺数据），而不是把「组件 expand 失败」悄悄降级成
     * 「该组件 0 行 + item 报 SUCCESS」（task-0729 debug 2026-08-03 真实案例：272 次
     * ContextNotActiveException 被吞，核价卡片全清零却全报成功）。谁要在此加哨兵兜底前，先确认
     * 没有破坏这条「升版失败必须可见」的硬约束。
     */
    @Transactional
    public void refreshCostingCardValuesForLine(UUID lineItemId) {
        refreshCostingCardValuesForLine(lineItemId, null);
    }

    /**
     * task-0806 · FR-2 重载：接受调用方（{@code MaterialVersionUpgradeService#upgrade} S5，
     * 经由 {@code PriceAdjustJobExecutionService} 的分组批量预渲染）已算好的树渲染结果，跳过本方法
     * 内部原本要做的单行 {@code bomTreeRenderService.render(templateId, List.of(li))} 调用——
     * 这正是 18 项 job 发 306 条 SQL（应为 17×分组数）的根因所在（需求文档 §1.1/D-2）。
     *
     * <p>🔒 <b>原方法签名与行为一个字不许改</b>（需求文档硬约束 2）：上面的零参方法原样保留，
     * 委派本重载并传 {@code precomputed=null}——{@code null} 语义 = "调用方未参与批量预渲染"，
     * 与改造前完全一致地内部调用 {@code render()}；其余现存调用方（{@code MaterialVersionUpgradeService}
     * 未接批量预渲染的路径）零影响。
     *
     * <p>{@code precomputed} 非 {@code null}（哪怕其 {@link PrecomputedTreeRows#baseRowsByComponent}
     * 本身是 {@code null}，代表"批量预渲染了、但这个 line item 没有树数据"）时，本方法<b>不再调用
     * {@code render()}</b>，直接用传入结果——避免"批量预渲染了但仍然逐项 render 兜底"这种表面批量、
     * 实际没提速的假优化。
     *
     * @param precomputed {@code null} = 未参与批量预渲染，内部按原逻辑调用 {@code render()}；
     *                    非 {@code null} = 已批量预渲染，直接消费（跨 {@code REQUIRES_NEW} 只读传递，
     *                    需求文档 §4：不得跨 item 复用可变对象——本方法只读该对象，不 mutate）
     */
    @Transactional
    public void refreshCostingCardValuesForLine(UUID lineItemId, PrecomputedTreeRows precomputed) {
        if (lineItemId == null) return;
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        if (li == null) return;
        Quotation q = Quotation.findById(li.quotationId);
        if (q == null || q.costingCardTemplateId == null) return;
        Map<String, ArrayNode> baseRows;
        if (precomputed != null) {
            baseRows = precomputed.baseRowsByComponent;
        } else if (templateHasTreeTab(q.costingCardTemplateId)) {
            Map<UUID, Map<String, ArrayNode>> rendered =
                bomTreeRenderService.render(q.costingCardTemplateId, java.util.List.of(li));
            baseRows = rendered.get(li.id);
        } else {
            baseRows = null;
        }
        final Map<String, ArrayNode> precomputedBaseRows = baseRows;
        li.costingCardValues = safeCall(() ->
            buildCostingCardValues(li, q.costingCardTemplateId, q.customerId, q.id, null, null, precomputedBaseRows));
        LOG.infof("[card-snapshot] refreshCostingCardValuesForLine done li=%s precomputed=%b",
            lineItemId, precomputed != null);
    }

    /**
     * task-0806 · FR-2/FR-7 载体：批量预渲染结果的显式"已提供"标记。
     *
     * <p>区分「未参与批量预渲染，仍需内部调用 {@code render()}」（对应 {@code precomputed == null}）
     * 与「已参与批量预渲染，该 line item 结果为空（该模板无树页签 / 该行无匹配业务数据）」
     * （对应 {@code precomputed != null && precomputed.baseRowsByComponent == null}）——
     * holder 本身非 {@code null} 即代表"已提供，不得再调 render()"，与它内部的 map 是否为 {@code null}
     * 是两个独立维度，不能用同一个裸 {@code Map} 参数表达（那样"批量算出的 0 结果"与"没参与批量"
     * 会被折叠成同一个 {@code null}，无法区分）。
     *
     * <p>🔒 <b>只读</b>：{@link #baseRowsByComponent} 一经构造不再被本类任何方法 mutate（需求文档
     * §4 幂等与并发：跨 item 只读传递，不得复用可变对象——2026-06-22 教训，见 CLAUDE.md
     * 记忆 {@code cpq-expand-layer-not-threadsafe}）。
     */
    public static final class PrecomputedTreeRows {
        public final Map<String, ArrayNode> baseRowsByComponent;

        public PrecomputedTreeRows(Map<String, ArrayNode> baseRowsByComponent) {
            this.baseRowsByComponent = baseRowsByComponent;
        }
    }

    // =========================================================================
    // B2: 批量 EM 预取 —— saveDraft 首存 N-行循环外一次预取，消除每行重复的
    //     「模板 components_snapshot 读+解析」与「compdata 逐行查」。
    // =========================================================================

    /**
     * 首存 card values 批量预取上下文（per-call，单线程使用）。{@code null} 传入 = 逐行旧路径（零破坏）。
     * <ul>
     *   <li>{@code templateSnapshotById}：全单只有报价/核价两个模板，各 parse 一次复用（替代 ×N 读+解析）；
     *       解析后的 JsonNode 全程<b>只读</b>（assemble 只读 tab.path、不 mutate 入参），跨行共享安全。
     *   <li>{@code compDataByLine}：整单一次 IN 查所有行的 (component_id, snapshot_rows, deleted_row_keys)，
     *       按 lineItemId 分桶（替代 buildCardValues 每行一次 compdata 查）。每元素 = [component_id, snapshot_rows, deleted_row_keys]，
     *       与逐行查 SELECT 列序一致 → buildCardValues prefetch 分支与逐行分支产物逐位相同。
     * </ul>
     */
    public static final class CardValuesPrefetch {
        final Map<UUID, JsonNode> templateSnapshotById;
        final Map<UUID, List<Object[]>> compDataByLine;
        /**
         * F1(方案 B)：componentId(字符串) → rowKeyFields 已解析节点。整单一次 IN 查替代 assemble 内
         * 每行每组件 {@code SELECT row_key_fields ...}（2550→1）。值用 {@link com.fasterxml.jackson.databind.node.NullNode}
         * 哨兵表示"已查、row_key_fields 为 null"(与 {@link #rowKeyFieldsMapFromScope()} 缺该 key 逐位等价),
         * 故 key 缺失=未预取→回落逐行查,key 存在但值=NullNode→该组件无行键(不再查库)。
         */
        final Map<String, JsonNode> rowKeyFieldsByComp;
        /**
         * F4：templateId → driver 组件清单（[c.id, c.bom_recursive_expand] 列表）。整单一次查替代
         * {@code expandFlatDriverBaseRows} 每行重发的 {@code SELECT DISTINCT ... template_component}
         * （仅依赖 templateId，跨行同值）。key 缺失=未预取→回落逐行查。
         */
        final Map<UUID, List<Object[]>> driverCompsByTemplate;
        /**
         * task-260825 D-3：建单时冻结的报价卡结构（{@code quotation_view_structure.kind=QUOTE_CARD}
         * 的 {@code tabs} 数组）。按 quotationId 整单一次查（{@link #loadFrozenQuoteTabs}），取代
         * {@link #buildCardValues} 原先每行一次的逐行查询——该查询的入参是 quotationId，整单恒定。
         * {@code null} 合法：表示该单没有冻结结构（首次组装尚未 ensureStructure / 历史单），
         * 调用方按原三级降级链继续回退 {@link #templateSnapshotById} → 模板表查询。
         */
        final JsonNode frozenQuoteTabs;
        CardValuesPrefetch(Map<UUID, JsonNode> t, Map<UUID, List<Object[]>> c, Map<String, JsonNode> rkf,
                           Map<UUID, List<Object[]>> dc, JsonNode frozenQuoteTabs) {
            this.templateSnapshotById = t;
            this.compDataByLine = c;
            this.rowKeyFieldsByComp = rkf;
            this.driverCompsByTemplate = dc;
            this.frozenQuoteTabs = frozenQuoteTabs;
        }
    }

    /**
     * B2 预取构建：解析报价/核价模板 snapshot 各一次 + 整单一次 IN 查所有行 compdata。
     * 失败降级——任一片缺失时对应 build 方法 prefetch 分支回落逐行查（getOrDefault / containsKey 判定）。
     */
    @Transactional
    public CardValuesPrefetch precomputeCardValuesPrefetch(UUID quotationId, java.util.Collection<UUID> lineItemIds) {
        Map<UUID, JsonNode> tplById = new HashMap<>();
        Map<UUID, List<Object[]>> byLine = new HashMap<>();
        Map<String, JsonNode> rkfByComp = new HashMap<>();
        Map<UUID, List<Object[]>> driverCompsByTpl = new HashMap<>();
        // task-260825 D-3：冻结报价卡结构整单一次查（quotationId 恒定，取代 buildCardValues 逐行查）。
        // loadFrozenQuoteTabs 内部已捕获异常返回 null，本处不需要再包 try。
        JsonNode frozenQuoteTabs = loadFrozenQuoteTabs(quotationId);
        try {
            Quotation q = Quotation.findById(quotationId);
            if (q != null) {
                parseTemplateSnapshotInto(tplById, q.customerTemplateId);
                parseTemplateSnapshotInto(tplById, q.costingCardTemplateId);
                // F4：driver 组件清单整单一次查（报价+核价模板各一次,替代 expandFlatDriverBaseRows 每行重查）。
                prefetchDriverComps(driverCompsByTpl, q.customerTemplateId);
                prefetchDriverComps(driverCompsByTpl, q.costingCardTemplateId);
            }
            // F1(方案 B)：从两份模板 snapshot 收集 distinct componentId，整单一次 IN 查 row_key_fields。
            prefetchRowKeyFields(tplById, rkfByComp);
            if (lineItemIds != null && !lineItemIds.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Object[]> rows = em.createNativeQuery(
                    "SELECT line_item_id, component_id, snapshot_rows, deleted_row_keys, row_data " +
                    "FROM quotation_line_component_data WHERE line_item_id IN (:ids)")
                    .setParameter("ids", lineItemIds)
                    .getResultList();
                for (Object[] r : rows) {
                    if (r[0] == null) continue;
                    UUID lid = (r[0] instanceof UUID u) ? u : UUID.fromString(r[0].toString());
                    // 仅保留 [component_id, snapshot_rows, deleted_row_keys, row_data]，与逐行查列序一致。
                    // BL-0127：row_data 是第 4 列 —— 预取路径与逐行路径必须同时带上，否则
                    // 「预取命中时不回种编辑、未命中才回种」= 一条链路好一条静默坏（AP-41 族）。
                    byLine.computeIfAbsent(lid, k -> new ArrayList<>())
                          .add(new Object[]{ r[1], r[2], r[3], r[4] });
                }
            }
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] precomputeCardValuesPrefetch failed quotation=%s: %s", quotationId, e.getMessage());
        }
        return new CardValuesPrefetch(tplById, byLine, rkfByComp, driverCompsByTpl, frozenQuoteTabs);
    }

    /**
     * F4：整单一次查某模板的 driver 组件清单（与 {@link #expandFlatDriverBaseRows} 内逐行查 <b>同一条 SQL</b>，
     * 结果仅依赖 templateId → 跨行同值）。kill switch {@code cpq.firstsave-drivercomps-prefetch}（默认 true）。
     */
    private void prefetchDriverComps(Map<UUID, List<Object[]>> into, UUID templateId) {
        if (templateId == null || into.containsKey(templateId)) return;
        boolean enabled = "true".equalsIgnoreCase(
            System.getProperty("cpq.firstsave-drivercomps-prefetch",
                System.getenv().getOrDefault("CPQ_FIRSTSAVE_DRIVERCOMPS_PREFETCH", "true")));
        if (!enabled) return;
        try {
            // task-0806 B8：改走 PublishedTemplateReader（冻结快照）。列形状(id, bom_recursive_expand)
            // 保持不变——下游 expandFlatDriverBaseRows 的 dc.length 兜底判断依赖这个 2 列旧形状。
            List<com.cpq.template.entity.TemplateComponentSnapshot> driverComps =
                publishedTemplateReader.driverCompsOf(templateId);
            List<Object[]> rows = new ArrayList<>(driverComps.size());
            for (com.cpq.template.entity.TemplateComponentSnapshot s : driverComps) {
                rows.add(new Object[]{ s.componentId, s.bomRecursiveExpand });
            }
            into.put(templateId, rows);
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] prefetchDriverComps failed tmpl=%s: %s（已降级,回落逐行查）", templateId, e.getMessage());
        }
    }

    /**
     * F1(方案 B)：整单一次经 {@link com.cpq.template.service.PublishedTemplateReader} 取两份模板
     * （{@code tplById} 的 key 即 {@code q.customerTemplateId}/{@code q.costingCardTemplateId}，至多 2 个）
     * 的全部冻结页签，按 componentId 落 {@code rkfByComp}（null/空/解析失败 → NullNode 哨兵）。
     *
     * <p>task-0806 B19：改走冻结快照，不再直读活 {@code component} 表（原
     * {@code SELECT id, row_key_fields FROM component WHERE id IN(...)}）。SQL 条数仍恒为
     * O(模板数)（≤2），与行数/组件数无关。
     *
     * <p>⚠️ 已知限度：若同一 componentId 同时被 {@code customerTemplateId} 与
     * {@code costingCardTemplateId} 两个模板引用，且两模板冻结时 {@code rowKeyFields} 恰好不同
     * （task-0806 版本漂移场景），本方法按模板遍历顺序后写覆盖先写——与旧实现（活表只有一行,
     * 天然无歧义）相比存在理论上的二义性；报价/核价两侧目前观察到的组件集合不重叠，暂未发现
     * 实际冲突（B19 验证已查库确认），后续如需彻底消除应改为按
     * {@code (templateId, componentId)} 复合键存储。
     *
     * <p>失败整体降级（map 留空 → assemble 回落逐行查），不影响正确性。
     */
    private void prefetchRowKeyFields(Map<UUID, JsonNode> tplById, Map<String, JsonNode> rkfByComp) {
        // kill switch: cpq.firstsave-rkf-prefetch(默认 true)。off → map 留空 → assemble 回落逐行查(1:1 旧行为)。
        boolean enabled = "true".equalsIgnoreCase(
            System.getProperty("cpq.firstsave-rkf-prefetch",
                System.getenv().getOrDefault("CPQ_FIRSTSAVE_RKF_PREFETCH", "true")));
        if (!enabled) return;
        if (tplById.isEmpty()) return;
        try {
            JsonNode nullSentinel = com.fasterxml.jackson.databind.node.NullNode.getInstance();
            Map<UUID, List<com.cpq.template.entity.TemplateComponentSnapshot>> byTpl =
                publishedTemplateReader.allTabsOfMany(tplById.keySet());
            for (List<com.cpq.template.entity.TemplateComponentSnapshot> tabs : byTpl.values()) {
                for (com.cpq.template.entity.TemplateComponentSnapshot s : tabs) {
                    if (s.componentId == null) continue;
                    String cid = s.componentId.toString();
                    // 逐位复刻旧 loadRowKeyFieldsNode 口径：cell null/blank → null；否则 readTree，失败 → null。
                    JsonNode node = nullSentinel;
                    if (s.rowKeyFields != null && !s.rowKeyFields.isBlank()) {
                        try {
                            JsonNode parsed = MAPPER.readTree(s.rowKeyFields);
                            if (parsed != null) node = parsed;
                        } catch (Exception ignore) { /* 解析失败 → null 哨兵(同旧实现 catch 口径) */ }
                    }
                    rkfByComp.put(cid, node);
                }
            }
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] prefetchRowKeyFields failed: %s（已降级,回落逐行查）", e.getMessage());
        }
    }

    /** 解析模板 components_snapshot 为 JsonNode 存入 map（templateId→snapshot）；失败/空跳过。 */
    private void parseTemplateSnapshotInto(Map<UUID, JsonNode> into, UUID templateId) {
        if (templateId == null || into.containsKey(templateId)) return;
        try {
            @SuppressWarnings("unchecked")
            var rows = em.createNativeQuery("SELECT components_snapshot FROM template WHERE id = :tid")
                .setParameter("tid", templateId).getResultList();
            if (!rows.isEmpty() && rows.get(0) != null) {
                JsonNode snap = MAPPER.readTree(rows.get(0).toString());
                if (snap.isArray()) into.put(templateId, snap);
            }
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] parseTemplateSnapshotInto failed tmpl=%s: %s", templateId, e.getMessage());
        }
    }

    // =========================================================================
    // task-0729 B8.2：《SUBTOTAL 双端对拍清单》—— 一次性只读对拍工具方法
    // =========================================================================

    /**
     * task-0729 B8.2：逐 line item 用【已含 B8.1 口径补齐】的 {@link #buildCardValues} 重算报价侧
     * SUBTOTAL（100% 只读——buildCardValues 只 SELECT + 纯内存计算，不落库任何字段，
     * 不影响 li.subtotal / quoteCardValues / 任何其它列），与已落库 {@code li.subtotal} 对比。
     *
     * <p>取值口径与 backtask B0-S6 一致：复用 {@link CostingSubtotalUtil#extractUnitSubtotal(String)}
     * 从组装出的 JSON 里找 {@code componentType==='SUBTOTAL'} 的 tab 取其 {@code subtotal}
     * （不新写一套提取逻辑）。
     *
     * <p>合并前一次性对拍用，非常驻业务端点；对全库逐单遍历，量级见 §11.17.0（35 单/25 line_item），
     * 单机跑量级可接受，不做分页/异步。
     *
     * @return 每行 {quotationNo, materialNo, quotationStatus, liSubtotal, computedSubtotal, diff, note}
     */
    public List<Map<String, Object>> reconcileQuoteSubtotalsForTask0729B8() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
            "SELECT li.id, q.quotation_number, " +
            "       COALESCE(li.product_part_no_snapshot, li.customer_part_no, '') AS material_no, " +
            "       li.subtotal, q.customer_template_id, q.status " +
            "FROM quotation_line_item li " +
            "JOIN quotation q ON q.id = li.quotation_id " +
            "ORDER BY q.quotation_number, li.sort_order").getResultList();

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            UUID liId = (UUID) r[0];
            String quotationNo = (String) r[1];
            String materialNo = (String) r[2];
            java.math.BigDecimal liSubtotal = (java.math.BigDecimal) r[3];
            UUID templateId = (UUID) r[4];
            String status = (String) r[5];

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("quotationNo", quotationNo);
            row.put("materialNo", materialNo);
            row.put("quotationStatus", status);
            row.put("liSubtotal", liSubtotal);

            if (templateId == null) {
                row.put("note", "无 customerTemplateId，跳过（可能是选配/异常单）");
                out.add(row);
                continue;
            }
            try {
                QuotationLineItem li = QuotationLineItem.findById(liId);
                if (li == null) {
                    row.put("note", "line item 已不存在（并发删除）");
                    out.add(row);
                    continue;
                }
                String cardJson = buildCardValues(li, templateId, null); // 只读：不持久化
                java.math.BigDecimal computed = CostingSubtotalUtil.extractUnitSubtotal(cardJson);
                row.put("computedSubtotal", computed);
                java.math.BigDecimal base = liSubtotal != null ? liSubtotal : java.math.BigDecimal.ZERO;
                row.put("diff", com.cpq.common.PrecisionPolicy.roundForCalculation(base.subtract(computed)));
            } catch (Exception e) {
                row.put("note", "重算异常: " + e.getMessage());
                LOG.warnf("[b8-reconcile] li=%s quotation=%s failed: %s", liId, quotationNo, e.getMessage());
            }
            out.add(row);
        }
        return out;
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
    /**
     * task-0729 B0：可见性升级为 public（不改逻辑），供 {@code com.cpq.priceadjust.service.MaterialVersionUpgradeService}
     * 的 S0（L3 口径守卫，需要用旧价重跑一遍）与 S5（重算卡片）复用，不新写第二份报价侧卡片值组装逻辑。
     */
    public String buildCardValues(QuotationLineItem li, UUID templateId) {
        return buildCardValues(li, templateId, null);
    }

    /** B2 重载：{@code prefetch!=null} 时复用预取模板 snapshot + 整单 IN compdata；{@code null}=逐行查（零破坏）。 */
    String buildCardValues(QuotationLineItem li, UUID templateId, CardValuesPrefetch prefetch) {
        return buildCardValues(li, templateId, prefetch, null);
    }

    /**
     * repair-0803 B1 重载：{@code errOut != null} 时把失败原文写入 {@code errOut[0]}，
     * 供调用方落<b>带错误原文的哨兵</b>（{@link #failedSentinelWithError}）而非通用哨兵。
     *
     * <p><b>为什么需要</b>：报价侧原先只落 {@link #orSentinel} 通用哨兵，前端仅显示
     * 「该料号卡片数据待重算」而<b>不含任何错误原文</b>——核价侧早有此能力（BL-0030，
     * {@code costingRenderError} → {@code failedSentinelWithError}），报价侧一直缺。
     * 2026-08-02 排查 QT-20260802-0049 时，因看不到是哪条 SQL 出错，只能靠翻后端控制台日志
     * （dev 环境还不落日志文件），排查成本极高。本重载把该能力补齐到报价侧。
     *
     * <p>行为完全向后兼容：{@code errOut == null}（即原 3 参重载）时与改造前逐字节一致。
     */
    String buildCardValues(QuotationLineItem li, UUID templateId, CardValuesPrefetch prefetch,
                           String[] errOut) {
        if (li == null || li.id == null || templateId == null) return null;
        try {
            // 1. 配置源：**优先用建单时冻结的 quotation_view_structure**（严格冻结语义）。
            //
            //    为什么不能直接用 template.components_snapshot：结构创建即冻（68fed021「草稿默认冻结」），
            //    前端渲染与它算 li.subtotal 全读这份冻结结构；而模板快照会随组件保存自动刷新
            //    （ComponentService → refreshSnapshotsByComponent）。两者一旦分叉——例如给组件新加
            //    conditional_formula——就会出现「后端按新配置算出卡片小计 214、前端按冻结配置算出
            //    总额 14」这种同卡双值。配置源归一到冻结结构后，两侧恒等。
            //
            //    冻结结构缺失（首次组装尚未 ensureStructure / 历史单）→ 回退模板快照（旧行为，零破坏）。
            //
            //    task-260825 D-3：该查询入参是 quotationId，整单恒定 —— prefetch 命中时直接复用
            //    prefetch.frozenQuoteTabs（已在 precomputeCardValuesPrefetch 里整单查一次），
            //    取代原先每行一次的 loadFrozenQuoteTabs 调用（1845 行 → 1845 次往返 ≈31s，撑爆
            //    Narayana 60s 事务预算）。prefetch 缺失（非批量路径）→ 逐行查，行为与改动前一致。
            JsonNode snapshot = (prefetch != null) ? prefetch.frozenQuoteTabs : loadFrozenQuoteTabs(li.quotationId);
            if (snapshot == null) {
                snapshot = (prefetch != null) ? prefetch.templateSnapshotById.get(templateId) : null;
                if (snapshot == null) {
                    @SuppressWarnings("unchecked")
                    var tmplRows = em.createNativeQuery(
                        "SELECT components_snapshot FROM template WHERE id = :tid")
                        .setParameter("tid", templateId)
                        .getResultList();
                    if (tmplRows.isEmpty() || tmplRows.get(0) == null) return null;
                    snapshot = MAPPER.readTree(tmplRows.get(0).toString());
                }
            }
            if (snapshot == null || !snapshot.isArray()) return null;

            // 2. snapshot_rows + deleted_row_keys —— prefetch 命中取整单 IN 预取的本行分桶，否则逐行查
            List<Object[]> compData;
            if (prefetch != null && prefetch.compDataByLine != null) {
                compData = prefetch.compDataByLine.getOrDefault(li.id, java.util.List.of());
            } else {
                @SuppressWarnings("unchecked")
                List<Object[]> q = em.createNativeQuery(
                    "SELECT component_id, snapshot_rows, deleted_row_keys, row_data " +
                    "FROM quotation_line_component_data WHERE line_item_id = :lid")
                    .setParameter("lid", li.id)
                    .getResultList();
                compData = q;
            }

            Map<String, String> snapByCompId = new LinkedHashMap<>();
            Map<String, List<DeletedRowKeys.Tombstone>> delByComp = new HashMap<>();
            // BL-0127：row_data = 用户「已定值」的承载（前端 bake/编辑都写这里），供下方回种 editRows。
            Map<String, String> rowDataByCompId = new LinkedHashMap<>();
            for (Object[] r : compData) {
                if (r[0] == null) continue;
                String cid = r[0].toString();
                if (r[1] != null) snapByCompId.put(cid, r[1].toString());
                delByComp.put(cid, DeletedRowKeys.parse(r[2] == null ? null : r[2].toString()));
                if (r.length > 3 && r[3] != null) rowDataByCompId.put(cid, r[3].toString());
            }

            // 3. 预构建每个组件的 baseRows（按 componentId）
            // task-0721 B7/B11：剪枝节点(quotation_line_item.deleted_tree_nodes)按 __nodeId 前缀匹配
            // 隐藏整枝——跨该行所有树页签联动(prefix 匹配天然跨组件生效，因 nodeId 命名空间对同一
            // line item 的所有树页签共享)。非树行(无 __nodeId)不受影响。小计与前端展示同源：两者都读
            // 这份已剪枝的 baseRowsByComp，不会各读各的（AP-22 族纪律）。
            List<String> prunedNodeIds = parsePrunedNodeIds(li.deletedTreeNodes);
            Map<String, ArrayNode> baseRowsByComp = new LinkedHashMap<>();
            for (JsonNode tab : snapshot) {
                String cid = tab.path("componentId").asText("");
                ArrayNode rows = buildBaseRowsFromSnapshotRows(snapByCompId.get(cid), cid);
                if (!prunedNodeIds.isEmpty()) rows = filterPrunedTreeRows(rows, prunedNodeIds);
                baseRowsByComp.put(cid, rows);
            }

            // 4. 组装 tabs（Task 3: 填 formulaResults；报价侧传真实墓碑）
            // F1：报价侧透传 rkf 预取（prefetch 缺失 → null → 回落逐行查）
            // task-0729 B8.1：产品属性上下文（口径补齐，当前 0 usage 场景零额外查询开销）
            // BL-0127：末位透传 row_data —— editRows 不再恒空，由 seedEditRowsFromRowData 从
            //   「用户已定值」回种。原先此处硬编码 editRows=null，叠加 saveDraft 的 D-1 置 NULL，
            //   使用户编辑永远进不了快照（行内公式列冻在 driver 默认口径，而列小计走前端实时引擎 → 分叉）。
            ObjectNode root = assembleTabsWithFormulaResults(snapshot, baseRowsByComp, null, null, delByComp,
                prefetch != null ? prefetch.rowKeyFieldsByComp : null,
                buildProductAttributesContext(li, li.quotationId, templateId),
                rowDataByCompId);

            return MAPPER.writeValueAsString(root);

        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildCardValues failed li=%s tmpl=%s: %s",
                li.id, templateId, e.getMessage());
            // repair-0803 B1：把失败原文透出给调用方（errOut==null 时行为与改造前一致）。
            // 注意用 e.toString() 而非 getMessage()——PG 的 "current transaction is aborted" 类异常
            // getMessage() 常为空或过短，带上异常类名才能定位到真正的首因。
            if (errOut != null && errOut.length > 0) {
                String msg = e.getMessage();
                errOut[0] = "卡片渲染失败: " + ((msg == null || msg.isBlank()) ? e.toString() : msg);
            }
            return null;
        }
    }

    /**
     * 读建单时冻结的报价卡结构的 {@code tabs} 数组，供 {@link #buildCardValues} 按冻结配置算值。
     *
     * <p><b>为什么可以直接喂给 FormulaCalculator</b>：结构是 camelCase 投影（{@code fieldType} /
     * {@code isSubtotal} / {@code conditionalFormula} …），而 FormulaCalculator 的 field 访问器
     * 全部 camelCase + snake_case 双读（见其「field 访问器」段），tab 级只消费
     * {@code componentId / componentCode / componentType / tabName / fields / formulas /
     * formula_assignments} 七个键，结构侧均已搬运。
     *
     * @return tabs 数组；无冻结结构 / 形状异常 / 读取失败 → {@code null}（调用方回退模板快照）
     */
    /**
     * 报价侧算值的**唯一配置源入口**：优先建单时冻结的结构，缺失回退模板快照。
     *
     * <p>报价侧所有算值路径（首次物化 / 草稿刷新 / 单元格编辑 / 存草稿投影 / 公式试算）
     * 必须经此取配置，否则就会重演「一条路径读冻结结构、另一条读会自动刷新的模板快照」
     * 导致的同卡双值（卡片小计 214 vs 报价总额 14）。新增算值路径时一并接这里。
     */
    private JsonNode loadQuoteTabsForValues(UUID quotationId, UUID templateId) {
        JsonNode frozen = loadFrozenQuoteTabs(quotationId);
        return frozen != null ? frozen : loadComponentsSnapshot(templateId);
    }

    /**
     * 显式刷新专用配置读取：数据缺失返回 null，历史 JSON 形状/解析失败由调用方降级为 no-op；
     * 数据库查询异常不捕获，确保原子刷新能定位到当前行并整单回滚。
     */
    private JsonNode loadQuoteTabsForRefresh(UUID quotationId, UUID templateId) {
        if (quotationId != null) {
            @SuppressWarnings("unchecked")
            List<Object> frozenRows = em.createNativeQuery(
                "SELECT structure FROM quotation_view_structure " +
                "WHERE quotation_id = :qid AND view_kind = :kind")
                .setParameter("qid", quotationId)
                .setParameter("kind", "QUOTE_CARD")
                .getResultList();
            if (!frozenRows.isEmpty() && frozenRows.get(0) != null) {
                try {
                    JsonNode root = MAPPER.readTree(frozenRows.get(0).toString());
                    JsonNode tabs = root == null ? null : root.get("tabs");
                    if (root == null || !root.isObject() || tabs == null || !tabs.isArray()) {
                        throw new HistoricalCardValuesException(
                            "Frozen quote-card structure is not a normalized {tabs:[...]} object");
                    }
                    return tabs;
                } catch (HistoricalCardValuesException e) {
                    throw e;
                } catch (Exception e) {
                    throw new HistoricalCardValuesException("Unable to parse frozen quote-card structure", e);
                }
            }
        }

        if (templateId == null) return null;
        @SuppressWarnings("unchecked")
        List<Object> templateRows = em.createNativeQuery(
            "SELECT components_snapshot FROM template WHERE id = :tid")
            .setParameter("tid", templateId)
            .getResultList();
        if (templateRows.isEmpty() || templateRows.get(0) == null) return null;
        try {
            JsonNode snapshot = MAPPER.readTree(templateRows.get(0).toString());
            return snapshot != null && snapshot.isArray() ? snapshot : null;
        } catch (Exception e) {
            throw new HistoricalCardValuesException("Unable to parse template components_snapshot", e);
        }
    }

    private JsonNode loadFrozenQuoteTabs(UUID quotationId) {
        if (quotationId == null) return null;
        try {
            @SuppressWarnings("unchecked")
            var rows = em.createNativeQuery(
                "SELECT structure FROM quotation_view_structure " +
                "WHERE quotation_id = :qid AND view_kind = :kind")
                .setParameter("qid", quotationId)
                .setParameter("kind", "QUOTE_CARD")
                .getResultList();
            if (rows.isEmpty() || rows.get(0) == null) return null;
            JsonNode tabs = MAPPER.readTree(rows.get(0).toString()).path("tabs");
            return tabs.isArray() ? tabs : null;
        } catch (Exception e) {
            // 读不到不阻断算值：回退模板快照（与本方法引入前的行为一致）
            LOG.warnf("[card-snapshot] loadFrozenQuoteTabs failed q=%s: %s", quotationId, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // buildCostingCardValues — 核价侧单独 expand（无现成快照）
    // =========================================================================

    /** 进程级缓存：templateId → 该核价模板下是否存在树页签组件（bom_recursive_expand=true）。
     * 供批量层判定走 {@link BomTreeRenderService}（含树页签）还是非树页签平铺路径
     * （{@link #expandFlatDriverBaseRows}）。模板组件挂载改动频率低，TTL 30s
     * （与既有 expandCache 同量级）足够新鲜度，避免每批次重查。 */
    private final com.github.benmanes.caffeine.cache.Cache<UUID, Boolean> treeTabCache =
        com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .expireAfterWrite(30, java.util.concurrent.TimeUnit.SECONDS)
            .maximumSize(500)
            .build();

    /**
     * Task 3.1 事项B：该核价模板是否含树页签组件（勾了 {@code bom_recursive_expand} 的 driver 组件）。
     *
     * <p>🔒 task-0806：可见性从包内可见放宽为 {@code public}（纯签名放宽，逻辑一字未改），供
     * {@code PriceAdjustJobExecutionService#precomputeBatch}（跨包）复用同一份判定——批量预渲染
     * 分组前必须先过这道门槛，否则对"不含树页签"的核价模板，批量路径（直接喂
     * {@code precomputedBaseRows}）与老路径（{@code templateHasTreeTab==false} 时走
     * {@link #expandFlatDriverBaseRows} 旧引擎）会分叉进 {@code buildCostingCardValues} 的不同分支，
     * 产出可能不同。不新写第二份判定，直接复用本方法。
     */
    public boolean templateHasTreeTab(UUID templateId) {
        if (templateId == null) return false;
        Boolean cached = treeTabCache.getIfPresent(templateId);
        if (cached != null) return cached;
        // task-0806 B8：改走 PublishedTemplateReader（冻结快照），不再直读活 component 表。
        boolean has = publishedTemplateReader.hasRecursiveExpand(templateId);
        treeTabCache.put(templateId, has);
        return has;
    }

    /**
     * 核价侧卡片值：加载核价模板 driver 组件，按报价行 partNo/compositeType 展开一次，
     * 组装 tabs[].{baseRows, editRows, formulaResults}。
     * 与报价侧 buildCardValues 相比：这里 expand 是必要的（非双写），
     * 因为 snapshotLines 只快照报价模板组件。
     */
    private String buildCostingCardValues(QuotationLineItem li, UUID costingTemplateId,
                                           UUID customerId, UUID quotationId) {
        return buildCostingCardValues(li, costingTemplateId, customerId, quotationId, null);
    }

    /** P2-C4 重载：透传整单 union 预取 Map(null=逐行旧路径)。包级可见供 A/B 等价测试(纯读、不写 managed)。 */
    String buildCostingCardValues(QuotationLineItem li, UUID costingTemplateId,
                                           UUID customerId, UUID quotationId,
                                           Map<UUID, Map<String, ExpandDriverResponse>> unionByComp) {
        return buildCostingCardValues(li, costingTemplateId, customerId, quotationId, unionByComp, null);
    }

    /** B2 重载：{@code prefetch!=null} 时复用预取核价模板 snapshot；{@code null}=逐行读+解析（零破坏）。
     * delegate 到七参重载（{@code precomputedBaseRows=null}）→ 七参重载内部按 {@link #templateHasTreeTab}
     * 自行判定走树渲染兜底还是非树页签平铺路径，本重载零负担。 */
    String buildCostingCardValues(QuotationLineItem li, UUID costingTemplateId,
                                           UUID customerId, UUID quotationId,
                                           Map<UUID, Map<String, ExpandDriverResponse>> unionByComp,
                                           CardValuesPrefetch prefetch) {
        return buildCostingCardValues(li, costingTemplateId, customerId, quotationId, unionByComp, prefetch, null);
    }

    /**
     * 七参重载（Task 3.1 事项B + 正确性兜底）：{@code baseRowsByComp} 来源三分支——
     * <ol>
     *   <li>{@code precomputedBaseRows != null}：批量层已整单调过 {@link BomTreeRenderService#render}
     *       并按 lineItemId 拆好，直接复用（最优路径，不重复 render）。</li>
     *   <li>{@code precomputedBaseRows == null} 且 {@link #templateHasTreeTab} 为真：说明调用方（未接线的
     *       入口 / 单测）没有预先 render，但该模板确实含树页签 —— 不能静默退化到平铺路径丢树结构，
     *       就地单行调 {@code bomTreeRenderService.render(costingTemplateId, List.of(li))} 兜底
     *       （与 {@link #snapshotCostingSideOnly} 的单行兜底同款做法）。render 无结果时退化为空 map，不抛。</li>
     *   <li>{@code templateHasTreeTab} 为假：非树页签平铺路径（{@link #expandFlatDriverBaseRows}）。</li>
     * </ol>
     * 批量层接线因此退化为**纯性能优化**（省去本方法内部再 render 一次）；任何入口调用本方法都能拿到
     * 正确的树结构，不存在"忘记接线就静默错渲染"的隐患。
     *
     * <p>新契约用递归 SQL 直接建树，无「环检测」概念，故此路径<b>不回传 cyclePartNos</b>
     * （root 节点不含 {@code cyclePartNos} 字段；前端「已截断展开」告警此契约暂无等价物）。
     */
    String buildCostingCardValues(QuotationLineItem li, UUID costingTemplateId,
                                           UUID customerId, UUID quotationId,
                                           Map<UUID, Map<String, ExpandDriverResponse>> unionByComp,
                                           CardValuesPrefetch prefetch,
                                           Map<String, ArrayNode> precomputedBaseRows) {
        if (li == null || li.id == null || costingTemplateId == null) return null;
        try {
            // 1. 取核价模板 components_snapshot —— prefetch 命中复用已解析，否则逐行读+解析
            JsonNode snapshot = (prefetch != null) ? prefetch.templateSnapshotById.get(costingTemplateId) : null;
            if (snapshot == null) {
                @SuppressWarnings("unchecked")
                var tmplRows = em.createNativeQuery(
                    "SELECT components_snapshot FROM template WHERE id = :tid")
                    .setParameter("tid", costingTemplateId)
                    .getResultList();
                if (tmplRows.isEmpty() || tmplRows.get(0) == null) return null;
                snapshot = MAPPER.readTree(tmplRows.get(0).toString());
            }
            if (snapshot == null || !snapshot.isArray()) return null;

            Map<String, ArrayNode> baseRowsByComp;
            if (precomputedBaseRows != null) {
                // 含树页签：批量层已整单调 BomTreeRenderService 渲染好，直接用（最优路径，不重复 render）。
                baseRowsByComp = precomputedBaseRows;
            } else if (templateHasTreeTab(costingTemplateId)) {
                // 安全兜底：未接线的调用入口（或单测）没传 precomputedBaseRows，但该模板确实含树页签 ——
                // 不能静默退化到平铺路径（会丢树结构）。就地单行调 BomTreeRenderService.render，
                // 与 snapshotCostingSideOnly 的单行兜底同款做法。render 失败/无结果 → 退化为空 map（不抛）。
                Map<String, ArrayNode> fb = bomTreeRenderService
                    .render(costingTemplateId, java.util.List.of(li))
                    .get(li.id);
                baseRowsByComp = (fb != null) ? fb : new LinkedHashMap<>();
            } else {
                // 不含树页签：非树页签平铺路径，逐组件按 partNo 展开（无 spine/闭包）。
                // F4：透传整单预取的 driver 组件清单（prefetch 缺失 → null → 回落逐行查）
                List<Object[]> driverCompsPrefetch = (prefetch != null && prefetch.driverCompsByTemplate != null)
                    ? prefetch.driverCompsByTemplate.get(costingTemplateId) : null;
                baseRowsByComp = expandFlatDriverBaseRows(costingTemplateId, li, customerId, quotationId,
                    unionByComp, driverCompsPrefetch);
            }

            // 5. 组装 tabs（Task 3: 填 formulaResults；核价侧 editRows 恒空）
            // 核价侧 side==COSTING 显式不传墓碑（spec §3.7 隔离）：editRowsByComp=null + rkfOverride=null + delByComp=null。
            // F1（B-1 修正）：核价侧也透传 rkf 预取（否则核价 ~1020 次 row_key_fields 单查原样保留）。
            // task-0729 B8.1：产品属性上下文（优先取报价侧冻结结构 schema，缺失回退核价模板自身）
            ObjectNode root = assembleTabsWithFormulaResults(snapshot, baseRowsByComp, null, null, null,
                prefetch != null ? prefetch.rowKeyFieldsByComp : null,
                buildProductAttributesContext(li, quotationId, costingTemplateId));

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

    /**
     * 从 snapshot_rows JSON 反序列化为 baseRows ArrayNode（[{driverRow,basicDataValues, ...系统列}]）。
     *
     * <p><b>task-0721 B3</b>：树页签行携带 {@code __nodeId/__parentId/__lvl/__hfPartNo/__parentNo/
     * __bomVersion/__nodeType} 等系统列（{@code BomTreeRenderService.treeRowNode} 写入，与
     * {@code driverRow}/{@code basicDataValues} 同级）。原实现经窄 POJO
     * {@code List<ExpandDriverResponse.Row>}（仅声明这两个字段）反序列化——Jackson 默认
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=true}，遇到额外的 {@code __xxx} 顶层字段会直接抛异常，
     * 被下方 catch 吞掉后返回<b>空</b> baseRows（AP-31 型静默失败：树页签渲染出全空白）。
     * 改为通用 {@link JsonNode} 解析 + 逐行 deep copy，系统列随 JSON 原样透传（"纯加法"，
     * backtask B3 要点）。对既有平铺组件（JSON 恒为 {@code [{"driverRow":..,"basicDataValues":..}]}，
     * 无额外字段）产出与原实现逐位相同，零回归。
     */
    private ArrayNode buildBaseRowsFromSnapshotRows(String rowsJson, String componentId) {
        ArrayNode baseRows = MAPPER.createArrayNode();
        if (rowsJson == null || rowsJson.isBlank()) return baseRows;
        try {
            JsonNode parsed = MAPPER.readTree(rowsJson);
            if (parsed != null && parsed.isArray()) {
                for (JsonNode n : parsed) {
                    ObjectNode rowNode = n.isObject() ? ((ObjectNode) n).deepCopy() : MAPPER.createObjectNode();
                    if (!rowNode.has("driverRow") || rowNode.get("driverRow").isNull()) {
                        rowNode.set("driverRow", MAPPER.createObjectNode());
                    }
                    if (!rowNode.has("basicDataValues") || rowNode.get("basicDataValues").isNull()) {
                        rowNode.set("basicDataValues", MAPPER.createObjectNode());
                    }
                    baseRows.add(rowNode);
                }
            }
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildBaseRows deserialize failed comp=%s: %s", componentId, e.getMessage());
        }
        return baseRows;
    }

    /**
     * task-0721 B7：解析 {@code quotation_line_item.deleted_tree_nodes}（JSON 字符串数组）。
     * repair-0727 B5：委托 {@link com.cpq.quotation.rowkey.PrunedTreeNodes#parse}（提取为可复用 static
     * 工具，供 {@code RowKeyUniquenessService} 同源复用，避免各读各的，行为逐字节不变）。
     */
    private static List<String> parsePrunedNodeIds(String json) {
        return com.cpq.quotation.rowkey.PrunedTreeNodes.parse(json);
    }

    /**
     * task-0721 B7/B11：按 {@code __nodeId} 前缀匹配剔除被剪枝的树节点行（该节点自身 + 全部子孙）。
     * 非树行（无 {@code __nodeId}）原样保留。前端展示（此处）与后端小计（同一份 baseRowsByComp
     * 流入 {@code assembleTabsWithFormulaResults}）共享同一过滤结果，天然同源，不会各读各的。
     * repair-0727 B5：命中判断委托 {@link com.cpq.quotation.rowkey.PrunedTreeNodes#isPruned}
     * （同一份前缀匹配实现，供 {@code RowKeyUniquenessService} 复用，行为逐字节不变）。
     */
    private static ArrayNode filterPrunedTreeRows(ArrayNode rows, List<String> prunedNodeIds) {
        if (rows == null || rows.isEmpty() || prunedNodeIds == null || prunedNodeIds.isEmpty()) return rows;
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode row : rows) {
            JsonNode nodeIdNode = row.get("__nodeId");
            if (nodeIdNode == null || nodeIdNode.isNull()) {
                out.add(row); // 非树行,不受剪枝影响
                continue;
            }
            String nodeId = nodeIdNode.asText("");
            if (!com.cpq.quotation.rowkey.PrunedTreeNodes.isPruned(nodeId, prunedNodeIds)) out.add(row);
        }
        return out;
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
        // 五参签名零破坏：delegate 到六参（rkfPrefetch=null → 逐行查 row_key_fields，行为不变）。
        return assembleTabsWithFormulaResults(snapshot, baseRowsByComp, editRowsByComp, rkfOverride, delByComp, null);
    }

    /**
     * 六参重载（F1/方案 B：rowKeyFields 整单预取）：{@code rkfPrefetch} 非空时,组件行键从预取内存读
     * （key 缺失→回落 {@link #rowKeyFieldsMapFromScope()}；值=NullNode 哨兵→该组件无行键,不查库）。
     * {@code rkfPrefetch=null} 时全程走 {@link #rowKeyFieldsMapFromScope()}（task-0806 B19 起不再直读活表）。
     * 六参签名零破坏：delegate 到七参（productAttributesCtx=null → 空 map，行为不变）。
     */
    private ObjectNode assembleTabsWithFormulaResults(JsonNode snapshot, Map<String, ArrayNode> baseRowsByComp,
                                                      Map<String, ArrayNode> editRowsByComp,
                                                      Map<String, JsonNode> rkfOverride,
                                                      Map<String, List<DeletedRowKeys.Tombstone>> delByComp,
                                                      Map<String, JsonNode> rkfPrefetch) {
        return assembleTabsWithFormulaResults(snapshot, baseRowsByComp, editRowsByComp, rkfOverride, delByComp,
            rkfPrefetch, null);
    }

    /**
     * 七参重载（task-0729 B8.1，D3 口径补齐）：{@code productAttributesCtx} 非空时供
     * {@code product_attribute} token 求值（对齐前端 {@code QuotationStep2.tsx:1302-1307}
     * {@code evalProductSubtotalFromSubtotals} 的 NUMBER 型产品属性上下文）。{@code null}=空 map，
     * 与改造前逐位一致（零破坏）；当前库 0 组件引用该 token，属纯防御补齐。
     */
    private ObjectNode assembleTabsWithFormulaResults(JsonNode snapshot, Map<String, ArrayNode> baseRowsByComp,
                                                      Map<String, ArrayNode> editRowsByComp,
                                                      Map<String, JsonNode> rkfOverride,
                                                      Map<String, List<DeletedRowKeys.Tombstone>> delByComp,
                                                      Map<String, JsonNode> rkfPrefetch,
                                                      Map<String, BigDecimal> productAttributesCtx) {
        // 七参签名零破坏：delegate 到八参（rowDataByComp=null → 不回种，行为逐位不变）。
        return assembleTabsWithFormulaResults(snapshot, baseRowsByComp, editRowsByComp, rkfOverride, delByComp,
            rkfPrefetch, productAttributesCtx, null);
    }

    /**
     * 八参重载（BL-0127）：{@code rowDataByComp} 非空时，从 {@code quotation_line_component_data.row_data}
     * <b>回种用户已定值的 INPUT* 列</b>为 editRows，再进入既有 PASS1/PASS2。
     *
     * <p><b>为什么需要</b>：{@link #buildCardValues} 原先对 editRows 传 {@code null}（"加产品时恒空"），
     * 而 {@code saveDraft} 的 D-1 失效会把整份 {@code quote_card_values}（含 editRows）置 NULL，
     * 于是「用户编辑 → 快照」这条路被彻底掐断：重建后的 formulaResults 永远是 driver 默认值口径。
     * 前端行内 FORMULA 单元格优先读该快照、列小计却走本地实时引擎 → 同一列两个值（本任务报障现象）。
     *
     * <p><b>口径</b>：「键存在即已定值」——与前端 bake 守卫（{@code QuotationStep2.tsx} 的
     * {@code isKeyUnset}）和 {@code 2026-08-03 row-data-snapshot-authority} 的 D1/D2 同一判据：
     * {@code row_data} 行里只要该 INPUT* 键存在（哪怕值是空串）就是用户已定值，回种；键不存在才交给
     * 后端按 {@code default_source} 解析。
     *
     * <p>{@code rowDataByComp=null}（核价侧 / editCardValue / 加产品 / 单测旧调用）→ 与改造前逐位一致。
     */
    private ObjectNode assembleTabsWithFormulaResults(JsonNode snapshot, Map<String, ArrayNode> baseRowsByComp,
                                                      Map<String, ArrayNode> editRowsByComp,
                                                      Map<String, JsonNode> rkfOverride,
                                                      Map<String, List<DeletedRowKeys.Tombstone>> delByComp,
                                                      Map<String, JsonNode> rkfPrefetch,
                                                      Map<String, BigDecimal> productAttributesCtx,
                                                      Map<String, String> rowDataByComp) {
        final Map<String, BigDecimal> productAttrs =
            productAttributesCtx != null ? productAttributesCtx : java.util.Map.of();
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode tabs = root.putArray("tabs");

        // B1: per-call computeRows 复用缓存——同次 assemble 内，仅当 tab 不读 componentSubtotals/crossTabRows
        // 时，PASS1(小计) 与 PASS2(结果×1~2) 共用一份 computeRows，避免对同一输入重复逐行求值。
        // 局部对象、单线程使用 → 线程安全（守 expand/公式层非并发约束）。
        final FormulaCalculator.RowCache rowCache = formulaCalculator.newRowCache();

        final ArrayNode emptyEdit = MAPPER.createArrayNode();
        // rowKeyFields 缓存（每组件一次）。F1：优先读整单预取(命中=0 往返);未命中或无预取→回落
        // rowKeyFieldsMapFromScope()（task-0806 B19：整模板一次经 PublishedTemplateReader 冻结快照取，
        // 不再逐组件查活 component 表）。懒加载：全部命中 rkfPrefetch 时 rkfScopeFallback 永不计算。
        Map<String, JsonNode> rkfByComp = new LinkedHashMap<>();
        Map<String, JsonNode> rkfScopeFallback = null;
        for (JsonNode tab : snapshot) {
            String cid = tab.path("componentId").asText("");
            if (!rkfByComp.containsKey(cid)) {
                JsonNode hit = (rkfPrefetch != null) ? rkfPrefetch.get(cid) : null;
                JsonNode rkf;
                if (hit != null) {
                    // hit 存在：NullNode 哨兵→null(无行键)；真实节点→用之。
                    rkf = hit.isNull() ? null : hit;
                } else {
                    // hit 缺失→回落模板域快照（首次未命中才查，同一 assemble 调用内只查一次）。
                    if (rkfScopeFallback == null) rkfScopeFallback = rowKeyFieldsMapFromScope();
                    rkf = rkfScopeFallback.get(cid);
                }
                rkfByComp.put(cid, rkf);
            }
        }
        // v6-N 草稿行键覆盖：装配里 rkfByComp 默认读持久化行键；试算时把宿主 cid 行键覆盖为草稿行键。
        if (rkfOverride != null) rkfByComp.putAll(rkfOverride);

        // 草稿重刷：旧 editRows 按 rowKey 对齐到新 baseRows，丢弃新数据中不存在的 rowKey（AP-54 业务键对齐）
        Map<String, ArrayNode> filteredEdit = filterEditRowsToNewBaseRows(
            snapshot, baseRowsByComp, editRowsByComp, rkfByComp, emptyEdit, delByComp);

        // BL-0127 回种：必须放在 filterEditRowsToNewBaseRows **之后** —— 该方法按不带 __nodeId 前缀的
        // computeRowKey 建 newKeys 集合，而回种产出的是带前缀的权威键（buildRawRowKeys），
        // 放前面会被它整批过滤掉（树页签尤甚）。回种键本就是照当前 baseRows 算的，无需再过滤。
        // 显式 editRows（editCardValue 写的）优先级更高：同 (rowKey, 字段) 冲突时保留既有值。
        if (rowDataByComp != null && !rowDataByComp.isEmpty()) {
            seedEditRowsFromRowData(snapshot, baseRowsByComp, rowDataByComp, rkfByComp, delByComp, filteredEdit);
        }

        // PASS 1: componentSubtotals（顺序累加，后 tab 可引用前 tab 小计；含保留的 editRows）
        // 报价侧：传入 deleted 以反映永久删除行后的正确小计基数（核价侧 delByComp=null → 不过滤）
        Map<String, BigDecimal> componentSubtotals = new java.util.HashMap<>();
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
                rkfByComp.get(cid), baseRows, editRows, componentSubtotals, deleted, rkfNames,
                rowCache, cid.isBlank() ? null : cid);
            // task-0810：累加和 componentSubtotals 全程 BigDecimal，节点出口统一 12 位。
            java.math.BigDecimal subBd = java.math.BigDecimal.ZERO;
            for (java.math.BigDecimal v : byCol.values()) subBd = subBd.add(v);
            BigDecimal sub = subBd;
            String code = tab.path("componentCode").asText(null);
            String tabName = tab.path("tabName").asText("");
            if (!cid.isBlank()) componentSubtotals.put(cid, sub);
            if (code != null && !code.isBlank()) componentSubtotals.put(code, sub);
            componentSubtotals.put(tabName, sub);
            // Plan 2-核心：per-column 键 `${key}#${列名}`，供按列引用/显示。
            for (java.util.Map.Entry<String, java.math.BigDecimal> e : byCol.entrySet()) {
                BigDecimal cv = e.getValue();
                if (!cid.isBlank()) componentSubtotals.put(cid + "#" + e.getKey(), cv);
                if (code != null && !code.isBlank()) componentSubtotals.put(code + "#" + e.getKey(), cv);
                componentSubtotals.put(tabName + "#" + e.getKey(), cv);
            }
        }

        // PASS 2: 按组件 cross_tab_ref 依赖拓扑序逐 tab 算（A 必须先于引用它的 B），
        //   每算完一个组件即把其"按字段名标量行"(resolvedRows) 存入 crossTabRows 供后续组件引用。
        //   输出顺序仍按原 snapshot 顺序（拓扑序只决定计算次序，不改变 UI tab 顺序）。

        // 1) 组件级拓扑序（仅 NORMAL tab；SUBTOTAL 不参与，单独在原序补算）
        // 1a) 收集参与拓扑的页签（cid + code/tabName 别名 + formulas + fields）。
        //     依赖建图交给 CrossTabComponentOrder.buildComponentDeps —— cross_tab_ref 全量建边；
        //     component_subtotal 按<b>列粒度</b>判定（repair-0803）：引用零依赖列（INPUT_NUMBER 等）
        //     不建边，否则「产品.税率(INPUT) → 物料成本 → 产品.管理费」这条列级直线依赖链会被
        //     折成 产品⇄物料 页签级假环 → topoOrder 误抛循环引用 → 整卡渲染失败（QT-20260803-0052）。
        List<String> compIds = new ArrayList<>();
        List<CrossTabComponentOrder.TabDep> tabDeps = new ArrayList<>();
        for (JsonNode tab : snapshot) {
            // 仅 NORMAL tab 进拓扑序（跳过 SUBTOTAL 及 EXCEL —— EXCEL 不参与公式计算/cross_tab_ref）
            if (!"NORMAL".equals(tab.path("componentType").asText("NORMAL"))) continue;
            String cid = tab.path("componentId").asText("");
            compIds.add(cid);
            tabDeps.add(new CrossTabComponentOrder.TabDep(cid,
                tab.path("componentCode").asText(""), tab.path("tabName").asText(""),
                tab.path("formulas"), tab.path("fields")));
        }
        // repair-0803 FR-12：成环时用页签名称渲染链路（原先直接打印 componentId 集合，配置员不可读）
        Map<String, String> tabNameById = new LinkedHashMap<>();
        for (CrossTabComponentOrder.TabDep td : tabDeps) {
            String nm = (td.tabName() != null && !td.tabName().isBlank()) ? td.tabName() : td.code();
            if (nm != null && !nm.isBlank()) tabNameById.put(td.cid(), nm);
        }
        List<String> order = CrossTabComponentOrder.topoOrder(
            compIds, CrossTabComponentOrder.buildComponentDeps(tabDeps), tabNameById);

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
                componentSubtotals, new java.util.HashMap<>(), productAttrs, // task-0729 B8.1：产品属性上下文
                crossTabRows, deleted, rkfNames,
                rowCache, cid.isBlank() ? null : cid); // 带墓碑新重载：报价侧过滤删除行；核价侧 deleted=null → 不变
            List<Map<String, Object>> pass1Resolved = buildResolvedRows(
                tab, baseRows, editRows, pass1Results, rkfByComp.get(cid), deleted, rkfNames);
            // 存入 crossTabRows（双键，供后续兄弟组件 cross_tab_ref 查询）
            // 单位换算（cross_tab 物化点）：跨组件引用方读 canonical（按同行单位列换算）。
            // 仅换喂 crossTabRows 的副本——pass1Resolved 原值留给 backfill(各自换副本) + 落库 resolvedRows。
            List<Map<String, Object>> pass1CrossTab = convertRowsForCrossTab(tab.path("fields"), pass1Resolved);
            injectTreeAttrsForCrossTab(baseRows, pass1CrossTab);
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
                    componentSubtotals, new java.util.HashMap<>(), productAttrs, // task-0729 B8.1
                    crossTabRows, deleted, rkfNames,
                    rowCache, cid.isBlank() ? null : cid);
                resolved = buildResolvedRows(
                    tab, baseRows, editRows, formulaResults, rkfByComp.get(cid), deleted, rkfNames);
                // 更新 crossTabRows 为第 2 次 resolved（二阶列已算对，兄弟组件引用此组件 cross_tab_ref 时应取最终值）
                List<Map<String, Object>> resolvedCrossTab = convertRowsForCrossTab(tab.path("fields"), resolved);
                injectTreeAttrsForCrossTab(baseRows, resolvedCrossTab);
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
            String tabType = tab.path("componentType").asText("NORMAL");
            if ("EXCEL".equals(tabType)) continue;
            if (tabNodeById.containsKey(cid)) continue; // 已在拓扑序里算过
            ArrayNode baseRows = baseRowsByComp.getOrDefault(cid, MAPPER.createArrayNode());
            ArrayNode editRows = filteredEdit.getOrDefault(cid, MAPPER.createArrayNode());
            ArrayNode formulaResults = formulaCalculator.calculate(
                tab.path("fields"), tab.path("formulas"), tab.path("formula_assignments"),
                rkfByComp.get(cid), baseRows, editRows,
                componentSubtotals, new java.util.HashMap<>(), productAttrs, // task-0729 B8.1
                crossTabRows);
            List<Map<String, Object>> resolved = buildResolvedRows(
                tab, baseRows, editRows, formulaResults, rkfByComp.get(cid));
            // SUBTOTAL tab 不回填列小计：其 is_subtotal 列由组件级聚合公式(component_subtotal token)决定，
            // 不能从 resolvedRows 重算覆盖（评审 #1）。列小计回填仅针对 NORMAL 组件的 cross_tab 列。
            // SUBTOTAL 行不并入 crossTabRows（不可被 cross_tab_ref 引用）
            //
            // task-0713 返修（技术总监 live 验收发现）：SUBTOTAL 组件（如"核价-总公式(CNY)"/"汇总"）
            // 恒 0 driver 行（无 data_driver_path），上面的 formulaCalculator.calculate 对 0 基础行
            // 必然 0 结果，且本 tab 的 cid/code/tabName 从未写入 componentSubtotals（PASS1 只登记
            // NORMAL tab，见上方循环的 componentType!=NORMAL 跳过）——buildTabNode 读 componentSubtotals
            // 拿不到值，subtotal 字段整个被省略，导致 CostingSubtotalUtil 之类的下游读不到"总公式"结果。
            // 镜像报价侧 ComponentDataEffectiveRows#evaluateSubtotalFormula 的同款做法：取该组件首个
            // 公式表达式，用已经积累了全部 NORMAL tab 值的 componentSubtotals 当上下文求值，再登记回
            // componentSubtotals（cid/code/tabName 三键），buildTabNode 沿用既有查找逻辑即可原样命中，
            // 不改 buildTabNode 调用签名。
            if ("SUBTOTAL".equals(tabType)) {
                JsonNode formulas = tab.path("formulas");
                if (formulas.isArray() && formulas.size() > 0) {
                    JsonNode expr = formulas.get(0).path("expression");
                    if (expr.isArray() && expr.size() > 0) {
                        FormulaCalculator.RowContext subCtx = new FormulaCalculator.RowContext();
                        subCtx.componentSubtotals = componentSubtotals;
                        subCtx.productAttributes = productAttrs; // task-0729 B8.1：SUBTOTAL 公式(产品小计)是
                        // product_attribute token 最可能出现的位置，对齐前端 evalProductSubtotalFromSubtotals
                        java.math.BigDecimal evaluated = formulaCalculator.evaluateExpression(expr, subCtx);
                        if (evaluated != null) {
                            BigDecimal v = productCardSubtotalResult(evaluated);
                            String subCode = tab.path("componentCode").asText(null);
                            String subTabName = tab.path("tabName").asText("");
                            if (!cid.isBlank()) componentSubtotals.put(cid, v);
                            if (subCode != null && !subCode.isBlank()) componentSubtotals.put(subCode, v);
                            if (!subTabName.isBlank()) componentSubtotals.put(subTabName, v);
                        }
                    }
                }
            }
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

    /**
     * task-0729 B8.1（D3 口径补齐）：给 FormulaCalculator 上下文构造 {@code productAttributes}
     * （NUMBER 型）Map，供 {@code product_attribute} token 求值——对齐前端
     * {@code QuotationStep2.tsx:1302-1307} {@code evalProductSubtotalFromSubtotals} 的口径：
     * 只取 {@code field_type==="NUMBER"} 的属性，值取自 {@code productAttributeValues}，
     * parse 失败/缺失的项静默跳过（不抛错、不当 0 参与求和，与前端 {@code isNaN} 跳过一致）。
     *
     * <p>schema 来源优先级：① 冻结结构 {@code quotation_view_structure.structure.productAttributes}
     * （与前端 {@code item.productAttributes} 同源——Task5「productAttributes 冻进结构」）；
     * ② 回退模板当前 {@code template.product_attributes}（结构缺失时，与 tabs 的回退顺序一致）。
     *
     * <p><b>短路</b>：{@code li.productAttributeValues} 为空/{@code "{}"}（当前库现状）时直接返回空
     * map，不发起任何查询——本次改动前该场景 0 现网使用，短路后对现有性能零影响。
     *
     * @param quotationId 用于查冻结结构（可空——materializeAndProject 等路径经 li.quotationId 传入）
     * @param templateId  回退查询用（报价侧传 customerTemplateId；核价侧传 costingTemplateId）
     */
    private Map<String, BigDecimal> buildProductAttributesContext(
            QuotationLineItem li, UUID quotationId, UUID templateId) {
        if (li == null || li.productAttributeValues == null) return java.util.Map.of();
        String valuesJson = li.productAttributeValues.trim();
        if (valuesJson.isEmpty() || "{}".equals(valuesJson)) return java.util.Map.of();
        try {
            JsonNode valuesNode = MAPPER.readTree(valuesJson);
            if (!valuesNode.isObject() || valuesNode.isEmpty()) return java.util.Map.of();

            JsonNode schema = null;
            if (quotationId != null) {
                @SuppressWarnings("unchecked")
                var rows = em.createNativeQuery(
                    "SELECT structure FROM quotation_view_structure WHERE quotation_id = :qid AND view_kind = :kind")
                    .setParameter("qid", quotationId).setParameter("kind", "QUOTE_CARD").getResultList();
                if (!rows.isEmpty() && rows.get(0) != null) {
                    JsonNode pa = MAPPER.readTree(rows.get(0).toString()).path("productAttributes");
                    if (pa.isArray()) schema = pa;
                }
            }
            if (schema == null && templateId != null) {
                @SuppressWarnings("unchecked")
                var rows = em.createNativeQuery("SELECT product_attributes FROM template WHERE id = :tid")
                    .setParameter("tid", templateId).getResultList();
                if (!rows.isEmpty() && rows.get(0) != null) {
                    JsonNode pa = MAPPER.readTree(rows.get(0).toString());
                    if (pa.isArray()) schema = pa;
                }
            }
            if (schema == null) return java.util.Map.of();

            Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
            for (JsonNode attr : schema) {
                if (!"NUMBER".equals(attr.path("field_type").asText(""))) continue;
                String name = attr.path("name").asText("");
                if (name.isBlank()) continue;
                JsonNode v = valuesNode.get(name);
                if (v == null || v.isNull()) continue;
                try {
                    out.put(name, new BigDecimal(v.asText()));
                } catch (NumberFormatException ignore) { /* 与前端 isNaN 跳过对齐，不参与求和 */ }
            }
            return out;
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] buildProductAttributesContext failed li=%s: %s", li.id, e.getMessage());
            return java.util.Map.of();
        }
    }

    /** 组装单个 tabNode（baseRows/editRows/formulaResults/subtotal/resolvedRows）。 */
    private ObjectNode buildTabNode(JsonNode tab, String cid, ArrayNode baseRows, ArrayNode editRows,
            ArrayNode formulaResults, List<Map<String, Object>> resolvedRows,
            Map<String, BigDecimal> componentSubtotals) {
        ObjectNode tabNode = MAPPER.createObjectNode();
        tabNode.put("componentId", cid);
        tabNode.put("tabName", tab.path("tabName").asText(""));
        // task-0713 返修：输出补 componentType（原缺失，导致下游无法区分 NORMAL/SUBTOTAL/EXCEL tab）。
        // 纯新增字段，不改动既有任何字段的取值。
        tabNode.put("componentType", tab.path("componentType").asText("NORMAL"));
        tabNode.set("baseRows", baseRows);
        tabNode.set("editRows", editRows); // 加产品/核价 → 空；草稿重刷 → 保留的编辑
        tabNode.set("formulaResults", formulaResults);

        // 值快照带上本 tab 小计（供 Excel CARD_FORMULA 的 __subtotal__ 引用，见 CardEffectiveRows）
        String code = tab.path("componentCode").asText(null);
        String tabName = tab.path("tabName").asText("");
        BigDecimal sub = componentSubtotals.get(cid);
        if (sub == null && code != null) sub = componentSubtotals.get(code);
        if (sub == null) sub = componentSubtotals.get(tabName);
        if (sub != null) tabNode.put("subtotal", com.cpq.common.PrecisionPolicy.toPlainDecimalString(sub));

        // Plan 2c：per-column 小计（供 [页签.列名] 引用）。从 componentSubtotals 的
        // `${cid|code|tabName}#${列名}` 键提取（Plan 2 Task 3 已写入）。
        ObjectNode byColNode = MAPPER.createObjectNode();
        for (String prefix : new String[]{ cid, code, tabName }) {
            if (prefix == null || prefix.isBlank()) continue;
            String keyPrefix = prefix + "#";
            for (Map.Entry<String, BigDecimal> en : componentSubtotals.entrySet()) {
                if (en.getKey().startsWith(keyPrefix) && en.getValue() != null) {
                    String col = en.getKey().substring(keyPrefix.length());
                    // BL-0017：`__amount_total__` 是供 [页签(总计)] 公式求值的内部聚合键，
                    // 不是真实列；不得泄漏进 subtotalByColumn（否则污染快照 + golden 漂移）。
                    if (com.cpq.quotation.service.card.ComponentDataEffectiveRows.AMOUNT_TOTAL_KEY.equals(col)) continue;
                    if (!byColNode.has(col)) byColNode.put(col,
                        com.cpq.common.PrecisionPolicy.toPlainDecimalString(en.getValue()));
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
        // repair-0727 B0：行键唯一化预扫改走 FormulaCalculator.buildRawRowKeys 单一口径（与
        // computeRows / RowDataMaterializer 三处对齐），不再各自重算 —— 修复树页签 FORMULA 叶子
        // 列因 effKey 漂移取不到值的 bug（EffKeyNodeIdAlignmentTest 证实）。
        List<String> rawKeys = formulaCalculator.buildRawRowKeys(rowKeyFields, fieldsDef, baseRows, deleted);
        List<String> uniqKeys = FormulaCalculator.uniquifyRowKeys(rawKeys);
        // 存量兼容：旧键（不带 __nodeId 前缀）用于 edByKey/frByKey 回退查找，兼容改造前写入的
        // editRows.rowKey（前端 useCardSnapshots.buildUniqueRowKeys 尚未加前缀，见 F-1 #4）。
        // 仅当 deleted!=null 时新旧口径可能分叉才需要计算；deleted==null 时 uniqKeys 本身即旧键。
        List<String> legacyKeys = (deleted != null)
            ? FormulaCalculator.uniquifyRowKeys(formulaCalculator.buildRawRowKeys(rowKeyFields, fieldsDef, baseRows, null))
            : uniqKeys;

        // driver 默认行永久删除：先唯一化(上方)，再按墓碑双命中过滤；fps 用完整 baseRows 计算（守头号不变量）。
        // keep==null 表示不过滤（deleted 为 null/空 → 核价侧及旧路径零影响）。
        boolean[] keep = null;
        if (deleted != null && !deleted.isEmpty()) {
            List<String> fps = new ArrayList<>(baseRows.size());
            List<String> nodeIds = new ArrayList<>(baseRows.size());
            for (JsonNode br : baseRows) {
                fps.add(DeletedRowKeys.rowFingerprint(rowKeyFieldNames, br.path("driverRow")));
                JsonNode nid = br.get("__nodeId");
                nodeIds.add((nid != null && !nid.isNull()) ? nid.asText(null) : null);
            }
            keep = DeletedRowKeys.keepMask(uniqKeys, fps, nodeIds, deleted);
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
            if (editValues == null) editValues = edByKey.get(legacyKeys.get(ri)); // 存量兼容回退
            JsonNode formulaValues = frByKey.get(rowKey);
            if (formulaValues == null) formulaValues = frByKey.get(legacyKeys.get(ri)); // 存量兼容回退
            Map<String, Object> resolvedRow = formulaCalculator.resolveRowByFieldName(
                fieldsDef, driverRow, basicDataValues, editValues, formulaValues);
            // P2-B 核价 Excel 树：透传 spine 节点身份，供 Excel 按 __nodeId 过滤本节点有效行
            JsonNode nodeId = br.path("__nodeId");
            if (!nodeId.isMissingNode() && !nodeId.isNull()) {
                if (!(resolvedRow instanceof java.util.LinkedHashMap))
                    resolvedRow = new java.util.LinkedHashMap<>(resolvedRow);
                resolvedRow.put("__nodeId", nodeId.asText());
            }
            // task-0729 B10（标记透传）：PriceReconciler 把 __priceLocked/__priceVersion 写进
            // snapshot_rows 的 driverRow（不是 br 顶层，与 __nodeId 不同层级）——同款透传写法，
            // 让前端 ComponentCell 已经在读的这两个标记真正出现在 quoteCardValues 的行上。
            JsonNode priceLocked = driverRow.path("__priceLocked");
            if (priceLocked.isBoolean() && priceLocked.asBoolean()) {
                if (!(resolvedRow instanceof java.util.LinkedHashMap))
                    resolvedRow = new java.util.LinkedHashMap<>(resolvedRow);
                resolvedRow.put("__priceLocked", true);
                JsonNode priceVersion = driverRow.path("__priceVersion");
                if (!priceVersion.isMissingNode() && !priceVersion.isNull()) {
                    resolvedRow.put("__priceVersion", priceVersion.asText());
                }
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
     *
     * <p><b>BL-0127</b>：合法键集合必须同时含<b>两档口径</b> ——
     * ① {@code computeRowKey} 的内容键（无 {@code __nodeId} 前缀，本方法原有口径）；
     * ② {@link FormulaCalculator#buildRawRowKeys} 的权威键（报价侧树页签带 {@code nodeId::} 前缀）。
     * 只认 ① 会把带前缀的 editRows <b>整批丢掉</b> —— 而带前缀正是
     * {@code computeRows}/{@code buildResolvedRows} 查表用的键，也是 repair-0805 F5 之后
     * {@code editCardValue} 写进来的键。实测：树页签「物料」6 行回种全被这里过滤，
     * 非树页签只有恰好无前缀的行侥幸存活（6 行里活 2 行）。
     */
    private Map<String, ArrayNode> filterEditRowsToNewBaseRows(
            JsonNode snapshot, Map<String, ArrayNode> baseRowsByComp,
            Map<String, ArrayNode> editRowsByComp, Map<String, JsonNode> rkfByComp, ArrayNode emptyEdit,
            Map<String, List<DeletedRowKeys.Tombstone>> delByComp) {
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
            // BL-0127：并入带 __nodeId 前缀的权威键（报价侧树页签），否则这一档 editRows 会被整批丢掉
            List<DeletedRowKeys.Tombstone> del = (delByComp == null) ? null : delByComp.get(cid);
            newKeys.addAll(FormulaCalculator.uniquifyRowKeys(
                    formulaCalculator.buildRawRowKeys(rkf, fieldsDef, baseRows, del)));

            ArrayNode kept = MAPPER.createArrayNode();
            for (JsonNode er : oldEdits) {
                if (newKeys.contains(er.path("rowKey").asText(""))) kept.add(er);
            }
            if (kept.size() > 0) filtered.put(cid, kept);
        }
        return filtered;
    }

    // =========================================================================
    // BL-0127 — 从 row_data 回种用户已定值的 INPUT* 列为 editRows
    // =========================================================================

    /** 可回种的字段类型（用户输入型）。FORMULA / BASIC_DATA / DATA_SOURCE / FIXED_VALUE 一律不回种 ——
     *  回种公式值 = 后端不再算 = 权威反转成前端，属架构级错误。 */
    private static final java.util.Set<String> SEEDABLE_FIELD_TYPES =
        java.util.Set.of("INPUT", "INPUT_TEXT", "INPUT_NUMBER");

    /** 字段类型访问器（冻结结构用 camel {@code fieldType}，模板快照用 snake {@code field_type}）。 */
    private static String fieldTypeOf(JsonNode f) {
        if (f.has("fieldType")) return f.path("fieldType").asText("");
        return f.path("field_type").asText("");
    }

    /**
     * 把 {@code row_data}（前端 bake/编辑写入的「已定值」承载）里的 INPUT* 列回种为 editRows，
     * 就地合并进 {@code into}（= 已过滤的 editRows 映射）。
     *
     * <p><b>行对齐</b>（本方法唯一的风险点，逐条对照 `实现计划.md` R1/R2/R6）：
     * <ol>
     *   <li>行键一律走 {@link FormulaCalculator#buildRawRowKeys} + {@code uniquifyRowKeys} 单一口径
     *       （带 {@code __nodeId::} 前缀），<b>不另造算法</b>；</li>
     *   <li>{@code row_data} 的行序 = {@code snapshot_rows} <b>减去墓碑行</b>（全库实测：188/194 等长，
     *       6 例不等长中 3 例差值恰为墓碑数、3 例是纯手动行页签），故按 {@code keepMask} 保留序<b>顺序配对</b>；</li>
     *   <li><b>逐行校验</b>：用行键字段名从扁平行直读拼出内容键，与该 baseRow 的内容键比对；
     *       不等即<b>跳过该行</b>（宁可不回种、退化成改造前行为，也不能把值写到别的料号头上）。</li>
     * </ol>
     *
     * <p><b>取值口径</b>：「键存在即已定值」——扁平行里该 INPUT* 键存在就回种（值为 {@code ""} 也回种，
     * 代表用户显式清空）；键不存在则不回种，交由后端按 {@code default_source} 解析。与前端 bake 守卫
     * （{@code isKeyUnset}）同判据。
     *
     * <p><b>价格锁豁免</b>：{@code driverRow.__priceLocked == true} 的行整行跳过 —— 这些行的价格由
     * task-0729 归位写入、UI 上本就只读，回种可能把陈旧覆盖值顶回去（正是 {@code cleanEditRowOverrides}
     * 要清掉的东西）。代价：锁定行上的其它可编辑列本次不回种（相对改造前无退化）。
     */
    // 包级可见（非 private）：供 EditRowsFromRowDataTest 直接钉行为契约（行对齐是本改动唯一风险点，
    // 只经 buildCardValues 端到端测会把对齐失败掩盖成"值恰好一样"）。
    void seedEditRowsFromRowData(JsonNode snapshot,
                                 Map<String, ArrayNode> baseRowsByComp,
                                 Map<String, String> rowDataByComp,
                                 Map<String, JsonNode> rkfByComp,
                                 Map<String, List<DeletedRowKeys.Tombstone>> delByComp,
                                 Map<String, ArrayNode> into) {
        for (JsonNode tab : snapshot) {
            String cid = tab.path("componentId").asText("");
            if (cid.isBlank()) continue;
            String rdJson = rowDataByComp.get(cid);
            if (rdJson == null || rdJson.isBlank()) continue;
            ArrayNode baseRows = baseRowsByComp.get(cid);
            if (baseRows == null || baseRows.size() == 0) continue;

            JsonNode fieldsDef = tab.path("fields");
            List<String> seedable = new ArrayList<>();
            for (JsonNode f : fieldsDef) {
                if (!SEEDABLE_FIELD_TYPES.contains(fieldTypeOf(f))) continue;
                if (f.has("editable") && f.path("editable").isBoolean() && !f.path("editable").asBoolean()) continue;
                String name = f.path("name").asText("");
                if (!name.isEmpty()) seedable.add(name);
            }
            if (seedable.isEmpty()) continue;

            JsonNode flatRows;
            try {
                flatRows = MAPPER.readTree(rdJson);
            } catch (Exception e) {
                LOG.warnf("[seed-editrows] comp=%s row_data 解析失败，跳过回种: %s", cid, e.getMessage());
                continue;
            }
            if (flatRows == null || !flatRows.isArray() || flatRows.size() == 0) continue;

            JsonNode rkf = rkfByComp.get(cid);
            List<DeletedRowKeys.Tombstone> deleted = (delByComp == null) ? null : delByComp.get(cid);
            List<String> uniqKeys = FormulaCalculator.uniquifyRowKeys(
                formulaCalculator.buildRawRowKeys(rkf, fieldsDef, baseRows, deleted));
            List<String> rkfNames = rowKeyFieldNamesOf(rkf);

            // 墓碑保留掩码：与 buildResolvedRows / computeRows 同款（fps 用完整 baseRows 算，守 AP-54 头号不变量）
            boolean[] keep = null;
            if (deleted != null && !deleted.isEmpty()) {
                List<String> fps = new ArrayList<>(baseRows.size());
                List<String> nodeIds = new ArrayList<>(baseRows.size());
                for (JsonNode br : baseRows) {
                    fps.add(DeletedRowKeys.rowFingerprint(rkfNames, br.path("driverRow")));
                    JsonNode nid = br.get("__nodeId");
                    nodeIds.add((nid != null && !nid.isNull()) ? nid.asText(null) : null);
                }
                keep = DeletedRowKeys.keepMask(uniqKeys, fps, nodeIds, deleted);
            }

            // 配对索引：优先按内容键（扁平行里确实有的那几段行键字段）建 key→行队列；
            // 拿不到可用段时退回位置配对。见 buildFlatRowIndex 说明。
            int[] usableParts = usableKeyPartIndexes(rkfNames, flatRows);
            Map<String, java.util.ArrayDeque<Integer>> flatByKey =
                (usableParts.length > 0) ? buildFlatRowIndex(rkfNames, usableParts, flatRows) : null;
            boolean[] flatUsed = new boolean[flatRows.size()];

            ArrayNode seeded = MAPPER.createArrayNode();
            int rd = 0, skipped = 0, paired = 0;
            for (int ri = 0; ri < baseRows.size(); ri++) {
                if (keep != null && !keep[ri]) continue;
                JsonNode br = baseRows.get(ri);
                String baseContentKey = formulaCalculator.computeRowKey(
                    rkf, fieldsDef, br.path("driverRow"), br.path("basicDataValues"));

                JsonNode flat;
                if (flatByKey != null) {
                    // 按内容键取 —— driver 重展开后的行序与 row_data 行序可以完全不同
                    // （实测「材料成本」页签：baseRows=H85/Zn,TU2丝/Cu,羰基镍粉/Ni,Ag粉/Ag,H85/Cu
                    //   而 row_data=Ag粉/Ag,H85/Cu,H85/Zn,TU2丝/Cu,羰基镍粉/Ni）。按位置配会把
                    //   Ag粉 的单价写到 H85/Zn 头上 —— 静默改错数。
                    if (baseContentKey == null || baseContentKey.isEmpty()) { skipped++; continue; }
                    var q = flatByKey.get(restrictKey(baseContentKey, rkfNames.size(), usableParts));
                    Integer idx = (q == null || q.isEmpty()) ? null : q.pollFirst();
                    if (idx == null) { skipped++; continue; }    // row_data 里没有这一行（新展开出来的行）
                    flat = flatRows.get(idx);
                    flatUsed[idx] = true;
                } else {
                    if (rd >= flatRows.size()) break;            // 保留行多于 row_data 行（尚未保存）→ 余下不回种
                    flat = flatRows.get(rd);
                    rd++;
                }
                if (flat == null || !flat.isObject()) { skipped++; continue; }

                // 价格锁豁免
                if (br.path("driverRow").path("__priceLocked").asBoolean(false)) { skipped++; continue; }

                // 位置配对时仍做一次内容校验（内容键配对已天然自证，不必重复）
                if (flatByKey == null && baseContentKey != null && !baseContentKey.isEmpty() && !rkfNames.isEmpty()) {
                    if (!flatRowMatchesContentKey(baseContentKey, rkfNames, flat)) { skipped++; continue; }
                }

                ObjectNode values = MAPPER.createObjectNode();
                for (String fname : seedable) {
                    if (!flat.has(fname)) continue;            // 键不存在 = 从未定值
                    values.set(fname, flat.get(fname));        // 键存在即已定值（含 ""）
                }
                if (values.size() == 0) continue;
                ObjectNode row = MAPPER.createObjectNode();
                row.put("rowKey", uniqKeys.get(ri));
                row.set("values", values);
                seeded.add(row);
                paired++;
            }

            if (skipped > 0) {
                LOG.warnf("[seed-editrows] comp=%s 行对齐校验失败/豁免 %d 行（已跳过，未回种），成功 %d 行，" +
                          "baseRows=%d row_data=%d", cid, skipped, paired, baseRows.size(), flatRows.size());
            }
            if (seeded.size() == 0) continue;
            mergeSeededInto(into, cid, seeded);
        }
    }

    /**
     * 挑出「可用于配对」的行键段下标 —— 即<b>每一条扁平行都带非空值</b>的那些行键字段。
     *
     * <p>行键常含 driver 侧才有的字段（如 {@code 销售料号}），{@code row_data} 行天然没有这一段；
     * 只要还剩至少一段两边都有，就能按内容配对，比按位置可靠得多。一段都不剩 → 返回空数组，
     * 调用方退回位置配对（与本改动引入前的既有实现同等信任级别）。
     */
    private static int[] usableKeyPartIndexes(List<String> rkfNames, JsonNode flatRows) {
        if (rkfNames == null || rkfNames.isEmpty() || flatRows == null || flatRows.size() == 0) return new int[0];
        List<Integer> usable = new ArrayList<>();
        for (int i = 0; i < rkfNames.size(); i++) {
            boolean allPresent = true;
            for (JsonNode flat : flatRows) {
                JsonNode v = (flat == null) ? null : flat.get(rkfNames.get(i));
                if (v == null || v.isNull() || v.asText("").isEmpty()) { allPresent = false; break; }
            }
            if (allPresent) usable.add(i);
        }
        int[] out = new int[usable.size()];
        for (int i = 0; i < out.length; i++) out[i] = usable.get(i);
        return out;
    }

    /** 按可用段给扁平行建 内容键 → 行下标队列（同键多行按出现序排队，供撞键行依次取用）。 */
    private static Map<String, java.util.ArrayDeque<Integer>> buildFlatRowIndex(
            List<String> rkfNames, int[] usableParts, JsonNode flatRows) {
        Map<String, java.util.ArrayDeque<Integer>> map = new LinkedHashMap<>();
        for (int i = 0; i < flatRows.size(); i++) {
            JsonNode flat = flatRows.get(i);
            if (flat == null || !flat.isObject()) continue;
            List<String> parts = new ArrayList<>(usableParts.length);
            for (int p : usableParts) {
                JsonNode v = flat.get(rkfNames.get(p));
                parts.add(v == null || v.isNull() ? "" : normalizePart(v.asText("")));
            }
            map.computeIfAbsent(String.join("||", parts), k -> new java.util.ArrayDeque<>()).addLast(i);
        }
        return map;
    }

    /** 把 baseRow 的完整内容键裁到可用段，口径与 {@link #buildFlatRowIndex} 一致。 */
    private static String restrictKey(String baseKey, int expectedParts, int[] usableParts) {
        String[] parts = baseKey.split("\\|\\|", -1);
        if (parts.length != expectedParts) return baseKey;    // 段数对不上 → 原样（必然不命中，走 skip）
        List<String> kept = new ArrayList<>(usableParts.length);
        for (int p : usableParts) kept.add(normalizePart(parts[p]));
        return String.join("||", kept);
    }

    /** 段值归一：数值统一成 plain string（{@code 25} / {@code 25.0} 视作同值），非数值原样。 */
    private static String normalizePart(String s) {
        try {
            return new java.math.BigDecimal(s).stripTrailingZeros().toPlainString();
        } catch (Exception ignore) {
            return s;
        }
    }

    /**
     * 行对齐校验：扁平行（字段名 → 值）与该 baseRow 的内容键比对。
     *
     * <p><b>只比对扁平行里<u>确实存在</u>的行键段</b>，缺失段视作「无证据」而非「不匹配」——
     * 行键常含 driver 侧才有的字段（如 {@code 销售料号}），{@code row_data} 里天然没有这一段；
     * 若把缺失当不匹配，会把大量对齐正常的行误判成错位而拒绝回种（实测：三个页签 10 行全被误拒）。
     *
     * <p>一段可比段都没有时返回 {@code true} = 退回位置信任 —— 与本方法引入前的既有实现
     * （{@code Math.min} 纯下标配对）同等信任级别，不会更糟；有可比段时任一段不符即判错位。
     */
    private static boolean flatRowMatchesContentKey(String baseKey, List<String> rkfNames, JsonNode flat) {
        String[] baseParts = baseKey.split("\\|\\|", -1);
        if (baseParts.length != rkfNames.size()) return true;   // 口径不可比 → 不作为否决依据
        for (int i = 0; i < rkfNames.size(); i++) {
            JsonNode v = flat.get(rkfNames.get(i));
            if (v == null || v.isNull()) continue;              // 扁平行没这一段 → 无证据
            String s = v.asText("");
            if (s.isEmpty()) continue;
            if (!samePart(baseParts[i], s)) return false;
        }
        return true;
    }

    /** 单段比对：先逐字比，再做数值比（{@code 25} vs {@code 25.0} 属同值，JSON 类型漂移常见）。 */
    private static boolean samePart(String a, String b) {
        if (a.equals(b)) return true;
        try {
            return new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b)) == 0;
        } catch (Exception ignore) {
            return false;   // 非数值 → 判不等
        }
    }

    /**
     * 合并回种结果：同 (rowKey, 字段) 冲突时 <b>row_data 覆盖既有 editRows</b>。
     *
     * <p>方向沿用 2026-06-02 {@code mergeRowDataInputsIntoEdits} 的既定语义（"row_data 是当前权威输入"）：
     * {@code editRows} 由 {@code editCardValue} 在失焦那一刻写，{@code row_data} 由 1.5s 防抖 saveDraft
     * 随后写 —— 后者更新。{@code buildCardValues} 路径下 {@code into} 恒空，本方向只对
     * {@code mergeRowDataInputsIntoEdits} 那三个调用点生效，保持它们行为语义不变。
     */
    private void mergeSeededInto(Map<String, ArrayNode> into, String cid, ArrayNode seeded) {
        ArrayNode existing = into.get(cid);
        if (existing == null || existing.size() == 0) { into.put(cid, seeded); return; }
        Map<String, ObjectNode> byKey = new LinkedHashMap<>();
        for (JsonNode er : existing) {
            if (er.isObject()) byKey.put(er.path("rowKey").asText(""), (ObjectNode) er);
        }
        for (JsonNode sr : seeded) {
            String k = sr.path("rowKey").asText("");
            ObjectNode hit = byKey.get(k);
            if (hit == null) { existing.add(sr); continue; }
            JsonNode hitValues = hit.path("values");
            if (!hitValues.isObject()) { hit.set("values", sr.path("values")); continue; }
            ObjectNode hv = (ObjectNode) hitValues;
            sr.path("values").fields().forEachRemaining(e -> hv.set(e.getKey(), e.getValue()));
        }
    }

    /**
     * 非树页签平铺路径（Task 5.2 硬切换：从旧 {@code expandTemplateDriverBaseRows}（closure 版）的
     * <b>非递归分支</b>剥离而来，去掉 closure/spine 依赖）。逐核价 driver 组件按根料号单值展开
     * （无系统列，等同报价侧取数）。
     *
     * <p><b>核价侧调用前提</b>：{@code templateId} 对应模板<b>不含树页签组件</b>
     * （{@link #templateHasTreeTab(UUID)} == false）——含树页签的模板恒由
     * {@link BomTreeRenderService} 整单渲染出 {@code precomputedBaseRows}，
     * {@link #buildCostingCardValues} 只在 {@code precomputedBaseRows == null} 时才调用本方法。
     *
     * <p><b>报价侧（task-0721 起，2026-07-22 二次修正）</b>：{@link #refreshQuoteCardValues}/
     * {@link #dryRunTokenRows} 复用本方法（{@code unionByComp}/{@code driverCompsPrefetch} 传 null）
     * ——⚠️此前文档曾断言"报价侧恒不含树页签组件"，task-0721 引入 {@code tab_type='BOM'} 报价侧树页签
     * 后该假设已不成立。<b>本方法现直接跳过 {@code tab_type='BOM'} 的树组件</b>（不 put 任何条目到
     * {@code baseRowsByComp}）——原因有二：①树组件按本行料号做单值平铺展开语义上就是错的（树组件
     * $view 的 {@code hf_part_no} 语义是"边的子件料号"而非产品根料号，恒 0 行匹配）；②2026-07-22
     * 真实事故实证：若树组件 $view 本身查询报错（如列名/表结构不匹配），
     * {@code componentDriverService.expand} 抛出的异常会在此处被 catch 且置空条目——但 PostgreSQL
     * 层面该 SQL 错误已把<b>当前事务</b>置于 aborted 状态（"current transaction is aborted"），
     * 调用方紧随其后的 {@code overlayTreeTabsFromFrozenSnapshot(...)}（同一事务内的另一条 SQL）
     * 会连带失败，导致整个 {@code refreshQuoteCardValues} 抛异常、外层 catch 吞掉、
     * {@code quote_card_values} 保留旧值不更新（症状：树 tab 卡在物化时的行数，不再随加叶子/剪枝
     * 等操作后的最新 {@code snapshot_rows} 更新）。跳过树组件 = 从根源上不让它有机会执行可能出错的
     * 查询、不占用/污染刷新事务，把"树页签渲染"完全交给调用方紧随其后的
     * {@code overlayTreeTabsFromFrozenSnapshot(...)}（无树页签模板 → 该覆盖调用查 0 条 → no-op，
     * 零回归）。
     *
     * <p>{@code unionByComp}（{@link #precomputeCostingDriverUnion} 整单按 partNo 预取）与
     * {@code driverCompsPrefetch}（整单一次查 driver 组件清单）两个性能分支与 closure 无关，照抄保留。
     */
    private Map<String, ArrayNode> expandFlatDriverBaseRows(UUID templateId, QuotationLineItem li,
                                                             UUID customerId, UUID quotationId,
                                                             Map<UUID, Map<String, ExpandDriverResponse>> unionByComp,
                                                             List<Object[]> driverCompsPrefetch) {
        Map<String, ArrayNode> baseRowsByComp = new LinkedHashMap<>();
        String partNo = li.productPartNoSnapshot;
        if (partNo == null || partNo.isBlank()) return baseRowsByComp;

        List<Object[]> driverComps;
        if (driverCompsPrefetch != null) {
            driverComps = driverCompsPrefetch;          // F4：命中预取,0 往返（2 列，见下方 dc.length 兜底）
        } else {
            DRIVER_COMPS_QUERY_COUNT.incrementAndGet();
            // task-0806 B8：改走 PublishedTemplateReader（冻结快照）。列形状 (id, bom_recursive_expand,
            // tab_type) 保持不变——task-0721 二次修正加的第 3 列供下方跳过 tab_type='BOM' 树组件
            // （树组件不该在这里跑 live $view，交给调用方 overlayTreeTabsFromFrozenSnapshot）。
            List<com.cpq.template.entity.TemplateComponentSnapshot> rows =
                publishedTemplateReader.driverCompsOf(templateId);
            List<Object[]> queried = new ArrayList<>(rows.size());
            for (com.cpq.template.entity.TemplateComponentSnapshot s : rows) {
                queried.add(new Object[]{ s.componentId, s.bomRecursiveExpand, s.tabType });
            }
            driverComps = queried;
        }

        String compositeType = li.compositeType;
        QuotationIdContext.set(quotationId);
        // task-0806 B17-a：打开模板渲染域，让下方 componentDriverService.expand 内部
        // SqlViewRuntimeContext.setNested 能拿到真实 templateId（原恒传 null，见 ComponentDriverService
        // 调用点注释），使 ComponentSqlViewService.lookupForResolver 的「② 模板已发布 snapshot 优先」
        // 真正生效。unionByComp 命中分支（nrBucket!=null）不调用 expand，其结果来自调用方各自的
        // 预取（如 CardSnapshotService#precomputeCostingDriverUnion，已自行 open 同一域），故此处
        // 无条件 open 不影响那条分支的正确性，只是多余但无害。
        UUID _tplPrev = TemplateRenderScope.open(templateId);
        try {
            for (Object[] dc : driverComps) {
                if (dc == null || dc[0] == null) continue;
                // task-0721 二次修正：tab_type='BOM' 树组件整体跳过，不跑 live $view（不 put 任何条目，
                // 交给调用方 overlayTreeTabsFromFrozenSnapshot 用已冻结的 snapshot_rows 填充）。
                // dc.length 兜底：driverCompsPrefetch 传入的旧 2 列数组（核价侧既有调用点）天然跳过本判断，
                // 该调用点从不会含树组件（templateHasTreeTab 已在上游拦截），零回归。
                if (dc.length > 2 && "BOM".equals(dc[2])) continue;
                String cidStr = dc[0].toString();
                UUID compId = UUID.fromString(cidStr);
                try {
                    // Phase 2-2'：非递归无行维度组件命中整单合桶(unionByComp)则按 partNo 取,否则逐行 expand 兜底。
                    ExpandDriverResponse exp;
                    Map<String, ExpandDriverResponse> nrBucket =
                        (unionByComp != null) ? unionByComp.get(compId) : null;
                    if (nrBucket != null) {
                        exp = nrBucket.get(partNo);   // 命中合桶：0 往返(unionMulti 已按 hf_part_no 回分)
                    } else {
                        NON_RECURSIVE_EXPAND_QUERY_COUNT.incrementAndGet();
                        exp = componentDriverService.expand(
                            compId, customerId, partNo, null, null, null, li.id, compositeType);
                    }
                    List<ExpandDriverResponse.Row> rows =
                        (exp != null && exp.rows != null) ? exp.rows : new ArrayList<>();
                    baseRowsByComp.put(cidStr, buildBaseRowsFromRows(rows));
                } catch (Exception e) {
                    LOG.warnf("[card-snapshot] expand(flat) comp=%s li=%s: %s", compId, li.id, e.getMessage());
                    baseRowsByComp.put(cidStr, MAPPER.createArrayNode());
                }
            }
        } finally {
            TemplateRenderScope.restore(_tplPrev);
            QuotationIdContext.clear();
        }
        return baseRowsByComp;
    }

    /**
     * task-0721 收尾修复（委托方真实渲染验收抓到的阻断级 bug）：{@link #expandFlatDriverBaseRows} 类注释
     * 里"报价侧（{@link #refreshQuoteCardValues}/{@link #dryRunTokenRows}）恒不含树页签组件"是
     * task-0721 之前的假设——task-0721 引入 {@code tab_type='BOM'} 报价侧树页签后该假设不再成立。
     * 该方法对树组件仍会走"按本行 partNo 单值平铺展开"（{@code componentDriverService.expand}
     * 以 {@code hf_part_no = ANY([productPartNo])} 过滤），但树组件 $view 的 {@code hf_part_no} 语义是
     * "边的子件料号"，产品根料号从不会是任何 BOM 行的子件 → 恒 0 行匹配，覆盖掉
     * {@link BomTreeRenderService} 首次物化时冻结写入的正确 spine，症状 = UI 打开正常、点「刷新
     * 基础数据」/调 {@code POST .../refresh-card-snapshot} 后树页签变空。
     *
     * <p>修法对齐 B1 冻结不变量（"报价侧树在物化阶段产生并冻结，不随基础数据变动漂移"——与
     * {@link com.cpq.component.service.CostingBomTreeConfigService#invalidateRenderedCardValues}
     * 对 {@code QUOTE} usage 的处理同一哲学）：树页签组件不参与实时展开，直接读取该组件<b>当前已
     * 持久化的</b> {@code snapshot_rows}（上次物化 / 加叶子 / 删除操作冻结的最新状态），走与
     * {@link #buildCardValues} 完全相同的解析函数 {@link #buildBaseRowsFromSnapshotRows}
     * （保留全部 {@code __*} 系统列，不窄化为 {@link ExpandDriverResponse.Row}）。
     *
     * <p>在调用方 {@code expandFlatDriverBaseRows(...)} 返回后立即调用本方法覆盖树组件条目
     * （树组件在 {@code expandFlatDriverBaseRows} 里产出的 0 行/无效行会被本方法的正确值替换）。
     * 非树模板（无 {@code tab_type='BOM'} 组件）→ 查询立即返回空列表，方法整体 no-op，零回归。
     */
    private void overlayTreeTabsFromFrozenSnapshot(UUID templateId, UUID lineItemId,
                                                    Map<String, ArrayNode> baseRowsByComp) {
        if (templateId == null || lineItemId == null) return;
        // task-0806 B8：改走 PublishedTemplateReader（冻结快照），不再直读活 component 表。
        List<com.cpq.template.entity.TemplateComponentSnapshot> treeTabs =
                publishedTemplateReader.treeTabsOf(templateId);
        if (treeTabs.isEmpty()) return;
        for (com.cpq.template.entity.TemplateComponentSnapshot tab : treeTabs) {
            UUID cid = tab.componentId;
            String cidStr = cid.toString();
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery(
                    "SELECT snapshot_rows::text FROM quotation_line_component_data " +
                    "WHERE line_item_id = :lid AND component_id = :cid")
                    .setParameter("lid", lineItemId).setParameter("cid", cid)
                    .getResultList();
            String rowsJson = rows.isEmpty() ? null : (String) rows.get(0);
            baseRowsByComp.put(cidStr, buildBaseRowsFromSnapshotRows(rowsJson, cidStr));
        }
    }

    /** 从 quote_card_values JSON 提取各组件的 baseRows（componentId → baseRows 数组）。
     * 包级可见（task-0713 B7）：{@code CostingVersionService} 非主树切换时，从核价单缓存的
     * costingCardValues 里取出「未重查页签」的 baseRows 原样复用，只重跑被切换的那一个组件。 */
    Map<String, ArrayNode> extractBaseRowsByComp(String cardValuesJson) {
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

    /**
     * repair-260829（卡片值算早了骨架值锁死）B-1：③步落库前的产物自检——
     * 两个条件<b>同时成立</b>才判定"算早了"（数据源未就绪时被提前渲染出的骨架值）：
     * <ol>
     *   <li>{@code builtQuoteJson} 算出的<b>所有</b>页签 {@code baseRows} 合计 == 0</li>
     *   <li>{@code cds} 里<b>本次渲染涉及的组件</b>（{@code componentId} 出现在 {@code builtQuoteJson}
     *       的 {@code tabs} 内）中，至少一条 {@code snapshot_rows} 非空
     *       （即 Pass1.5 已预载的 {@code cds}，零额外查询——见 {@link #snapshotNewLinesCardValuesCore}
     *       调用处注释：这正是本判据能捕捉"算早了"的关键，Pass1 的 build 早于 Pass1.5 的
     *       componentData 预载，中间若恰逢 ①步提交，两次读到的数据新鲜度不同）</li>
     * </ol>
     *
     * <p>🚨 条件②不可省：只有条件①会把"组件视图合法返 0 行"的正常空结果（{@code snapshot_rows}
     * 本身就是 {@code '[]'} 或 {@code null}）误判成"算早了"，导致合法空结果永远写不进库
     * （对应 {@code AC-6}）。条件①用"**所有**页签合计"而非"任一页签"，是为了不误伤
     * {@code SUBTOTAL} 类型页签（{@code baseRows} 恒为 0 属正常，见 {@code AC-10}-③）。
     *
     * <p>🔒 <b>条件②必须按 {@code builtQuoteJson} 的 {@code tabs} 集合筛过 {@code cds}，不能看
     * {@code cds} 的全部行</b>（2026-08-29 用户实测发现并纠正）——{@code cds = cdByLine.get(li.id)}
     * 是该行<b>全部</b> {@code quotation_line_component_data}，可能含 {@code component_id} 不在
     * 当前模板 {@code tabs} 里的历史残留 orphan 行（dev 库实测存在）。若不筛，"orphan 行
     * {@code snapshot_rows} 非空 + 模板内组件合法返 0 行"这一组合会被误判成"算早了"——
     * 而误判的后果不是"多算一次"，是<b>死循环</b>：不落库 → {@code quote_card_values} 保持
     * {@code NULL} → 下次 {@code IS NULL} 判据又选中 → 又命中误判 → 永远写不进库，比原缺陷
     * （至少写进去了、只是内容空）更糟。筛过之后，本判据命中的语义收窄为"这个正在渲染的组件
     * 有源数据、却渲染出 0 行"——这正是"build 读到旧快照、cds 预载读到新数据"那个时序差的
     * 精确特征，不会误伤"组件本来就没数据"的合法场景，也天然不会自然出现在健康单里
     * （有数据就该渲染出行），因此可安全地直接传参构造这个组合做纯逻辑单测。
     *
     * <p>只在 {@code builtQuoteJson} 非 null 时才可能判定——build 抛异常已有独立的失败哨兵路径
     * （{@code failedSentinelWithError}/{@code CARD_VALUE_FAILED_SENTINEL}），不归本判据管，
     * 避免与既有失败语义（{@code E-7}）重叠改动。
     */
    boolean isEarlySkeletonRender(String builtQuoteJson,
            List<com.cpq.quotation.entity.QuotationLineComponentData> cds) {
        if (builtQuoteJson == null || builtQuoteJson.isBlank()) return false;
        // 条件①：算出的所有页签 baseRows 合计 == 0（顺带拿到本次渲染涉及的 componentId 集合，
        // 供下方条件②筛 orphan comp_data 用——同一次 JSON 解析，不重复解析）
        Map<String, ArrayNode> baseRowsByComp = extractBaseRowsByComp(builtQuoteJson);
        if (baseRowsByComp.isEmpty()) return false;   // 无页签(如模板 0 driver 组件)不归本判据管
        for (ArrayNode rows : baseRowsByComp.values()) {
            if (rows != null && rows.size() > 0) return false;   // 有任一页签非空 → 不是"算早了"
        }
        // 条件②：仅认"本次渲染涉及的组件"(componentId 出现在 baseRowsByComp/tabs 内)的
        // snapshot_rows —— 排除 orphan comp_data(component_id 不在当前模板/渲染范围内的历史
        // 残留行)干扰判据（见上方类注释）。源本来就没数据时不拦（合法空结果，E-4）。
        if (cds != null) {
            for (com.cpq.quotation.entity.QuotationLineComponentData cd : cds) {
                if (cd == null || cd.componentId == null) continue;
                if (!baseRowsByComp.containsKey(cd.componentId.toString())) continue;   // orphan，不归本次渲染
                String sr = cd.snapshotRows;
                if (sr == null) continue;
                String trimmed = sr.trim();
                if (!trimmed.isEmpty() && !"[]".equals(trimmed) && !"null".equals(trimmed)) {
                    return true;   // 条件①②同时成立
                }
            }
        }
        return false;
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

    /**
     * 显式刷新专用的历史卡片解析。已有值无法解析或不是标准 {@code {tabs:[...]}} 形状时，
     * 调用方必须保留旧值并把该行记为 no-op，不能用空 map 继续覆盖。
     */
    private ExistingQuoteCardState parseExistingQuoteCardState(String cardValuesJson) {
        Map<String, ArrayNode> baseRows = new LinkedHashMap<>();
        Map<String, ArrayNode> editRows = new LinkedHashMap<>();
        if (cardValuesJson == null || cardValuesJson.isBlank()) {
            return new ExistingQuoteCardState(baseRows, editRows);
        }
        try {
            JsonNode root = MAPPER.readTree(cardValuesJson);
            JsonNode tabs = root == null ? null : root.get("tabs");
            if (root == null || !root.isObject() || tabs == null || !tabs.isArray()) {
                throw new HistoricalCardValuesException(
                    "Existing quote_card_values is not a normalized {tabs:[...]} object");
            }
            for (JsonNode tab : tabs) {
                String cid = tab.path("componentId").asText("");
                if (cid.isBlank()) continue;
                JsonNode base = tab.path("baseRows");
                baseRows.put(cid, base.isArray() ? (ArrayNode) base : MAPPER.createArrayNode());
                JsonNode edits = tab.path("editRows");
                if (edits.isArray() && !edits.isEmpty()) {
                    editRows.put(cid, (ArrayNode) edits);
                }
            }
            return new ExistingQuoteCardState(baseRows, editRows);
        } catch (HistoricalCardValuesException e) {
            throw e;
        } catch (Exception e) {
            throw new HistoricalCardValuesException("Unable to parse existing quote_card_values", e);
        }
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
        refreshQuoteCardValues(li, force, LineFailurePolicy.BEST_EFFORT);
    }

    /**
     * task-260819 B-19（N+1 硬约束）：批量循环调用方（如 {@code QuotationService} 复制报价单时
     * 逐行 {@code refreshQuoteCardValues}）用这个重载，传入循环外已整单算好的 {@code precomputedUnion}
     * （见 {@link #collectTotalMaterialNoUnionForLines}），避免每行各发一次递归 SQL。
     *
     * <p>task-260819 D-58（B+）：参数类型由 {@code List<String>} 改为完整的
     * {@link BomTreeRenderService.MaterialUnionResult}——单纯只传 {@code totalMaterialNo} 不够，
     * 单料号 expand 路径（{@code expandFlatDriverBaseRows} 内部）加宽 outer {@code hfPartNos} 时
     * 只能用「这一个成品自己的闭包」（{@code materialsByRoot}），绝不能用整单料号池
     * （{@code totalMaterialNo}）——批量场景下两者是不同的集合，混用会把别的成品的行带进这一卡。
     */
    @Transactional
    public void refreshQuoteCardValues(QuotationLineItem li, boolean force,
                                       BomTreeRenderService.MaterialUnionResult precomputedUnion) {
        refreshQuoteCardValues(li, force, LineFailurePolicy.BEST_EFFORT, precomputedUnion);
    }

    /**
     * task-260819 B-19：供批量循环调用方（跨类，如 {@code QuotationService}）在循环外整单算一次
     * 料号并集，循环内配合 {@link #refreshQuoteCardValues(QuotationLineItem, boolean,
     * BomTreeRenderService.MaterialUnionResult)} 复用。
     */
    public BomTreeRenderService.MaterialUnionResult collectTotalMaterialNoUnionForLines(List<QuotationLineItem> lines) {
        return bomTreeRenderService.collectTotalMaterialNoUnion(lines, "QUOTE");
    }

    private RefreshOutcome refreshQuoteCardValues(
            QuotationLineItem li, boolean force, LineFailurePolicy failurePolicy) {
        return refreshQuoteCardValues(li, force, failurePolicy, null);
    }

    /**
     * task-260819 B-19（D-52/AC-58）：{@code precomputedUnion} 非 {@code null} = 调用方
     * （批量循环，如 {@link #refreshDraftQuoteCards}）已整单一次算好料号并集，直接复用，避免每行
     * 各发一次递归 SQL（N+1 硬约束）；{@code null} = 单行独立调用（如 {@link QuotationService}
     * 新建行后的单次刷新），本方法按这一行自己的 BOM 闭包现算——与 {@code BomTreeRenderService#render}
     * 既有的「传几行算几行」惯例一致（每次调用仍恒为 1 次递归 SQL，只是这一次只覆盖 1 行）。
     */
    private RefreshOutcome refreshQuoteCardValues(
            QuotationLineItem li, boolean force, LineFailurePolicy failurePolicy,
            BomTreeRenderService.MaterialUnionResult precomputedUnion) {
        if (li == null || li.id == null) {
            return RefreshOutcome.NO_OP;
        }
        try {
            QuotationLineItem managed = QuotationLineItem.findById(li.id);
            if (managed == null) {
                return RefreshOutcome.NO_OP;
            }

            // 草稿默认冻结（2026-06-18）：已首次 bake 的行非 force 调用直接 no-op，
            // 防 on-open / 误调覆盖冻结值。force=true（显式刷新/删恢复行）才重算。
            if (!force && managed.cardSnapshotAt != null) return RefreshOutcome.NO_OP;

            Quotation q = Quotation.findById(managed.quotationId);
            if (q == null || q.customerTemplateId == null) {
                // 兼容存量过渡数据：缺报价/报价模板历来是 no-op，不视为刷新事务失败。
                return RefreshOutcome.NO_OP;
            }

            // task-0806 B19：模板渲染域，覆盖下方 mergeRowDataInputsIntoEdits / assembleTabsWithFormulaResults
            // 内部对 rowKeyFields 冻结快照的取值（rowKeyFieldsMapFromScope() 读 TemplateRenderScope.currentTemplateId()）。
            UUID _tplPrev = TemplateRenderScope.open(q.customerTemplateId);
            try {
            // task-0725 T3-P2：报价侧 pending 可见域，包住下方 expandFlatDriverBaseRows（:2124）。
            // 覆盖「刷新基础数据」按钮入口（refreshDraftQuoteCards → 本方法 force=true）。
            // status 从 q.status 取；open() 内建冻结判定，非 DRAFT 时 pendingOwner()=null（AC-10）。
            UUID _pqPrev = QuotePendingScope.open(q.id, q.status);
            try {
                JsonNode snapshot = loadQuoteTabsForRefresh(q.id, q.customerTemplateId);
                if (snapshot == null) {
                    // 已发布但尚未冻结快照是受支持的过渡状态，保留旧值并 no-op。
                    return RefreshOutcome.NO_OP;
                }

                ExistingQuoteCardState existing = parseExistingQuoteCardState(managed.quoteCardValues);

                // 1. 重查基础值（报价模板 driver 组件 expand 种子；非树页签走平铺实时展开）
                // task-260819 B-19（AC-58）：expandFlatDriverBaseRows 内部经 componentDriverService.expand
                // 走到可能含 :total_material_no 的 $view，此处局部注入——precomputedUnion 非空则复用
                // 批量调用方整单算好的值（避免逐行重发递归 SQL）；为空则按本行自己的 BOM 闭包现算一次
                // （与 render() 单行调用的既有惯例一致）。
                // task-260819 D-58（B+）：一并透传 materialsByRoot——单料号 expand 路径加宽 outer
                // hfPartNos 时只能用「这一个成品自己的闭包」，批量场景下不能拿整单料号池顶替
                // （precomputedUnion 的 materialsByRoot 天然是 root→自身闭包 的形状，逐根独立，
                // 直接透传即可，不会把批量里其它成品的行带进来）。
                BomTreeRenderService.MaterialUnionResult _effUnion = (precomputedUnion != null)
                        ? precomputedUnion
                        : bomTreeRenderService.collectTotalMaterialNoUnion(java.util.List.of(managed), "QUOTE");
                com.cpq.datasource.sqlview.BomTreeVarsContext.set(new com.cpq.datasource.sqlview.BomTreeVarsContext.Vars(
                        null, _effUnion.totalMaterialNo, null,
                        com.cpq.datasource.sqlview.BomTreeVarsContext.Mode.RENDER, null, _effUnion.materialsByRoot));
                Map<String, ArrayNode> baseRowsByComp;
                try {
                    baseRowsByComp = expandFlatDriverBaseRows(q.customerTemplateId, managed, q.customerId, q.id,
                        null, null);
                } finally {
                    com.cpq.datasource.sqlview.BomTreeVarsContext.clear();
                }
                // 1.5 task-0721 收尾修复：树页签（tab_type='BOM'）不走实时展开——覆盖为该组件当前已冻结的
                // snapshot_rows（见 overlayTreeTabsFromFrozenSnapshot 方法注释；无树页签的模板 no-op）。
                overlayTreeTabsFromFrozenSnapshot(q.customerTemplateId, managed.id, baseRowsByComp);

                // 1.6 repair-0729 Task2（空覆盖护栏，AP-60 同族不变量）：本次重查（expand + 树覆盖）
                // 对某组件产出的新 baseRows 若为空，而库内已持久化的旧 quote_card_values 中该组件的
                // baseRows 非空 → 判定为「一次重查空结果」，不得覆盖已持久化的非空数据。命中时把旧
                // baseRows 放回 baseRowsByComp，继续走正常组装（不改变后续流程结构）；新旧都空 / 新值
                // 非空 → 不动，正常更新。
                Map<String, ArrayNode> oldBaseRowsByComp = existing.baseRowsByComp();
                for (Map.Entry<String, ArrayNode> oldEntry : oldBaseRowsByComp.entrySet()) {
                    String guardCid = oldEntry.getKey();
                    ArrayNode oldRows = oldEntry.getValue();
                    int oldRowCount = oldRows == null ? 0 : oldRows.size();
                    if (oldRowCount == 0) continue;
                    ArrayNode newRows = baseRowsByComp.get(guardCid);
                    int newRowCount = newRows == null ? 0 : newRows.size();
                    if (newRowCount == 0) {
                        LOG.warnf("[card-snapshot] EMPTY_OVERWRITE_BLOCKED lineItemId=%s componentId=%s " +
                                "oldRowCount=%d: 重查得到空结果, 已持久化的旧 baseRows 非空, 拦截覆盖(保留旧值)",
                                managed.id, guardCid, oldRowCount);
                        EMPTY_OVERWRITE_BLOCKED_COUNT.incrementAndGet();
                        baseRowsByComp.put(guardCid, oldRows);
                    }
                }

                // 2. 旧 editRows（按 rowKey 对齐保留）
                Map<String, ArrayNode> oldEdits = existing.editRowsByComp();

                // 2.5 (2026-06-02 修复 报价卡片 FORMULA 单元格读陈旧 formulaResults=0): 把 row_data
                //     (autosave 持久化的当前 INPUT 值, 与前端渲染 comp.rows 同源) 按 rowKey 合并进 editRows，
                //     让重算的 formulaResults 用当前 单价 等输入。否则 INPUT 仅在 editQuoteCardValue 写过的行
                //     进 editRows，autosave 写 row_data 但 editQuoteCardValue 漏的行 formulaResults 缺输入算 0，
                //     单元格(快照优先)读 0 而列小计(前端实时)正确 → 不一致。详见 RECORD 2026-06-02。
                Map<String, ArrayNode> mergedEdits =
                    mergeRowDataInputsIntoEditsRequired(snapshot, baseRowsByComp, oldEdits, managed.id);

                // 2.6. 查各组件 deleted_row_keys 墓碑（报价侧需过滤永久删除的 driver 默认行）
                Map<String, List<DeletedRowKeys.Tombstone>> delByComp = loadTombstonesByComp(managed.id);

                // 3. 组装新 quote_card_values（保留编辑 + 重算 formulaResults；报价侧传真实墓碑）
                // task-0729 B8.1：产品属性上下文（口径补齐）
                ObjectNode root = assembleTabsWithFormulaResults(snapshot, baseRowsByComp, mergedEdits, null, delByComp,
                    null, buildProductAttributesContext(managed, q.id, q.customerTemplateId));
                // 方向3 T1 写路径 3/5（刷新基础数据）：走收敛点，卡片值与派生小计同一动作落库。
                assignQuoteCardValues(managed, MAPPER.writeValueAsString(root), q.id);

                // 4. 报价 Excel 值前端权威（buildExcelSnapshot + saveDraft），此处不再后端重算。
                // Phase6 (2026-06-21) 退役：原 buildExcelValues 重算已删除；
                // quote_excel_values 唯一写入源 = saveDraft（前端值） + snapshotLineValues（==null bootstrap 兜底）。

                // 5. 更新报价侧时间戳
                managed.quoteValuesAt = OffsetDateTime.now();
                // 核价两列：物理不参与本次 UPDATE
                return RefreshOutcome.UPDATED;
            } finally {
                QuotePendingScope.restore(_pqPrev);
            }
            } finally {
                TemplateRenderScope.restore(_tplPrev);
            }
        } catch (TemplateNotFrozenException | HistoricalCardValuesException e) {
            LOG.warnf("[card-snapshot] refreshQuoteCardValues compatibility no-op li=%s: %s",
                li.id, e.getMessage());
            return RefreshOutcome.NO_OP;
        } catch (Exception e) {
            if (failurePolicy == LineFailurePolicy.PROPAGATE_UNRECOVERABLE) {
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Refresh failed for line=" + li.id, e);
            }
            LOG.warnf("[card-snapshot] refreshQuoteCardValues failed li=%s: %s", li.id, e.getMessage());
            return RefreshOutcome.NO_OP;
        }
    }

    /**
     * 草稿态<b>显式刷新</b>整单报价侧卡片值（R1：仅刷"值"，不重建结构）。
     * <ul>
     *   <li>仅 {@code status="DRAFT"} 执行；非 DRAFT（已提交/冻结）→ no-op 返 0。</li>
     *   <li>遍历该报价单全部 lineItems，逐行 force=true 强制重算；整单共用一个事务，
     *       任一行失败则整单回滚，避免部分刷新。</li>
     * </ul>
     * <p><b>R1（2026-06-18 草稿默认冻结）</b>：显式刷新只刷"值"，不重建结构。
     * 原 {@code rebuildStructureForDraft} 调用已移除——结构创建即冻、永不变。
     * {@code rebuildStructureForDraft} 方法本体保留，迁移端点 / 首次结构组装按需调用。
     *
     * @return 实际重刷的行数（非 DRAFT 返 0）。
     */
    @Transactional
    public int refreshDraftQuoteCards(UUID quotationId) {
        return refreshDraftQuoteCards(quotationId, DraftRefreshPolicy.HTTP_ATOMIC);
    }

    /** 迁移专用事务入口：与 HTTP 共用原子写入，但不把失败包装成 HTTP 409。 */
    @Transactional
    public int refreshDraftQuoteCardsForMigration(UUID quotationId) {
        return refreshDraftQuoteCards(quotationId, DraftRefreshPolicy.MIGRATION);
    }

    private int refreshDraftQuoteCards(UUID quotationId, DraftRefreshPolicy policy) {
        if (quotationId == null) return 0;
        Quotation q = Quotation.findById(quotationId, LockModeType.PESSIMISTIC_WRITE);
        if (q == null || !"DRAFT".equals(q.status)) return 0; // 非 DRAFT no-op
        // R1（2026-06-18 草稿默认冻结）：显式刷新只刷"值"，不重建结构。
        // 原 rebuildStructureForDraft 调用已移除——结构创建即冻、永不变。
        List<QuotationLineItem> lines = QuotationLineItem.list(
            "quotationId = ?1 ORDER BY sortOrder, id", quotationId);
        // task-260819 B-19（N+1 硬约束）：整单只在循环外算一次料号并集，循环内各行复用同一份值，
        // 不为每行各发一次递归 SQL（本方法是「批量刷新整单」这一个业务操作，SQL 条数必须与行数无关）。
        BomTreeRenderService.MaterialUnionResult _precomputedUnion =
                bomTreeRenderService.collectTotalMaterialNoUnion(lines, "QUOTE");
        int n = 0;
        for (QuotationLineItem li : lines) {
            try {
                // 2026-06-02 修复(草稿打开刷不出后台改的基础数据): 显式刷新是用户"重查最新 SQL"的显式动作，
                //   先定向清掉本行 driver 展开缓存（30s TTL）。否则后台直接改库（未走 app 导入 → 未调 evictAll）
                //   时缓存命中旧值，refreshQuoteCardValues 重 expand 仍拿陈旧数据 → baseRows/含量 刷不出新值。
                if (li.id != null) componentDriverService.evictForLineItem(li.id);
                // 整单方法已经建立单一事务；不可恢复的解析/组装错误必须传播。
                // 缺模板、未冻结快照、driver 降级/树快照回退仍按既有兼容契约处理。
                // 每行 flush 只为把数据库错误精确归因到当前行，仍未提交；任一后续行失败会回滚前序 flush。
                RefreshOutcome outcome = refreshQuoteCardValues(
                    li, true, LineFailurePolicy.PROPAGATE_UNRECOVERABLE, _precomputedUnion);
                if (outcome == RefreshOutcome.UPDATED) {
                    em.flush();
                    n++;
                }
            } catch (Exception e) {
                LOG.errorf(e, "[card-snapshot] atomic refresh failed quotation=%s line=%s",
                    quotationId, li.id);
                throw draftRefreshFailure(policy, quotationId, li.id, e);
            }
        }
        // 方向3 T1：整单刷新改了每行 li.subtotal → 单头必须跟上，否则分叉只是从「行」搬到「单」。
        // 🔒 放在循环【外】：recomputeDraftHeaderTotals 内部要查本单全部行，放循环里就是 O(N²)。
        if (n > 0) {
            self.recomputeDraftHeaderTotals(quotationId);
            em.flush();
        }
        return n;
    }

    private RuntimeException draftRefreshFailure(
            DraftRefreshPolicy policy, UUID quotationId, UUID lineItemId, Exception cause) {
        String detail = "Quotation refresh failed for line " + lineItemId + ": " + rootCauseMessage(cause);
        if (policy == DraftRefreshPolicy.HTTP_ATOMIC) {
            return new BusinessException(409, detail);
        }
        return new IllegalStateException(
            "Migration refresh failed for quotation " + quotationId + ", line " + lineItemId
                + ": " + rootCauseMessage(cause), cause);
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
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
            // Resolve only the lock key first. Loading the line before the quotation lock would
            // reverse ensure's lock order and could deadlock.
            @SuppressWarnings("unchecked")
            List<Object> quotationIds = em.createNativeQuery(
                    "SELECT quotation_id FROM quotation_line_item WHERE id = :lineItemId")
                .setParameter("lineItemId", lineItemId)
                .setMaxResults(1)
                .getResultList();
            if (quotationIds.isEmpty()) return null;
            UUID lockedQuotationId = asUuid(quotationIds.get(0));
            if (lockedQuotationId == null) return null;

            concurrencyProbe.beforeEditLockWait(lockedQuotationId);
            awaitQuotationCalculationLock(lockedQuotationId);

            // The wait may outlive any persistence-context state. Reload and validate under lock.
            em.clear();
            QuotationLineItem li = QuotationLineItem.findById(lineItemId);
            if (li == null || !lockedQuotationId.equals(li.quotationId)) return null;
            Quotation q = Quotation.findById(li.quotationId);
            if (q == null || q.customerTemplateId == null) return null;
            if (!"DRAFT".equals(q.status)) return null; // 仅草稿态可编辑
            if (li.quoteCardValues == null || li.quoteCardValues.isBlank()) return null;

            // task-0806 B19：模板渲染域，覆盖下方 materializeWholeLineRowData / assembleTabsWithFormulaResults
            // 内部对 rowKeyFields 冻结快照的取值。
            UUID _tplPrev = TemplateRenderScope.open(q.customerTemplateId);
            try {
            JsonNode snapshot = loadQuoteTabsForValues(q.id, q.customerTemplateId);
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
            // 方向3 T1 写路径 4/5（单元格失焦）：此处只赋卡片值，派生小计的覆盖延后到下方
            // materializeWholeLineRowData（REQUIRES_NEW）跑完 + flush/clear 之后 —— 原因见
            // applySubtotalsFromCardValues 的注释（提前覆盖会与内层 REQUIRES_NEW 自锁，JTA 60s 超时）。
            li.quoteCardValues = MAPPER.writeValueAsString(root);

            // 失焦同步（Excel 视图随卡片更新）：按组件拓扑序重物化整行所有非 SUBTOTAL 组件的 row_data 并落库。
            // 背景：Excel 视图（ComponentDataEffectiveRows.compute）只读 quotation_line_component_data.row_data，
            // 且对 NORMAL 组件仅做 row_data 列求和，<b>不在读时重算 FORMULA 叶子</b>；row_data 此前仅由 1.5s 防抖
            // saveDraft 写。若只物化被编辑组件（旧 Option A），跨页签依赖（如「来料.材料成本」= Σ(元素…) 持久在
            // 「来料」row_data）不会随「元素」编辑刷新 → 卡片与 Excel 不一致（来料列仍是配置态旧值）。
            // 解法：复用 ConfigureSnapshotService 配置态同款单趟拓扑序物化（依赖在前、引用在后，跨组件
            // crossTabRows/componentSubtotals 累积），但额外透传本次编辑产生的 editRows / 各组件行键 / 墓碑，
            // 使引用方（来料）物化时读到依赖方（元素）的最新列小计 → Excel 跨页签依赖随编辑传播。
            materializeWholeLineRowData(li, snapshot, baseRowsByComp, editRowsByComp, delByComp);

            // flush 先把上面 li.quoteCardValues 的脏写 + materializeWholeLineRowData(REQUIRES_NEW 原生 SQL)
            // 的 row_data 落库并对齐 L1 缓存；clear 后 li 脱管，须按 id 重读为托管实体，
            // 否则后续对 quoteValuesAt 的写不会在事务提交时刷库。
            // (Phase6 前端权威后，此处不再重算 quoteExcelValues；保留 flush/clear 仅为 row_data 落库 + quoteValuesAt 写库)
            em.flush();
            em.clear();
            QuotationLineItem liManaged = QuotationLineItem.findById(lineItemId);
            if (liManaged == null) return null; // 理论不达：上面已 flush，该行必在库

            // 报价 Excel 值前端权威（buildExcelSnapshot + saveDraft），此处不再后端重算。
            // Phase6 (2026-06-21) 退役：原 buildExcelValues 重算已删除；
            // 返回 resp.quoteExcelValues = liManaged.quoteExcelValues（DB 现值 = 前端最近 saveDraft 保存值，无害）。

            liManaged.quoteValuesAt = OffsetDateTime.now();
            // 核价两列：物理不参与本次 UPDATE

            // 方向3 T1：REQUIRES_NEW 物化 + flush/clear 都已过去，此刻覆盖派生小计不会再与内层争锁。
            applySubtotalsFromCardValues(liManaged, liManaged.quotationId);
            recomputeDraftHeaderTotals(liManaged.quotationId);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("quoteCardValues", liManaged.quoteCardValues);
            resp.put("quoteExcelValues", liManaged.quoteExcelValues);
            resp.put("quoteValuesAt", liManaged.quoteValuesAt != null ? liManaged.quoteValuesAt.toString() : null);
            return resp;
            } finally {
                TemplateRenderScope.restore(_tplPrev);
            }
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] editCardValue failed li=%s comp=%s rowKey=%s field=%s: %s",
                lineItemId, componentId, rowKey, fieldName, e.getMessage());
            return null;
        }
    }

    /**
     * 删除 / 恢复 driver 行后重算并投影（Phase 1 止血，方案 D）。
     *
     * <p>问题：{@code refreshQuoteCardValues} 只组装 {@code quote_card_values}（墓碑过滤 N-1），
     * <b>不物化 {@code row_data}</b> → 前端 {@code buildSnapshotExpansions}（读 quoteCardValues.baseRows，过滤 N-1）
     * 与 {@code comp.rows}（读未过滤 row_data，仍 N）行数错位 → {@code rowAt} 按下标硬配 → 删错行 + 受控输入
     * 按错位下标写回逐帧搅坏（详见 dev-docs/task-删除行删错架构重构/设计方案.md L1/L2/L4）。
     *
     * <p>本方法在墓碑变更后：① 组装 quoteCardValues（墓碑过滤）② <b>物化 row_data（同墓碑 → N-1，与前端
     * 展开同序，两存储恢复对齐）</b> ③ 返回整单投影（{@code quoteCardValues/quoteExcelValues/quoteValuesAt}
     * 同 {@link #editCardValue} + 额外 {@code componentData}[{componentId,rowData,deletedRowKeys,subtotal}]），
     * 供前端原子重灌 {@code comp.rows} / {@code deletedRowKeys} / {@code quoteCardValues}，消除 desync 窗口。
     *
     * <p>仅 {@code DRAFT}；失败返 {@code null}（端点据此不回灌，前端保留乐观墓碑兜底）。核价两列不参与。
     */
    @Transactional
    public java.util.Map<String, Object> refreshQuoteProjection(UUID lineItemId) {
        return materializeAndProject(lineItemId, true);
    }

    /**
     * repair-0727 B3.2 — 树删除专用变体：<b>只物化 row_data + 出 componentData 投影，不重算
     * quoteCardValues</b>。
     *
     * <p><b>为什么需要这个变体（性能事故复盘）</b>：{@code QuotationTreeService#executeDelete}
     * 在调用本方法前已经跑过一次 {@link #snapshotQuoteSideOnly}——那是一次<b>全量</b>重建
     * （从 fresh {@code snapshot_rows} + fresh {@code deletedTreeNodes} + fresh
     * {@code deleted_row_keys} 出发，PRUNE/ROW 两种模式都正确），此时 {@code li.quoteCardValues}
     * 已经是新鲜且正确的。若接着再调 {@link #refreshQuoteProjection}（它内部还会把
     * {@code assembleTabsWithFormulaResults} 重跑一遍产出**逐值等价**的 {@code quoteCardValues}），
     * 等于同一份公式重算白做了两次。首次实现按"直接调用 refreshQuoteProjection"上线后，
     * 在 DAG 级联删除场景实测触发 60s+ 超时（{@code QuoteBomTreeEndToEndTest#b7_dagCascade_realEndpoints}
     * 抛 {@code ARJUNA016102 The transaction is not active}，即 JTA 事务超时后再提交）。
     * 本方法跳过冗余的 {@code assembleTabsWithFormulaResults} 重算，只做"物化 row_data + 组装
     * componentData"，把树删除的额外开销降到"必要的那一半"。
     *
     * <p>仅 {@code DRAFT}；失败返回 {@code null}（调用方据此不带 {@code componentData}，回落旧契约）。
     */
    @Transactional
    public java.util.Map<String, Object> materializeRowDataAndProject(UUID lineItemId) {
        return materializeAndProject(lineItemId, false);
    }

    /**
     * 删除 / 恢复 driver 行后重算并投影（Phase 1 止血，方案 D）。
     *
     * <p>问题：{@code refreshQuoteCardValues} 只组装 {@code quote_card_values}（墓碑过滤 N-1），
     * <b>不物化 {@code row_data}</b> → 前端 {@code buildSnapshotExpansions}（读 quoteCardValues.baseRows，过滤 N-1）
     * 与 {@code comp.rows}（读未过滤 row_data，仍 N）行数错位 → {@code rowAt} 按下标硬配 → 删错行 + 受控输入
     * 按错位下标写回逐帧搅坏（详见 dev-docs/task-删除行删错架构重构/设计方案.md L1/L2/L4）。
     *
     * <p>本方法在墓碑变更后：① 组装 quoteCardValues（墓碑过滤，可选，见 {@code rebuildQuoteCardValues}）
     * ② <b>物化 row_data（同墓碑 → N-1，与前端展开同序，两存储恢复对齐）</b> ③ 返回整单投影
     * （{@code quoteCardValues/quoteExcelValues/quoteValuesAt} 同 {@link #editCardValue} + 额外
     * {@code componentData}[{componentId,rowData,deletedRowKeys,subtotal}]），供前端原子重灌
     * {@code comp.rows} / {@code deletedRowKeys} / {@code quoteCardValues}，消除 desync 窗口。
     *
     * <p>仅 {@code DRAFT}；失败返 {@code null}（端点据此不回灌，前端保留乐观墓碑兜底）。核价两列不参与。
     *
     * @param rebuildQuoteCardValues true=既有 {@code delete-driver-row} 路径（{@code li.quoteCardValues}
     *                                可能已过期，需要重算）；false=repair-0727 树删除路径（调用方保证
     *                                {@code li.quoteCardValues} 已是本次操作后的新鲜值，跳过重算避免冗余）
     */
    private java.util.Map<String, Object> materializeAndProject(UUID lineItemId, boolean rebuildQuoteCardValues) {
        if (lineItemId == null) return null;
        try {
            QuotationLineItem li = QuotationLineItem.findById(lineItemId);
            if (li == null) return null;
            Quotation q = Quotation.findById(li.quotationId);
            if (q == null || q.customerTemplateId == null || !"DRAFT".equals(q.status)) return null;

            // task-0806 B19：模板渲染域，覆盖下方 mergeRowDataInputsIntoEdits / materializeWholeLineRowData /
            // assembleTabsWithFormulaResults 内部对 rowKeyFields 冻结快照的取值。
            UUID _tplPrev = TemplateRenderScope.open(q.customerTemplateId);
            try {
            JsonNode snapshot = loadQuoteTabsForValues(q.id, q.customerTemplateId);
            if (snapshot == null) return null;

            // 关键：用「已存 baseRows」(extractBaseRowsByComp,与前端算墓碑 fp 同源)而非重 expand。
            // 墓碑 fp = 前端对 quoteCardValues.baseRows[i].driverRow 算的指纹;若这里重 expand 得到不同的
            // driverRow,keepMask 的 fp 匹配不上 → 不过滤(删不掉行)。对齐 editCardValue 的取数口径。
            Map<String, ArrayNode> baseRowsByComp = extractBaseRowsByComp(li.quoteCardValues);
            Map<String, ArrayNode> oldEdits = extractEditRowsByComp(li.quoteCardValues);
            Map<String, ArrayNode> mergedEdits =
                mergeRowDataInputsIntoEdits(snapshot, baseRowsByComp, oldEdits, li.id);
            Map<String, List<DeletedRowKeys.Tombstone>> delByComp = loadTombstonesByComp(li.id);

            if (rebuildQuoteCardValues) {
                // 组装 quoteCardValues（墓碑过滤）+ 物化 row_data（同墓碑 → N-1，与前端展开同序）
                // task-0729 B8.1：产品属性上下文（口径补齐）
                ObjectNode root = assembleTabsWithFormulaResults(snapshot, baseRowsByComp, mergedEdits, null, delByComp,
                    null, buildProductAttributesContext(li, q.id, q.customerTemplateId));
                // 方向3 T1 写路径 5/5（树删除重灌）：同 editCardValue —— 此处只赋卡片值，派生小计的
                // 覆盖延后到 materializeWholeLineRowData（REQUIRES_NEW）+ flush/clear 之后，
                // 否则外层先锁住 cd 行、内层 REQUIRES_NEW 再写同一批行 → 自锁 60s 超时。
                li.quoteCardValues = MAPPER.writeValueAsString(root);
            }
            // task-0806 阶段③a：本调用同样受 cpq.editpath-batch-write kill switch 控制（见
            // materializeWholeLineRowData javadoc）。baseRowsByComp/mergedEdits/delByComp 与批量写
            // 开关无关——AP-51 行数权威（row_data 行数 = 墓碑过滤后 baseRows 行数）不受影响。
            materializeWholeLineRowData(li, snapshot, baseRowsByComp, mergedEdits, delByComp);

            // flush 落库(quoteCardValues 脏写[若有] + materialize REQUIRES_NEW row_data)后 clear，按 id 重读托管实体
            em.flush();
            em.clear();
            QuotationLineItem liM = QuotationLineItem.findById(lineItemId);
            if (liM == null) return null;
            liM.quoteValuesAt = OffsetDateTime.now();
            // 与上方赋值配对：重灌了卡片值才需要覆盖派生小计。
            // rebuildQuoteCardValues=false（树删除 tx2）时，li.subtotal / cd.subtotal 已由 tx1 的
            // snapshotQuoteSideOnly → assignQuoteCardValues 算好；本方法只重写 cd.row_data、不动卡片值，
            // 故派生小计无需再算一次。
            if (rebuildQuoteCardValues) {
                applySubtotalsFromCardValues(liM, liM.quotationId);
            }
            // 🔴 单头重算【必须在 if 之外】（D3-28 缺陷：树删除后 li.subtotal 变了、q.total_amount 不动）。
            // 上面那个 if 的配对关系对「覆盖派生小计」成立、对「重算单头」**不成立**：
            //   单头要跟的是 li.subtotal 的【当前值】，而 rebuildQuoteCardValues=false 这条路上
            //   它已经被 tx1（QuotationTreeService#executeDelete → snapshotQuoteSideOnly）改掉了。
            // 症状：树删除后行总价 37.330516→30.026838 正确，但列表页「总金额」仍是删除前的数
            //       —— 分叉只是从「行」搬到了「单」。
            // 安全性：本方法自带 DRAFT 闸门 + 自己 findById 取托管实体，幂等；四个调用方都是
            //       单行单请求的端点（不在任何 N 行循环里），多调一次无害、漏调就是分叉。
            // 🔒 位置不可上移：必须留在 materializeWholeLineRowData（REQUIRES_NEW）+ flush/clear
            //       之后，否则外层先锁住行、内层另开事务再写 → 自锁至 JTA 60s 超时（见
            //       applySubtotalsFromCardValues 注释里的实测记录）。
            recomputeDraftHeaderTotals(liM.quotationId);

            java.util.Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("quoteCardValues", liM.quoteCardValues);
            resp.put("quoteExcelValues", liM.quoteExcelValues);
            resp.put("quoteValuesAt", liM.quoteValuesAt != null ? liM.quoteValuesAt.toString() : null);
            // componentData 投影：供前端原子替换 comp.rows(materialized N-1) + deletedRowKeys(权威)
            List<java.util.Map<String, Object>> comps = new ArrayList<>();
            for (com.cpq.quotation.entity.QuotationLineComponentData cd :
                    com.cpq.quotation.entity.QuotationLineComponentData
                        .<com.cpq.quotation.entity.QuotationLineComponentData>list(
                            "lineItemId = ?1 ORDER BY sortOrder, id", liM.id)) {
                java.util.Map<String, Object> c = new LinkedHashMap<>();
                c.put("componentId", cd.componentId != null ? cd.componentId.toString() : null);
                c.put("rowData", cd.rowData);
                c.put("deletedRowKeys", cd.deletedRowKeys);
                c.put("subtotal", cd.subtotal);
                comps.add(c);
            }
            resp.put("componentData", comps);
            return resp;
            } finally {
                TemplateRenderScope.restore(_tplPrev);
            }
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] materializeAndProject(rebuild=%s) failed li=%s: %s",
                rebuildQuoteCardValues, lineItemId, e.getMessage());
            return null;
        }
    }

    /**
     * 失焦同步核心：按组件拓扑序把<b>整行所有非 SUBTOTAL 组件</b>的 row_data 用真实公式引擎重物化并落库，
     * 使 Excel 视图（只读 row_data、对 NORMAL 组件仅列求和不重算 FORMULA 叶子）随卡片编辑即时刷新，
     * <b>含跨页签依赖</b>（如「来料.材料成本」引用「元素」列小计 —— 编辑「元素.单价」后「来料」也随之重物化）。
     *
     * <p>委派 {@link ConfigureSnapshotService#materializeLineRowData}（与配置态加产品同一物化入口：单趟
     * 组件拓扑序 + 跨组件 crossTabRows/componentSubtotals 累积），仅额外透传本次编辑产生的
     * editRows / 各组件行键 / 墓碑。落库策略受 {@code cpq.editpath-batch-write} kill switch 控制
     * （task-0806 阶段③a）：默认整行一次 {@link ConfigureSnapshotService#writeRowDataBatch}
     * （N×M → N×1，同一 REQUIRES_NEW 事务内一条多值 UPDATE + 未命中再一条多值 INSERT）；关闭时退回
     * 每组件一次 {@link ConfigureSnapshotService#writeRowData}（REQUIRES_NEW UPSERT，原行为）。
     * 两条路径写的是同一份 {@code computeLineRowData} 计算结果，只是"怎么写"不同——等价性见
     * {@code RowDataBatchWriteEquivTest}。
     *
     * <p><b>两个调用方都受益</b>：本方法有两处调用——{@link #editCardValue}（单元格失焦同步，:3296）与
     * {@link #materializeAndProject}（树删除/恢复重算，:3422）。批量写只改变落库的 SQL 执行策略，
     * 不改变喂给 {@code computeLineRowData} 的 baseRowsByComp/editRowsByComp/墓碑等输入，因此
     * {@code materializeAndProject} 依赖的"物化后 row_data 行数与墓碑过滤后的 quoteCardValues 行数
     * 对齐"（AP-51 行数权威）不受影响——行数由 computeLineRowData 内部按 baseRows 迭代决定，与
     * batchWriteEnabled 无关，该分支只发生在 byComp 算好之后。
     *
     * <p><b>行键来源（AP-54 对齐）</b>：各组件 rowKeyFields 取自 {@link #rowKeyFieldsMapFromScope()}
     * （task-0806 B19 起改走冻结快照），与 {@link #loadComponentsSnapshot} 冻进 tab 的行键同源，
     * 故 effKey 口径与卡片重算一致。
     *
     * <p><b>降级纪律</b>：整体异常被吞并记 warn，绝不让 row_data 同步失败回滚整次卡片编辑
     * （与 ConfigureSnapshotService 全程降级一致）；单组件物化/写库失败在 materializeLineRowData 内逐组件降级
     * （批量写路径本身失败时也会自动降级为逐组件写，见 {@code materializeLineRowData} 实现）。
     *
     * @param baseRowsByComp  componentId(字符串) → baseRows（= snapshot_rows）
     * @param editRowsByComp  componentId(字符串) → editRows（含本次编辑）
     * @param delByComp       componentId(字符串) → 永久删除墓碑列表
     */
    private void materializeWholeLineRowData(QuotationLineItem li, JsonNode snapshot,
                                             Map<String, ArrayNode> baseRowsByComp,
                                             Map<String, ArrayNode> editRowsByComp,
                                             Map<String, List<DeletedRowKeys.Tombstone>> delByComp) {
        try {
            if (li == null || snapshot == null || baseRowsByComp == null || baseRowsByComp.isEmpty()) return;

            // String 键 → UUID 键转换 + 各组件行键加载（与 loadComponentsSnapshot 冻进 tab 的 row_key_fields 同源）。
            // task-0806 B19：rowKeyFieldsMapFromScope() 整模板一次经 PublishedTemplateReader 取
            // （TemplateRenderScope 由调用方——editCardValue/materializeAndProject——已 open），
            // 不再对每个组件各查一次活 component 表。
            Map<String, JsonNode> rkfMap = rowKeyFieldsMapFromScope();
            Map<UUID, JsonNode> baseByComp = new LinkedHashMap<>();
            Map<UUID, JsonNode> editsByComp = new LinkedHashMap<>();
            Map<UUID, JsonNode> rkfByComp = new LinkedHashMap<>();
            Map<UUID, List<DeletedRowKeys.Tombstone>> tombsByComp = new LinkedHashMap<>();
            for (Map.Entry<String, ArrayNode> e : baseRowsByComp.entrySet()) {
                UUID cid;
                try { cid = UUID.fromString(e.getKey()); } catch (Exception ex) { continue; }
                baseByComp.put(cid, e.getValue());
                ArrayNode er = editRowsByComp != null ? editRowsByComp.get(e.getKey()) : null;
                if (er != null) editsByComp.put(cid, er);
                JsonNode rkf = rkfMap.get(e.getKey());
                if (rkf != null) rkfByComp.put(cid, rkf);
                List<DeletedRowKeys.Tombstone> ts = delByComp != null ? delByComp.get(e.getKey()) : null;
                if (ts != null) tombsByComp.put(cid, ts);
            }

            // task-0806 阶段③a：编辑路径此前固定调用 6 参重载 materializeLineRowData(...)，
            // 该重载内部把 batchWriteEnabled 硬编码为 false（见 ConfigureSnapshotService:1190-1197）
            // → 每个页签各一次 REQUIRES_NEW 原生 SQL UPSERT，8 页签 = 8 次独立事务提交（生产态实测
            // 基线中位 775ms，逐组件写占其中约 234ms）。首存路径早就有批量写 writeRowDataBatch
            // （N×M → N×1，同一事务内一条多值 UPDATE…FROM(VALUES…) + 未命中再一条多值 INSERT，
            // 见 ConfigureSnapshotService:1673 起），编辑路径只是历史上没接上，不是刻意选择逐组件写。
            //
            // kill switch: cpq.editpath-batch-write（默认 true，与既有 cpq.firstsave-batch-write
            // 同款命名/读取惯例，见 ConfigureSnapshotService:243-247）。
            //   关闭：-Dcpq.editpath-batch-write=false 或 export CPQ_EDITPATH_BATCH_WRITE=false
            //   回退到逐组件写（原行为），用于怀疑批量写导致问题时快速止血，无需回滚代码。
            //
            // K4 时序未变：本参数只改变"落库走几条 SQL"，不改变"落库在整体流程里的先后位置"——
            // 仍然是本方法（REQUIRES_NEW）跑完 → 外层 flush()/clear() → 再覆盖派生小计
            // （applySubtotalsFromCardValues，见其类注释里 60s 自锁的实测记录）。批量写反而把
            // 8 次 REQUIRES_NEW 收敛成 1 次，出现自锁窗口的机会只少不多。
            //
            // 等价性：两个分支的落库内容来自同一份 computeLineRowData 计算结果（byComp），
            // batchWriteEnabled 只切换"怎么写"不切换"写什么"——见
            // RowDataBatchWriteEquivTest（对 writeRowDataBatch / writeRowData 两条落库路径的
            // 逐位一致性护栏）。
            boolean editBatchWrite = "true".equalsIgnoreCase(
                    System.getProperty("cpq.editpath-batch-write",
                            System.getenv().getOrDefault("CPQ_EDITPATH_BATCH_WRITE", "true")));
            configureSnapshotService.materializeLineRowData(
                    li.id, snapshot, baseByComp, editsByComp, rkfByComp, tombsByComp, editBatchWrite);
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] 失焦同步：整行物化失败 li=%s: %s（已降级，不影响卡片编辑）",
                    li != null ? li.id : "null", e.getMessage());
        }
    }

    /**
     * task-0806 B19：componentId → rowKeyFields 解析节点，整模板一次经
     * {@link com.cpq.template.service.PublishedTemplateReader} 取（不再逐组件查活 {@code component} 表）。
     * templateId 取自 {@link TemplateRenderScope}（调用方须已 {@code open}——四处报价侧渲染入口
     * {@code refreshQuoteCardValues}/{@code editCardValue}/{@code materializeAndProject}/
     * {@code dryRunTokenRows} 均已在方法体外层 open 该行所属 {@code customerTemplateId}，
     * {@code assembleTabsWithFormulaResults} 内部懒加载调用同样吃这份外层 scope）。
     *
     * <p>scope 未打开（理论不达，说明新增了未接线的调用点）→ 记 ERROR 并返回空 map，
     * 与旧实现"查不到 → null"同一降级形状，不阻断编辑主流程（本类一贯的降级纪律）。
     *
     * <p>替代原 {@code loadRowKeyFieldsNode}/{@code loadRowKeyFields}（直读活表，已删除）。
     */
    private Map<String, JsonNode> rowKeyFieldsMapFromScope() {
        Map<String, JsonNode> out = new HashMap<>();
        UUID tid = TemplateRenderScope.currentTemplateId();
        if (tid == null) {
            LOG.errorf("[card-snapshot] rowKeyFieldsMapFromScope: TemplateRenderScope 未打开，" +
                    "无法从冻结快照取 rowKeyFields（理论不达，检查调用链是否漏 open；本次降级为无行键）");
            return out;
        }
        for (com.cpq.template.entity.TemplateComponentSnapshot s : publishedTemplateReader.allTabsOf(tid)) {
            if (s.componentId == null) continue;
            JsonNode node = null;
            if (s.rowKeyFields != null && !s.rowKeyFields.isBlank()) {
                try {
                    node = MAPPER.readTree(s.rowKeyFields);
                } catch (Exception ignore) { /* 解析失败 → null，同旧实现 catch 口径 */ }
            }
            out.put(s.componentId.toString(), node);
        }
        return out;
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

    /**
     * F1 可观测性：row_key_fields 活表单行查执行次数。task-0806 B19 起该活表读已被
     * {@link #rowKeyFieldsMapFromScope()} 全面取代（不再有任何代码路径递增本计数器），
     * 恒为 0——比 B19 之前"预取命中后应降到 0"更强的不变量（连回落路径都不再摸 component 表）。
     * 字段本身保留：{@code RkfPrefetchEquivTest} 仍断言其值，删除会破坏该测试编译。
     */
    public static final java.util.concurrent.atomic.AtomicLong ROW_KEY_FIELDS_QUERY_COUNT =
        new java.util.concurrent.atomic.AtomicLong();

    /** repair-0729：被护栏拦下的「空覆盖非空」次数（供测试断言/监控）。 */
    public static final java.util.concurrent.atomic.AtomicLong EMPTY_OVERWRITE_BLOCKED_COUNT =
        new java.util.concurrent.atomic.AtomicLong();

    /** F4 可观测性：driver 组件清单查执行次数(整单首存应由 ~170 降到 0,全部命中预取)。供测试断言/监控。 */
    public static final java.util.concurrent.atomic.AtomicLong DRIVER_COMPS_QUERY_COUNT =
        new java.util.concurrent.atomic.AtomicLong();

    /** Phase 2-2' 可观测性：非递归 driver 单值逐行 expand 兜底次数(eligible 组件整单合桶后应由 ~N×行 降到 0)。 */
    public static final java.util.concurrent.atomic.AtomicLong NON_RECURSIVE_EXPAND_QUERY_COUNT =
        new java.util.concurrent.atomic.AtomicLong();

    // task-0806 B19：loadRowKeyFields(componentId) 直读活 component 表已随其唯一形态
    // （单组件单查）被 rowKeyFieldsMapFromScope() 取代并删除——见该方法 Javadoc。
    // task-0806 B8：loadElementRoleFields 已随其唯一调用点（buildCardStructure）改走
    // PublishedTemplateReader 一并删除——元素角色字段现从冻结快照取，不再直读活 component 表。

    /**
     * 2026-06-02 修复: 把 quotation_line_component_data.row_data（autosave 持久化的当前 INPUT 值，
     * 与前端渲染 comp.rows 同源）按 rowKey 合并进 editRows，供草稿打开重刷 formulaResults 用当前输入重算。
     *
     * <p><b>BL-0127（2026-08-05）改为 delegate 到 {@link #seedEditRowsFromRowData}，原地实现已删除。</b>
     * 两个原因：
     * <ol>
     *   <li><b>原实现在冻结结构上一直是死的</b> —— 它按 snake {@code field_type} 取字段类型，而
     *       {@code loadQuoteTabsForValues} 优先返回<b>冻结结构</b>（{@code quotation_view_structure}），
     *       后者全库 <b>1507/1507 是 camel {@code fieldType}</b>（0 条 snake）→ {@code inputFields} 恒空
     *       → 整个合并恒 no-op。2026-06-02 想拦的「单元格读快照旧值、列小计前端实时算 → 不一致」
     *       就是本次 QT-20260805-0080 的同一症状，守卫没生效，故障因此活到今天。</li>
     *   <li><b>原实现按 {@code Math.min(baseRows, rowData)} 直接下标配对</b>，不看墓碑 —— 有永久删除行时
     *       row_data（减墓碑口径）会整体错位，把 A 行输入写到 B 行头上；且行键用不带 {@code __nodeId}
     *       前缀的旧口径，树页签对不上。若只把 camel 补上、不换算法，等于把一个恒 no-op 的函数
     *       "唤醒"成一个会静默改错数的函数。</li>
     * </ol>
     * 收敛后全工程只有一份 row_data→editRows 算法（repair-0727 B0「单一口径」纪律）。
     * 合并方向（row_data 覆盖 editRows 同字段）与原语义保持一致，见 {@link #mergeSeededInto}。
     * 任一步失败 → 降级返回原 editRows，不阻断打开（行为不变）。
     */
    private Map<String, ArrayNode> mergeRowDataInputsIntoEdits(
            JsonNode snapshot, Map<String, ArrayNode> baseRowsByComp,
            Map<String, ArrayNode> oldEdits, UUID lineItemId) {
        try {
            return mergeRowDataInputsIntoEditsRequired(
                snapshot, baseRowsByComp, oldEdits, lineItemId);
        } catch (Exception e) {
            LOG.warnf("[card-snapshot] mergeRowDataInputsIntoEdits 降级 li=%s: %s", lineItemId, e.getMessage());
            return oldEdits;
        }
    }

    private Map<String, ArrayNode> mergeRowDataInputsIntoEditsRequired(
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
            Map<String, String> rowDataByComp = new LinkedHashMap<>();
            for (Object[] r : rd) {
                if (r[0] != null && r[1] != null) rowDataByComp.put(r[0].toString(), r[1].toString());
            }
            if (rowDataByComp.isEmpty()) return oldEdits;

            // 行键节点：task-0806 B19 起整模板一次经 rowKeyFieldsMapFromScope() 取
            // （TemplateRenderScope 由调用方——refreshQuoteCardValues/materializeAndProject/
            // dryRunTokenRows——已 open），不再对每个组件各查一次活 component 表。
            Map<String, JsonNode> rkfMap = rowKeyFieldsMapFromScope();
            Map<String, JsonNode> rkfByComp = new LinkedHashMap<>();
            for (JsonNode tab : snapshot) {
                String cid = tab.path("componentId").asText("");
                if (!cid.isBlank() && !rkfByComp.containsKey(cid)) rkfByComp.put(cid, rkfMap.get(cid));
            }

            seedEditRowsFromRowData(snapshot, baseRowsByComp, rowDataByComp, rkfByComp,
                loadTombstonesByComp(lineItemId), merged);
            return merged;
        } catch (Exception e) {
            throw new IllegalStateException(
                "Unable to merge persisted row_data for line=" + lineItemId, e);
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
     *       （{@link #expandFlatDriverBaseRows} + {@link #extractEditRowsByComp} + {@link #mergeRowDataInputsIntoEdits}）。</li>
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

        // task-0725 T3-P2：报价侧 pending 可见域，包住下方 expandFlatDriverBaseRows（:2679 原行号）。
        // dryRunTokenRows 恒走 q.customerTemplateId（报价侧公式 dry-run 预览），无核价对等入口。
        UUID _pqPrev = QuotePendingScope.open(q.id, q.status);
        // task-0806 B19：模板渲染域，覆盖下方 mergeRowDataInputsIntoEdits / dryRunTokenRowsCore→
        // assembleTabsWithFormulaResults 内部对 rowKeyFields 冻结快照的取值。
        UUID _tplPrev = TemplateRenderScope.open(q.customerTemplateId);
        try {
            JsonNode snapshot = loadQuoteTabsForValues(q.id, q.customerTemplateId);
            if (snapshot == null || !snapshot.isArray()) {
                throw new IllegalStateException("模板 components_snapshot 缺失 tid=" + q.customerTemplateId);
            }

            // 1. baseRows 展开 + editRows 合并（与 refreshQuoteCardValues 同源）
            // task-260819 B-19（AC-58）：单行调用，按这一行自己的 BOM 闭包现算一次（非批量循环，
            // 不涉及 N+1）。
            // task-260819 D-58（B+）：一并透传 materialsByRoot 供单料号 expand 路径加宽用。
            BomTreeRenderService.MaterialUnionResult _dryRunUnion =
                    bomTreeRenderService.collectTotalMaterialNoUnion(java.util.List.of(li), "QUOTE");
            com.cpq.datasource.sqlview.BomTreeVarsContext.set(new com.cpq.datasource.sqlview.BomTreeVarsContext.Vars(
                    null, _dryRunUnion.totalMaterialNo, null,
                    com.cpq.datasource.sqlview.BomTreeVarsContext.Mode.RENDER, null, _dryRunUnion.materialsByRoot));
            Map<String, ArrayNode> baseRowsByComp;
            try {
                baseRowsByComp = expandFlatDriverBaseRows(q.customerTemplateId, li, q.customerId, q.id, null, null);
            } finally {
                com.cpq.datasource.sqlview.BomTreeVarsContext.clear();
            }
            // task-0721 收尾修复：树页签（tab_type='BOM'）不走实时展开，覆盖为已冻结 snapshot_rows
            // （与 refreshQuoteCardValues 同一处理，见 overlayTreeTabsFromFrozenSnapshot 方法注释）。
            overlayTreeTabsFromFrozenSnapshot(q.customerTemplateId, li.id, baseRowsByComp);
            Map<String, ArrayNode> oldEdits = extractEditRowsByComp(li.quoteCardValues);
            Map<String, ArrayNode> mergedEdits =
                mergeRowDataInputsIntoEdits(snapshot, baseRowsByComp, oldEdits, li.id);

            return dryRunTokenRowsCore(snapshot, hostComponentId, draftTokens, draftSelfRowKeyFields,
                baseRowsByComp, mergedEdits);
        } finally {
            TemplateRenderScope.restore(_tplPrev);
            QuotePendingScope.restore(_pqPrev);
        }
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
            Map<String, BigDecimal> componentSubtotals) {
        // task-0729 B8.1（BL-0017 遗漏路径补齐）：金额列集合（is_amount && is_subtotal），
        // 照抄 ComponentDataEffectiveRows 的口径，不新写一套算法。amountCols ⊆ subtotalFields
        // （is_amount 列必先 is_subtotal=true 才会被 findSubtotalFieldNames 选中），
        // 故 subtotalFields 为空时 amountCols 必空、Σ=0，仍需登记（token 缺失时下游按 0 兜底，
        // 但登记后与「真实登记的 0」在语义上一致，且防止把该页签排除在哨兵体系之外）。
        java.util.Set<String> amountCols =
            com.cpq.quotation.service.card.ComponentDataEffectiveRows.amountColsFromFields(fields);
        List<String> subtotalFields = formulaCalculator.findSubtotalFieldNames(fields);
        if (subtotalFields.isEmpty()) {
            putAmountTotalSentinel(cid, code, tabName, BigDecimal.ZERO, componentSubtotals);
            return;
        }

        // 单位换算（物化点5）：求和用换算后行（canonical）；resolvedRows 本身（落库）保持原值不动。
        List<Map<String, Object>> rowsForSum = new ArrayList<>(resolvedRows.size());
        for (Map<String, Object> r : resolvedRows) {
            rowsForSum.add(com.cpq.engine.unit.UnitConversion.convertObjectRow(fields, r));
        }

        // task-0810：累加、跨节点缓存和写回均为 BigDecimal，禁止 double 与 4 位中间截断。
        java.math.BigDecimal totalSum = java.math.BigDecimal.ZERO;
        // BL-0017（task-0729 B8.1）：Σ 金额列（is_amount && is_subtotal）。merge master 时同步改为
        // BigDecimal 精确求和（与 totalSum 同一处理），累加未四舍五入的原始 colSum，最后统一舍入
        // （避免复合舍入误差）。
        java.math.BigDecimal amountTotal = java.math.BigDecimal.ZERO;
        for (String col : subtotalFields) {
            java.math.BigDecimal colSum = java.math.BigDecimal.ZERO;
            for (Map<String, Object> row : rowsForSum) {
                Object val = row.get(col);
                if (val == null) continue;
                java.math.BigDecimal d;
                if (val instanceof Number n) {
                    d = com.cpq.common.PrecisionPolicy.of(n);
                } else {
                    try { d = new java.math.BigDecimal(val.toString().trim()); } catch (NumberFormatException ignore) { continue; }
                }
                colSum = colSum.add(d);
            }
            BigDecimal colSumValue = com.cpq.common.PrecisionPolicy.roundForCalculation(colSum);
            // 写 per-column 键（三种 key 形式，与 PASS1 写法对称）
            if (!cid.isBlank()) componentSubtotals.put(cid + "#" + col, colSumValue);
            if (code != null && !code.isBlank()) componentSubtotals.put(code + "#" + col, colSumValue);
            componentSubtotals.put(tabName + "#" + col, colSumValue);
            totalSum = totalSum.add(colSum);
            if (amountCols.contains(col)) amountTotal = amountTotal.add(colSum); // BL-0017：累加未舍入原值，最后统一舍入
        }
        // 回填总小计（= 所有 is_subtotal 列之和，与 PASS1 computeTabSubtotalsByColumn 逻辑对称）
        BigDecimal roundedTotal = com.cpq.common.PrecisionPolicy.roundForCalculation(totalSum);
        if (!cid.isBlank()) componentSubtotals.put(cid, roundedTotal);
        if (code != null && !code.isBlank()) componentSubtotals.put(code, roundedTotal);
        componentSubtotals.put(tabName, roundedTotal);

        // BL-0017 哨兵键登记（task-0729 B8.1 补齐）：`${cid|code|tabName}#__amount_total__` = Σ金额列。
        // 🔒 buildTabNode:~1806 的排除逻辑必须保留 —— 登记后该键会真的出现在 componentSubtotals 里，
        // 若泄漏进 subtotalByColumn 会污染快照 + 造成 golden 漂移（本次未改动该排除逻辑）。
        BigDecimal roundedAmountTotal =
            com.cpq.common.PrecisionPolicy.roundForCalculation(amountTotal);
        putAmountTotalSentinel(cid, code, tabName, roundedAmountTotal, componentSubtotals);
    }

    /**
     * BL-0017 哨兵键三键写入（与本类其余 per-column key 写法对称：cid / code / tabName 三种前缀）。
     * 抽成小方法供 {@link #backfillSubtotalsFromResolved} 的空 subtotalFields 早退分支与正常分支复用。
     */
    private static void putAmountTotalSentinel(
            String cid, String code, String tabName, BigDecimal amountTotal,
            Map<String, BigDecimal> componentSubtotals) {
        String key = com.cpq.quotation.service.card.ComponentDataEffectiveRows.AMOUNT_TOTAL_KEY;
        if (cid != null && !cid.isBlank()) componentSubtotals.put(cid + "#" + key, amountTotal);
        if (code != null && !code.isBlank()) componentSubtotals.put(code + "#" + key, amountTotal);
        if (tabName != null && !tabName.isBlank()) componentSubtotals.put(tabName + "#" + key, amountTotal);
    }

    static BigDecimal productCardSubtotalResult(BigDecimal value) {
        return com.cpq.common.PrecisionPolicy.roundProductCardSubtotal(value);
    }

    static BigDecimal productCardSubtotalResult(BigDecimal value, int scale) {
        return com.cpq.common.PrecisionPolicy.roundForResultScale(value, scale);
    }

    /**
     * 单位换算（cross_tab 物化点）：把一组 resolved 行换算成 canonical 副本喂 crossTabRows，原行不变。
     * 配 unit_source_field 的输入列按同行单位归一到 KG/PCS；未配列原样。与前端 buildCrossTabRows putCrossTab 对称。
     */

    /**
     * task-0803（2026-08-04）：给喂给 {@code crossTabRows} 的源行<b>副本</b>注入三个树属性键
     * （{@code 是否叶子 / 是否根 / 层级}），让 SUMIF 族的条件能写
     * {@code SUMIF([物料BOM.是否叶子]=1, [物料BOM.金额])} —— 即「只聚合源页签里是叶子的那些行」。
     *
     * <p>🔑 <b>为什么注入而不是改谓词求值器</b>：{@code ConditionPredicateEvaluator.resolve} 对
     * {@code sourceField} 就是 {@code arow.get(字段名)}，把属性物化成行上的键即可零改动接入，
     * 也不必把 {@code __parentId/__lvl} propagate 进落库的 resolvedRows。
     *
     * <p>🔑 <b>为什么改副本安全</b>：{@code convertRowsForCrossTab} 已经产出独立副本
     * （见其调用点注释「仅换喂 crossTabRows 的副本」），落库的 resolvedRows 是另一份，
     * 故注入不会污染卡片值 JSON。
     *
     * <p>⚠️ <b>必须按 {@code __nodeId} 匹配，不能按下标</b>：{@code buildResolvedRows} 会剔除墓碑行，
     * 结果集比 baseRows 短且下标错位。resolvedRows 恰好携带 {@code __nodeId}（见其内 put），据此回查。
     *
     * <p>⚠️ 同名列被覆盖是<b>刻意</b>的 —— 与表达式/条件里「保留字优先于同名字段」口径一致。
     * 非树页签（baseRows 无 {@code __nodeId}）整体跳过，零影响。
     */
    private void injectTreeAttrsForCrossTab(ArrayNode baseRows, List<Map<String, Object>> crossTabCopy) {
        if (baseRows == null || crossTabCopy == null || crossTabCopy.isEmpty()) return;
        if (!com.cpq.quotation.service.formula.TreeRelations.isTreeRows(baseRows)) return;
        com.cpq.quotation.service.formula.TreeRelations rel =
            com.cpq.quotation.service.formula.TreeRelations.of(baseRows, java.util.Set.of());
        Map<String, Integer> idxByNodeId = new HashMap<>();
        for (int i = 0; i < baseRows.size(); i++) {
            JsonNode nid = baseRows.get(i).get("__nodeId");
            if (nid != null && !nid.isNull() && !nid.asText("").isEmpty()) {
                idxByNodeId.putIfAbsent(nid.asText(), i);
            }
        }
        for (Map<String, Object> row : crossTabCopy) {
            Object nid = row.get("__nodeId");
            if (nid == null) continue;
            Integer i = idxByNodeId.get(nid.toString());
            if (i == null) continue;
            row.put("是否叶子", rel.isLeaf(i) ? 1 : 0);
            row.put("是否根", rel.isRoot(i) ? 1 : 0);
            row.put("层级", rel.lvl(i));
        }
    }

    private List<Map<String, Object>> convertRowsForCrossTab(JsonNode fields, List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(com.cpq.engine.unit.UnitConversion.convertObjectRow(fields, r));
        }
        return out;
    }

    // =========================================================================
    // migrateFreezeDrafts — 存量 DRAFT 草稿迁移（一次性运维端点，D1）
    // =========================================================================

    /**
     * 存量 DRAFT 草稿迁移端点逻辑（D1，2026-06-18）。
     *
     * <p>背景："草稿默认冻结"改造（Bug1 路径修复 + A1/A2）完成后，存量 DRAFT 报价单的
     * {@code quote_card_values} 可能含 {@code #ERROR[QUERY_ERROR]}（Bug1 路径不一致导致）。
     * 本方法对存量 DRAFT 进行干净重烤，清掉脏值。
     *
     * <p><b>dryRun=true</b>（默认/安全）：只扫描——统计哪些草稿的 {@code quote_card_values}
     * 含 {@code #ERROR} 子串，返回清单，<b>不改任何数据</b>。
     *
     * <p><b>dryRun=false</b>：对每个 DRAFT 报价单调 {@link #refreshDraftQuoteCardsForMigration(UUID)}
     * （显式迁移策略；内部逐行 force=true 干净重烤，单报价整单事务；任一不可恢复失败则该报价整单回滚）。
     * 重烤后再检查是否仍含 {@code #ERROR}，记录每单结果。不同报价间仍由 per-quotation try-catch 隔离。
     *
     * <p><b>I-1 约束</b>：迁移必须经 {@code self} 调迁移专用入口，确保单报价事务边界生效，
     * 且失败作为迁移结果记录，不继承 HTTP 409 语义。
     *
     * @param dryRun true=只扫描不改数据，false=触发重烤
     * @return 每个 DRAFT 报价单的扫描/迁移结果列表
     */
    public List<Map<String, Object>> migrateFreezeDrafts(boolean dryRun) {
        // 1. 查所有 DRAFT 报价单
        @SuppressWarnings("unchecked")
        List<Object[]> draftRows = em.createNativeQuery(
            "SELECT id, quotation_number FROM quotation WHERE status = 'DRAFT' ORDER BY created_at")
            .getResultList();

        List<Map<String, Object>> results = new ArrayList<>();

        for (Object[] row : draftRows) {
            UUID quotationId = UUID.fromString(row[0].toString());
            String quoteNo = row[1] == null ? "" : row[1].toString();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("quotationId", quotationId.toString());
            entry.put("quoteNo", quoteNo);

            try {
                // 2. 扫描 quote_card_values 是否含 #ERROR
                boolean beforeHasError = checkQuoteCardValuesHasError(quotationId);
                entry.put("before", beforeHasError);

                if (dryRun) {
                    // dryRun：只统计含错行项数，不改数据
                    int errorLineCount = countErrorLineItems(quotationId);
                    entry.put("errorLineCount", errorLineCount);
                    entry.put("status", "DRY_RUN");
                } else {
                    // 3. 触发重烤（迁移策略：单报价整单事务；经 self 代理触发事务拦截器）
                    int refreshed = self.refreshDraftQuoteCardsForMigration(quotationId);
                    entry.put("refreshedLines", refreshed);

                    // 4. 重烤后再扫描是否仍含 #ERROR
                    em.clear(); // 清 L1 缓存，读最新 DB 值
                    boolean afterHasError = checkQuoteCardValuesHasError(quotationId);
                    entry.put("after", afterHasError);
                    entry.put("status", afterHasError ? "STILL_ERROR" : "OK");
                }
            } catch (Exception e) {
                LOG.warnf("[migrate-freeze-drafts] quotation=%s (%s) failed: %s", quotationId, quoteNo, e.getMessage());
                entry.put("status", "FAILED");
                entry.put("error", e.getMessage());
            }

            results.add(entry);
        }

        LOG.infof("[migrate-freeze-drafts] dryRun=%b total=%d results=%s",
            dryRun, results.size(),
            results.stream().map(r -> r.get("status")).toList());
        return results;
    }

    /**
     * 检查指定报价单是否有任意行的 {@code quote_card_values} 含 {@code #ERROR} 子串。
     * 用 PostgreSQL {@code ::text LIKE} 避免 JSONB 解析开销。
     */
    private boolean checkQuoteCardValuesHasError(UUID quotationId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
            "SELECT 1 FROM quotation_line_item " +
            "WHERE quotation_id = :qid " +
            "  AND quote_card_values IS NOT NULL " +
            "  AND quote_card_values::text LIKE '%#ERROR%' " +
            "LIMIT 1")
            .setParameter("qid", quotationId)
            .getResultList();
        return !rows.isEmpty();
    }

    /**
     * 统计指定报价单中 {@code quote_card_values} 含 {@code #ERROR} 的行项数（dryRun 用）。
     */
    private int countErrorLineItems(UUID quotationId) {
        Object cnt = em.createNativeQuery(
            "SELECT COUNT(*) FROM quotation_line_item " +
            "WHERE quotation_id = :qid " +
            "  AND quote_card_values IS NOT NULL " +
            "  AND quote_card_values::text LIKE '%#ERROR%'")
            .setParameter("qid", quotationId)
            .getSingleResult();
        return cnt == null ? 0 : ((Number) cnt).intValue();
    }
}
