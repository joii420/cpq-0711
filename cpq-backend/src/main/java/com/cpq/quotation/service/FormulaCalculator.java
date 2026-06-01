package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报价单整份快照 Phase 2 Task 2 — 公式编排引擎搬后端（1:1 复刻前端 {@code formulaEngine.ts} +
 * {@code QuotationStep2.tsx} 的 computeAllFormulas / computeTabSubtotal / previous_row_subtotal 编排）。
 *
 * <p><b>4 层职责</b>（与计划 Task 2 Step1 产出对齐）：
 * <ol>
 *   <li><b>evaluateExpression</b>（单公式）：token 拼算术串求值；{@code ×→*} {@code ÷→/}；
 *       4 位小数 HALF_UP；缺值/解析异常/除零 → 0。token 取值来源与前端一致。</li>
 *   <li><b>字段值收集</b>（AP-37 每 field_type）：从 driverRow + editRows + basicDataValues 构建 fieldValues。</li>
 *   <li><b>computeTabSubtotal</b>：逐行算 is_subtotal 字段之和。</li>
 *   <li><b>previous_row_subtotal 行间累加</b>：tab 内按行序求值，上行 is_subtotal 传下行。</li>
 * </ol>
 *
 * <p><b>取值来源</b>：{@code baseRows[i].basicDataValues} 已含 {@code {path}} / {@code @gvar:CODE} /
 * DATA_SOURCE 三类解析值（与前端 basicDataValues 优先级一致），直接取，无需重查基础表。
 *
 * <p><b>纪律</b>：AP-51 行数权威 = baseRows（driver 展开结果）；AP-54 editRows 按 rowKey 对齐而非下标。
 *
 * <p>无可变状态的纯计算 bean（{@code @ApplicationScoped} 便于注入；同时支持 {@code new} 直接单测）。
 */
