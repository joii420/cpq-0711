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
        public final Map<String, BigDecimal> subtotalByColumn; // Plan 2c：列名→该列总计；缺失为空 Map

        public TabRows(List<Map<String, Object>> rows, BigDecimal subtotal) {
            this(rows, subtotal, java.util.Map.of());
        }

        public TabRows(List<Map<String, Object>> rows, BigDecimal subtotal, Map<String, BigDecimal> subtotalByColumn) {
            this.rows = rows;
            this.subtotal = subtotal;
            this.subtotalByColumn = subtotalByColumn != null ? subtotalByColumn : java.util.Map.of();
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
        return parse(cardValues, componentsSnapshot, rowKeyFieldsOf, null);
    }

    /**
     * 字段感知版：额外接收 fieldsOf（componentId → fields JsonNode）以正确解析 _前缀驱动列。
     *
     * <p>回退路径（旧快照无 resolvedRows）的 rowKey 计算使用字段 defaultSource/basicDataPath 解析，
     * 与 FormulaCalculator.computeRowKey(fields 感知版) 语义一致：
     * <ol>
     *   <li>直接读 driverRow[字段名]（字段名 == 视图列名的场景）</li>
     *   <li>按字段 defaultSource 解析：
     *       BNF_PATH/BASIC_DATA → basicDataValues["{path}"]；
     *       GLOBAL_VARIABLE → basicDataValues["@gvar:code"]</li>
     *   <li>全部 key 段为空 → 行号兜底（与前端 computeRowKey 一致）</li>
     * </ol>
     * 分隔符统一为 {@code ||} (FormulaCalculator.computeRowKey 同款)。
     *
     * @param fieldsOf           componentId → fields JsonNode（含 fieldType/defaultSource）；可为 null（退旧逻辑）
     */
    public static Map<String, TabRows> parse(
            JsonNode cardValues, JsonNode componentsSnapshot,
            Function<String, JsonNode> rowKeyFieldsOf,
            Function<String, JsonNode> fieldsOf) {
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
            JsonNode fields = fieldsOf != null ? fieldsOf.apply(cid) : null;

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
                        JsonNode basicDataValues = br.path("basicDataValues");
                        String rowKey = computeRowKey(rkf, fields, driverRow, basicDataValues, i);

                        Map<String, Object> row = new LinkedHashMap<>();
                        putAll(row, driverRow);
                        putAll(row, basicDataValues);
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
            // Plan 2c：读 per-column 小计（[页签.列名] 引用）。
            Map<String, BigDecimal> byCol = new java.util.LinkedHashMap<>();
            JsonNode byColNode = tab.path("subtotalByColumn");
            if (byColNode.isObject()) {
                byColNode.fields().forEachRemaining(en -> {
                    if (en.getValue() != null && !en.getValue().isNull())
                        byCol.put(en.getKey(), en.getValue().decimalValue());
                });
            }
            out.put(tabKey, new TabRows(rows, subtotal, byCol));
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
            out.put(e.getKey(), new TabRows(kept, e.getValue().subtotal, e.getValue().subtotalByColumn));
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

    /**
     * 字段感知版 rowKey 计算（与 FormulaCalculator.computeRowKey 4-参数重载语义一致）。
     *
     * <p>解析策略（优先级从高到低）：
     * <ol>
     *   <li>直接读 driverRow[fieldName]（字段名即视图列名的场景，如 material_no）</li>
     *   <li>按字段 defaultSource/basicDataPath 解析：
     *       BNF_PATH/BASIC_DATA → basicDataValues["{path}"]；
     *       GLOBAL_VARIABLE → basicDataValues["@gvar:code"]</li>
     *   <li>全部 key 段为空 → 行号兜底（String.valueOf(idx)）</li>
     * </ol>
     * 各段之间用 {@code ||} 拼接（与 FormulaCalculator / 前端 computeRowKey 一致）。
     *
     * @param rkf             rowKeyFields 数组（字段名列表）；null/空 → 行号兜底
     * @param fields          组件字段定义数组（含 fieldType/defaultSource）；null → 跳过 defaultSource 解析
     * @param driverRow       driver 展开的原始行（键可能为视图列别名，如 _料件）
     * @param basicDataValues 该行预查询好的基础数据值（含 "{path}" / "@gvar:code" 键）
     * @param idx             行号（全空 key 兜底）
     */
    private static String computeRowKey(JsonNode rkf, JsonNode fields,
                                         JsonNode driverRow, JsonNode basicDataValues,
                                         int idx) {
        if (rkf == null || !rkf.isArray() || rkf.size() == 0) return String.valueOf(idx);
        // 哨兵：__seq_no__ 直接退行号
        if (rkf.size() == 1 && "__seq_no__".equals(rkf.get(0).asText(""))) return String.valueOf(idx);

        // 懒构建：按字段名索引 fields，只在直接读 driverRow 失败时才用
        Map<String, JsonNode> fieldByName = null;

        List<String> parts = new ArrayList<>();
        boolean any = false;

        for (JsonNode k : rkf) {
            String fieldName = k.asText("");
            String part = "";

            // 1. 直接读 driverRow[字段名]
            String direct = pickNonEmpty(driverRow, fieldName);
            if (direct != null) {
                part = direct;
                any = true;
            } else if (fields != null && fields.isArray()) {
                // 2. 按字段 defaultSource/basicDataPath 解析
                if (fieldByName == null) {
                    fieldByName = new HashMap<>();
                    for (JsonNode f : fields) {
                        String n = f.path("name").asText("");
                        if (!n.isEmpty()) fieldByName.put(n, f);
                    }
                }
                JsonNode fieldDef = fieldByName.get(fieldName);
                if (fieldDef != null && basicDataValues != null && basicDataValues.isObject()) {
                    String resolved = resolveFromFieldDef(fieldDef, basicDataValues);
                    if (resolved != null && !resolved.isEmpty()) {
                        part = resolved;
                        any = true;
                    }
                }
            }
            parts.add(part);
        }

        // 全部 key 段为空 → 行号兜底
        if (!any) return String.valueOf(idx);
        return String.join("||", parts);
    }

    /**
     * 按字段定义（defaultSource 或 basicDataPath）从 basicDataValues 解析值。
     * BNF_PATH/BASIC_DATA → basicDataValues["{path}"]；GLOBAL_VARIABLE → basicDataValues["@gvar:code"]。
     * 返回 null 表示未解析到有效值。
     */
    private static String resolveFromFieldDef(JsonNode fieldDef, JsonNode basicDataValues) {
        // 先尝试 defaultSource（适用 INPUT/INPUT_NUMBER/INPUT_TEXT 型字段）
        JsonNode ds = fieldDef.path("defaultSource");
        if (!ds.isMissingNode() && ds.isObject()) {
            String dsType = ds.path("type").asText("");
            if ("GLOBAL_VARIABLE".equals(dsType)) {
                String code = ds.path("code").asText("");
                if (!code.isEmpty()) {
                    String v = pickNonEmpty(basicDataValues, "@gvar:" + code);
                    if (v != null) return v;
                }
            } else if ("BNF_PATH".equals(dsType) || "BASIC_DATA".equals(dsType)) {
                String path = ds.path("path").asText("");
                if (!path.isEmpty()) {
                    String v = pickNonEmpty(basicDataValues, bnfDriverLookupKey(path));
                    if (v != null) return v;
                }
            }
        }
        // 再尝试 basicDataPath（适用 BASIC_DATA 型字段）
        String bdPath = fieldDef.path("basicDataPath").asText("");
        if (!bdPath.isEmpty()) {
            String v = pickNonEmpty(basicDataValues, bnfDriverLookupKey(bdPath));
            if (v != null) return v;
        }
        return null;
    }

    /**
     * 后端 ComponentDriverService 把 basic_data_path 加花括号作 key；
     * 与 FormulaCalculator.bnfDriverLookupKey 逻辑等价。
     */
    private static String bnfDriverLookupKey(String path) {
        String p = path == null ? "" : path.trim();
        if (p.startsWith("{") && p.endsWith("}")) p = p.substring(1, p.length() - 1).trim();
        return "{" + p + "}";
    }

    /** 取 node[field] 文本，缺失/null/空串 → null。 */
    private static String pickNonEmpty(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        if (v == null || v.isMissingNode() || v.isNull()) return null;
        String s = v.asText("");
        return s.isEmpty() ? null : s;
    }

    private CardEffectiveRows() {}
}
