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
        /** cross_tab_ref：B 当前行原始值（字段名→原始值，含文本），供匹配键 b 取值。 */
        public Map<String, Object> currentRowRaw = new HashMap<>();
        /** cross_tab_ref：同卡片已算行存储（组件标识→行表，行=字段名→已算值）。 */
        public Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();
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
            case "b_field": {
                String n = token.has("value") ? token.path("value").asText("") : token.path("name").asText("");
                Double v = toNumber(ctx.currentRowRaw.get(n));
                expr.append(numStr(v != null ? v : 0.0));
                break;
            }
            case "global_variable": {
                Double v = resolveGvar(token, ctx);
                expr.append(numStr(v != null ? v : 0.0));
                break;
            }
            case "cross_tab_ref": {
                Object v = evalCrossTab(token, ctx);
                if (v instanceof FormulaErrorMarker) {
                    throw new IllegalStateException("cross_tab_ref multi/non-numeric");
                }
                Double n = (v instanceof Number num) ? num.doubleValue() : toNumber(v);
                expr.append(numStr(n != null ? n : 0.0));
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
    // cross_tab_ref — 跨页签引用求值
    // ======================================================================

    /** 多匹配/非数字聚合错误哨兵。 */
    private static final class FormulaErrorMarker {}
    private static final FormulaErrorMarker ERR = new FormulaErrorMarker();

    /** cross_tab_ref 求值。返回 Number / String（NONE 文本）/ ERR。 */
    Object evalCrossTab(JsonNode token, RowContext ctx) {
        String source = token.path("source").asText("");
        String target = token.path("target").asText("");
        String agg = token.path("agg").asText("NONE").toUpperCase();
        List<Map<String, Object>> rows = ctx.crossTabRows.getOrDefault(source, List.of());

        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> arow : rows) {
            boolean ok = true;
            for (JsonNode pair : token.path("match")) {
                Object av = arow.get(pair.path("a").asText(""));
                Object bv = ctx.currentRowRaw.get(pair.path("b").asText(""));
                if (isBlank(av) || isBlank(bv) || !valEquals(av, bv)) { ok = false; break; }
            }
            if (ok) hits.add(arow);
        }

        if ("COUNT".equals(agg)) return java.math.BigDecimal.valueOf(hits.size());
        if ("NONE".equals(agg)) {
            if (hits.isEmpty()) return java.math.BigDecimal.ZERO;
            if (hits.size() > 1) return ERR;
            return targetRowValue(hits.get(0), token, ctx);
        }
        if (hits.isEmpty()) return java.math.BigDecimal.ZERO;
        List<Double> nums = new ArrayList<>(hits.size());
        for (Map<String, Object> h : hits) {
            Double n = toNumber(targetRowValue(h, token, ctx));
            if (n == null) return ERR;
            nums.add(n);
        }
        double r;
        switch (agg) {
            case "SUM": r = nums.stream().mapToDouble(Double::doubleValue).sum(); break;
            case "AVG": r = nums.stream().mapToDouble(Double::doubleValue).average().orElse(0); break;
            case "MAX": r = nums.stream().mapToDouble(Double::doubleValue).max().orElse(0); break;
            case "MIN": r = nums.stream().mapToDouble(Double::doubleValue).min().orElse(0); break;
            default: return ERR;
        }
        return java.math.BigDecimal.valueOf(r);
    }

    /** 取匹配 A 行的目标值: 有 targetExpr → 在 (A行 field + B行 b_field + B 上下文 gvar) 求值; 否则 arow[target]。 */
    private Object targetRowValue(Map<String, Object> arow, JsonNode token, RowContext ctx) {
        JsonNode te = token.path("targetExpr");
        if (te.isArray() && te.size() > 0) {
            RowContext sub = new RowContext();
            for (Map.Entry<String, Object> e : arow.entrySet()) {
                Double n = toNumber(e.getValue());
                if (n != null) sub.fieldValues.put(e.getKey(), n);
            }
            sub.currentRowRaw = ctx.currentRowRaw;
            sub.basicDataValues = ctx.basicDataValues;
            sub.crossTabRows = ctx.crossTabRows;
            return evaluateExpression(te, sub);
        }
        return arow.get(token.path("target").asText(""));
    }

    private static boolean isBlank(Object o) {
        return o == null || (o instanceof String s && s.isBlank());
    }

    /**
     * 匹配键相等比较：数字按数值，否则按 trim 文本。
     * 注意：依赖实例方法 toNumber，故声明为实例方法（非 static）。
     */
    private boolean valEquals(Object a, Object b) {
        Double na = toNumber(a), nb = toNumber(b);
        if (na != null && nb != null) return na.doubleValue() == nb.doubleValue();
        return String.valueOf(a).trim().equals(String.valueOf(b).trim());
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
        return calculate(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
            componentSubtotals, quotationFields, productAttributes, Map.of());
    }

    /**
     * calculate 重载：额外透传 cross_tab_ref 兄弟组件已算行存储（Task 1.3/1.4）。
     *
     * <p>逐行 RowContext 注入 {@code crossTabRows}（同卡片兄弟组件已算行）+ {@code currentRowRaw}
     * （本行原始合并值，<b>含文本</b>，供 cross_tab_ref 匹配键 b 取值）。
     * 9 参旧签名委派此重载并传 {@code Map.of()}，行为不变。
     *
     * @param crossTabRows 组件标识 → 行表（行=字段名→已算值），cross_tab_ref source 维度查询；null 视作空。
     */
    public ArrayNode calculate(JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
                               JsonNode rowKeyFields,
                               JsonNode baseRows, JsonNode editRows,
                               Map<String, Double> componentSubtotals,
                               Map<String, Double> quotationFields,
                               Map<String, Double> productAttributes,
                               Map<String, List<Map<String, Object>>> crossTabRows) {
        ArrayNode out = MAPPER.createArrayNode();
        List<RowResult> rows = computeRows(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
            componentSubtotals, quotationFields, productAttributes, crossTabRows);
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
        // Plan 2-核心：委托按列计算后求所有小计列之和（单小计列时 = 原行为）。
        Map<String, BigDecimal> byCol = computeTabSubtotalsByColumn(
            fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows, componentSubtotals);
        BigDecimal sum = ZERO4;
        for (BigDecimal v : byCol.values()) sum = sum.add(v);
        return sum.setScale(4, RoundingMode.HALF_UP);
    }

    /** 逐列求和：每个 is_subtotal 列 → 该列各行结果之和。Plan 2-核心：多小计列。 */
    public Map<String, BigDecimal> computeTabSubtotalsByColumn(
            JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
            JsonNode rowKeyFields, JsonNode baseRows, JsonNode editRows,
            Map<String, Double> componentSubtotals) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        List<String> subtotalFields = findSubtotalFieldNames(fields);
        if (subtotalFields.isEmpty()) return out;
        List<RowResult> rows = computeRows(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
            componentSubtotals, new HashMap<>(), new HashMap<>(), Map.of());
        for (String sf : subtotalFields) {
            double sum = 0.0;
            for (RowResult rr : rows) {
                Double v = rr.formulaValues.get(sf);
                if (v != null) sum += v;
            }
            out.put(sf, BigDecimal.valueOf(sum).setScale(4, RoundingMode.HALF_UP));
        }
        return out;
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
                                        Map<String, Double> productAttributes,
                                        Map<String, List<Map<String, Object>>> crossTabRows) {
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
            // cross_tab_ref（Task 1.3）：兄弟组件已算行 + 本行原始合并值（含文本，供匹配键 b 取值）
            ctx.crossTabRows = crossTabRows != null ? crossTabRows : Map.of();
            ctx.currentRowRaw = toRawRowMap(mergedRow);

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
                    } else if ("BNF_PATH".equals(dsType) || "BASIC_DATA".equals(dsType)) {
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
    // 通用逐行解析：resolveRowByFieldName（Object 版，保留字符串，按字段名输出）
    // ======================================================================

    /**
     * 通用：把一行解析成"按字段名的标量值"(String/Number 都保留)。配置驱动，零硬编码字段名。
     * 复用 collectFieldValues 同款字段定义驱动解析(bnfDriverLookupKey/lookupBdv/datasourceBinding/content...)，
     * 但保留字符串、并合入 INPUT(editValues 覆盖)/FORMULA(formulaValues) 结果。
     *
     * <p>别名不泄漏：字段定义里 {@code basic_data_path="$ys_view.material_type"} 但字段名是"类型"，
     * 输出 key 只用字段名"类型"，不会暴露 SQL 列别名 {@code material_type}。
     *
     * @param fields          组件字段定义数组
     * @param driverRow       driver 展开行(SQL 别名键, 含简单 BASIC_DATA 标量)
     * @param basicDataValues 行级 BNF/path 值({path}/@gvar:CODE 键), 可为 null
     * @param editValues      本行 editRows.values(按字段名), 可为 null
     * @param formulaValues   本行 formulaResults.values(按字段名), 可为 null
     * @return 字段名 → 标量值(String/Number)；FORMULA 字段取 formulaValues；
     *         字段名按 fields 顺序插入(LinkedHashMap 保序)
     */
    public Map<String, Object> resolveRowByFieldName(JsonNode fields, JsonNode driverRow,
            JsonNode basicDataValues, JsonNode editValues, JsonNode formulaValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (fields == null || !fields.isArray()) return out;

        for (JsonNode f : fields) {
            String name = fieldName(f);
            if (name.isEmpty()) continue;
            String type = fieldType(f);

            // ── FORMULA / LIST_FORMULA: 直接取已算好的 formulaResults ──
            if ("FORMULA".equals(type) || "LIST_FORMULA".equals(type)) {
                Object v = formulaValues != null ? unwrapNode(nodeToObject(formulaValues.path(name))) : null;
                if (nonEmpty(v)) out.put(name, v);
                continue;
            }

            // ── INPUT_NUMBER / INPUT_TEXT / INPUT: editValues 覆盖 → driverRow[name] → default_source → content ──
            if ("INPUT_NUMBER".equals(type) || "INPUT_TEXT".equals(type) || "INPUT".equals(type)) {
                Object v = (editValues != null) ? nodeToObject(editValues.path(name)) : null;
                if (!nonEmpty(v) && driverRow != null) v = nodeToObject(driverRow.path(name));
                if (!nonEmpty(v)) {
                    JsonNode ds = defaultSource(f);
                    if (ds != null && basicDataValues != null) {
                        String dsType = ds.path("type").asText("");
                        if ("GLOBAL_VARIABLE".equals(dsType)) {
                            Object g = lookupBdv(basicDataValues, "@gvar:" + ds.path("code").asText(""));
                            if (nonEmpty(g)) v = g;
                        } else if ("BNF_PATH".equals(dsType) || "BASIC_DATA".equals(dsType)) {
                            String p = ds.path("path").asText("");
                            if (!p.isEmpty()) {
                                Object g = lookupBdv(basicDataValues, bnfDriverLookupKey(p));
                                if (nonEmpty(g)) v = g;
                            }
                        }
                    }
                }
                if (!nonEmpty(v)) {
                    String c = content(f);
                    if (c != null && !c.isEmpty()) v = c;
                }
                if (nonEmpty(v)) out.put(name, unwrapNode(v));
                continue;
            }

            // ── BASIC_DATA: basicDataValues[bnfDriverLookupKey(path)] → driverRow[name] → content ──
            if ("BASIC_DATA".equals(type)) {
                Object v = null;
                String path = basicDataPath(f);
                if (path != null && !path.isEmpty() && basicDataValues != null) {
                    v = lookupBdv(basicDataValues, bnfDriverLookupKey(path));
                }
                // fallback: driverRow 中按字段名查（适用 bdv 未存该 path 但 driverRow 有 SQL 列的情形）
                if (!nonEmpty(v) && driverRow != null) v = nodeToObject(driverRow.path(name));
                if (!nonEmpty(v)) {
                    String c = content(f);
                    if (c != null && !c.isEmpty()) v = c;
                }
                if (nonEmpty(v)) out.put(name, unwrapNode(v));
                continue;
            }

            // ── DATA_SOURCE: binding(GLOBAL_VARIABLE/BNF_PATH) 优先 → driverRow[name] → content ──
            if ("DATA_SOURCE".equals(type)) {
                Object v = (driverRow != null) ? nodeToObject(driverRow.path(name)) : null;
                JsonNode binding = datasourceBinding(f);
                if (binding != null && basicDataValues != null) {
                    String dsType = binding.path("type").asText("DATABASE_QUERY");
                    if ("GLOBAL_VARIABLE".equals(dsType)) {
                        Object g = lookupBdv(basicDataValues, "@gvar:" + binding.path("global_variable_code").asText(""));
                        if (nonEmpty(g)) v = g;
                    } else if ("BNF_PATH".equals(dsType)) {
                        String bnf = binding.path("bnf_path").asText("");
                        if (!bnf.isEmpty()) {
                            Object g = lookupBdv(basicDataValues, bnfDriverLookupKey(bnf));
                            if (nonEmpty(g)) v = g;
                        }
                    }
                }
                if (!nonEmpty(v)) {
                    String c = content(f);
                    if (c != null && !c.isEmpty()) v = c;
                }
                if (nonEmpty(v)) out.put(name, unwrapNode(v));
                continue;
            }

            // ── FIXED_VALUE / 其他: content 优先 → driverRow[name] ──
            Object v = null;
            String c = content(f);
            if (c != null && !c.isEmpty()) v = c;
            if (!nonEmpty(v) && driverRow != null) v = nodeToObject(driverRow.path(name));
            if (nonEmpty(v)) out.put(name, unwrapNode(v));
        }
        return out;
    }

    /**
     * 将 JsonNode 解包成 Java 原生 String/Number，便于调用方直接做字符串比较。
     * 非 JsonNode 原样返回（String/Number 已是原生类型）。
     */
    private Object unwrapNode(Object o) {
        if (!(o instanceof JsonNode)) return o;
        JsonNode n = (JsonNode) o;
        if (n.isNull() || n.isMissingNode()) return null;
        if (n.isTextual()) return n.textValue();
        if (n.isNumber()) return n.numberValue();
        if (n.isBoolean()) return n.booleanValue();
        // 数组/对象保持 JsonNode 以便上层自行处理
        return n;
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

    /** 返回所有 is_subtotal 字段名（按字段顺序）。Plan 2-核心：多小计列。 */
    public List<String> findSubtotalFieldNames(JsonNode fields) {
        List<String> out = new ArrayList<>();
        if (fields == null || !fields.isArray()) return out;
        for (JsonNode f : fields) {
            boolean isSub = f.path("isSubtotal").asBoolean(false) || f.path("is_subtotal").asBoolean(false);
            if (isSub) out.add(fieldName(f));
        }
        return out;
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

    /**
     * mergedRow（driverRow ⊕ editValues，值为 JsonNode）→ 原始标量映射（字段名→原始值）。
     * 文本保留为 String、数字为 Number、布尔为 Boolean（复用 unwrapNode），供 cross_tab_ref
     * 匹配键 b 取值——<b>不</b>做数值强转，故子件编号 "P1" 等文本匹配键能正确比较。
     */
    private Map<String, Object> toRawRowMap(Map<String, JsonNode> mergedRow) {
        Map<String, Object> map = new HashMap<>();
        if (mergedRow == null) return map;
        for (Map.Entry<String, JsonNode> e : mergedRow.entrySet()) {
            Object v = unwrapNode(e.getValue());
            if (v != null) map.put(e.getKey(), v);
        }
        return map;
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
