package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.card.CardDataProvider;
import com.cpq.quotation.service.card.CardRef;
import com.cpq.template.service.CardAggregateSource;
import com.cpq.template.service.TemplateFormulaService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Excel CARD_FORMULA 列编排：列依赖拓扑排序 + 环检测 + 整列求值编排。 */
@ApplicationScoped
public class CardFormulaEvaluator {

    private static final Pattern BRACKET = Pattern.compile("\\[([^\\[\\]]+)]");

    @Inject
    TemplateFormulaService templateFormulaService;

    public static final String DASH = "—";

    /** 提取公式里"裸 col_key"（不含 . 的占位）作为列间依赖。 */
    static Set<String> columnDeps(String formula, Set<String> allCols) {
        Set<String> deps = new LinkedHashSet<>();
        if (formula == null) return deps;
        Matcher m = BRACKET.matcher(formula);
        while (m.find()) {
            String ref = m.group(1).trim();
            if (!ref.contains(".") && allCols.contains(ref)) deps.add(ref);
        }
        return deps;
    }

    /** Kahn 拓扑排序；存在环抛 BusinessException。入参: col_key → formula。 */
    public static List<String> topoOrder(Map<String, String> formulas) {
        Set<String> cols = formulas.keySet();
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        Map<String, Integer> indeg = new LinkedHashMap<>();
        for (String c : cols) deps.put(c, columnDeps(formulas.get(c), cols));
        for (String c : cols) indeg.put(c, deps.get(c).size()); // 入度 = 本列依赖数
        Deque<String> q = new ArrayDeque<>();
        for (String c : cols) if (indeg.get(c) == 0) q.add(c);
        List<String> order = new ArrayList<>();
        while (!q.isEmpty()) {
            String c = q.poll();
            order.add(c);
            for (String other : cols) {
                if (deps.get(other).contains(c)) {
                    indeg.put(other, indeg.get(other) - 1);
                    if (indeg.get(other) == 0) q.add(other);
                }
            }
        }
        if (order.size() != cols.size()) {
            Set<String> cyc = new LinkedHashSet<>(cols);
            cyc.removeAll(order);
            throw new BusinessException(400, "Excel 列公式存在循环引用: " + cyc);
        }
        return order;
    }

    /** 算本料号一组 CARD_FORMULA 列。columns: 每项含 col_key/formula/refs。 */
    public Map<String, Object> evaluateColumns(
            List<Map<String, Object>> columns,
            List<QuotationLineComponentData> tabs,
            UUID customerId, String partNo, UUID quotationId) {

        CardDataProvider provider = new CardDataProvider(tabs);

        Map<String, String> formulaByCol = new LinkedHashMap<>();
        Map<String, Map<String, Object>> refsByCol = new HashMap<>();
        for (var c : columns) {
            String key = (String) c.get("col_key");
            formulaByCol.put(key, stripEq((String) c.get("formula")));
            refsByCol.put(key, asRefMap(c.get("refs")));
        }

        List<String> order = topoOrder(formulaByCol);
        Map<String, Object> cached = new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();

        for (String col : order) {
            String formula = formulaByCol.get(col);
            Map<String, Object> refs = refsByCol.get(col);

            boolean[] anyNonEmpty = {false};
            boolean[] anyRef = {false};
            Map<String, CardAggregateSource.Binding> aggBindings = new HashMap<>();
            String resolved = resolveCardScalars(formula, refs, provider, anyNonEmpty, anyRef, aggBindings);

            resolved = com.cpq.formula.PercentLiteral.rewrite(resolved);

            CardAggregateSource.set(new CardAggregateSource.Ctx(provider, aggBindings));
            Object val;
            try {
                val = templateFormulaService.evaluateExpressionPublic(
                        resolved, Map.of(), cached, customerId, partNo, List.of());
            } finally {
                CardAggregateSource.clear();
            }

            // 空值规则一：有卡片单值引用、且全为空、且无聚合 → 「—」；聚合空集→0 由引擎处理
            Object finalVal = (anyRef[0] && !anyNonEmpty[0] && !hasAggregate(formula)) ? DASH : val;

            out.put(col, finalVal);
            cached.put(col, (finalVal == null || DASH.equals(finalVal)) ? null : finalVal);
        }
        return out;
    }

    private static String stripEq(String f) {
        if (f == null) return "";
        f = f.trim();
        return f.startsWith("=") ? f.substring(1).trim() : f;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asRefMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : Map.of();
    }

    private static boolean hasAggregate(String f) {
        return f != null && f.toUpperCase().matches(".*\\b(SUM|COUNT|AVG|MIN|MAX)_OVER\\b.*");
    }

    /**
     * 处理 [..] 占位：聚合源(refs中isAggregateSource的[页签])→保持原样并登记Binding(tabKey+cols别名)；
     * 卡片单值(含. 的[页签.字段]/[页签.小计])→解析成字面量并统计是否全空；其余(裸col_key)→原样交引擎(走cached)。
     */
    private String resolveCardScalars(String formula, Map<String, Object> refs,
            CardDataProvider provider,
            boolean[] anyNonEmpty, boolean[] anyRef,
            Map<String, CardAggregateSource.Binding> aggBindings) {
        Set<String> aggTokens = new HashSet<>();
        for (var e : refs.entrySet()) {
            CardRef r = CardRef.fromMap(asRefMap(e.getValue()));
            if (r != null && r.isAggregateSource()) {
                aggBindings.put(e.getKey(), new CardAggregateSource.Binding(r.tab, r.cols));
                aggTokens.add(e.getKey());
            }
        }
        Matcher m = Pattern.compile("\\[([^\\[\\]]+)]").matcher(formula);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String token = m.group(1).trim();
            if (aggTokens.contains(token) || !refs.containsKey(token)) {
                // 聚合源/裸col_key → 原样交引擎
                m.appendReplacement(sb, Matcher.quoteReplacement("[" + token + "]"));
                continue;
            }
            CardRef ref = CardRef.fromMap(asRefMap(refs.get(token)));
            anyRef[0] = true;
            String literal;
            if (ref.isSubtotal()) {
                BigDecimal s = provider.subtotalOf(ref.tab);
                if (s != null) anyNonEmpty[0] = true;
                literal = s != null ? s.toPlainString() : "0";
            } else {
                Object v = pickFieldValue(provider, ref);
                if (v != null && !"".equals(v)) anyNonEmpty[0] = true;
                literal = toNum(v);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(literal));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Object pickFieldValue(CardDataProvider provider, CardRef ref) {
        List<Map<String, Object>> rows = provider.rowsOf(ref.tab);
        if (rows.isEmpty()) return null;
        if (ref.mode == CardRef.Mode.ROW_WHERE
                && ref.cond != null && !ref.cond.isBlank()) {
            // 用 ref.cols 把每行重映射成别名行供条件求值
            List<Map<String, Object>> aliased = new ArrayList<>(rows.size());
            for (var row : rows) {
                Map<String, Object> a = new HashMap<>();
                for (var ce : ref.cols.entrySet()) a.put(ce.getKey(), row.get(ce.getValue()));
                aliased.add(a);
            }
            int idx = templateFormulaService.firstMatchIndex(aliased, ref.cond);
            return idx < 0 ? null : rows.get(idx).get(ref.field); // 原始行的中文字段值
        }
        return rows.get(0).get(ref.field); // FIRST_ROW
    }

    private static String toNum(Object v) {
        if (v == null) return "0";
        if (v instanceof Number) return v.toString();
        try {
            return new BigDecimal(v.toString()).toPlainString();
        } catch (Exception e) {
            return "0";
        }
    }
}
