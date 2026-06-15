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

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(CardFormulaEvaluator.class);

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

    /** 决策A：从某列 refs.condRows 收集 rhs.type=column 的列依赖边（WHERE 里引用的列）。 */
    static Set<String> condRowColumnDeps(Map<String, Object> refs, Set<String> allCols) {
        Set<String> out = new LinkedHashSet<>();
        if (refs == null) return out;
        for (Object refObj : refs.values()) {
            CardRef r = CardRef.fromMap(asRefMap(refObj));
            if (r == null || !r.hasCondRows()) continue;
            for (CardRef.CondRow cr : r.condRows) {
                if (cr.rhs != null && cr.rhs.type == CardRef.RhsType.COLUMN
                        && cr.rhs.value != null && allCols.contains(cr.rhs.value)) {
                    out.add(cr.rhs.value);
                }
            }
        }
        return out;
    }

    /** 兼容旧签名（仅按公式文本建依赖）。 */
    public static List<String> topoOrder(Map<String, String> formulas) {
        return topoOrder(formulas, Map.of());
    }

    /** Kahn 拓扑排序；存在环抛 BusinessException。依赖边 = 公式 [col] + condRows 的 rhs(column)。 */
    public static List<String> topoOrder(Map<String, String> formulas,
                                         Map<String, Map<String, Object>> refsByCol) {
        Set<String> cols = formulas.keySet();
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        Map<String, Integer> indeg = new LinkedHashMap<>();
        for (String c : cols) {
            Set<String> d = columnDeps(formulas.get(c), cols);
            d.addAll(condRowColumnDeps(refsByCol == null ? null : refsByCol.get(c), cols));
            deps.put(c, d);
        }
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

    /** 算本料号一组 CARD_FORMULA 列。columns: 每项含 col_key/formula/refs。既有签名：从持久化 componentData 构造 provider。productRow 缺省为空 Map（动态 RHS 取不到 product 键 → 不匹配）。 */
    public Map<String, Object> evaluateColumns(
            List<Map<String, Object>> columns,
            List<QuotationLineComponentData> tabs,
            UUID customerId, String partNo, UUID quotationId) {
        return evaluateColumns(columns, tabs, customerId, partNo, quotationId, Map.of());
    }

    /** 新重载：直接吃已构造好的 provider（来自 CardEffectiveRows，精确命中）。 */
    public Map<String, Object> evaluateColumns(
            List<Map<String, Object>> columns,
            CardDataProvider provider,
            UUID customerId, String partNo, UUID quotationId) {
        return evaluateColumns(columns, provider, customerId, partNo, quotationId, Map.of());
    }

    /** 6 参：带本产品行 productRow（= componentRowData），供 ROW_WHERE 动态 RHS=product 解析。 */
    public Map<String, Object> evaluateColumns(
            List<Map<String, Object>> columns,
            List<QuotationLineComponentData> tabs,
            UUID customerId, String partNo, UUID quotationId, Map<String, Object> productRow) {
        return evaluateColumnsInternal(columns, new CardDataProvider(tabs), customerId, partNo, quotationId, productRow);
    }

    public Map<String, Object> evaluateColumns(
            List<Map<String, Object>> columns,
            CardDataProvider provider,
            UUID customerId, String partNo, UUID quotationId, Map<String, Object> productRow) {
        return evaluateColumnsInternal(columns, provider, customerId, partNo, quotationId, productRow);
    }

    private Map<String, Object> evaluateColumnsInternal(
            List<Map<String, Object>> columns, CardDataProvider provider,
            UUID customerId, String partNo, UUID quotationId, Map<String, Object> productRow) {

        Map<String, String> formulaByCol = new LinkedHashMap<>();
        Map<String, Map<String, Object>> refsByCol = new HashMap<>();
        for (var c : columns) {
            String key = (String) c.get("col_key");
            formulaByCol.put(key, stripEq((String) c.get("formula")));
            refsByCol.put(key, asRefMap(c.get("refs")));
        }

        List<String> order = topoOrder(formulaByCol, refsByCol);
        Map<String, Object> cached = new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();

        for (String col : order) {
            String formula = formulaByCol.get(col);
            Map<String, Object> refs = refsByCol.get(col);

            boolean[] anyNonEmpty = {false};
            boolean[] anyRef = {false};
            Map<String, CardAggregateSource.Binding> aggBindings = new HashMap<>();
            String resolved = resolveCardScalars(formula, refs, provider, anyNonEmpty, anyRef, aggBindings,
                    productRow, cached, partNo);

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
        // *_OVER 家族 + SUMIF/COUNTIF/AVGIF/MINIF/MAXIF 条件聚合家族（否则含 SUMIF 的全空 ref 列会被 DASH 误抑制）
        return f != null && f.toUpperCase().matches(".*\\b((SUM|COUNT|AVG|MIN|MAX)_OVER|(SUM|COUNT|AVG|MIN|MAX)IF)\\b.*");
    }

    /**
     * 处理 [..] 占位：聚合源(refs中isAggregateSource的[页签])→保持原样并登记Binding(tabKey+cols别名)；
     * 卡片单值(含. 的[页签.字段]/[页签.小计])→解析成字面量并统计是否全空；其余(裸col_key)→原样交引擎(走cached)。
     */
    private String resolveCardScalars(String formula, Map<String, Object> refs,
            CardDataProvider provider,
            boolean[] anyNonEmpty, boolean[] anyRef,
            Map<String, CardAggregateSource.Binding> aggBindings,
            Map<String, Object> productRow, Map<String, Object> cached, String partNo) {
        Set<String> aggTokens = new HashSet<>();
        for (var e : refs.entrySet()) {
            CardRef r = CardRef.fromMap(asRefMap(e.getValue()));
            if (r != null && r.isAggregateSource()) {
                String dynPred = r.hasCondRows()
                        ? buildDynamicCond(r, productRow, cached, partNo)   // 动态 WHERE 谓词(按本产品行)
                        : null;                                             // 静态 → 走公式文本谓词
                aggBindings.put(e.getKey(), new CardAggregateSource.Binding(r.tab, r.cols, dynPred));
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
                // Plan 2c：指定列 → 取该列总计；无列名或 per-column 缺失 → 回退页签各列之和。
                String col = ref.subtotalColumn();
                BigDecimal s = col != null ? provider.subtotalOfColumn(ref.tab, col) : null;
                if (s == null) s = provider.subtotalOf(ref.tab);
                if (s != null) anyNonEmpty[0] = true;
                literal = s != null ? s.toPlainString() : "0";
            } else {
                Object v = pickFieldValue(provider, ref, productRow, cached, partNo);
                if (v != null && !"".equals(v)) anyNonEmpty[0] = true;
                literal = toNum(v);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(literal));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Object pickFieldValue(CardDataProvider provider, CardRef ref,
                                  Map<String, Object> productRow, Map<String, Object> cached, String partNo) {
        List<Map<String, Object>> rows = provider.rowsOf(ref.tab);
        if (rows.isEmpty()) return null;
        if (ref.mode == CardRef.Mode.ROW_WHERE) {
            String cond = ref.hasCondRows()
                    ? buildDynamicCond(ref, productRow, cached, partNo)   // 动态 RHS
                    : ref.cond;                                           // 旧式字面量条件
            if (cond != null && !cond.isBlank()) {
                // 用 ref.cols 把每行重映射成别名行供条件求值
                List<Map<String, Object>> aliased = new ArrayList<>(rows.size());
                for (var row : rows) {
                    Map<String, Object> a = new HashMap<>();
                    for (var ce : ref.cols.entrySet()) a.put(ce.getKey(), row.get(ce.getValue()));
                    aliased.add(a);
                }
                int idx = templateFormulaService.firstMatchIndex(aliased, cond);
                return idx < 0 ? null : rows.get(idx).get(ref.field); // 原始行的中文字段值
            }
        }
        return rows.get(0).get(ref.field); // FIRST_ROW（或 ROW_WHERE 无条件）
    }

    /**
     * 把 ref.condRows 在本产品行上下文解析成带标量字面量的 JEXL 谓词（左值用别名）。
     * RHS 取空 → 该条件用永假 1==2（取不到键 → 不匹配），AND/OR 语义自然成立。
     */
    private String buildDynamicCond(CardRef ref, Map<String, Object> productRow,
                                    Map<String, Object> cached, String partNo) {
        // 反查别名：字段 → 别名（cols 是 别名→字段）
        Map<String, String> fieldToAlias = new HashMap<>();
        for (var e : ref.cols.entrySet()) fieldToAlias.put(e.getValue(), e.getKey());
        StringBuilder sb = new StringBuilder();
        List<CardRef.CondRow> rows = ref.condRows;
        for (int i = 0; i < rows.size(); i++) {
            CardRef.CondRow c = rows.get(i);
            String alias = fieldToAlias.getOrDefault(c.left, c.left);
            Object scalar = resolveRhs(c.rhs, productRow, cached, partNo);
            String expr;
            if (scalar == null || scalar.toString().isEmpty()) {
                expr = "1==2"; // 取不到键 → 不匹配
            } else if ("in".equalsIgnoreCase(c.op) && c.rhs.type == CardRef.RhsType.LITERAL) {
                // IN 仅字面量场景（逗号分隔 → (alias=='v1' || alias=='v2')）
                List<String> vals = new ArrayList<>();
                for (String v : scalar.toString().split(",")) { v = v.trim(); if (!v.isEmpty()) vals.add(v); }
                if (vals.isEmpty()) { expr = "1==2"; }
                else {
                    StringBuilder in = new StringBuilder("(");
                    for (int k = 0; k < vals.size(); k++) {
                        if (k > 0) in.append(" || ");
                        in.append(alias).append("=='").append(vals.get(k)).append("'");
                    }
                    expr = in.append(")").toString();
                }
            } else {
                // 标量比较（动态 product/column 或字面量非 IN）；IN+非字面量退化为 eq
                String op;
                if ("in".equalsIgnoreCase(c.op)) {
                    if (c.rhs.type != CardRef.RhsType.LITERAL) {
                        LOG.warnf("[CardFormula] ROW_WHERE 条件 '%s' 用 IN + 动态 RHS(%s) 不支持，已退化为 ==",
                                c.left, c.rhs.type);
                    }
                    op = "==";
                } else {
                    op = opToJexl(c.op);
                }
                expr = alias + op + toJexlLiteral(scalar);
            }
            sb.append(expr);
            if (i < rows.size() - 1) sb.append("or".equalsIgnoreCase(c.logic) ? " || " : " && ");
        }
        return sb.toString();
    }

    /** 解析 RHS 为标量；只允许 productRow / partNo / 已算列（cached）。 */
    private Object resolveRhs(CardRef.Rhs rhs, Map<String, Object> productRow,
                             Map<String, Object> cached, String partNo) {
        if (rhs == null) return null;
        return switch (rhs.type) {
            case LITERAL -> rhs.value;
            case PRODUCT -> "__partNo__".equals(rhs.value) ? partNo
                            : (productRow == null ? null : productRow.get(rhs.value));
            case COLUMN -> cached == null ? null : cached.get(rhs.value);
        };
    }

    /** 标量 → JEXL 字面量：可解析为数字则裸写，否则单引号字符串（转义反斜杠与单引号，防撇号值静默错配）。 */
    private static String toJexlLiteral(Object v) {
        String s = v.toString();
        try { new BigDecimal(s); return s; }
        catch (Exception e) {
            return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
    }

    private static String opToJexl(String op) {
        return switch (op == null ? "eq" : op) {
            case "ne" -> "!="; case "gt" -> ">"; case "gte" -> ">=";
            case "lt" -> "<"; case "lte" -> "<="; default -> "==";
        };
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
