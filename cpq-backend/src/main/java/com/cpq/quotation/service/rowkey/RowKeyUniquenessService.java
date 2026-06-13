package com.cpq.quotation.service.rowkey;

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
 */
@ApplicationScoped
public class RowKeyUniquenessService {

    private static final Logger LOG = Logger.getLogger(RowKeyUniquenessService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    FormulaCalculator formulaCalculator;

    /** 单组件两路原始 JSON。 */
    public record CompRows(String componentId, String snapshotRowsJson, String rowDataJson) {}
    /** 单明细的全部组件行。 */
    public record LineItemComps(String lineItemLabel, List<CompRows> comps) {}

    /** fields 含字段定义，用于 computeDedupKey 字段感知解析（修复 _前缀视图列 bug）。 */
    private record TabKeyCfg(String componentName, JsonNode rowKeyFields, JsonNode fields) {}

    public List<RowKeyConflict> collectConflicts(String structureJson, List<LineItemComps> items) {
        List<RowKeyConflict> out = new ArrayList<>();
        Map<String, TabKeyCfg> cfgByComp = parseStructure(structureJson);
        if (cfgByComp.isEmpty() || items == null) return out;

        for (LineItemComps item : items) {
            if (item == null || item.comps() == null) continue;
            for (CompRows comp : item.comps()) {
                TabKeyCfg cfg = cfgByComp.get(comp.componentId());
                if (cfg == null || !cfg.rowKeyFields().isArray() || cfg.rowKeyFields().isEmpty()) continue;

                ArrayNode snapshotRows = parseArray(comp.snapshotRowsJson());
                ArrayNode rowData = parseArray(comp.rowDataJson());

                List<JsonNode> driverDataRows = new ArrayList<>();
                List<JsonNode> manualRows = new ArrayList<>();
                for (JsonNode r : rowData) {
                    if ("manual".equals(r.path("_origin").asText(""))) manualRows.add(r);
                    else driverDataRows.add(r);
                }

                List<String> keys = new ArrayList<>();
                for (int i = 0; i < snapshotRows.size(); i++) {
                    JsonNode br = snapshotRows.get(i);
                    JsonNode driverRow = br.path("driverRow");
                    JsonNode basicDataValues = br.path("basicDataValues");
                    JsonNode overlay = i < driverDataRows.size() ? driverDataRows.get(i) : MAPPER.createObjectNode();
                    // 字段感知重载：透传 fields + basicDataValues，修复 _前缀视图列与字段名不一致 bug
                    keys.add(formulaCalculator.computeDedupKey(
                            cfg.rowKeyFields(), cfg.fields(), driverRow, basicDataValues, overlay));
                }
                ObjectNode emptyDriver = MAPPER.createObjectNode();
                for (JsonNode mr : manualRows) {
                    // 手动行：driverRow 为空，basicDataValues 为空，rowValues = mr
                    keys.add(formulaCalculator.computeDedupKey(
                            cfg.rowKeyFields(), cfg.fields(), emptyDriver, MAPPER.createObjectNode(), mr));
                }

                String label = (item.lineItemLabel() == null ? "" : item.lineItemLabel() + " · ") + cfg.componentName();
                out.addAll(RowKeyConflictDetector.detect(label, keys));
            }
        }
        return out;
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
