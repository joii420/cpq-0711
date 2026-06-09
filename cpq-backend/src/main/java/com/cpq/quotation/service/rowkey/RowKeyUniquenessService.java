package com.cpq.quotation.service.rowkey;

import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
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

    /** 单组件两路原始 JSON。 */
    public record CompRows(String componentId, String snapshotRowsJson, String rowDataJson) {}
    /** 单明细的全部组件行。 */
    public record LineItemComps(String lineItemLabel, List<CompRows> comps) {}

    private record TabKeyCfg(String componentName, JsonNode rowKeyFields) {}

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
                    JsonNode driverRow = snapshotRows.get(i).path("driverRow");
                    JsonNode overlay = i < driverDataRows.size() ? driverDataRows.get(i) : MAPPER.createObjectNode();
                    keys.add(FormulaCalculator.computeDedupKey(cfg.rowKeyFields(), driverRow, overlay));
                }
                ObjectNode emptyDriver = MAPPER.createObjectNode();
                for (JsonNode mr : manualRows) {
                    keys.add(FormulaCalculator.computeDedupKey(cfg.rowKeyFields(), emptyDriver, mr));
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
                map.put(cid, new TabKeyCfg(name, tab.path("rowKeyFields")));
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