@ApplicationScoped
public class FormulaCalculator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigDecimal ZERO4 = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

    /** 单行公式求值上下文（对齐 formulaEngine.ts evaluateExpression 的多个参数）。 */
    public static class RowContext {
        /** field / datasource_field token 取值：字段名 → 数值 */
        public Map<String, Double> fieldValues = new HashMap<>();
        /** component_subtotal token 取值：component_code / tab_name / value → 跨 tab 小计 */
        public Map<String, Double> componentSubtotals = new HashMap<>();
        /** quotation_field token 取值 */
        public Map<String, Double> quotationFields = new HashMap<>();
        /** product_attribute token 取值 */
        public Map<String, Double> productAttributes = new HashMap<>();
        /** path / global_variable token 取值：{@code "{path}"} / {@code "@gvar:CODE"} → 已解析值 */
        public Map<String, Object> basicDataValues = new HashMap<>();
        /** previous_row_subtotal token：上一行 is_subtotal 值；行 0 为 null → token 走 fallback。 */
        public Double previousRowSubtotal = null;
    }

    // ======================================================================
    // Layer 1 — evaluateExpression（单公式 token 数组 → BigDecimal）
    // ======================================================================

    public BigDecimal evaluateExpression(JsonNode tokens, RowContext ctx) {
        if (tokens == null || !tokens.isArray() || tokens.size() == 0) return ZERO4;
        RowContext c = ctx != null ? ctx : new RowContext();
        try {
            StringBuilder expr = new StringBuilder();
            for (JsonNode token : tokens) {
                appendToken(expr, token, c);
            }
            double result = new ArithParser(expr.toString()).parse();
            if (Double.isNaN(result) || Double.isInfinite(result)) return ZERO4; // 除零/非有限 → 0
            return BigDecimal.valueOf(result).setScale(4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return ZERO4; // 解析异常 → 0（对齐前端 try/catch）
        }
    }

    private void appendToken(StringBuilder expr, JsonNode token, RowContext ctx) {
        String type = token.path("type").asText("");
        switch (type) {
            case "field":
                expr.append(numStr(ctx.fieldValues.getOrDefault(token.path("value").asText(""), 0.0)));
                break;
            case "operator": {
                String v = token.path("value").asText("");
                String op = "×".equals(v) ? "*" : "÷".equals(v) ? "/" : v;
                expr.append(op);
                break;
            }
            case "bracket_open":
                expr.append('(');
                break;
            case "bracket_close":
                expr.append(')');
                break;
            case "number":
                expr.append(token.has("value") ? token.path("value").asText("0") : "0");
                break;
            case "component_subtotal": {
                Double v = firstNonNull(
                    ctx.componentSubtotals.get(asTextOrNull(token, "component_code")),
                    ctx.componentSubtotals.get(asTextOrNull(token, "tab_name")),
                    ctx.componentSubtotals.get(asTextOrNull(token, "value")));
                expr.append(numStr(v != null ? v : 0.0));
                break;
            }
            case "previous_row_subtotal": {
                double v = 0.0;
                if (ctx.previousRowSubtotal != null) {
                    v = ctx.previousRowSubtotal;
                } else {
                    String fb = asTextOrNull(token, "fallback_component_code");
                    if (fb != null) {
                        Double cs = ctx.componentSubtotals.get(fb);
                        if (cs != null) v = cs;
                    }
                }
                expr.append(numStr(v));
                break;
            }
            case "product_attribute": {
                Double v = ctx.productAttributes.get(token.path("attribute_name").asText(""));
                expr.append(numStr(v != null ? v : 0.0));
                break;
            }
            case "quotation_field": {
                Double v = ctx.quotationFields.get(token.path("value").asText(""));
                expr.append(numStr(v != null ? v : 0.0));
                break;
            }
            case "path": {
                String p = token.has("path") ? token.path("path").asText("") : token.path("value").asText("");
                Double v = resolvePath(p, ctx);
                expr.append(numStr(v != null ? v : 0.0));
                break;
            }
            case "datasource_field": {
                String n = token.has("name") ? token.path("name").asText("") : token.path("value").asText("");
                Double v = ctx.fieldValues.get(n);
                expr.append(numStr(v != null ? v : 0.0));
                break;
            }
            case "global_variable": {
                Double v = resolveGvar(token, ctx);
                expr.append(numStr(v != null ? v : 0.0));
                break;
            }
            default:
                // 未知 token 忽略（对齐前端 switch 不命中分支）
                break;
        }
    }

    /** path token 取值：basicDataValues["{path}"] → toNumber；缺失 → null（后端无 pathCache，basicDataValues 已解析）。 */
    private Double resolvePath(String pathStr, RowContext ctx) {
        if (pathStr == null || pathStr.isEmpty()) return null;
        String lookup = (pathStr.startsWith("{") && pathStr.endsWith("}")) ? pathStr : "{" + pathStr + "}";
        return toNumber(ctx.basicDataValues.get(lookup));
    }

    /** global_variable token：优先 @gvar:CODE（AP-49 方向 A），再退到 {path}。 */
    private Double resolveGvar(JsonNode token, RowContext ctx) {
        String code = token.has("code") ? token.path("code").asText("") : token.path("value").asText("");
        if (code != null && !code.isEmpty()) {
            Object gv = ctx.basicDataValues.get("@gvar:" + code);
            Double n = toNumber(gv);
            if (n != null) return n;
        }
        String pathStr = token.path("path").asText("");
        if (!pathStr.isEmpty()) {
            String lookup = (pathStr.startsWith("{") && pathStr.endsWith("}")) ? pathStr : "{" + pathStr + "}";
            return toNumber(ctx.basicDataValues.get(lookup));
        }
        return null;
    }

    // ======================================================================
    // Layer 2-4 — calculate / computeTabSubtotal（逐行 + previous_row_subtotal 累加）
    // ======================================================================

    /**
     * 计算一个 tab 的 formulaResults：逐行（按 baseRows 顺序）求值所有 FORMULA 字段，
     * editRows 按 rowKey 覆盖，previous_row_subtotal 跨行累加。
     *
     * @return ArrayNode of {@code [{ "rowKey": "...", "values": { "<formulaField>": <num> } }]}
     */
    public ArrayNode calculate(JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
                               JsonNode rowKeyFields,
                               JsonNode baseRows, JsonNode editRows,
                               Map<String, Double> componentSubtotals,
                               Map<String, Double> quotationFields,
                               Map<String, Double> productAttributes) {
        ArrayNode out = MAPPER.createArrayNode();
        List<RowResult> rows = computeRows(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
            componentSubtotals, quotationFields, productAttributes);
        for (RowResult rr : rows) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("rowKey", rr.rowKey);
            ObjectNode values = node.putObject("values");
            for (Map.Entry<String, Double> e : rr.formulaValues.entrySet()) {
                values.put(e.getKey(), e.getValue());
            }
            out.add(node);
        }
        return out;
    }

    /** 跨行累加 is_subtotal 字段之和（layer 3）。 */
    public BigDecimal computeTabSubtotal(JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
                                         JsonNode rowKeyFields,
                                         JsonNode baseRows, JsonNode editRows,
                                         Map<String, Double> componentSubtotals) {
        String subtotalField = findSubtotalFieldName(fields);
        if (subtotalField == null) return ZERO4;
        List<RowResult> rows = computeRows(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
            componentSubtotals, new HashMap<>(), new HashMap<>());
        double sum = 0.0;
        for (RowResult rr : rows) {
            Double v = rr.formulaValues.get(subtotalField);
            if (v != null) sum += v;
        }
        return BigDecimal.valueOf(sum).setScale(4, RoundingMode.HALF_UP);
    }

    private static class RowResult {
        final String rowKey;
        final Map<String, Double> formulaValues;
        RowResult(String rowKey, Map<String, Double> v) { this.rowKey = rowKey; this.formulaValues = v; }
    }

    /**
     * 逐行求值核心（calculate + computeTabSubtotal 共用）。AP-51：行数权威 = baseRows（driver 展开结果）。
     */
    private List<RowResult> computeRows(JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
                                        JsonNode rowKeyFields,
                                        JsonNode baseRows, JsonNode editRows,
                                        Map<String, Double> componentSubtotals,
                                        Map<String, Double> quotationFields,
                                        Map<String, Double> productAttributes) {
        List<RowResult> out = new ArrayList<>();
        if (baseRows == null || !baseRows.isArray()) return out;

        // editRows 按 rowKey 索引（AP-54：业务键对齐，不用下标）
        Map<String, JsonNode> editByKey = indexEditRows(editRows);

        String subtotalField = findSubtotalFieldName(fields);
        // 公式字段拓扑序（依赖先算），与前端 computeAllFormulas 一致
        List<FormulaField> formulaFields = collectFormulaFields(fields, formulas, formulaAssignments);
        List<String> order = topoOrder(formulaFields);

        Double prevRowSubtotal = null;
        int idx = 0;
        for (JsonNode baseRow : baseRows) {
            JsonNode driverRow = baseRow.path("driverRow");
            JsonNode basicDataValues = baseRow.path("basicDataValues");

            String rowKey = computeRowKey(rowKeyFields, driverRow);
            String effKey = (rowKey != null && !rowKey.isEmpty()) ? rowKey : String.valueOf(idx);

            JsonNode editValues = editByKey.containsKey(effKey)
                ? editByKey.get(effKey).path("values") : null;

            // mergedRow = driverRow + editRows（编辑覆盖）
            Map<String, JsonNode> mergedRow = mergeRow(driverRow, editValues);

            // Layer 2: 字段值收集（AP-37 每 field_type）
            Map<String, Double> fieldValues =
                collectFieldValues(fields, mergedRow, basicDataValues);

            RowContext ctx = new RowContext();
            ctx.fieldValues = fieldValues;
            ctx.componentSubtotals = componentSubtotals != null ? componentSubtotals : new HashMap<>();
            ctx.quotationFields = quotationFields != null ? quotationFields : new HashMap<>();
            ctx.productAttributes = productAttributes != null ? productAttributes : new HashMap<>();
            ctx.basicDataValues = toBasicDataMap(basicDataValues);
            ctx.previousRowSubtotal = prevRowSubtotal;

            // 按拓扑序求值，结果回填 fieldValues 供下游公式引用
            Map<String, Double> results = new LinkedHashMap<>();
            for (String name : order) {
                FormulaField ff = findByName(formulaFields, name);
                if (ff == null) continue;
                double val = evaluateExpression(ff.expression, ctx).doubleValue();
                results.put(name, val);
                ctx.fieldValues.put(name, val);
            }

            out.add(new RowResult(effKey, results));

            // previous_row_subtotal: 本行 is_subtotal 传下行
            if (subtotalField != null && results.containsKey(subtotalField)) {
                prevRowSubtotal = results.get(subtotalField);
            }
            idx++;
        }
        return out;
    }

    // ======================================================================
    // rowKey
    // ======================================================================

    /** rowKey = 按 rowKeyFields 从 driverRow 取值用 {@code ||} 拼接；rowKeyFields 空/null → null。 */
    public String computeRowKey(JsonNode rowKeyFields, JsonNode driverRow) {
        if (rowKeyFields == null || !rowKeyFields.isArray() || rowKeyFields.size() == 0) return null;
        // 哨兵：按行号对齐
        if (rowKeyFields.size() == 1 && "__seq_no__".equals(rowKeyFields.get(0).asText(""))) return null;
        List<String> parts = new ArrayList<>();
        for (JsonNode k : rowKeyFields) {
            String field = k.asText("");
            JsonNode v = driverRow != null ? driverRow.path(field) : null;
            parts.add(v != null && !v.isMissingNode() && !v.isNull() ? v.asText("") : "");
        }
        return String.join("||", parts);
    }

    // ======================================================================
    // Layer 2 — 字段值收集（AP-37 每 field_type，port computeAllFormulas:420-548）
    // ======================================================================

    private Map<String, Double> collectFieldValues(JsonNode fields, Map<String, JsonNode> mergedRow,
                                                    JsonNode basicDataValues) {
        Map<String, Double> fieldValues = new HashMap<>();
        if (fields == null || !fields.isArray()) return fieldValues;

        for (JsonNode f : fields) {
            String fieldType = fieldType(f);
            String key = fieldName(f);
            if (key.isEmpty()) continue;
            if ("FORMULA".equals(fieldType)) continue; // 公式字段后算

            if ("BASIC_DATA".equals(fieldType)) {
                String path = basicDataPath(f);
                if (path != null && !path.isEmpty()) {
                    Double num = toNumber(lookupBdv(basicDataValues, bnfDriverLookupKey(path)));
                    fieldValues.put(key, num != null ? num : 0.0); // 未求值占 0
                }
                continue;
            }

            if ("DATA_SOURCE".equals(fieldType)) {
                JsonNode binding = datasourceBinding(f);
                Object resolved = nodeToObject(mergedRow.get(key));
                if (binding != null && basicDataValues != null) {
                    String dsType = binding.path("type").asText("DATABASE_QUERY");
                    if ("GLOBAL_VARIABLE".equals(dsType)) {
                        String code = binding.path("global_variable_code").asText("");
                        Object v = lookupBdv(basicDataValues, "@gvar:" + code);
                        if (nonEmpty(v)) resolved = v;
                    } else if ("BNF_PATH".equals(dsType)) {
                        String bnf = binding.path("bnf_path").asText("");
                        if (!bnf.isEmpty()) {
                            Object v = lookupBdv(basicDataValues, bnfDriverLookupKey(bnf));
                            if (nonEmpty(v)) resolved = v;
                        }
                    }
                }
                if (!nonEmpty(resolved)) {
                    String content = content(f);
                    if (content != null && !content.isEmpty()) resolved = content;
                }
                Double num = toNumber(resolved);
                if (num != null) fieldValues.put(key, num);
                continue;
            }

            // FIXED_VALUE：空 → content 兜底
            JsonNode rawNode = mergedRow.get(key);
            Object raw = nodeToObject(rawNode);
            if (!nonEmpty(raw) && "FIXED_VALUE".equals(fieldType)) {
                String content = content(f);
                if (content != null && !content.isEmpty()) raw = content;
            }

            // INPUT_NUMBER：空 → default_source(GLOBAL_VARIABLE/BNF_PATH) → content 兜底
            if (!nonEmpty(raw) && "INPUT_NUMBER".equals(fieldType)) {
                Object resolved = null;
                JsonNode ds = defaultSource(f);
                if (ds != null && basicDataValues != null) {
                    String dsType = ds.path("type").asText("");
                    if ("GLOBAL_VARIABLE".equals(dsType)) {
                        String code = ds.path("code").asText("");
                        Object v = lookupBdv(basicDataValues, "@gvar:" + code);
                        if (nonEmpty(v)) resolved = v;
                    } else if ("BNF_PATH".equals(dsType)) {
                        String path = ds.path("path").asText("");
                        if (!path.isEmpty()) {
                            Object v = lookupBdv(basicDataValues, bnfDriverLookupKey(path));
                            if (nonEmpty(v)) resolved = v;
                        }
                    }
                }
                if (resolved != null) {
                    raw = resolved;
                } else {
                    String content = content(f);
                    if (content != null && !content.isEmpty()) raw = content;
                }
            }

            Double val = toNumber(raw);
            if (val != null) fieldValues.put(key, val);
        }
        return fieldValues;
    }

    // ======================================================================
    // 公式解析 + 拓扑序（port resolveFormula / getFormulaDeps / computeAllFormulas 拓扑）
    // ======================================================================

    private static class FormulaField {
        final String name;
        final JsonNode expression;
        FormulaField(String name, JsonNode expression) { this.name = name; this.expression = expression; }
    }

    private List<FormulaField> collectFormulaFields(JsonNode fields, JsonNode formulas,
                                                    JsonNode formulaAssignments) {
        List<FormulaField> out = new ArrayList<>();
        if (fields == null || !fields.isArray()) return out;
        int fullIdx = 0;
        for (JsonNode f : fields) {
            if ("FORMULA".equals(fieldType(f))) {
                String name = fieldName(f);
                JsonNode expr = resolveFormulaExpression(f, name, fields, formulas, formulaAssignments, fullIdx);
                if (expr != null) out.add(new FormulaField(name, expr));
            }
            fullIdx++;
        }
        return out;
    }

    /**
     * port resolveFormula: 0.field.formula_name 显式 1.formula_assignments[完整字段下标]
     * 2.exact name 3.positional。
     *
     * <p><b>注意</b>：formula_assignments 的 key 是字段在<b>完整 fields 数组</b>中的下标
     * （非 FORMULA-only 位置），与前端 {@code comp.fields.indexOf(field)} 一致。
     */
    private JsonNode resolveFormulaExpression(JsonNode field, String fieldName, JsonNode fields,
                                              JsonNode formulas, JsonNode formulaAssignments, int fullFieldIndex) {
        if (formulas == null || !formulas.isArray()) return null;

        // 0. 显式 formula_name 绑定（最高优先；绑定了但找不到 → null 不 fallback）
        String formulaName = field.has("formula_name") ? field.path("formula_name").asText(null)
            : field.path("formulaName").asText(null);
        if (formulaName != null && !formulaName.isEmpty()) {
            JsonNode found = findFormulaByName(formulas, formulaName);
            return found != null ? found.path("expression") : null;
        }

        // 1. 模板级 formula_assignments[完整字段下标] → 公式名
        if (formulaAssignments != null && formulaAssignments.isObject()) {
            JsonNode assigned = formulaAssignments.path(String.valueOf(fullFieldIndex));
            if (!assigned.isMissingNode() && !assigned.isNull()) {
                String assignedName = assigned.asText("");
                if (!assignedName.isEmpty()) {
                    JsonNode found = findFormulaByName(formulas, assignedName);
                    if (found != null) return found.path("expression");
                }
            }
        }

        // 2. 字段名 == 公式名
        JsonNode byName = findFormulaByName(formulas, fieldName);
        if (byName != null) return byName.path("expression");

        // 3. positional fallback（FORMULA 字段在 fields 中的相对位置）
        int posIdx = formulaFieldPosition(fields, fieldName);
        if (posIdx >= 0 && posIdx < formulas.size()) {
            return formulas.get(posIdx).path("expression");
        }
        return null;
    }

    private JsonNode findFormulaByName(JsonNode formulas, String name) {
        for (JsonNode fm : formulas) {
            if (name.equals(fm.path("name").asText(null))) return fm;
        }
        return null;
    }

    private int formulaFieldPosition(JsonNode fields, String fieldName) {
        int pos = 0;
        for (JsonNode f : fields) {
            if (!"FORMULA".equals(fieldType(f))) continue;
            if (fieldName.equals(fieldName(f))) return pos;
            pos++;
        }
        return -1;
    }

    private List<String> topoOrder(List<FormulaField> formulaFields) {
        // 依赖图：公式 field token 引用的其他公式字段
        Map<String, List<String>> deps = new LinkedHashMap<>();
        java.util.Set<String> nameSet = new java.util.HashSet<>();
        for (FormulaField ff : formulaFields) nameSet.add(ff.name);
        for (FormulaField ff : formulaFields) {
            List<String> d = new ArrayList<>();
            if (ff.expression != null && ff.expression.isArray()) {
                for (JsonNode t : ff.expression) {
                    if ("field".equals(t.path("type").asText(""))) {
                        String v = t.path("value").asText("");
                        if (nameSet.contains(v)) d.add(v);
                    }
                }
            }
            deps.put(ff.name, d);
        }
        // Kahn：先算依赖数为 0 的
        Map<String, Integer> revIn = new LinkedHashMap<>();
        for (FormulaField ff : formulaFields) revIn.put(ff.name, deps.get(ff.name).size());
        List<String> queue = new ArrayList<>();
        for (FormulaField ff : formulaFields) if (revIn.get(ff.name) == 0) queue.add(ff.name);
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String cur = queue.remove(0);
            order.add(cur);
            for (FormulaField ff : formulaFields) {
                if (deps.get(ff.name).contains(cur)) {
                    revIn.put(ff.name, revIn.get(ff.name) - 1);
                    if (revIn.get(ff.name) == 0) queue.add(ff.name);
                }
            }
        }
        // 环 → 尾部追加
        for (FormulaField ff : formulaFields) if (!order.contains(ff.name)) order.add(ff.name);
        return order;
    }

    private FormulaField findByName(List<FormulaField> list, String name) {
        for (FormulaField ff : list) if (ff.name.equals(name)) return ff;
        return null;
    }

    // ======================================================================
    // 工具方法
    // ======================================================================

    private String findSubtotalFieldName(JsonNode fields) {
        if (fields == null || !fields.isArray()) return null;
        for (JsonNode f : fields) {
            boolean isSub = f.path("isSubtotal").asBoolean(false) || f.path("is_subtotal").asBoolean(false);
            if (isSub) return fieldName(f);
        }
        return null;
    }

    private Map<String, JsonNode> indexEditRows(JsonNode editRows) {
        Map<String, JsonNode> map = new HashMap<>();
        if (editRows != null && editRows.isArray()) {
            for (JsonNode er : editRows) {
                String rk = er.path("rowKey").asText(null);
                if (rk != null) map.put(rk, er);
            }
        }
        return map;
    }

    private Map<String, JsonNode> mergeRow(JsonNode driverRow, JsonNode editValues) {
        Map<String, JsonNode> merged = new HashMap<>();
        if (driverRow != null && driverRow.isObject()) {
            driverRow.fields().forEachRemaining(e -> merged.put(e.getKey(), e.getValue()));
        }
        if (editValues != null && editValues.isObject()) {
            editValues.fields().forEachRemaining(e -> merged.put(e.getKey(), e.getValue()));
        }
        return merged;
    }

    private Map<String, Object> toBasicDataMap(JsonNode basicDataValues) {
        Map<String, Object> map = new HashMap<>();
        if (basicDataValues != null && basicDataValues.isObject()) {
            basicDataValues.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue()));
        }
        return map;
    }

    private Object lookupBdv(JsonNode basicDataValues, String key) {
        if (basicDataValues == null || !basicDataValues.isObject()) return null;
        JsonNode v = basicDataValues.get(key);
        return (v == null || v.isMissingNode()) ? null : v;
    }

    // ---- field 访问器（同时兼容 structure camelCase + snapshot snake_case） ----

    private String fieldType(JsonNode f) {
        if (f.has("fieldType")) return f.path("fieldType").asText("");
        return f.path("field_type").asText("");
    }

    private String fieldName(JsonNode f) {
        String n = f.path("name").asText("");
        if (!n.isEmpty()) return n;
        return f.path("key").asText("");
    }

    private String basicDataPath(JsonNode f) {
        if (f.has("basicDataPath")) return f.path("basicDataPath").asText(null);
        return f.path("basic_data_path").asText(null);
    }

    private JsonNode datasourceBinding(JsonNode f) {
        JsonNode b = f.path("datasourceBinding");
        if (b.isMissingNode() || b.isNull()) b = f.path("datasource_binding");
        return (b.isMissingNode() || b.isNull()) ? null : b;
    }

    private JsonNode defaultSource(JsonNode f) {
        JsonNode d = f.path("defaultSource");
        if (d.isMissingNode() || d.isNull()) d = f.path("default_source");
        return (d.isMissingNode() || d.isNull()) ? null : d;
    }

    private String content(JsonNode f) {
        if (f.has("defaultValue") && !f.path("defaultValue").isNull()) return f.path("defaultValue").asText(null);
        if (f.has("content") && !f.path("content").isNull()) return f.path("content").asText(null);
        return null;
    }

    private static String asTextOrNull(JsonNode token, String field) {
        JsonNode v = token.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText(null);
    }

    private static Double firstNonNull(Double... vals) {
        for (Double v : vals) if (v != null) return v;
        return null;
    }

    /** Object/JsonNode → Double（数字直取；字符串 parseFloat；数组/列表取首值递归；否则 null）。 */
    private Double toNumber(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try { return Double.parseDouble(((String) o).trim()); } catch (Exception e) { return null; }
        }
        if (o instanceof JsonNode) {
            JsonNode n = (JsonNode) o;
            if (n.isNull() || n.isMissingNode()) return null;
            if (n.isNumber()) return n.doubleValue();
            if (n.isTextual()) {
                try { return Double.parseDouble(n.textValue().trim()); } catch (Exception e) { return null; }
            }
            if (n.isArray()) return n.size() == 0 ? null : toNumber(n.get(0));
            return null;
        }
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            return l.isEmpty() ? null : toNumber(l.get(0));
        }
        return null;
    }

    private Object nodeToObject(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        return n;
    }

    private boolean nonEmpty(Object o) {
        if (o == null) return false;
        if (o instanceof String) return !((String) o).isEmpty();
        if (o instanceof JsonNode) {
            JsonNode n = (JsonNode) o;
            if (n.isNull() || n.isMissingNode()) return false;
            if (n.isTextual()) return !n.textValue().isEmpty();
            if (n.isArray()) return n.size() > 0;
            return true;
        }
        return true;
    }

    /** 后端 ComponentDriverService 把 basic_data_path 加花括号作 key；port useDriverExpansions.bnfDriverLookupKey。 */
    private String bnfDriverLookupKey(String path) {
        String p = path == null ? "" : path.trim();
        if (p.startsWith("{") && p.endsWith("}")) p = p.substring(1, p.length() - 1).trim();
        return "{" + p + "}";
    }

    private static String numStr(double d) {
        // 避免科学计数法（如 1.0E-7），用 BigDecimal toPlainString，供 ArithParser 解析
        return new BigDecimal(Double.toString(d)).toPlainString();
    }

    // ======================================================================
    // 算术串求值（递归下降，double；复刻 new Function('return (expr)')）
    // ======================================================================

    private static class ArithParser {
        private final String s;
        private int i = 0;

        ArithParser(String s) { this.s = s; }

        double parse() {
            double v = expr();
            skip();
            if (i < s.length()) throw new RuntimeException("trailing: " + s.substring(i));
            return v;
        }

        private void skip() { while (i < s.length() && s.charAt(i) == ' ') i++; }

        private double expr() {
            double v = term();
            while (true) {
                skip();
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    char op = s.charAt(i++);
                    double r = term();
                    v = op == '+' ? v + r : v - r;
                } else break;
            }
            return v;
        }

        private double term() {
            double v = factor();
            while (true) {
                skip();
                if (i < s.length() && (s.charAt(i) == '*' || s.charAt(i) == '/')) {
                    char op = s.charAt(i++);
                    double r = factor();
                    v = op == '*' ? v * r : v / r;
                } else break;
            }
            return v;
        }

        private double factor() {
            skip();
            if (i >= s.length()) throw new RuntimeException("unexpected eof");
            char c = s.charAt(i);
            if (c == '+') { i++; return factor(); }
            if (c == '-') { i++; return -factor(); }
            if (c == '(') {
                i++;
                double v = expr();
                skip();
                if (i >= s.length() || s.charAt(i) != ')') throw new RuntimeException("missing )");
                i++;
                return v;
            }
            return number();
        }

        private double number() {
            skip();
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (i == start) throw new RuntimeException("expected number at " + i);
            return Double.parseDouble(s.substring(start, i));
        }
    }
}
