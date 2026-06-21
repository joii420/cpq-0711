package com.cpq.configure.service;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.formula.dataloader.QuotationIdContext;
import com.cpq.quotation.service.CrossTabComponentOrder;
import com.cpq.quotation.service.RowDataMaterializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 加产品整份快照 — Phase 1（docs/方案-加产品整份快照.md）。
 *
 * <p>在 {@code ConfigureProductService.configure()} <b>提交之后</b>由 Resource 调用,把每个报价行
 * 各组件的整行展开值(ExpandDriverResponse.rows = [{driverRow, basicDataValues}])冻进
 * {@code quotation_line_component_data.snapshot_rows}(基础冻结层,与编辑层 row_data 分开)。
 *
 * <p><b>事务设计(关键)</b>:
 * <ul>
 *   <li>本协调方法 {@link #snapshotLines} <b>不带事务</b> —— expand 与渲染端 batch-expand 一样在
 *       无事务上下文执行,内部坏路径(如 {@code mat_bom.length})只产出 #ERROR 值,
 *       <b>不会污染/中止</b>外层事务(避免连累 configure 主写入)。</li>
 *   <li>写入用 {@link #writeSnapshot}(REQUIRES_NEW)独立小事务,逐组件隔离;某组件失败不影响其它。</li>
 *   <li>必须在 configure 提交后调用,REQUIRES_NEW 才能读到已提交的 line_item/基础数据/工序。</li>
 * </ul>
 *
 * <p><b>Phase 1 加性纪律</b>:仅"写"快照,渲染链路不读 snapshot_rows(仍实时展开),对现有渲染零影响;
 * 全程降级,任何失败只记日志,绝不影响加产品响应。
 */
@ApplicationScoped
public class ConfigureSnapshotService {

    private static final Logger LOG = Logger.getLogger(ConfigureSnapshotService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    @Inject
    ComponentDriverService componentDriverService;

    @Inject
    RowDataMaterializer rowDataMaterializer;

    /** 自注入:用于触发 REQUIRES_NEW 拦截器(同 bean 内自调用不经代理则拦截器失效)。 */
    @Inject
    ConfigureSnapshotService self;

    public static class DriverComp {
        public UUID id;
        public String name;
        public String driverPath;
    }

    /** 组合父级的子件行(lineItemId + partNo),用于按子件聚合展开。 */
    public static class ChildLine {
        public UUID lineItemId;
        public String partNo;
    }

    /**
     * 重快照整张报价单的所有行(saveDraft 全量重建后调用:行已是新 UUID,需按新行重建快照)。
     * 直接查 quotation_line_item,自包含;UPSERT 保留编辑层 row_data。
     */
    public void snapshotQuotation(UUID quotationId) {
        if (quotationId == null) return;
        try {
            List<Map<String, Object>> lines = self.loadQuotationLines(quotationId);
            snapshotLines(quotationId, lines);
        } catch (Exception e) {
            LOG.warnf("[add-snapshot] quotation=%s 整单重快照失败(已降级): %s", quotationId, e.getMessage());
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> loadQuotationLines(UUID quotationId) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, product_part_no_snapshot, composite_type " +
                "FROM quotation_line_item WHERE quotation_id = :q")
                .setParameter("q", quotationId).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", r[0]);
            m.put("productPartNo", r[1]);
            m.put("compositeType", r[2]);
            out.add(m);
        }
        return out;
    }

    /**
     * 为 configure 返回的各报价行写整份快照。expand 无事务运行(错误隔离),写入 REQUIRES_NEW。
     * 全程 try/catch 降级,绝不抛出影响加产品。
     */
    public void snapshotLines(UUID quotationId, List<Map<String, Object>> lineItems) {
        if (quotationId == null || lineItems == null || lineItems.isEmpty()) return;
        try {
            // 清 driver 进程缓存(30s TTL),保证快照冻结的是"当前"基础值而非缓存旧值
            // (尤其"从基础刷新"在基础变更后需读到最新)。
            componentDriverService.evictAll();
            UUID customerId = self.loadCustomerId(quotationId);
            List<DriverComp> comps = self.loadDriverComponents(quotationId);
            if (comps.isEmpty()) return;
            // 统一协议(2026-05-30):所有 (line × component) 走同一路径,SQL 视图自己用
                //   :quotationId + :customerCode + 外层 :hfPartNos
                // 自适应 SIMPLE / COMPOSITE 语义(视图内 UNION ALL),Java 不再按 driverPath 判定聚合。
            QuotationIdContext.set(quotationId);
            try {
                // 物化所需:模板 components_snapshot(含各 tab 的 componentCode/fields/formulas)。一次加载,逐行复用。
                JsonNode componentsSnapshot = self.loadComponentsSnapshot(quotationId);
                for (Map<String, Object> li : lineItems) {
                    UUID lineItemId = asUuid(li.get("id"));
                    String partNo = li.get("productPartNo") != null ? li.get("productPartNo").toString() : null;
                    String compositeType = li.get("compositeType") != null ? li.get("compositeType").toString() : null;
                    if (lineItemId == null || partNo == null || partNo.isBlank()) continue;
                    // 本行各组件刚写入的 snapshot_rows JSON(componentId → rowsJson),供随后物化 row_data 复用。
                    Map<UUID, String> snapByComp = new LinkedHashMap<>();
                    for (DriverComp comp : comps) {
                        try {
                            ExpandDriverResponse exp = componentDriverService.expand(
                                    comp.id, customerId, partNo, null, null, null, lineItemId, compositeType);
                            List<ExpandDriverResponse.Row> rows = (exp != null && exp.rows != null) ? exp.rows : new ArrayList<>();
                            String rowsJson = MAPPER.writeValueAsString(rows);
                            self.writeSnapshot(lineItemId, comp.id, comp.name, rowsJson);
                            snapByComp.put(comp.id, rowsJson);
                        } catch (Exception e) {
                            LOG.warnf("[add-snapshot] line=%s comp=%s 跳过: %s", lineItemId, comp.id, e.getMessage());
                        }
                    }
                    // 写时算齐:把 FORMULA 叶子列算进 row_data(扁平),让 Excel 视图无需用户编辑即正确求和。
                    try {
                        materializeRowData(lineItemId, componentsSnapshot, snapByComp);
                    } catch (Exception e) {
                        LOG.warnf("[add-snapshot] line=%s 物化 row_data 失败(已降级,仍可编辑后修正): %s",
                                lineItemId, e.getMessage());
                    }
                }
            } finally {
                QuotationIdContext.clear();
            }
        } catch (Exception e) {
            LOG.warnf("[add-snapshot] quotation=%s 快照整体失败(已降级): %s", quotationId, e.getMessage());
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public UUID loadCustomerId(UUID quotationId) {
        List<Object> r = em.createNativeQuery("SELECT customer_id FROM quotation WHERE id = :q")
                .setParameter("q", quotationId).getResultList();
        return r.isEmpty() || r.get(0) == null ? null : UUID.fromString(r.get(0).toString());
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public List<DriverComp> loadDriverComponents(UUID quotationId) {
        // 按 template_component 取 live driver 组件(live component id,expand 可直接加载)
        // driver_path 用于判定组合父级该"聚合子件"(composite_child_*_mirror)还是"父级展开"($zcj_bom 子配件清单)
        List<Object[]> rows = em.createNativeQuery(
                "SELECT DISTINCT c.id, c.name, c.data_driver_path FROM quotation q " +
                "JOIN template_component tc ON tc.template_id = q.customer_template_id " +
                "JOIN component c ON c.id = tc.component_id " +
                "WHERE q.id = :q AND c.data_driver_path IS NOT NULL AND c.data_driver_path <> ''")
                .setParameter("q", quotationId).getResultList();
        List<DriverComp> out = new ArrayList<>();
        for (Object[] r : rows) {
            if (r[0] == null) continue;
            DriverComp dc = new DriverComp();
            dc.id = UUID.fromString(r[0].toString());
            dc.name = r[1] != null ? r[1].toString() : null;
            dc.driverPath = r[2] != null ? r[2].toString() : null;
            out.add(dc);
        }
        return out;
    }

    /**
     * 解析组合父级的子件行(REQUIRES_NEW,读已提交)。
     * <ol>
     *   <li>优先按 {@code parent_line_item_id = 父行} 关联(configure 当下 insertLineItem 已写 parentId);</li>
     *   <li>关联缺失(saveDraft 全量重建后 tempParentIndex 二阶段未接上 → parent_line_item_id 为 NULL)时,
     *       回退按 BOM:本报价单内 partNo 命中父级 {@code material_bom_item[characteristic='ASSEMBLY']}
     *       装配子料号的 PART 行(同单 → 天然按客户隔离,无需再过滤 customer_no)。</li>
     * </ol>
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public List<ChildLine> resolveCompositeChildren(UUID quotationId, UUID parentLineItemId, String parentPartNo) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, product_part_no_snapshot FROM quotation_line_item " +
                "WHERE parent_line_item_id = :pid AND composite_type = 'PART' " +
                "  AND product_part_no_snapshot IS NOT NULL")
                .setParameter("pid", parentLineItemId).getResultList();
        if (rows.isEmpty() && parentPartNo != null && !parentPartNo.isBlank()) {
            rows = em.createNativeQuery(
                    "SELECT child.id, child.product_part_no_snapshot FROM quotation_line_item child " +
                    "WHERE child.quotation_id = :q AND child.composite_type = 'PART' " +
                    "  AND child.product_part_no_snapshot IN (" +
                    "     SELECT mbi.component_no FROM material_bom_item mbi " +
                    "     WHERE mbi.system_type = 'QUOTE' AND mbi.material_no = :pp " +
                    "       AND mbi.characteristic = 'ASSEMBLY' AND mbi.component_no IS NOT NULL AND mbi.is_current = true)")
                    .setParameter("q", quotationId).setParameter("pp", parentPartNo).getResultList();
        }
        List<ChildLine> out = new ArrayList<>();
        for (Object[] r : rows) {
            if (r[0] == null || r[1] == null) continue;
            ChildLine cl = new ChildLine();
            cl.lineItemId = UUID.fromString(r[0].toString());
            cl.partNo = r[1].toString();
            out.add(cl);
        }
        return out;
    }

    /** 取本报价单 customer_template 的 components_snapshot(物化 row_data 用);缺失/解析失败 → null。
     *  只读 → SUPPORTS(表意:无需独立写事务;configure 已提交,SELECT 读最新已提交态即可)。 */
    @Transactional(Transactional.TxType.SUPPORTS)
    @SuppressWarnings("unchecked")
    public JsonNode loadComponentsSnapshot(UUID quotationId) {
        try {
            List<Object> r = em.createNativeQuery(
                    "SELECT t.components_snapshot FROM quotation q " +
                    "JOIN template t ON t.id = q.customer_template_id WHERE q.id = :q")
                    .setParameter("q", quotationId).getResultList();
            if (r.isEmpty() || r.get(0) == null) return null;
            JsonNode node = MAPPER.readTree(r.get(0).toString());
            return node.isArray() ? node : null;
        } catch (Exception e) {
            LOG.warnf("[add-snapshot] quotation=%s 取 components_snapshot 失败: %s", quotationId, e.getMessage());
            return null;
        }
    }

    /**
     * 写时算齐 row_data(单趟拓扑序):用真实公式引擎把各非 SUBTOTAL 组件的 FORMULA 叶子列算进
     * {@code row_data}(扁平),供 Excel 视图({@code ComponentDataEffectiveRows.columnSums})无需用户
     * 编辑即正确求和。
     *
     * <p><b>对齐生产兄弟 {@link com.cpq.quotation.service.CardSnapshotService#assembleTabsWithFormulaResults}</b>:
     * 按组件级 {@link CrossTabComponentOrder#topoOrder} 排序(依赖边 = {@code cross_tab_ref} 源
     * + {@code component_subtotal} 跨组件引用),保证<b>被引用组件先于引用方计算</b>。每算完一个组件即:
     * <ol>
     *   <li>把其扁平行存入 {@code crossTabRows}(双键 componentId / componentCode)供后续 cross_tab_ref 引用;</li>
     *   <li>累计其按列小计到 {@code componentSubtotals}(键 {@code code#col} / {@code name#col},与
     *       {@link com.cpq.quotation.service.card.ComponentDataEffectiveRows} 一致)供后续 component_subtotal 求值;</li>
     *   <li>UPSERT 落库一次。</li>
     * </ol>
     * 因此到引用方计算时,它依赖的全部小计 / cross-tab 行均已就绪 —— 消除了旧 2-pass 里
     * {@code cross_tab_ref → 依赖 component_subtotal 的列} 链路读到引用方 Pass1 陈旧值的缺陷,
     * 同时去掉了冗余复算。
     *
     * <p>AP-51:行数权威 = snapshot_rows(materializeComponentRows 内按其行数迭代,绝不 Math.max);
     * SUBTOTAL 组件跳过(读时按公式重算)。
     */
    private void materializeRowData(UUID lineItemId, JsonNode componentsSnapshot,
                                    Map<UUID, String> snapByComp) {
        if (componentsSnapshot == null || snapByComp == null || snapByComp.isEmpty()) return;

        // componentId → snapshot tab(仅非 SUBTOTAL,且本行有 snapshot_rows)
        Map<UUID, JsonNode> tabByComp = new LinkedHashMap<>();
        for (JsonNode tab : componentsSnapshot) {
            String type = tab.path("componentType").asText("NORMAL");
            if ("SUBTOTAL".equals(type)) continue; // 读时重算,不物化
            UUID cid = asUuid(tab.path("componentId").asText(null));
            if (cid != null && snapByComp.containsKey(cid)) tabByComp.put(cid, tab);
        }
        if (tabByComp.isEmpty()) return;

        // ── 组件级拓扑序(与生产兄弟 assembleTabsWithFormulaResults 同款) ──
        // 解析表:componentId / componentCode / tabName → componentId 字符串(供 component_subtotal 依赖解析)。
        Map<String, String> refToCid = new HashMap<>();
        for (Map.Entry<UUID, JsonNode> e : tabByComp.entrySet()) {
            String cid = e.getKey().toString();
            JsonNode tab = e.getValue();
            refToCid.put(cid, cid);
            String code = tab.path("componentCode").asText("");
            if (!code.isBlank()) refToCid.put(code, cid);
            String tn = tab.path("tabName").asText("");
            if (!tn.isBlank()) refToCid.put(tn, cid);
        }
        List<String> compIds = new ArrayList<>();
        Map<String, java.util.Set<String>> compDeps = new LinkedHashMap<>();
        for (Map.Entry<UUID, JsonNode> e : tabByComp.entrySet()) {
            String cid = e.getKey().toString();
            JsonNode formulas = e.getValue().path("formulas");
            compIds.add(cid);
            // cross_tab_ref 源依赖 + component_subtotal 跨组件依赖(token 精确,与前端/生产兄弟对齐)。
            java.util.Set<String> deps = new java.util.LinkedHashSet<>(
                    CrossTabComponentOrder.extractSourceRefs(formulas));
            for (String r : CrossTabComponentOrder.extractSubtotalRefs(formulas)) {
                String tcid = refToCid.get(r);
                if (tcid != null && !tcid.equals(cid)) deps.add(tcid); // 排除自引用(本组件二阶列由引擎内两阶段处理)
            }
            compDeps.put(cid, deps);
        }
        List<String> order;
        try {
            order = CrossTabComponentOrder.topoOrder(compIds, compDeps);
        } catch (Exception cyc) {
            // 环(配置异常)→ 降级按原序物化,绝不中止整份快照(沿用本类全程降级纪律)。
            LOG.warnf("[add-snapshot] line=%s 组件拓扑序失败(降级原序): %s", lineItemId, cyc.getMessage());
            order = compIds;
        }

        Map<String, Double> componentSubtotals = new HashMap<>();
        Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();

        // ── 单趟拓扑序:依赖在前,引用在后 —— 引用方计算时其依赖小计 / cross-tab 行已就绪 ──
        for (String cidStr : order) {
            UUID cid = asUuid(cidStr);
            JsonNode tab = cid != null ? tabByComp.get(cid) : null;
            if (tab == null) continue;
            String code = tab.path("componentCode").asText(null);
            String tabName = tab.path("tabName").asText(null);
            JsonNode snapshotRows = parseRows(snapByComp.get(cid));

            ArrayNode flat = rowDataMaterializer.materializeComponentRows(
                    componentsSnapshot, code, snapshotRows, componentSubtotals, crossTabRows);

            // crossTabRows(双键 componentId / componentCode):供后续组件 cross_tab_ref 引用。
            List<Map<String, Object>> flatRows = toRowMaps(flat);
            crossTabRows.put(cidStr, flatRows);
            if (code != null && !code.isBlank()) crossTabRows.put(code, flatRows);

            // 列小计累计(键 code#col / name#col):供后续组件 component_subtotal token 求值。
            accumulateColumnSubtotals(flat, code, tabName, componentSubtotals);

            try {
                self.writeRowData(lineItemId, cid, MAPPER.writeValueAsString(flat));
            } catch (Exception ex) {
                LOG.warnf("[add-snapshot] line=%s comp=%s 写 row_data 失败: %s", lineItemId, cid, ex.getMessage());
            }
        }
    }

    /** 解析 snapshot_rows JSON 为 JsonNode 数组;失败 → 空数组。 */
    private JsonNode parseRows(String json) {
        if (json == null || json.isBlank()) return MAPPER.createArrayNode();
        try {
            JsonNode n = MAPPER.readTree(json);
            return n.isArray() ? n : MAPPER.createArrayNode();
        } catch (Exception e) {
            return MAPPER.createArrayNode();
        }
    }

    /** 扁平行 ArrayNode → List<Map>(供 crossTabRows)。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toRowMaps(ArrayNode flat) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (flat == null) return out;
        for (JsonNode r : flat) {
            try {
                out.add(MAPPER.convertValue(r, Map.class));
            } catch (Exception ignore) { /* 跳过坏行 */ }
        }
        return out;
    }

    /** 对扁平行各数值列求和,写入 componentSubtotals(键 code#col / name#col),与 ComponentDataEffectiveRows 一致。 */
    private void accumulateColumnSubtotals(ArrayNode flat, String code, String tabName,
                                           Map<String, Double> componentSubtotals) {
        if (flat == null) return;
        Map<String, Double> colSums = new LinkedHashMap<>();
        for (JsonNode row : flat) {
            if (row == null || !row.isObject()) continue;
            row.fields().forEachRemaining(en -> {
                JsonNode v = en.getValue();
                if (v != null && v.isNumber()) {
                    colSums.merge(en.getKey(), v.doubleValue(), Double::sum);
                }
            });
        }
        for (Map.Entry<String, Double> e : colSums.entrySet()) {
            if (code != null && !code.isBlank()) componentSubtotals.put(code + "#" + e.getKey(), e.getValue());
            if (tabName != null && !tabName.isBlank()) componentSubtotals.put(tabName + "#" + e.getKey(), e.getValue());
        }
    }

    /**
     * 写一个组件物化后的 row_data(REQUIRES_NEW 独立小事务)。
     * UPSERT:行已存在(writeSnapshot 已建)→ UPDATE row_data;不存在 → INSERT。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void writeRowData(UUID lineItemId, UUID componentId, String rowDataJson) {
        String expr = rowDataJson == null ? "NULL" : "CAST(:rd AS jsonb)";
        var update = em.createNativeQuery(
                "UPDATE quotation_line_component_data SET row_data = " + expr + " " +
                "WHERE line_item_id = :lid AND component_id = :cid")
                .setParameter("lid", lineItemId).setParameter("cid", componentId);
        if (rowDataJson != null) update.setParameter("rd", rowDataJson);
        int updated = update.executeUpdate();
        if (updated == 0) {
            var insert = em.createNativeQuery(
                    "INSERT INTO quotation_line_component_data " +
                    "(line_item_id, component_id, row_data, created_at) " +
                    "VALUES (:lid, :cid, " + expr + ", NOW())")
                    .setParameter("lid", lineItemId).setParameter("cid", componentId);
            if (rowDataJson != null) insert.setParameter("rd", rowDataJson);
            insert.executeUpdate();
        }
    }

    /**
     * 写一个组件的快照(REQUIRES_NEW 独立事务,逐组件隔离)。
     * <b>UPSERT 而非删插</b>:更新基础冻结层 snapshot_rows,<b>保留编辑层 row_data</b>
     * (saveDraft 已写的编辑/或用户编辑不被覆盖);无行则插入。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void writeSnapshot(UUID lineItemId, UUID componentId, String tabName, String rowsJson) {
        // rowsJson == null → snapshot_rows = NULL(快照"未命中",渲染回退实时);用 SQL 字面量 NULL,
        // 避免 native query 给 null 绑定推断不出类型。非 null 走 CAST(:rows AS jsonb)。
        String rowsExpr = rowsJson == null ? "NULL" : "CAST(:rows AS jsonb)";
        var update = em.createNativeQuery(
                "UPDATE quotation_line_component_data " +
                "SET snapshot_rows = " + rowsExpr + ", snapshot_at = NOW(), tab_name = COALESCE(tab_name, :tab) " +
                "WHERE line_item_id = :lid AND component_id = :cid")
                .setParameter("tab", tabName)
                .setParameter("lid", lineItemId).setParameter("cid", componentId);
        if (rowsJson != null) update.setParameter("rows", rowsJson);
        int updated = update.executeUpdate();
        if (updated == 0) {
            var insert = em.createNativeQuery(
                    "INSERT INTO quotation_line_component_data " +
                    "(line_item_id, component_id, tab_name, snapshot_rows, snapshot_at) " +
                    "VALUES (:lid, :cid, :tab, " + rowsExpr + ", NOW())")
                    .setParameter("lid", lineItemId).setParameter("cid", componentId)
                    .setParameter("tab", tabName);
            if (rowsJson != null) insert.setParameter("rows", rowsJson);
            insert.executeUpdate();
        }
    }

    private static UUID asUuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }
}
