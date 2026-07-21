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

    // =========================================================================
    // 元数据加载
    // =========================================================================

    static final class CompMeta {
        UUID id;
        String tabType;
        String fields;
        String rowKeyFields;
    }

    /** 该 line item 所属报价模板的全部组件元数据（id/tabType/fields/rowKeyFields）。 */
    @SuppressWarnings("unchecked")
    private List<CompMeta> loadTemplateComponents(UUID lineItemId) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT c.id, c.tab_type, c.fields, c.row_key_fields FROM quotation_line_item li " +
                "JOIN quotation q ON q.id = li.quotation_id " +
                "JOIN template_component tc ON tc.template_id = q.customer_template_id " +
                "JOIN component c ON c.id = tc.component_id " +
                "WHERE li.id = :lid")
                .setParameter("lid", lineItemId).getResultList();
        List<CompMeta> out = new ArrayList<>();
        for (Object[] r : rows) {
            if (r[0] == null) continue;
            CompMeta m = new CompMeta();
            m.id = UUID.fromString(r[0].toString());
            m.tabType = r[1] != null ? r[1].toString() : null;
            m.fields = r[2] != null ? r[2].toString() : "[]";
            m.rowKeyFields = r[3] != null ? r[3].toString() : null;
            out.add(m);
        }
        return out;
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

    private static String extractMaterialNo(JsonNode row) {
        if (row == null) return null;
        JsonNode driverRow = row.path("driverRow");
        String mn = driverRow.path("material_no").asText(null);
        if (mn != null && !mn.isBlank()) return mn;
        String hf = row.path("__hfPartNo").isNull() ? null : row.path("__hfPartNo").asText(null);
        if (hf != null && !hf.isBlank()) return hf;
        return null;
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
        HitContextBundle b = new HitContextBundle();
        b.comps = loadTemplateComponents(lineItemId);
        b.compData = loadComponentDataByLineItem(lineItemId);
        for (CompMeta cm : b.comps) {
            Object[] data = b.compData.get(cm.id);
            String rowsJson = data != null ? (String) data[0] : null;
            ArrayNode rows = parseRows(rowsJson);
            if (BomTreeRenderService.isQuoteTreeTabType(cm.tabType)) {
                b.treeComponentIds.add(cm.id);
                b.treeRowsByComp.put(cm.id.toString(), rows);
                for (JsonNode row : rows) {
                    String parentNo = row.path("__parentNo").isNull() ? null : row.path("__parentNo").asText(null);
                    String hfPartNo = row.path("__hfPartNo").isNull() ? null : row.path("__hfPartNo").asText(null);
                    if (parentNo != null && !parentNo.isBlank() && hfPartNo != null && !hfPartNo.isBlank()) {
                        b.ctx.addChild(parentNo, hfPartNo);
                    }
                }
            } else if (cm.tabType != null && !cm.tabType.isBlank()) {
                for (JsonNode row : rows) {
                    String mn = extractMaterialNo(row);
                    if (mn == null) continue;
                    b.ctx.addHit(cm.tabType, mn);
                    b.sourceComponentByMaterialNo.putIfAbsent(mn, cm.id);
                }
            }
        }
        return b;
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
            throw new BusinessException(400, "componentId 不是该报价行的树页签(tab_type=BOM)组件: " + componentId);
        }

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
        if (BomNodeTypeResolver.MATERIAL.equals(hostNodeType) || BomNodeTypeResolver.OUTSOURCED.equals(hostNodeType)) {
            throw new BusinessException(400,
                    (BomNodeTypeResolver.MATERIAL.equals(hostNodeType) ? "材质" : "外购件") + "节点不可再添加下级");
        }

        // ② 判定新料号类型（B5 严格模式：命中主件/零命中/冲突分别抛 400/400/409）
        BomNodeTypeResolver.Resolution resolution = bomNodeTypeResolver.resolveStrict(partNo, b.ctx);

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
        resp.put("quoteCardValues", li.quoteCardValues);
        return resp;
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
                if (BomTreeRenderService.isQuoteTreeTabType(cm.tabType)) continue; // 只级联到非树页签
                Object[] data = b.compData.get(cm.id);
                if (data == null || data[0] == null) continue;
                ArrayNode rows = parseRows((String) data[0]);
                List<Map<String, Object>> matchedRows = new ArrayList<>();
                for (JsonNode row : rows) {
                    String mn = extractMaterialNo(row);
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
            CompMeta treeComp = findCompMeta(b, componentId);
            if (treeComp != null) {
                String fp = computeRowFpForNode(treeRows, nodeId, treeComp);
                if (fp != null) {
                    appendRowTombstone(lineItemId, componentId, nodeId + "::" + rowKey, fp);
                }
            }
            deletedNodeIds.add(nodeId); // 语义：本次操作确认作废的节点(仅该行，非整枝)
        }

        // 级联行 → 各组件 deleted_row_keys
        if (!result.cascadeMaterials.isEmpty()) {
            for (CompMeta cm : b.comps) {
                if (BomTreeRenderService.isQuoteTreeTabType(cm.tabType)) continue;
                Object[] data = b.compData.get(cm.id);
                if (data == null || data[0] == null) continue;
                ArrayNode rows = parseRows((String) data[0]);
                List<String> rowKeyFieldNames = parseRowKeyFieldNames(cm.rowKeyFields);
                List<String> keysForThisComp = new ArrayList<>();
                for (JsonNode row : rows) {
                    String mn = extractMaterialNo(row);
                    if (mn == null || !result.cascadeMaterials.contains(mn)) continue;
                    String fp = DeletedRowKeys.rowFingerprint(rowKeyFieldNames, row.path("driverRow"));
                    appendRowTombstone(lineItemId, cm.id, mn, fp);
                    keysForThisComp.add(mn);
                }
                if (!keysForThisComp.isEmpty()) {
                    cascadeDeletedRowKeys.put(cm.id.toString(), keysForThisComp);
                }
            }
        }

        // ④ 重算小计与卡片值
        cardSnapshotService.snapshotQuoteSideOnly(li, q);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("deletedNodeIds", deletedNodeIds);
        resp.put("cascadeDeletedRowKeys", cascadeDeletedRowKeys);
        resp.put("quoteCardValues", li.quoteCardValues);
        return resp;
    }

    /** 追加一条行墓碑到指定组件的 deleted_row_keys（与既有 delete-driver-row 端点同一存储格式）。 */
    private void appendRowTombstone(UUID lineItemId, UUID componentId, String effKey, String fp) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> existing = em.createNativeQuery(
                    "SELECT deleted_row_keys FROM quotation_line_component_data WHERE line_item_id = :lid AND component_id = :cid")
                    .setParameter("lid", lineItemId).setParameter("cid", componentId).getResultList();
            List<DeletedRowKeys.Tombstone> tombstones = new ArrayList<>(
                    DeletedRowKeys.parse(!existing.isEmpty() && existing.get(0) != null ? existing.get(0).toString() : null));
            boolean already = false;
            for (DeletedRowKeys.Tombstone t : tombstones) {
                if (fp != null && fp.equals(t.fp())) { already = true; break; }
            }
            if (!already) tombstones.add(new DeletedRowKeys.Tombstone(effKey, fp));
            var arr = MAPPER.createArrayNode();
            for (DeletedRowKeys.Tombstone t : tombstones) {
                var o = arr.addObject();
                o.put("effKey", t.effKey());
                o.put("fp", t.fp());
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

    /** ROW 模式：在树组件行集合中找到 nodeId 对应行，算其 fp（供墓碑写入）。 */
    private String computeRowFpForNode(ArrayNode treeRows, String nodeId, CompMeta treeComp) {
        List<String> rkfNames = parseRowKeyFieldNames(treeComp.rowKeyFields);
        for (JsonNode row : treeRows) {
            String nid = row.path("__nodeId").isNull() ? null : row.path("__nodeId").asText(null);
            if (nodeId.equals(nid)) {
                return DeletedRowKeys.rowFingerprint(rkfNames, row.path("driverRow"));
            }
        }
        return null;
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
        if (!"材质元素".equals(targetTabType) && !"外购件".equals(targetTabType)) return; // 只约束这两类
        if (partNo == null || partNo.isBlank() || lineItemId == null) return;

        HitContextBundle b = buildHitContext(lineItemId);
        for (String treeCidStr : b.treeRowsByComp.keySet()) {
            ArrayNode rows = b.treeRowsByComp.get(treeCidStr);
            boolean hasChild = false;
            for (JsonNode row : rows) {
                String parentNo = row.path("__parentNo").isNull() ? null : row.path("__parentNo").asText(null);
                if (partNo.equals(parentNo)) { hasChild = true; break; }
            }
            if (hasChild) {
                throw new BusinessException(400,
                        "该料号在 BOM 树上已有下级，不能添加到「" + targetTabType + "」页签");
            }
        }
    }
}
