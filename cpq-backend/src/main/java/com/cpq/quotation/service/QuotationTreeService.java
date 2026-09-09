package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.rowkey.DeletedRowKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * task-0721 B6/B7/B8 — 报价单 BOM 树上编辑：加叶子 / 删除预览 / 执行删除（级联）/ 反向校验。
 *
 * <p>全部读写以 {@code quotation_line_component_data.snapshot_rows}（树结构权威来源，B3 物化写入）
 * 与 {@code quotation_line_item.deleted_tree_nodes}（节点级墓碑）/ 各组件
 * {@code deleted_row_keys}（行级墓碑）为准；写完后调用 {@link CardSnapshotService#snapshotQuoteSideOnly}
 * 从最新 {@code snapshot_rows} 重建 {@code quoteCardValues}（架构红线①：{@code buildCardValues} 只读
 * {@code snapshot_rows}，本类同样不越权自渲染）。
 */
@ApplicationScoped
public class QuotationTreeService {

    private static final Logger LOG = Logger.getLogger(QuotationTreeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    @Inject
    BomNodeTypeResolver bomNodeTypeResolver;

    @Inject
    CardSnapshotService cardSnapshotService;

    @Inject
    FormulaCalculator formulaCalculator;

    @Inject
    com.cpq.template.service.PublishedTemplateReader publishedTemplateReader;

    /** task-260904 B-4/B-18：树页签 / 受限页签的双判据（全工程唯一实现，需求文档 §1.35）。 */
    @Inject
    com.cpq.component.service.TabSemanticResolver tabSemanticResolver;

    /** task-260904 B-5/B-6：加叶子类型判定的主数据批量取数（2 条 SQL，与料号数无关）。 */
    @Inject
    MasterPartTypeService masterPartTypeService;

    // =========================================================================
    // 元数据加载
    // =========================================================================

    static final class CompMeta {
        UUID id;
        String tabType;
        String fields;
        String rowKeyFields;
        /** task-0721（2026-07-21 补录）：该页签「料号列」字段名（tabType=BOM 可为 null）。 */
        String partNoField;
        /** task-0721（2026-07-23 补录，匹配标识放宽）：该页签「名称列」字段名——partNoField 为空时的
         * 兜底标识列（如「外购件/费用」类页签无料号列，只用「料件名称」做标识）。 */
        String partNameField;
        /**
         * task-260904 B-4/B-18：是否报价侧 BOM 树页签 —— 双判据产物（新模型按绑定数据源
         * {@code semantic=='TREE'}，存量回退 {@code tab_type=='BOM'}）。
         * 🚫 不要在消费点重新按 {@link #tabType} 判：判据要查 {@code component_sql_view}，
         * 逐个判就是 N+1；本字段在 {@link #mapToCompMeta} 里整批一次算好。
         */
        boolean treeTab;
        /**
         * task-260904 B-9：是否「受限页签」（料号在 BOM 树上已有下级则禁止加入）。
         * 新模型按 {@code semantic=='MATERIAL_ELEMENT'}；存量回退 {@code tab_type∈{材质元素,外购件}}
         * ——与改动前逐字一致（AC-25：存量 15 个外购件组件行为不得变化）。
         */
        boolean restrictedTab;
    }

    /**
     * 该 line item 所属报价模板的全部组件元数据（id/tabType/fields/rowKeyFields/partNoField/partNameField）。
     *
     * <p>task-0806 B19：原一次 JOIN 直读活 {@code component}/{@code template_component} 表——
     * 改走 {@link #resolveCustomerTemplateId} 解出该行所属模板 id 后经
     * {@link com.cpq.template.service.PublishedTemplateReader#allTabsOf} 取冻结快照（PUBLISHED
     * 报价单必然绑定 PUBLISHED/ARCHIVED 模板，见该 reader 类注释）。
     *
     * <p>⚠️ repair-260829 B-8（2026-08-29 据实改写）：下面这句话曾经成立——「调用方
     * {@link #buildHitContext} 每次外部请求只调一次，非行/组件数量级循环，SQL 条数仍为 O(1)」——
     * 但 {@code task-0721} B8 把 {@link #assertCanAddToRestrictedTab} 接进 saveDraft 的行循环后，
     * 这条不变量就被破坏了（注释没跟着改，是本类第二个 N+1 潜伏至今的原因之一，同型于
     * {@link com.cpq.template.service.PublishedTemplateReader} 类注释「禁止新增单条查询方法被调用方
     * 放进循环」被同一次改动违反）。<b>现状</b>：{@code addLeaf}/{@code previewDelete}/
     * {@code executeDelete}（调用点 :234/:356/:461）确实每请求只调一次 {@link #buildHitContext}，
     * 该不变量对它们仍成立；但 saveDraft 批量路径经 {@link #assertCanAddToRestrictedTab}
     * （调用点 :767 附近）已改走本方法与 {@link #loadComponentDataByLineItem} 的批量预取重载
     * （{@link #loadTemplateComponentsForTemplate} + {@link #buildHitContext(List, Map)}），
     * 该路径的 SQL 条数与行数无关，但走的已经不是本方法本身。
     */
    private List<CompMeta> loadTemplateComponents(UUID lineItemId) {
        UUID templateId = resolveCustomerTemplateId(lineItemId);
        if (templateId == null) return List.of();
        return loadTemplateComponentsForTemplate(templateId);
    }

    /**
     * repair-260829 B-8：{@link #loadTemplateComponents(UUID)} 的 template 级批量入口——供
     * {@code QuotationService.processBatchStage1} 整单只调一次（{@code templateId} 已在调用方内存里，
     * 跳过 {@link #resolveCustomerTemplateId}），避免主循环里逐行重复解模板 id + 查快照。包内可见
     * （同包 {@code QuotationService} 直接调用），与 {@link #loadTemplateComponents(UUID)} 共用同一份
     * {@link #mapToCompMeta} 映射逻辑，两者产出的 {@link CompMeta} 语义逐字一致。
     */
    List<CompMeta> loadTemplateComponentsForTemplate(UUID templateId) {
        if (templateId == null) return List.of();
        return mapToCompMeta(publishedTemplateReader.allTabsOf(templateId));
    }

    /**
     * task-260904 B-4/B-18：由 static 改为实例方法 —— 双判据要查 {@code component_sql_view}，
     * 必须能拿到注入的 {@link com.cpq.component.service.TabSemanticResolver}。
     *
     * <p><b>N+1 纪律</b>：整批组件一次算完（{@code isTreeTabBatch}/{@code isRestrictedTabBatch}
     * 各 ≤2 条 SQL，与组件数无关），🚫 不在循环里逐个判。
     */
    private List<CompMeta> mapToCompMeta(List<com.cpq.template.entity.TemplateComponentSnapshot> tabs) {
        List<CompMeta> out = new ArrayList<>();
        for (com.cpq.template.entity.TemplateComponentSnapshot s : tabs) {
            CompMeta m = new CompMeta();
            m.id = s.componentId;
            m.tabType = s.tabType;
            m.fields = (s.fields != null && !s.fields.isBlank()) ? s.fields : "[]";
            m.rowKeyFields = s.rowKeyFields;
            m.partNoField = s.partNoField;
            m.partNameField = s.partNameField;
            out.add(m);
        }
        if (!out.isEmpty()) {
            Map<UUID, String> tabTypeById = new LinkedHashMap<>();
            for (CompMeta m : out) if (m.id != null) tabTypeById.put(m.id, m.tabType);
            Map<UUID, Boolean> treeFlags = tabSemanticResolver.isTreeTabBatch(tabTypeById);
            Map<UUID, Boolean> restrictedFlags = tabSemanticResolver.isRestrictedTabBatch(tabTypeById);
            for (CompMeta m : out) {
                m.treeTab = Boolean.TRUE.equals(treeFlags.get(m.id));
                m.restrictedTab = Boolean.TRUE.equals(restrictedFlags.get(m.id));
            }
        }
        return out;
    }

    /**
     * lineItemId → 所属报价单的 {@code customer_template_id}（1 次轻量原生查询，不含
     * {@code component} 表，仅用于定位模板 id 供后续 {@link com.cpq.template.service.PublishedTemplateReader}
     * 查询）。找不到 → null（调用方各自按"该行无模板组件"降级放行/返空）。
     */
    @SuppressWarnings("unchecked")
    private UUID resolveCustomerTemplateId(UUID lineItemId) {
        if (lineItemId == null) return null;
        List<Object> rows = em.createNativeQuery(
                "SELECT q.customer_template_id FROM quotation_line_item li " +
                "JOIN quotation q ON q.id = li.quotation_id WHERE li.id = :lid")
                .setParameter("lid", lineItemId).getResultList();
        if (rows.isEmpty() || rows.get(0) == null) return null;
        Object v = rows.get(0);
        return (v instanceof UUID u) ? u : UUID.fromString(v.toString());
    }

    /** (lineItemId 固定) componentId → [snapshot_rows, deleted_row_keys] 原始 JSON 字符串。 */
    @SuppressWarnings("unchecked")
    private Map<UUID, Object[]> loadComponentDataByLineItem(UUID lineItemId) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT component_id, snapshot_rows, deleted_row_keys FROM quotation_line_component_data " +
                "WHERE line_item_id = :lid")
                .setParameter("lid", lineItemId).getResultList();
        Map<UUID, Object[]> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            if (r[0] == null) continue;
            UUID cid = UUID.fromString(r[0].toString());
            out.put(cid, new Object[]{ r[1] == null ? null : r[1].toString(), r[2] == null ? null : r[2].toString() });
        }
        return out;
    }

    private static ArrayNode parseRows(String json) {
        if (json == null || json.isBlank()) return MAPPER.createArrayNode();
        try {
            JsonNode n = MAPPER.readTree(json);
            return n != null && n.isArray() ? (ArrayNode) n : MAPPER.createArrayNode();
        } catch (Exception e) {
            return MAPPER.createArrayNode();
        }
    }

    /**
     * task-0721（2026-07-21 补录，2026-07-23 放宽）：按组件「标识列」显式解析该行的标识值（不按字段名
     * 猜测）——优先 {@code partNoField}（取料号值），为空则回落 {@code partNameField}（取名称值，如
     * 「外购件/费用」类无料号列的页签用「组成件1」这样的名称做标识）。两者均缺失（非树页签未配置，
     * 理论上已被 B4 保存期校验拦住，此处防御）→ 返回 null，该行不参与命中/匹配。委托
     * {@link FormulaCalculator#computeRowKey} 的"直读 driverRow 优先，否则按字段 defaultSource 解析"
     * 链路，与行键计算同一套解析口径。类型判定即"候选值是否出现在某类型页签的标识列取值集合"的
     * 字符串比对——候选值本身可能是料号也可能是名称，比对时不区分语义。
     */
    /** 包级可见（供 {@code QuotationTreeServicePartNoFieldTest} 纯单测，无需 DB/CDI）。 */
    String extractMaterialNoByField(JsonNode row, CompMeta cm) {
        if (row == null || cm == null) return null;
        String identifierField = (cm.partNoField != null && !cm.partNoField.isBlank())
                ? cm.partNoField
                : cm.partNameField;
        if (identifierField == null || identifierField.isBlank()) return null;
        JsonNode fieldsNode = parseFieldsJsonSafe(cm.fields);
        JsonNode rkf = MAPPER.createArrayNode().add(identifierField);
        String mn = formulaCalculator.computeRowKey(rkf, fieldsNode, row.path("driverRow"), row.path("basicDataValues"));
        return (mn != null && !mn.isBlank()) ? mn : null;
    }

    private static JsonNode parseFieldsJsonSafe(String fieldsJson) {
        if (fieldsJson == null || fieldsJson.isBlank()) return MAPPER.createArrayNode();
        try {
            JsonNode n = MAPPER.readTree(fieldsJson);
            return n != null && n.isArray() ? n : MAPPER.createArrayNode();
        } catch (Exception e) {
            return MAPPER.createArrayNode();
        }
    }

    // =========================================================================
    // 类型判定上下文（B5 复用；B6/B8 均需要，与 B3 物化时用的规则完全一致）
    // =========================================================================

    static final class HitContextBundle {
        final BomNodeTypeResolver.TabHitContext ctx = new BomNodeTypeResolver.TabHitContext();
        /** materialNo → 首个命中该料号的（材质元素/零件/外购件/主件类型）组件 id，供 __sourceComponentId。 */
        final Map<String, UUID> sourceComponentByMaterialNo = new LinkedHashMap<>();
        /** 该行所有 tabType='BOM' 的组件 id（树页签，可能不止一个）。 */
        final List<UUID> treeComponentIds = new ArrayList<>();
        /** 树组件 id(字符串) → 该组件当前 snapshot_rows(ArrayNode，未剪枝，含系统列)。 */
        final Map<String, ArrayNode> treeRowsByComp = new LinkedHashMap<>();
        List<CompMeta> comps;
        Map<UUID, Object[]> compData;
    }

    private HitContextBundle buildHitContext(UUID lineItemId) {
        HitContextBundle b = buildHitContext(loadTemplateComponents(lineItemId), loadComponentDataByLineItem(lineItemId));
        // task-260904 AC-26（2026-09-05 用户裁决扩宽）：整单一次取全部行的成品料号，供规则五跨行拦截。
        // 🚫 N+1 纪律：只在本重载（addLeaf / previewDelete / executeDelete —— 每请求调一次）里查；
        //    saveDraft 的批量重载 buildHitContext(comps, compData) 走的是受限页签校验，
        //    不做类型判定、用不到规则五，故那条被逐行调用的路径一条查询都不加。
        for (String pn : loadQuotationFinishedPartNos(lineItemId)) {
            b.ctx.addQuotationFinishedPartNo(pn);
        }
        return b;
    }

    /**
     * task-260904 AC-26：本报价行所属<b>报价单</b>全部行的成品料号
     * （{@code quotation_line_item.product_part_no_snapshot}），<b>1 条 SQL</b>，与行数无关。
     */
    @SuppressWarnings("unchecked")
    private List<String> loadQuotationFinishedPartNos(UUID lineItemId) {
        if (lineItemId == null) return List.of();
        List<Object> rows = em.createNativeQuery(
                "SELECT DISTINCT sib.product_part_no_snapshot FROM quotation_line_item li " +
                "JOIN quotation_line_item sib ON sib.quotation_id = li.quotation_id " +
                "WHERE li.id = :lid AND sib.product_part_no_snapshot IS NOT NULL")
                .setParameter("lid", lineItemId).getResultList();
        List<String> out = new ArrayList<>();
        for (Object o : rows) {
            if (o == null) continue;
            String v = o.toString();
            if (!v.isBlank()) out.add(v);
        }
        return out;
    }

    /**
     * repair-260829 B-8：{@link #buildHitContext(UUID)} 的数据源可预取核心版本——装配逻辑与原方法
     * 逐字一致（不改「怎么判」），唯一差别是 {@code comps}/{@code compData} 由调用方传入而非本方法
     * 内部现查。saveDraft 批量路径专用（经 {@link #assertCanAddToRestrictedTab(String, List, UUID,
     * List, Map)}）；{@code addLeaf}/{@code previewDelete}/{@code executeDelete} 仍走上面的
     * {@link #buildHitContext(UUID)}，该不变量对它们不受影响。
     *
     * @param comps    该 lineItem 所属模板的全部组件元数据（{@link #loadTemplateComponents} 或
     *                 {@link #loadTemplateComponentsForTemplate} 的产出，模板级，调用方可整单只查一次）
     * @param compData 该 lineItem 的 {@code componentId → [snapshot_rows, deleted_row_keys]}（
     *                 {@link #loadComponentDataByLineItem} 的产出形状；调用方可从已有的批量数据
     *                 在内存里现拼，不必现查）
     */
    private HitContextBundle buildHitContext(List<CompMeta> comps, Map<UUID, Object[]> compData) {
        HitContextBundle b = new HitContextBundle();
        b.comps = comps != null ? comps : List.of();
        b.compData = compData != null ? compData : Map.of();
        for (CompMeta cm : b.comps) {
            Object[] data = b.compData.get(cm.id);
            String rowsJson = data != null ? (String) data[0] : null;
            ArrayNode rows = parseRows(rowsJson);
            if (cm.treeTab) {   // task-260904 B-18：双判据结果（CompMeta.treeTab，整批预算）
                b.treeComponentIds.add(cm.id);
                b.treeRowsByComp.put(cm.id.toString(), rows);
                for (JsonNode row : rows) {
                    String parentNo = row.path("__parentNo").isNull() ? null : row.path("__parentNo").asText(null);
                    String hfPartNo = row.path("__hfPartNo").isNull() ? null : row.path("__hfPartNo").asText(null);
                    if (hfPartNo == null || hfPartNo.isBlank()) continue;
                    if (parentNo != null && !parentNo.isBlank()) {
                        b.ctx.addChild(parentNo, hfPartNo);
                    } else {
                        // task-260904 B-20 规则五：无父 = 树根 = 成品。改读主数据后成品也在物料表里，
                        // 不登记根就会被判成「零件」而放行挂为他人叶子（AC-26）。
                        b.ctx.addRoot(hfPartNo);
                    }
                }
            } else if (cm.tabType != null && !cm.tabType.isBlank()) {
                for (JsonNode row : rows) {
                    String mn = extractMaterialNoByField(row, cm);
                    if (mn == null) continue;
                    b.ctx.addHit(cm.tabType, mn);
                    b.sourceComponentByMaterialNo.putIfAbsent(mn, cm.id);
                }
            }
        }
        return b;
    }

    /**
     * repair-260908 · C-3：本单客户号（{@code customer.code}），供
     * {@link MasterPartTypeService#load} 收窄 {@code ds_quote_material}。
     *
     * <p>N+1 自检：每次 addLeaf 恒 1 次（Panache 按 id 取，同事务内还走一级缓存），与树节点数无关。
     *
     * <p>🚫 取不到不返回 null 兜底 —— 返 null 会让下游退回「不过滤」，即改造前的 last-wins 静默错值。
     */
    private String resolveCustomerNo(Quotation q) {
        com.cpq.customer.entity.Customer c = com.cpq.customer.entity.Customer.findById(q.customerId);
        if (c == null || c.code == null || c.code.isBlank()) {
            throw new BusinessException(400, "报价单未绑定有效客户，无法判定料号类型: " + q.id);
        }
        return c.code;
    }

    // =========================================================================
    // B6 — 加叶子
    // =========================================================================

    @Transactional
    public Map<String, Object> addLeaf(UUID quotationId, UUID lineItemId, UUID componentId,
                                        String hostNodeId, String partNo) {
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        if (li == null || !li.quotationId.equals(quotationId)) {
            throw new BusinessException(404, "报价行不存在: " + lineItemId);
        }
        Quotation q = Quotation.findById(quotationId);
        if (q == null) throw new BusinessException(404, "报价单不存在: " + quotationId);

        HitContextBundle b = buildHitContext(lineItemId);
        String compIdStr = componentId != null ? componentId.toString() : null;
        ArrayNode rows = b.treeRowsByComp.get(compIdStr);
        if (rows == null) {
            // ② 校验顺序（api.md §3.5）。判据 task-260904 B-4 起为双判据（新模型按数据源
            // semantic=='TREE'，存量回退 tab_type=='BOM'），文案不变。
            throw new BusinessException(400, "componentId 不是该报价行的树页签(tab_type=BOM)组件: " + componentId);
        }

        // ── task-260904 B-5：主数据类型索引，本请求一次预取（N+1 纪律：2 条 SQL，与树节点数无关）──
        // 料号 = 本行树上下文里出现过的全部料号 ∪ 本次待挂料号；此后 resolveStrict/resolveLenient
        // 全程只读内存索引，不再查库。
        java.util.Set<String> masterLookupKeys = new LinkedHashSet<>(b.ctx.allKnownPartNos());
        if (partNo != null && !partNo.isBlank()) masterLookupKeys.add(partNo);
        // repair-260908 · C-3：主数据取数必须带客户号 —— ds_quote_material 的业务唯一键是
        // (customer_no, material_no)，不带客户号是顺序不保证的 last-wins（详见 MasterPartTypeService 类注释）。
        // 客户号来自本单，恒可解析（quotation.customer_id NOT NULL + FK；customer.code NOT NULL）。
        BomNodeTypeResolver.MasterTypeIndex master =
                masterPartTypeService.load(resolveCustomerNo(q), masterLookupKeys);
        b.ctx.attachMasterTypes(master);

        // ① 校验宿主节点存在 + 判定宿主类型
        int hostLastIdx = -1;
        String hostPartNo = null;
        Integer hostLvl = null;
        String hostNodeType = null;
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            String nid = row.path("__nodeId").isNull() ? null : row.path("__nodeId").asText(null);
            if (hostNodeId.equals(nid)) {
                hostLastIdx = i;
                hostPartNo = row.path("__hfPartNo").isNull() ? null : row.path("__hfPartNo").asText(null);
                hostLvl = row.path("__lvl").isMissingNode() ? null : row.path("__lvl").asInt();
                JsonNode nt = row.path("__nodeType");
                if (!nt.isMissingNode() && !nt.isNull()) hostNodeType = nt.asText(null);
            }
        }
        if (hostLastIdx < 0) {
            throw new BusinessException(400, "宿主节点不存在于该树: " + hostNodeId);
        }
        // 宿主类型若物化时未判定(null)，此处按当前上下文即时重判一次（结构可能已因加叶子等操作变化）
        if (hostNodeType == null && hostPartNo != null) {
            BomNodeTypeResolver.Resolution hr = bomNodeTypeResolver.resolveLenient(hostPartNo, b.ctx);
            hostNodeType = hr != null ? hr.nodeType : null;
        }
        // ④ 宿主不是材质 / 外购件（既有护栏，task-260904 AC-8 反向断言：不得失效）。
        //    宿主类型判定同样已改读主数据（B-8）——上面的 resolveLenient 走的就是新判定链。
        if (BomNodeTypeResolver.MATERIAL.equals(hostNodeType) || BomNodeTypeResolver.OUTSOURCED.equals(hostNodeType)) {
            throw new BusinessException(400,
                    (BomNodeTypeResolver.MATERIAL.equals(hostNodeType) ? "材质" : "外购件") + "节点不可再添加下级");
        }

        // ⑤ task-260904 B-6（AC-6）：料号必须在主数据（物料表或材质库）中存在，否则 400
        //    LEAF_PART_NOT_IN_MASTER，且不落任何行。必须早于 ⑥ —— 不存在的料号谈不上成不成环，
        //    先报"不存在"更贴近用户认知（api.md §3.5）。
        if (partNo == null || partNo.isBlank()) {
            throw new BusinessException(400, "料号不能为空");
        }
        if (!master.known(partNo)) {
            throw com.cpq.common.exception.LeafAddRejectedException.partNotInMaster(partNo);
        }

        // ⑥ task-260904 B-7（AC-7）：不成环。判据 = 待挂料号是否为宿主节点的祖先（含宿主自身）。
        //    树上下文已在 rows 里，🚫 不为此再查一次全树。
        List<String> cyclePath = detectLeafCycle(rows, hostNodeId, partNo);
        if (cyclePath != null) {
            throw com.cpq.common.exception.LeafAddRejectedException.cycleDetected(partNo, cyclePath);
        }

        // ⑦ 判定新料号类型（B5 严格模式，已改读主数据：成品/冲突分别抛 400/409）
        BomNodeTypeResolver.Resolution resolution = bomNodeTypeResolver.resolveStrict(partNo, b.ctx);
        if (resolution.materialTypeFallback) {
            // AC-12③：标明本次判定走的是「material_type 为空 → 默认零件」兜底分支，
            // 便于将来数据补齐后回归对照。
            LOG.infof("[quotation-tree] addLeaf line=%s part=%s 走 material_type 空值兜底分支 → 判「%s」",
                    lineItemId, partNo, resolution.nodeType);
        }

        // ③ 生成系统列
        String uuidTag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String newNodeId = hostNodeId + "/__manual_" + uuidTag;
        int newLvl = (hostLvl != null ? hostLvl : 0) + 1;
        UUID sourceComponentId = b.sourceComponentByMaterialNo.get(partNo);

        ObjectNode newRow = MAPPER.createObjectNode();
        ObjectNode driverRow = newRow.putObject("driverRow");
        driverRow.put("material_no", partNo);
        newRow.putObject("basicDataValues");
        newRow.put("__nodeId", newNodeId);
        newRow.put("__parentId", hostNodeId);
        newRow.put("__lvl", newLvl);
        newRow.put("__hfPartNo", partNo);
        if (hostPartNo != null) newRow.put("__parentNo", hostPartNo); else newRow.putNull("__parentNo");
        newRow.putNull("__bomVersion");
        newRow.put("__manual", true);
        if (sourceComponentId != null) newRow.put("__sourceComponentId", sourceComponentId.toString());
        newRow.put("__nodeType", resolution.nodeType);

        // ④ 插入位置：宿主节点行组的最后一行之后（不可 append 到数组末尾）
        ArrayNode rebuilt = MAPPER.createArrayNode();
        for (int i = 0; i < rows.size(); i++) {
            rebuilt.add(rows.get(i));
            if (i == hostLastIdx) rebuilt.add(newRow);
        }

        // 原生 UPDATE 与 Hibernate 持久化上下文脱节：先 flush(此处无待写脏字段,无害) 再让原生
        // UPDATE 落库，随后 buildCardValues 内的原生 SELECT 在同事务/同连接下能读到刚写入的新
        // snapshot_rows(读己之写，JDBC 同事务语义保证)。
        writeSnapshotRows(lineItemId, componentId, rebuilt);

        // ⑤ 用最新 snapshot_rows 重建 quoteCardValues（保持 buildCardValues 纯读约定）；
        // li 是当前事务内的托管实体引用，snapshotQuoteSideOnly 直接 mutate 其 quoteCardValues 字段。
        cardSnapshotService.snapshotQuoteSideOnly(li, q);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("nodeId", newNodeId);
        resp.put("nodeType", resolution.nodeType);
        // AC-12③：响应里显式标注是否走了 material_type 空值兜底（不改既有键，只新增一个标记键）。
        resp.put("materialTypeFallback", resolution.materialTypeFallback);
        resp.put("quoteCardValues", li.quoteCardValues);
        return resp;
    }

    /**
     * task-260904 B-7（AC-7）：加叶子成环检测。
     *
     * <p><b>判据</b> = 待挂料号 {@code partNo} 是否为宿主节点的<b>祖先</b>（含宿主自身 ⇒ 覆盖自环）。
     * 沿 {@code __parentId} 从宿主向根走一遍即可，🚫 不新增任何查询（树行 {@code rows} 已在手）。
     *
     * @return {@code null} = 不成环；否则返回环路径的料号序列（从命中的那个祖先出发，
     *         沿树往下到宿主，再回到待挂料号，形如 {@code A → B → A}）
     */
    private static List<String> detectLeafCycle(ArrayNode rows, String hostNodeId, String partNo) {
        if (rows == null || hostNodeId == null || partNo == null) return null;
        // nodeId → (parentId, partNo)：一次遍历建索引，纯内存。
        Map<String, String> parentByNode = new LinkedHashMap<>();
        Map<String, String> partByNode = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String nid = row.path("__nodeId").isNull() ? null : row.path("__nodeId").asText(null);
            if (nid == null || nid.isBlank()) continue;
            String pid = row.path("__parentId").isNull() ? null : row.path("__parentId").asText(null);
            String pno = row.path("__hfPartNo").isNull() ? null : row.path("__hfPartNo").asText(null);
            parentByNode.putIfAbsent(nid, pid);
            partByNode.putIfAbsent(nid, pno);
        }

        // 从宿主往根走，收集「宿主自身 + 全部祖先」的料号链（自底向上）。
        List<String> upward = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        String cur = hostNodeId;
        int hitIdx = -1;
        while (cur != null && !cur.isBlank() && visited.add(cur)) {
            String pno = partByNode.get(cur);
            upward.add(pno);
            if (partNo.equals(pno)) { hitIdx = upward.size() - 1; break; }
            cur = parentByNode.get(cur);
        }
        if (hitIdx < 0) return null;

        // upward = [宿主, 父, 祖父, ... , 命中的祖先]；环路径按「从祖先往下到宿主，再回到新叶子」呈现。
        List<String> path = new ArrayList<>();
        for (int i = hitIdx; i >= 0; i--) {
            String v = upward.get(i);
            path.add(v == null ? "(未知料号)" : v);
        }
        path.add(partNo);   // 新叶子挂回来 ⇒ 闭环
        return path;
    }

    /** UPSERT 写 snapshot_rows（沿用 quotation_line_component_data 现有行；不存在则不写，理论不触发——加叶子前置已校验存在）。 */
    private void writeSnapshotRows(UUID lineItemId, UUID componentId, ArrayNode rows) {
        try {
            String json = MAPPER.writeValueAsString(rows);
            int n = em.createNativeQuery(
                    "UPDATE quotation_line_component_data SET snapshot_rows = CAST(:rows AS jsonb) " +
                    "WHERE line_item_id = :lid AND component_id = :cid")
                    .setParameter("rows", json)
                    .setParameter("lid", lineItemId)
                    .setParameter("cid", componentId)
                    .executeUpdate();
            if (n == 0) {
                LOG.warnf("[quotation-tree] writeSnapshotRows: 未命中任何行 line=%s comp=%s", lineItemId, componentId);
            }
        } catch (Exception e) {
            throw new BusinessException(500, "写入 snapshot_rows 失败: " + e.getMessage());
        }
    }

    // =========================================================================
    // B7.1 — 删除影响面预览
    // =========================================================================

    /**
     * task-0721 B7 自测发现（2026-07-21）：本方法此前缺 {@code @Transactional}，纯只读也必须标注——
     * 否则在"同一 CDI bean 实例内、无外层事务边界"连续调用场景下（本类端到端测试即复现该场景：
     * previewDelete → executeDelete → previewDelete 三连call），Hibernate 持久化上下文可能跨调用
     * 复用同一份 L1 缓存的 {@link QuotationLineItem} 实体，读到 exec 提交前的旧 {@code deletedTreeNodes}
     * 值（级联计算据此漏判"已剪枝"的兄弟节点，误把已无残余 occurrence 的料号算成"仍保留"）。加
     * {@code @Transactional} 让每次调用都在独立事务边界内取得新鲜持久化上下文，读到最新提交值。
     */
    @Transactional
    public Map<String, Object> previewDelete(UUID quotationId, UUID lineItemId, UUID componentId,
                                              String mode, String nodeId, String rowKey) {
        QuotationLineItem li = loadLineItem(quotationId, lineItemId);
        BomTreeCascadeCalculator.Mode m = parseMode(mode);
        if (m == BomTreeCascadeCalculator.Mode.ROW && (rowKey == null || rowKey.isBlank())) {
            throw new BusinessException(400, "mode=ROW 时 rowKey 必填");
        }

        HitContextBundle b = buildHitContext(lineItemId);
        ArrayNode treeRows = requireTreeRows(b, componentId);
        List<BomTreeCascadeCalculator.TreeNodeRef> allNodes = toNodeRefs(treeRows);
        Set<String> alreadyDeleted = new LinkedHashSet<>(parsePrunedNodeIds(li.deletedTreeNodes));

        BomTreeCascadeCalculator.CascadeResult result =
                BomTreeCascadeCalculator.compute(allNodes, alreadyDeleted, m, nodeId);

        return buildPreviewResponse(lineItemId, b, result);
    }

    private Map<String, Object> buildPreviewResponse(UUID lineItemId, HitContextBundle b,
                                                       BomTreeCascadeCalculator.CascadeResult result) {
        List<Map<String, Object>> treeNodes = new ArrayList<>();
        for (BomTreeCascadeCalculator.TreeNodeRef n : result.removedNodes) {
            Map<String, Object> tn = new LinkedHashMap<>();
            tn.put("nodeId", n.nodeId);
            tn.put("partNo", n.partNo);
            tn.put("lvl", n.lvl);
            treeNodes.add(tn);
        }

        List<Map<String, Object>> cascadeTabs = new ArrayList<>();
        if (!result.cascadeMaterials.isEmpty()) {
            for (CompMeta cm : b.comps) {
                if (cm.treeTab) continue; // 只级联到非树页签（task-260904 B-18：双判据结果）
                Object[] data = b.compData.get(cm.id);
                if (data == null || data[0] == null) continue;
                ArrayNode rows = parseRows((String) data[0]);
                List<Map<String, Object>> matchedRows = new ArrayList<>();
                for (JsonNode row : rows) {
                    String mn = extractMaterialNoByField(row, cm);
                    if (mn == null || !result.cascadeMaterials.contains(mn)) continue;
                    Map<String, Object> rr = new LinkedHashMap<>();
                    rr.put("rowKey", mn); // 简化:以 material_no 作行标识(单料号单行页签场景下唯一)
                    rr.put("partNo", mn);
                    rr.put("summary", summarize(row));
                    matchedRows.add(rr);
                }
                if (!matchedRows.isEmpty()) {
                    Map<String, Object> tab = new LinkedHashMap<>();
                    tab.put("componentId", cm.id.toString());
                    tab.put("tabName", cm.tabType);
                    tab.put("rows", matchedRows);
                    cascadeTabs.add(tab);
                }
            }
        }

        List<Map<String, Object>> retainedParts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : result.retainedMaterials.entrySet()) {
            Map<String, Object> rp = new LinkedHashMap<>();
            rp.put("partNo", e.getKey());
            rp.put("remainingOccurrences", e.getValue());
            rp.put("reason", "该料号在树上还有 " + e.getValue() + " 处引用");
            retainedParts.add(rp);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("treeNodes", treeNodes);
        data.put("cascadeTabs", cascadeTabs);
        data.put("retainedParts", retainedParts);
        data.put("previewToken", computePreviewToken(lineItemId));
        return data;
    }

    /** 极简摘要：取行内前 2 个非空 driverRow 字段值拼接(供弹窗展示，非权威数据)。 */
    private static String summarize(JsonNode row) {
        JsonNode driverRow = row.path("driverRow");
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        var it = driverRow.fields();
        while (it.hasNext() && shown < 2) {
            var e = it.next();
            if (e.getValue() == null || e.getValue().isNull()) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(e.getValue().asText(""));
            shown++;
        }
        return sb.toString();
    }

    // =========================================================================
    // B7.2 — 执行删除
    // =========================================================================

    @Transactional
    public Map<String, Object> executeDelete(UUID quotationId, UUID lineItemId, UUID componentId,
                                              String mode, String nodeId, String rowKey, String previewToken) {
        QuotationLineItem li = loadLineItem(quotationId, lineItemId);
        Quotation q = Quotation.findById(quotationId);
        if (q == null) throw new BusinessException(404, "报价单不存在: " + quotationId);

        // ① previewToken 校验（树在预览后变化 → 409，要求前端重新预览）
        String currentToken = computePreviewToken(lineItemId);
        if (previewToken == null || !previewToken.equals(currentToken)) {
            throw new BusinessException(409, "树结构已变化，请重新预览后再执行删除");
        }

        BomTreeCascadeCalculator.Mode m = parseMode(mode);
        if (m == BomTreeCascadeCalculator.Mode.ROW && (rowKey == null || rowKey.isBlank())) {
            throw new BusinessException(400, "mode=ROW 时 rowKey 必填");
        }

        // ② 重新计算影响面（不信任任何前端传来的影响面数据——本接口契约本就只收 mode/nodeId/rowKey）
        HitContextBundle b = buildHitContext(lineItemId);
        ArrayNode treeRows = requireTreeRows(b, componentId);
        List<BomTreeCascadeCalculator.TreeNodeRef> allNodes = toNodeRefs(treeRows);
        List<String> prunedBefore = parsePrunedNodeIds(li.deletedTreeNodes);
        Set<String> alreadyDeleted = new LinkedHashSet<>(prunedBefore);
        BomTreeCascadeCalculator.CascadeResult result =
                BomTreeCascadeCalculator.compute(allNodes, alreadyDeleted, m, nodeId);

        // ③ 写墓碑
        List<String> deletedNodeIds = new ArrayList<>();
        Map<String, List<String>> cascadeDeletedRowKeys = new LinkedHashMap<>();

        if (m == BomTreeCascadeCalculator.Mode.PRUNE) {
            // 树节点 → quotation_line_item.deleted_tree_nodes（整枝，跨该行所有树页签联动）
            List<String> merged = new ArrayList<>(prunedBefore);
            for (BomTreeCascadeCalculator.TreeNodeRef n : result.removedNodes) {
                if (!merged.contains(n.nodeId)) merged.add(n.nodeId);
                deletedNodeIds.add(n.nodeId);
            }
            li.deletedTreeNodes = MAPPER.valueToTree(merged).toString();
        } else {
            // ROW：只标记该具体行（树组件自身 deleted_row_keys），节点本身不从树上消失（AC-6 退化为空行）。
            // repair-0727 B3.1：墓碑带上被删节点的 nodeId（api.md §2.2 nodeId 维度）。
            //
            // 2026-07-26 技术总监裁决 10.6（修正）：effKey 兼容字段不得再手工拼
            // "nodeId + "::" + rowKey"——B0 对齐后，前端传来的 rowKey(即 __effKey) 本身已经是
            // "nodeId::内容键" 格式（与 FormulaCalculator.buildRawRowKeys 产出的口径一致），
            // 再拼一次会写出 "nodeId::nodeId::base" 双重前缀。直接原样使用请求 rowKey。
            CompMeta treeComp = findCompMeta(b, componentId);
            if (treeComp != null) {
                String fp = computeRowFpForNode(treeRows, nodeId, rowKey, treeComp);
                if (fp != null) {
                    appendRowTombstone(lineItemId, componentId, rowKey, fp, nodeId);
                }
            }
            deletedNodeIds.add(nodeId); // 语义：本次操作确认作废的节点(仅该行，非整枝)
        }

        // 级联行 → 各组件 deleted_row_keys（非树页签，nodeId 传 null 保持 fp 单键语义，见 B3.1）
        if (!result.cascadeMaterials.isEmpty()) {
            for (CompMeta cm : b.comps) {
                if (cm.treeTab) continue;   // task-260904 B-18：双判据结果
                Object[] data = b.compData.get(cm.id);
                if (data == null || data[0] == null) continue;
                ArrayNode rows = parseRows((String) data[0]);
                List<String> rowKeyFieldNames = parseRowKeyFieldNames(cm.rowKeyFields);
                List<String> keysForThisComp = new ArrayList<>();
                for (JsonNode row : rows) {
                    String mn = extractMaterialNoByField(row, cm);
                    if (mn == null || !result.cascadeMaterials.contains(mn)) continue;
                    String fp = DeletedRowKeys.rowFingerprint(rowKeyFieldNames, row.path("driverRow"));
                    appendRowTombstone(lineItemId, cm.id, mn, fp, null);
                    keysForThisComp.add(mn);
                }
                if (!keysForThisComp.isEmpty()) {
                    cascadeDeletedRowKeys.put(cm.id.toString(), keysForThisComp);
                }
            }
        }

        // ④ 重算小计与卡片值（本方法事务内，与改动前逐字节一致）。
        //
        // repair-0727 B3.2/B3.3 补物化 row_data + 补 componentData 投影（实测 ①-b）——
        //
        // **性能事故复盘（务必读完再改）**：最初实现在本方法（本 @Transactional 事务）内，紧接着
        // snapshotQuoteSideOnly 之后同步调用 refreshQuoteProjection/materializeRowDataAndProject，
        // 在 DAG 级联删除场景（QuoteBomTreeEndToEndTest#b7_dagCascade_realEndpoints）实测撞 JTA 60s
        // 事务超时（ARJUNA016102 The transaction is not active）。根因：materializeWholeLineRowData
        // → ConfigureSnapshotService#materializeLineRowData → writeRowData 是 REQUIRES_NEW（另开一个
        // DB 连接/事务写 quotation_line_component_data.row_data），而本方法上面 ①②③ 已经在**同一个
        // 未提交事务**里用原生 UPDATE 写过同一张表的同一行（deleted_row_keys/snapshot_rows）—— 同一行
        // 被本事务持有写锁的情况下，REQUIRES_NEW 的另一个连接尝试 UPDATE 同一行会被阻塞，等到本方法
        // 自己都返回不了（本事务不提交，锁不释放）→ 互相等待直到 JTA reaper 60s 超时强杀。
        //
        // 与 delete-driver-row 端点对照（QuotationResource.deleteDriverRow / restoreDriverRows 现成
        // 写法，注释原文"tx1 写墓碑提交 → tx2 读已提交墓碑重算+物化"）：那里把"写墓碑"与"重算+物化"
        // 拆成两次**独立**的 @Transactional 方法调用（Resource 层未包外层事务，两次调用天然是两个
        // 独立事务，tx1 先提交，tx2 才开始，不会锁互等）。
        //
        // 故本方法**只做①②③④(写墓碑+quoteCardValues重算)，物化+componentData 投影挪到 Resource 层**
        // （QuotationTreeResource#deleteExecute），在本方法的事务提交之后，作为第二个独立事务调用
        // CardSnapshotService#materializeRowDataAndProject——与 delete-driver-row 同一模式，不重复
        // 踩坑。PRUNE 剪枝已经在①③里正确落进 li.deletedTreeNodes / li.quoteCardValues（本方法事务内
        // 完整提交），第二个事务里 materializeRowDataAndProject 读到的是已提交的新鲜值，行为仍正确。
        cardSnapshotService.snapshotQuoteSideOnly(li, q);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("deletedNodeIds", deletedNodeIds);
        resp.put("cascadeDeletedRowKeys", cascadeDeletedRowKeys);
        resp.put("quoteCardValues", li.quoteCardValues);
        return resp;
    }

    /** 追加一条行墓碑到指定组件的 deleted_row_keys（与既有 delete-driver-row 端点同一存储格式）。
     * repair-0727 B3.1：{@code nodeId} 可空——树组件 ROW 删除传被删节点 nodeId，级联到非树页签的
     * 行传 null（保持 fp 单键语义，api.md §2.2）。判重条件从"fp 相同"改为"fp 相同 且 nodeId 相同"
     * （二者均可为 null；null==null 视为相同），避免同 fp 不同 nodeId 的两条墓碑被误判重复而漏写。 */
    private void appendRowTombstone(UUID lineItemId, UUID componentId, String effKey, String fp, String nodeId) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> existing = em.createNativeQuery(
                    "SELECT deleted_row_keys FROM quotation_line_component_data WHERE line_item_id = :lid AND component_id = :cid")
                    .setParameter("lid", lineItemId).setParameter("cid", componentId).getResultList();
            List<DeletedRowKeys.Tombstone> tombstones = new ArrayList<>(
                    DeletedRowKeys.parse(!existing.isEmpty() && existing.get(0) != null ? existing.get(0).toString() : null));
            boolean already = false;
            for (DeletedRowKeys.Tombstone t : tombstones) {
                boolean fpMatch = fp != null && fp.equals(t.fp());
                boolean nodeIdMatch = java.util.Objects.equals(nodeId, t.nodeId());
                if (fpMatch && nodeIdMatch) { already = true; break; }
            }
            if (!already) tombstones.add(new DeletedRowKeys.Tombstone(effKey, fp, nodeId));
            var arr = MAPPER.createArrayNode();
            for (DeletedRowKeys.Tombstone t : tombstones) {
                var o = arr.addObject();
                o.put("effKey", t.effKey());
                o.put("fp", t.fp());
                if (t.nodeId() != null) o.put("nodeId", t.nodeId());
            }
            em.createNativeQuery(
                    "UPDATE quotation_line_component_data SET deleted_row_keys = CAST(:v AS jsonb) " +
                    "WHERE line_item_id = :lid AND component_id = :cid")
                    .setParameter("v", arr.toString())
                    .setParameter("lid", lineItemId).setParameter("cid", componentId)
                    .executeUpdate();
        } catch (Exception e) {
            LOG.warnf("[quotation-tree] appendRowTombstone failed line=%s comp=%s: %s", lineItemId, componentId, e.getMessage());
        }
    }

    /**
     * ROW 模式：在树组件行集合中找到 nodeId 对应行，算其 fp（供墓碑写入）。
     *
     * <p>repair-0727 B4：同一 {@code nodeId} 下可能有 >1 条业务行（driver 视图对同一树位置返回多行），
     * 原实现"永远取第一条匹配"，删第 2 行会算出第 1 行的 fp → 删错行。本次最小修复：
     * 该 nodeId 下恰 1 行 → 行为不变；>1 行 → 用请求里的 {@code rowKey}（前端 __effKey）在 B0
     * 对齐后的 effKey 算法（{@link FormulaCalculator#buildRawRowKeys}，与 computeRows /
     * buildResolvedRows / RowDataMaterializer 同一份口径）算出的候选中精确定位；仍无法定位（算不出
     * / 不匹配）→ 退回第一条并 {@code LOG.warn}（Q6 尚未定性，此处只兜底不新增契约字段）。
     *
     * <p>包级可见（供 {@code QuotationTreeServiceRowFpForNodeTest} 纯单测，无需 DB/CDI，同包直连）。
     */
    String computeRowFpForNode(ArrayNode treeRows, String nodeId, String rowKey, CompMeta treeComp) {
        List<String> rkfNames = parseRowKeyFieldNames(treeComp.rowKeyFields);
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode row : treeRows) {
            String nid = row.path("__nodeId").isNull() ? null : row.path("__nodeId").asText(null);
            if (nodeId.equals(nid)) matches.add(row);
        }
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) {
            return DeletedRowKeys.rowFingerprint(rkfNames, matches.get(0).path("driverRow"));
        }
        // >1 行：B0 对齐后的 effKey 算法（全量 treeRows 唯一化，与渲染/物化同源）定位与请求 rowKey 相等的那条
        JsonNode fieldsNode = parseFieldsJsonSafe(treeComp.fields);
        JsonNode rowKeyFieldsNode = MAPPER.valueToTree(rkfNames);
        List<String> rawKeys = formulaCalculator.buildRawRowKeys(rowKeyFieldsNode, fieldsNode, treeRows, List.of());
        List<String> effKeys = FormulaCalculator.uniquifyRowKeys(rawKeys);
        for (int i = 0; i < treeRows.size(); i++) {
            JsonNode row = treeRows.get(i);
            String nid = row.path("__nodeId").isNull() ? null : row.path("__nodeId").asText(null);
            if (!nodeId.equals(nid)) continue;
            if (rowKey != null && rowKey.equals(effKeys.get(i))) {
                return DeletedRowKeys.rowFingerprint(rkfNames, row.path("driverRow"));
            }
        }
        LOG.warnf("[quotation-tree] computeRowFpForNode: nodeId=%s 下 %d 条同节点行，rowKey=%s 未命中任何候选" +
                " effKey=%s，退回第一条(Q6 待定性)", nodeId, matches.size(), rowKey, effKeys);
        return DeletedRowKeys.rowFingerprint(rkfNames, matches.get(0).path("driverRow"));
    }

    private static List<String> parseRowKeyFieldNames(String rowKeyFieldsJson) {
        if (rowKeyFieldsJson == null || rowKeyFieldsJson.isBlank()) return List.of();
        try {
            JsonNode arr = MAPPER.readTree(rowKeyFieldsJson);
            if (!arr.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String s = n.asText("");
                if (!s.isBlank()) out.add(s);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private CompMeta findCompMeta(HitContextBundle b, UUID componentId) {
        for (CompMeta cm : b.comps) if (cm.id.equals(componentId)) return cm;
        return null;
    }

    // =========================================================================
    // 通用小工具
    // =========================================================================

    private QuotationLineItem loadLineItem(UUID quotationId, UUID lineItemId) {
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        if (li == null || !li.quotationId.equals(quotationId)) {
            throw new BusinessException(404, "报价行不存在: " + lineItemId);
        }
        return li;
    }

    private static BomTreeCascadeCalculator.Mode parseMode(String mode) {
        if ("PRUNE".equalsIgnoreCase(mode)) return BomTreeCascadeCalculator.Mode.PRUNE;
        if ("ROW".equalsIgnoreCase(mode)) return BomTreeCascadeCalculator.Mode.ROW;
        throw new BusinessException(400, "非法 mode: " + mode + "，必须是 PRUNE 或 ROW");
    }

    private static ArrayNode requireTreeRows(HitContextBundle b, UUID componentId) {
        String key = componentId != null ? componentId.toString() : null;
        ArrayNode rows = b.treeRowsByComp.get(key);
        if (rows == null) {
            throw new BusinessException(400, "componentId 不是该报价行的树页签(tab_type=BOM)组件: " + componentId);
        }
        return rows;
    }

    private static List<BomTreeCascadeCalculator.TreeNodeRef> toNodeRefs(ArrayNode rows) {
        // 同一 __nodeId 可能因业务多行重复出现，按 nodeId 去重(结构层面只关心节点本身)。
        Map<String, BomTreeCascadeCalculator.TreeNodeRef> byId = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String nid = row.path("__nodeId").isNull() ? null : row.path("__nodeId").asText(null);
            if (nid == null || nid.isBlank() || byId.containsKey(nid)) continue;
            String pn = row.path("__hfPartNo").isNull() ? null : row.path("__hfPartNo").asText(null);
            int lvl = row.path("__lvl").isMissingNode() ? 0 : row.path("__lvl").asInt();
            byId.put(nid, new BomTreeCascadeCalculator.TreeNodeRef(nid, pn, lvl));
        }
        return new ArrayList<>(byId.values());
    }

    private static List<String> parsePrunedNodeIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String s = n.asText("");
                if (!s.isBlank()) out.add(s);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * task-0721 B7.2（2026-07-21 裁决 Q6）：previewToken = 「该 line item 当前树结构 + 墓碑状态」
     * 的内容 hash（MD5 hex）。涵盖：全部树组件的 snapshot_rows 原文 + 全部组件的 deleted_row_keys
     * 原文 + quotation_line_item.deleted_tree_nodes 原文。任一变化 → token 变化 → 执行时比对不一致 409。
     */
    private String computePreviewToken(UUID lineItemId) {
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        Map<UUID, Object[]> compData = loadComponentDataByLineItem(lineItemId);
        StringBuilder sb = new StringBuilder();
        List<UUID> sortedIds = new ArrayList<>(compData.keySet());
        sortedIds.sort(UUID::compareTo);
        for (UUID cid : sortedIds) {
            Object[] d = compData.get(cid);
            sb.append(cid).append('|')
              .append(d[0] != null ? (String) d[0] : "").append('|')
              .append(d[1] != null ? (String) d[1] : "").append(';');
        }
        sb.append("#deletedTreeNodes=").append(li != null && li.deletedTreeNodes != null ? li.deletedTreeNodes : "");
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte bb : digest) hex.append(String.format("%02x", bb));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    // =========================================================================
    // B8 — 反向校验（已拥有子节点的料号，禁止被添加到「材质元素」/「外购件」类型页签）
    // =========================================================================

    /**
     * 挂在页签行新增/保存的既有校验链路上（不新增端点，api.md §6）。
     *
     * @param targetTabType 目标页签的 tabType（仅「材质元素」「外购件」两类触发校验，其余直接放行）
     * @param partNo        待添加的料号
     * @param lineItemId    所属报价行（用于加载该行的树结构，判断 partNo 是否已有子节点）
     */
    public void assertCanAddToRestrictedTab(String targetTabType, String partNo, UUID lineItemId) {
        assertCanAddToRestrictedTab(targetTabType, partNo == null ? List.of() : List.of(partNo), lineItemId);
    }

    /**
     * 批量版本（task-0721 saveDraft 接线用，2026-07-21 补录）：一次 {@link #buildHitContext} 校验
     * 多个料号，避免同一 lineItem 的一次保存里对同一 tab 内 N 行逐行重建树上下文（N 次冗余 DB 查询）。
     * 命中任一料号即抛异常（文案含具体是哪个料号），不逐个收集"全部违规清单"（保持与既有加叶子/反向
     * 校验同款"第一个错误即拦"语义，简单可预期）。
     *
     * @param targetTabType 目标页签的 tabType（仅「材质元素」「外购件」两类触发校验，其余直接放行）
     * @param partNos       待添加/待保留的料号集合（saveDraft 场景 = 该 tab 本次保存的全部行的料号）
     * @param lineItemId    所属报价行（用于加载该行的树结构，判断 partNo 是否已有子节点）
     */
    public void assertCanAddToRestrictedTab(String targetTabType, List<String> partNos, UUID lineItemId) {
        // task-260904 B-9：本重载只拿得到 tabType（拿不到 componentId），故只能用分支②（存量判据）。
        // 「哪些页签触发校验」的双判据版本在 assertCanAddRowsToRestrictedTab 系列里（那里有 componentId）。
        if (!com.cpq.component.service.TabSemanticResolver.isLegacyRestrictedTabType(targetTabType)) return;
        if (partNos == null || partNos.isEmpty() || lineItemId == null) return;
        assertNoChildrenInRestrictedTab(targetTabType, partNos, buildHitContext(lineItemId));
    }

    /**
     * repair-260829 B-8：saveDraft 批量路径专用重载——{@code comps}/{@code compData} 由调用方整单/
     * 该行预取好（见 {@link #buildHitContext(List, Map)}），{@link #buildHitContext(UUID)} 的两条
     * per-lineItem 查询（{@code loadTemplateComponents} + {@code loadComponentDataByLineItem}）
     * 在这条路径上不再触发。判定逻辑（{@link #assertNoChildrenInRestrictedTab}）与三参版本共用
     * 同一份代码，逐字不变——只改「数据从哪来」，不改「怎么判」。
     */
    private void assertCanAddToRestrictedTab(String targetTabType, List<String> partNos, UUID lineItemId,
                                              List<CompMeta> comps, Map<UUID, Object[]> compData) {
        // 同三参版本：这里只有 tabType，触发判据用分支②；调用方（六参 assertCanAddRowsToRestrictedTab）
        // 已用双判据把过一道门，本处是二次快速放行，不会放宽也不会收紧。
        if (!com.cpq.component.service.TabSemanticResolver.isLegacyRestrictedTabType(targetTabType)) return;
        if (partNos == null || partNos.isEmpty() || lineItemId == null) return;
        assertNoChildrenInRestrictedTab(targetTabType, partNos, buildHitContext(comps, compData));
    }

    /**
     * 批量版本共用的判定体（task-0721 B8 原逻辑，repair-260829 B-8 从
     * {@link #assertCanAddToRestrictedTab(String, List, UUID)} 抽出，供三参版本与新增的预取版本
     * 共享同一份代码，避免两处判定逻辑各写一份而彼此漂移）。命中任一料号即抛异常（文案含具体是哪个
     * 料号），不逐个收集"全部违规清单"（保持与既有加叶子/反向校验同款"第一个错误即拦"语义）。
     *
     * @param targetTabType 目标页签的 tabType（调用方已确认属于「材质元素」「外购件」两类之一）
     * @param partNos       待添加/待保留的料号集合（saveDraft 场景 = 该 tab 本次保存的全部行的料号）
     * @param b             该 lineItem 的树上下文（来源不限——现查或预取，本方法不关心）
     */
    private void assertNoChildrenInRestrictedTab(String targetTabType, List<String> partNos, HitContextBundle b) {
        if (b.treeRowsByComp.isEmpty()) return; // 该行尚无树结构(如首次物化前的新行)，无从校验，放行

        // 预先收集"有子节点的料号"集合(跨全部树页签合并)，一次遍历树行，避免对每个 partNo 各扫一遍树。
        Set<String> partNosWithChildren = new LinkedHashSet<>();
        for (ArrayNode rows : b.treeRowsByComp.values()) {
            for (JsonNode row : rows) {
                String parentNo = row.path("__parentNo").isNull() ? null : row.path("__parentNo").asText(null);
                if (parentNo != null && !parentNo.isBlank()) partNosWithChildren.add(parentNo);
            }
        }
        for (String partNo : partNos) {
            if (partNo == null || partNo.isBlank()) continue;
            if (partNosWithChildren.contains(partNo)) {
                throw new BusinessException(400,
                        "该料号在 BOM 树上已有下级，不能添加到「" + targetTabType + "」页签");
            }
        }
    }

    /**
     * task-0721（2026-07-21 补录）：saveDraft / 组件行编辑接线入口 —— 校验一个组件本次保存的
     * "扁平"行数据（{@code quotation_line_component_data.row_data} 的 JSON 形状：
     * 每行 = {@code {fieldName: value, ...}}，字段名直接做 key，<b>不是</b> {@code snapshot_rows}
     * 的嵌套 {@code {driverRow, basicDataValues}} 结构，故不复用 {@link #extractMaterialNoByField}）。
     *
     * <p>该组件不是 {@code tabType∈{材质元素,外购件}} → 直接放行（无需查库）。
     *
     * @param componentId  该 tab 的组件 id
     * @param flatRowsJson {@code row_data} 原始 JSON 字符串（saveDraft 请求携带的 {@code ComponentDataDraft.rowData}）
     * @param lineItemId   所属报价行
     */
    public void assertCanAddRowsToRestrictedTab(UUID componentId, String flatRowsJson, UUID lineItemId) {
        if (componentId == null || flatRowsJson == null || flatRowsJson.isBlank() || lineItemId == null) return;
        Object[] meta = loadSingleComponentTabMeta(componentId, lineItemId);
        if (meta == null) return;
        String tabType = meta[0] != null ? meta[0].toString() : null;
        // task-260904 B-9：触发判据改双判据（新模型按数据源 semantic=='MATERIAL_ELEMENT'，
        // 存量回退 tab_type∈{材质元素,外购件}）。⚠️ 本方法是 kill switch 关闭时的逐行逃生路径
        // （已是 O(N) 查询，见类注释 repair-260829 B-8），此处多的这 1 条查询不改变其数量级；
        // 热路径（批量版）走下面的 4/6 参重载，用预取好的 CompMeta.restrictedTab，零新增查询。
        if (!tabSemanticResolver.isRestrictedTab(componentId, tabType)) return;
        String partNoField = meta[1] != null ? meta[1].toString() : null;
        String partNameField = meta[2] != null ? meta[2].toString() : null;
        // task-0721（2026-07-23 放宽）：料号列优先，名称列兜底；两者皆缺失才防御性放行
        // (理论已被 B4 保存期校验拦住——类型页签必须配至少一个标识列)。
        boolean noField = partNoField == null || partNoField.isBlank();
        boolean noNameField = partNameField == null || partNameField.isBlank();
        if (noField && noNameField) return;

        ArrayNode rows = parseRows(flatRowsJson);
        List<String> partNos = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode v = noField ? row.path(partNameField) : row.path(partNoField);
            if ((v.isMissingNode() || v.isNull()) && !noField && !noNameField) {
                // 配了料号列但本行料号列缺值 → 回落名称列取值(与 extractMaterialNoByField 同一优先级)
                v = row.path(partNameField);
            }
            if (v.isMissingNode() || v.isNull()) continue;
            String mn = v.asText(null);
            if (mn != null && !mn.isBlank()) partNos.add(mn);
        }
        assertCanAddToRestrictedTab(tabType, partNos, lineItemId);
    }

    /**
     * repair-260829 B-1：{@link #assertCanAddRowsToRestrictedTab(UUID, String, UUID)} 的批量重载
     * 用元数据类型——调用方（{@code QuotationService.processBatchStage1}）在主循环之前整单只调一次
     * {@link com.cpq.template.service.PublishedTemplateReader#allTabsOf} 拿到 {@code componentId →
     * (tabType, partNoField, partNameField)} 的映射，循环内不再逐行重查。不可变值类型，字段语义与
     * {@link #loadSingleComponentTabMeta} 返回的三元素数组逐一对应。
     */
    public record TabMeta(String tabType, String partNoField, String partNameField) {}

    /**
     * repair-260829 B-1（saveDraft 批量路径 N+1 修复）：{@link #assertCanAddRowsToRestrictedTab(UUID,
     * String, UUID)} 的批量重载——元数据由调用方预取整单一次的 {@code metaByComponent} 传入，本方法
     * 不再触发任何查询。内部逻辑与三参方法逐字一致，唯一差别是 meta 从入参取而非现查
     * {@link #loadSingleComponentTabMeta}。
     *
     * <p>🚫 不替代三参方法——{@code QuotationService.java:653} 的逐行逃生路径
     * （{@code -Dcpq.savedraft-batch-stage1=false}）继续调三参方法，两者校验语义必须逐字相同
     * （问题说明.md AC-7）。
     *
     * @param componentId      该 tab 的组件 id
     * @param flatRowsJson     {@code row_data} 原始 JSON 字符串
     * @param lineItemId       所属报价行
     * @param metaByComponent  调用方整单预取好的 {@code componentId → TabMeta} 映射（当次保存要绑定的
     *                         模板——见 B-1 口径变化说明：调用方应取 {@code q.customerTemplateId}，非
     *                         逐行现查 {@code resolveCustomerTemplateId}）
     */
    public void assertCanAddRowsToRestrictedTab(UUID componentId, String flatRowsJson, UUID lineItemId,
                                                 Map<UUID, TabMeta> metaByComponent) {
        if (componentId == null || flatRowsJson == null || flatRowsJson.isBlank() || lineItemId == null) return;
        TabMeta meta = metaByComponent == null ? null : metaByComponent.get(componentId);
        if (meta == null) return;
        String tabType = meta.tabType();
        // task-260904 B-9：双判据触发（无 CompMeta 可用，走 resolver 单点入口）。
        if (!tabSemanticResolver.isRestrictedTab(componentId, tabType)) return;
        List<String> partNos = extractRestrictedTabPartNos(flatRowsJson, meta.partNoField(), meta.partNameField());
        assertCanAddToRestrictedTab(tabType, partNos, lineItemId);
    }

    /**
     * repair-260829 B-8：{@link #assertCanAddRowsToRestrictedTab(UUID, String, UUID, Map)} 的再扩展
     * ——额外接收该 saveDraft 请求整单预取好的树上下文数据（{@code treeComps} 模板级、调用方整单只算
     * 一次；{@code treeCompData} 该 lineItem 的 {@code componentId → [snapshot_rows, deleted_row_keys]}，
     * 调用方优先从已加载的 componentData 在内存里现拼，避免为每行重新触发
     * {@code loadComponentDataByLineItem} 的跨网查询——这正是本次 B-8 要消灭的第二个 N+1，见
     * 问题说明.md ⑤ B-8 段）。判定与取「本次待校验 partNos」的逻辑与四参版本共用同一份代码
     * （{@link #extractRestrictedTabPartNos} / {@link #assertNoChildrenInRestrictedTab}），
     * 只是最终校验调用改走预取重载 {@link #assertCanAddToRestrictedTab(String, List, UUID, List, Map)}。
     *
     * @param treeComps    该 lineItem 所属模板的全部组件元数据（{@link #loadTemplateComponentsForTemplate}
     *                     的产出，模板级，调用方整单只查一次）
     * @param treeCompData 该 lineItem 的 {@code componentId → [snapshot_rows, deleted_row_keys]}
     *                     （{@link #loadComponentDataByLineItem} 的产出形状；找不到该 lineItem 时传空
     *                     Map 语义等价——新行此刻在 DB 里也确实没有非 null 的 snapshot_rows）
     */
    public void assertCanAddRowsToRestrictedTab(UUID componentId, String flatRowsJson, UUID lineItemId,
                                                 Map<UUID, TabMeta> metaByComponent,
                                                 List<CompMeta> treeComps, Map<UUID, Object[]> treeCompData) {
        if (componentId == null || flatRowsJson == null || flatRowsJson.isBlank() || lineItemId == null) return;
        TabMeta meta = metaByComponent == null ? null : metaByComponent.get(componentId);
        if (meta == null) return;
        String tabType = meta.tabType();
        // task-260904 B-9：双判据触发。🚫 不调 resolver —— 本重载在 saveDraft 的整单循环里被逐行调用
        // （QuotationService:3144），调 resolver 就是新引入 N+1。treeComps 是调用方整单只查一次的
        // CompMeta 列表，其 restrictedTab 已由 mapToCompMeta 整批算好，此处纯内存查表。
        if (!isRestrictedByPrefetchedMeta(treeComps, componentId, tabType)) return;
        List<String> partNos = extractRestrictedTabPartNos(flatRowsJson, meta.partNoField(), meta.partNameField());
        assertCanAddToRestrictedTab(tabType, partNos, lineItemId, treeComps, treeCompData);
    }

    /**
     * task-260904 B-9：从调用方预取好的 {@link CompMeta} 列表里取「是否受限页签」（双判据结果），
     * 查不到该组件时回退分支②（存量判据）——与改动前逐字一致。<b>纯内存，零查询</b>。
     */
    private static boolean isRestrictedByPrefetchedMeta(List<CompMeta> comps, UUID componentId, String tabType) {
        if (comps != null && componentId != null) {
            for (CompMeta cm : comps) {
                if (componentId.equals(cm.id)) return cm.restrictedTab;
            }
        }
        return com.cpq.component.service.TabSemanticResolver.isLegacyRestrictedTabType(tabType);
    }

    /**
     * {@code row_data}（扁平行 JSON）→ 待校验料号列表的抽取逻辑，供 4 参与 6 参
     * {@code assertCanAddRowsToRestrictedTab} 共用（repair-260829 B-8 抽出，避免两处各写一份彼此漂移）。
     * 料号列优先、名称列兜底；两者皆缺失返回空列表（task-0721 2026-07-23 放宽的口径，逐字保留）。
     */
    private List<String> extractRestrictedTabPartNos(String flatRowsJson, String partNoField, String partNameField) {
        boolean noField = partNoField == null || partNoField.isBlank();
        boolean noNameField = partNameField == null || partNameField.isBlank();
        if (noField && noNameField) return List.of();

        ArrayNode rows = parseRows(flatRowsJson);
        List<String> partNos = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode v = noField ? row.path(partNameField) : row.path(partNoField);
            if ((v.isMissingNode() || v.isNull()) && !noField && !noNameField) {
                // 配了料号列但本行料号列缺值 → 回落名称列取值(与 extractMaterialNoByField 同一优先级)
                v = row.path(partNameField);
            }
            if (v.isMissingNode() || v.isNull()) continue;
            String mn = v.asText(null);
            if (mn != null && !mn.isBlank()) partNos.add(mn);
        }
        return partNos;
    }

    /**
     * componentId 在该 lineItem 所属模板冻结快照里的 {@code [tab_type, part_no_field, part_name_field]}；
     * 不存在 → null。task-0806 B19：改经 {@link #resolveCustomerTemplateId} 解模板 id 后走
     * {@link com.cpq.template.service.PublishedTemplateReader#allTabsOf} 内存挑单条，不再直读活
     * {@code component} 表（原 {@code SELECT tab_type, part_no_field, part_name_field FROM component
     * WHERE id = :cid}）。
     */
    private Object[] loadSingleComponentTabMeta(UUID componentId, UUID lineItemId) {
        UUID templateId = resolveCustomerTemplateId(lineItemId);
        if (templateId == null) return null;
        java.util.List<com.cpq.template.entity.TemplateComponentSnapshot> tabs = publishedTemplateReader.allTabsOf(templateId);
        for (com.cpq.template.entity.TemplateComponentSnapshot s : tabs) {
            if (componentId.equals(s.componentId)) {
                return new Object[]{ s.tabType, s.partNoField, s.partNameField };
            }
        }
        return null;
    }
}
