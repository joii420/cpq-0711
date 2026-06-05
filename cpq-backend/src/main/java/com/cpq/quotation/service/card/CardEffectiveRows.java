package com.cpq.quotation.service.card;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/**
 * 卡片值快照 → 每页签"有效合并行 + 小计"。报价/核价共用（中性命名，勿加 costing 前缀）。
 *
 * <p>有效行合成口径（与前端 ProductCard / QuotationStep2 从快照构造的有效行逐字段一致）：
 * effectiveRow[i] = driverRow[i] ∪ basicDataValues[i] ∪ formulaResults(rowKey).values ∪ editRows(rowKey).values
 * （后者覆盖前者；editRows = 用户手改，优先级最高；formulaResults = 卡片已算好的公式值，不重算）。
 *
 * <p>tabKey = componentId:sortOrder，sortOrder 从 components_snapshot 按 componentId 取（值快照不含 sortOrder）。
 */
public final class CardEffectiveRows {

    public static final class TabRows {
        public final List<Map<String, Object>> rows;
        public final BigDecimal subtotal; // 值快照 tab.subtotal；缺失为 null

        public TabRows(List<Map<String, Object>> rows, BigDecimal subtotal) {
            this.rows = rows;
            this.subtotal = subtotal;
        }
    }

    /**
     * @param cardValues         卡片值快照根（{tabs:[...]}）
     * @param componentsSnapshot 模板 components_snapshot 数组（用于 componentId→sortOrder）
     * @param rowKeyFieldsOf     componentId → rowKeyFields JsonNode（用于 rowKey 计算）；可返回 null
     */
    public static Map<String, TabRows> parse(
            JsonNode cardValues, JsonNode componentsSnapshot,
            Function<String, JsonNode> rowKeyFieldsOf) {
        Map<String, TabRows> out = new LinkedHashMap<>();
        if (cardValues == null) return out;

        Map<String, Integer> sortByComp = new HashMap<>();
        if (componentsSnapshot != null && componentsSnapshot.isArray()) {
            for (JsonNode c : componentsSnapshot) {
                String cid = c.path("componentId").asText("");
                if (!cid.isBlank()) sortByComp.put(cid, c.path("sortOrder").asInt(0));
            }
        }

        JsonNode tabs = cardValues.path("tabs");
        if (!tabs.isArray()) return out;

        for (JsonNode tab : tabs) {
            String cid = tab.path("componentId").asText("");
            if (cid.isBlank()) continue;
            int sortOrder = sortByComp.getOrDefault(cid, tab.path("sortOrder").asInt(0));
            String tabKey = cid + ":" + sortOrder;

            JsonNode baseRows = tab.path("baseRows");
            JsonNode rkf = rowKeyFieldsOf != null ? rowKeyFieldsOf.apply(cid) : null;

            Map<String, JsonNode> editByKey = indexByRowKey(tab.path("editRows"));
            Map<String, JsonNode> formulaByKey = indexByRowKey(tab.path("formulaResults"));

            List<Map<String, Object>> rows = new ArrayList<>();
            JsonNode resolved = tab.path("resolvedRows");
            if (resolved.isArray() && resolved.size() > 0) {
                // 优先：快照已存"按字段名标量行"，直接用（通用引擎解析好的，含类型等别名≠字段名的字段）
                for (JsonNode rr : resolved) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    putAll(row, rr);
                    rows.add(row);
                }
            } else {
                // 回退（旧快照无 resolvedRows）：driverRow∪basicDataValues∪formulaResults∪editRows 合并
                int i = 0;
                if (baseRows.isArray()) {
                    for (JsonNode br : baseRows) {
                        JsonNode driverRow = br.path("driverRow");
                        String rowKey = computeRowKey(rkf, driverRow, i);

                        Map<String, Object> row = new LinkedHashMap<>();
                        putAll(row, driverRow);
                        putAll(row, br.path("basicDataValues"));
                        putAll(row, valuesOf(formulaByKey.get(rowKey)));
                        putAll(row, valuesOf(editByKey.get(rowKey)));
                        // P2-B 核价 Excel 树：保留 spine 节点身份(resolvedRows 路径已由 putAll(row,rr) 自带)
                        JsonNode nodeId = br.path("__nodeId");
                        if (!nodeId.isMissingNode() && !nodeId.isNull()) row.put("__nodeId", nodeId.asText());
                        rows.add(row);
                        i++;
                    }
                }
            }

            BigDecimal subtotal = tab.has("subtotal") && !tab.path("subtotal").isNull()
                    ? tab.path("subtotal").decimalValue() : null;
            out.put(tabKey, new TabRows(rows, subtotal));
        }
        return out;
    }

    private static Map<String, JsonNode> indexByRowKey(JsonNode arr) {
        Map<String, JsonNode> m = new HashMap<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) m.put(n.path("rowKey").asText(""), n);
        }
        return m;
    }

    private static JsonNode valuesOf(JsonNode rowNode) {
        return rowNode == null ? null : rowNode.path("values");
    }

    /**
     * P2-B 核价 Excel 树：把每个 tab 的有效行过滤到指定 spine 节点（按行内 {@code __nodeId}）。
     * 返回新 Map（不改原 eff）；某 tab 无匹配行 → 该 tab 行列表为空（节点该组件无数据 → 列空白）。
     */
    public static Map<String, TabRows> filterByNodeId(Map<String, TabRows> eff, String nodeId) {
        Map<String, TabRows> out = new LinkedHashMap<>();
        if (eff == null) return out;
        for (Map.Entry<String, TabRows> e : eff.entrySet()) {
            List<Map<String, Object>> kept = new ArrayList<>();
            for (Map<String, Object> r : e.getValue().rows) {
                Object n = r.get("__nodeId");
                if (n != null && n.toString().equals(nodeId)) kept.add(r);
            }
            out.put(e.getKey(), new TabRows(kept, e.getValue().subtotal));
        }
        return out;
    }

    private static void putAll(Map<String, Object> target, JsonNode obj) {
        if (obj == null || !obj.isObject()) return;
        obj.fields().forEachRemaining(e -> target.put(e.getKey(), jsonToJava(e.getValue())));
    }

    private static Object jsonToJava(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        if (v.isNumber()) return v.numberValue();
        if (v.isBoolean()) return v.booleanValue();
        return v.asText();
    }

    private static String computeRowKey(JsonNode rkf, JsonNode driverRow, int idx) {
        if (rkf != null && rkf.isArray() && rkf.size() > 0
                && driverRow != null && driverRow.isObject()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode f : rkf) {
                String fn = f.asText("");
                if (!fn.isEmpty()) sb.append(driverRow.path(fn).asText("")).append("|");
            }
            String k = sb.toString();
            if (!k.isBlank() && !k.replace("|", "").isBlank()) return k;
        }
        return String.valueOf(idx);
    }

    private CardEffectiveRows() {}
}
