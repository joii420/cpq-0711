package com.cpq.quotation.service.rowkey;

import com.cpq.quotation.rowkey.DeletedRowKeys;
import com.cpq.quotation.rowkey.PrunedTreeNodes;
import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交时行键唯一性装配（两路位置化取数）：
 *   - 驱动列 ← snapshot_rows（按行下标 driverRow）
 *   - 输入值 / 手动行 ← row_data（按行下标；_origin='manual' 追加末尾）
 * 用 {@link FormulaCalculator#computeDedupKey} 算 input-inclusive 组合键，交 {@link RowKeyConflictDetector} 判重。
 * 解析失败按"跳过该单元"降级，不阻断提交。
 *
 * <p><b>repair-0727 B5（P0）</b>：判重前先按行墓碑（{@code deleted_row_keys}）+ 剪枝墓碑
 * （{@code deleted_tree_nodes}）过滤掉页面上已不可见的行——此前本服务只读 {@code snapshot_rows}
 * 全集，删除是软删（墓碑），故被删的行永远参与判重，用户即便把重复行都删了，提交仍报"行键重复"
 * 且 UI 上无任何操作可解除（P0 阻断，需求说明 §11.3 实机复现）。过滤实现委托
 * {@link PrunedTreeNodes}/{@link DeletedRowKeys}（与渲染/物化侧同源，AP-22 纪律，不另写一套）。
 * 树行（带 {@code __nodeId}）判重键额外拼 {@code "@" + __nodeId}（api.md §3.1.2）：同料号挂不同父
 * 是合法结构，不算撞键；同一节点下两条相同行键仍视为真撞键，照拦不误。非树行判重键与过滤逻辑
 * 逐字节不变。
 */
@ApplicationScoped
public class RowKeyUniquenessService {

    private static final Logger LOG = Logger.getLogger(RowKeyUniquenessService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    FormulaCalculator formulaCalculator;

    /**
     * 单组件三路原始 JSON。
     * @param deletedRowKeysJson repair-0727 B5 新增：该组件 {@code deleted_row_keys} 原文（行级墓碑，
     *                           可空——旧调用方/未升级测试传 null 时不过滤，逐字节旧行为）。
     */
    public record CompRows(String componentId, String snapshotRowsJson, String rowDataJson,
                            String deletedRowKeysJson) {
        /** 兼容旧三参构造（deletedRowKeysJson=null，不过滤）。 */
        public CompRows(String componentId, String snapshotRowsJson, String rowDataJson) {
            this(componentId, snapshotRowsJson, rowDataJson, null);
        }
    }

    /**
     * 单明细的全部组件行。productName=展示名(label)，productPartNo=料号(兜底匹配用)。
     * @param deletedTreeNodesJson repair-0727 B5 新增：该 lineItem 的
     *                             {@code quotation_line_item.deleted_tree_nodes} 原文（剪枝墓碑，可空）。
     */
    public record LineItemComps(String lineItemId, String productName, String productPartNo,
                                 String deletedTreeNodesJson, List<CompRows> comps) {
        /** 兼容旧四参构造（deletedTreeNodesJson=null，不剪枝过滤）。 */
        public LineItemComps(String lineItemId, String productName, String productPartNo, List<CompRows> comps) {
            this(lineItemId, productName, productPartNo, null, comps);
        }
    }

    /** fields 含字段定义，用于 computeDedupKey 字段感知解析（修复 _前缀视图列 bug）。 */
    private record TabKeyCfg(String componentName, JsonNode rowKeyFields, JsonNode fields) {}

    public List<RowKeyConflictDTO> collectConflicts(String structureJson, List<LineItemComps> items) {
        List<RowKeyConflictDTO> out = new ArrayList<>();
        Map<String, TabKeyCfg> cfgByComp = parseStructure(structureJson);
        if (cfgByComp.isEmpty() || items == null) return out;

        for (LineItemComps item : items) {
            if (item == null || item.comps() == null) continue;
            // ① 剪枝墓碑（该 lineItem 全部树页签共享同一 nodeId 命名空间，与 CardSnapshotService 同源）
            List<String> prunedNodeIds = PrunedTreeNodes.parse(item.deletedTreeNodesJson());

            for (CompRows comp : item.comps()) {
                TabKeyCfg cfg = cfgByComp.get(comp.componentId());
                if (cfg == null || !cfg.rowKeyFields().isArray() || cfg.rowKeyFields().isEmpty()) continue;

                ArrayNode snapshotRows = parseArray(comp.snapshotRowsJson());
                ArrayNode rowData = parseArray(comp.rowDataJson());
                // ② 行墓碑（api.md §2.2 规则：fp + nodeId 双维度，与 DeletedRowKeys.keepMask 同源）
                List<DeletedRowKeys.Tombstone> tombstones = DeletedRowKeys.parse(comp.deletedRowKeysJson());
                List<String> rowKeyFieldNames = rowKeyFieldNames(cfg.rowKeyFields());

                List<JsonNode> driverDataRows = new ArrayList<>();
                List<JsonNode> manualRows = new ArrayList<>();
                for (JsonNode r : rowData) {
                    if ("manual".equals(r.path("_origin").asText(""))) manualRows.add(r);
                    else driverDataRows.add(r);
                }

                List<String> keys = new ArrayList<>();
                for (int i = 0; i < snapshotRows.size(); i++) {
                    JsonNode br = snapshotRows.get(i);
                    JsonNode nodeIdNode = br.get("__nodeId");
                    String nodeId = (nodeIdNode != null && !nodeIdNode.isNull()) ? nodeIdNode.asText(null) : null;

                    // ① 剪枝过滤：__nodeId 前缀命中 deleted_tree_nodes 的行剔除，不参与判重
                    if (PrunedTreeNodes.isPruned(nodeId, prunedNodeIds)) continue;

                    JsonNode driverRow = br.path("driverRow");
                    JsonNode basicDataValues = br.path("basicDataValues");

                    // ② 行墓碑过滤：fp[+nodeId]双命中剔除，不参与判重
                    String fp = DeletedRowKeys.rowFingerprint(rowKeyFieldNames, driverRow);
                    if (DeletedRowKeys.isDeleted(fp, nodeId, tombstones)) continue;

                    JsonNode overlay = i < driverDataRows.size() ? driverDataRows.get(i) : MAPPER.createObjectNode();
                    // 字段感知重载：透传 fields + basicDataValues，修复 _前缀视图列与字段名不一致 bug
                    String key = formulaCalculator.computeDedupKey(
                            cfg.rowKeyFields(), cfg.fields(), driverRow, basicDataValues, overlay);
                    // ③ 树行判重键加节点维度（api.md §3.1.2）：同料号挂不同父不算撞键；非树行(nodeId为空)不变
                    if (key != null && nodeId != null && !nodeId.isBlank()) {
                        key = key + "@" + nodeId;
                    }
                    keys.add(key);
                }
                ObjectNode emptyDriver = MAPPER.createObjectNode();
                for (JsonNode mr : manualRows) {
                    // 手动行：driverRow 为空，basicDataValues 为空，rowValues = mr（非树行，不受 ①②③ 影响）
                    keys.add(formulaCalculator.computeDedupKey(
                            cfg.rowKeyFields(), cfg.fields(), emptyDriver, MAPPER.createObjectNode(), mr));
                }

                for (RowKeyConflict rc : RowKeyConflictDetector.detect(cfg.componentName(), keys)) {
                    List<Integer> oneBased = new ArrayList<>();
                    for (Integer idx : rc.rowIndices()) oneBased.add(idx + 1);   // 0 基 → 1 基（过滤后序号，api.md 约定）
                    out.add(new RowKeyConflictDTO(
                            item.lineItemId(), item.productName(), item.productPartNo(),
                            comp.componentId(), cfg.componentName(),
                            rc.rowKey(), oneBased));
                }
            }
        }
        return out;
    }

    /** rowKeyFields 节点 → 字段名列表（与 {@code CardSnapshotService#rowKeyFieldNamesOf} 提取规则一致）。 */
    private static List<String> rowKeyFieldNames(JsonNode rowKeyFieldsNode) {
        if (rowKeyFieldsNode == null || !rowKeyFieldsNode.isArray()) return List.of();
        List<String> names = new ArrayList<>(rowKeyFieldsNode.size());
        for (JsonNode n : rowKeyFieldsNode) {
            String name = n.asText("");
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    private Map<String, TabKeyCfg> parseStructure(String structureJson) {
        Map<String, TabKeyCfg> map = new HashMap<>();
        if (structureJson == null || structureJson.isBlank()) return map;
        try {
            for (JsonNode tab : MAPPER.readTree(structureJson).path("tabs")) {
                String cid = tab.path("componentId").asText("");
                if (cid.isBlank()) continue;
                String name = tab.path("componentName").asText(cid);
                // 存 fields 供 computeDedupKey 字段感知解析（修复 _前缀视图列与字段名不一致 bug）
                map.put(cid, new TabKeyCfg(name, tab.path("rowKeyFields"), tab.path("fields")));
            }
        } catch (Exception e) {
            LOG.warnf("[rowkey] parseStructure failed: %s", e.getMessage());
        }
        return map;
    }

    private ArrayNode parseArray(String json) {
        if (json == null || json.isBlank()) return MAPPER.createArrayNode();
        try {
            JsonNode n = MAPPER.readTree(json);
            return n.isArray() ? (ArrayNode) n : MAPPER.createArrayNode();
        } catch (Exception e) {
            LOG.warnf("[rowkey] parseArray failed: %s", e.getMessage());
            return MAPPER.createArrayNode();
        }
    }
}
