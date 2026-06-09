package com.cpq.quotation.service.rowkey;

import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交时行键唯一性装配：解析结构快照（rowKeyFields）+ 各明细值快照（baseRows[].driverRow），
 * 复用 public {@link FormulaCalculator#computeRowKey} 算组合行键，交 {@link RowKeyConflictDetector} 判重。
 * 解析失败按"跳过该单元"降级，不阻断提交（不引入因脏 JSON 误拦截）。
 */
@ApplicationScoped
public class RowKeyUniquenessService {

    private static final Logger LOG = Logger.getLogger(RowKeyUniquenessService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject FormulaCalculator formulaCalculator;

    /** 单个明细的值快照载体。 */
    public record LineItemRows(String lineItemLabel, String valuesJson) {}

    /** 结构 tab 的行键配置缓存项。 */
    private record TabKeyCfg(String componentName, JsonNode rowKeyFields) {}

    public List<RowKeyConflict> collectConflicts(String structureJson, List<LineItemRows> items) {
        List<RowKeyConflict> out = new ArrayList<>();
        Map<String, TabKeyCfg> cfgByComp = parseStructure(structureJson);
        if (cfgByComp.isEmpty() || items == null) return out;

        for (LineItemRows item : items) {
            JsonNode tabs = readTabs(item.valuesJson());
            for (JsonNode tab : tabs) {
                String cid = tab.path("componentId").asText("");
                TabKeyCfg cfg = cfgByComp.get(cid);
                if (cfg == null || !cfg.rowKeyFields().isArray() || cfg.rowKeyFields().isEmpty()) continue;

                JsonNode baseRows = tab.path("baseRows");
                if (!baseRows.isArray() || baseRows.isEmpty()) continue;

                List<String> keys = new ArrayList<>();
                for (JsonNode br : baseRows) {
                    JsonNode driverRow = br.path("driverRow");
                    keys.add(formulaCalculator.computeRowKey(cfg.rowKeyFields(), driverRow));
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

    private JsonNode readTabs(String valuesJson) {
        if (valuesJson == null || valuesJson.isBlank()) return MissingNode.getInstance();
        try {
            return MAPPER.readTree(valuesJson).path("tabs");
        } catch (Exception e) {
            LOG.warnf("[rowkey] readTabs failed: %s", e.getMessage());
            return MissingNode.getInstance();
        }
    }
}
