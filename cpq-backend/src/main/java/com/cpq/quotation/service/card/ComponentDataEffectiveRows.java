package com.cpq.quotation.service.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;

/**
 * 读时计算器：持久化 {@link QuotationLineComponentData}（row_data 为新鲜真相源）→
 * 系统既有「有效行」抽象 {@link CardEffectiveRows.TabRows}{rows, subtotal, subtotalByColumn}。
 *
 * <p>解决两个根因：①Excel 列 tabKey 是裸 componentId，持久化 CardDataProvider.resolve()
 * 解析不到 → 这里以裸 componentId 直接作 key（fromEffectiveRows 精确命中，不依赖 resolve 兜底）；
 * ②小计从不落库 → 这里现算（列求和 + SUBTOTAL 组件 component_subtotal 公式求值）。
 *
 * <p>双键登记：每个页签同时以「裸 componentId」（Excel 列 tabKey 约定）与
 * 「componentId:sortOrder」（CardRef 约定）登记同一 TabRows，兼容两类消费方。
 *
 * <p>DB-free 纯函数（meta 与 FormulaCalculator 由调用方注入），便于单测。
 */
public final class ComponentDataEffectiveRows {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 组件类型枚举值：仅 SUBTOTAL 组件走公式求总计。 */
    private static final String COMPONENT_TYPE_SUBTOTAL = "SUBTOTAL";
    /** 组件 formula 对象里表达式 token 数组的键名。 */
    private static final String EXPR_KEY = "expression";
    /**
     * componentSubtotals 键分隔符 `<code|name>#<col>`。
     * ⚠️ 必须与 {@link FormulaCalculator} 的 component_subtotal 取值键
     * （{@code compCode + "#" + colName} / {@code tabName + "#" + colName}）保持一致，
     * 否则 SUBTOTAL 公式静默取不到值（见 AP-37/AP-44 协议字符串漂移教训）。
     */
    private static final String SUBTOTAL_KEY_SEP = "#";
    /** 双键中 componentId:sortOrder 的分隔符（CardRef 约定）。 */
    private static final String TABKEY_SORT_SEP = ":";

    private ComponentDataEffectiveRows() {}

    /** 组件元数据（由调用方从 Component 实体加载，保持本类 DB-free）。 */
    public static final class Meta {
        public final String code;
        public final String name;
        public final String componentType;
        /** 组件 formulas JSON 数组（SUBTOTAL 组件求总计用），可为 null。 */
        public final JsonNode formulas;
        public Meta(String code, String name, String componentType, JsonNode formulas) {
            this.code = code;
            this.name = name;
            this.componentType = componentType;
            this.formulas = formulas;
        }
    }

    public static Map<String, CardEffectiveRows.TabRows> compute(
            List<QuotationLineComponentData> cdList,
            Map<UUID, Meta> metaById,
            FormulaCalculator fc) {
        Map<String, CardEffectiveRows.TabRows> out = new LinkedHashMap<>();
        if (cdList == null || cdList.isEmpty()) return out;
        Map<UUID, Meta> metas = metaById != null ? metaById : Map.of();

        // Pass 1：解析行 + 列求和；构建全局 componentSubtotals（code#col 与 name#col，避免同名列 费用 串值）
        List<TabAcc> accs = new ArrayList<>();
        Map<String, Double> componentSubtotals = new HashMap<>();
        for (QuotationLineComponentData cd : cdList) {
            if (cd == null) continue;
            List<Map<String, Object>> rows = parseRows(cd.rowData);
            Map<String, BigDecimal> colSums = columnSums(rows);
            Meta meta = cd.componentId != null ? metas.get(cd.componentId) : null;
            accs.add(new TabAcc(cd, rows, colSums, meta));
            if (meta != null) {
                for (Map.Entry<String, BigDecimal> e : colSums.entrySet()) {
                    // double 受限于 FormulaCalculator.RowContext.componentSubtotals 的 Map<String,Double> 契约；
                    // 列和本身仍是 BigDecimal（见 subtotalByColumn），勿擅自改回 BigDecimal 破坏契约。
                    double v = e.getValue().doubleValue();
                    if (meta.code != null) componentSubtotals.put(meta.code + SUBTOTAL_KEY_SEP + e.getKey(), v);
                    if (meta.name != null) componentSubtotals.put(meta.name + SUBTOTAL_KEY_SEP + e.getKey(), v);
                }
            }
        }

        // Pass 2：算 subtotal（SUBTOTAL → 公式求值；其余 → 沿用持久化值）+ 双键装配
        for (TabAcc a : accs) {
            BigDecimal subtotal = a.cd.subtotal;
            if (a.meta != null && COMPONENT_TYPE_SUBTOTAL.equals(a.meta.componentType)
                    && a.meta.formulas != null && a.meta.formulas.isArray() && a.meta.formulas.size() > 0) {
                // SUBTOTAL 组件取首个公式为总计；多公式场景不支持（约定）。
                JsonNode expr = a.meta.formulas.get(0).path(EXPR_KEY);
                if (expr.isArray() && expr.size() > 0) {
                    FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
                    ctx.componentSubtotals = componentSubtotals;
                    subtotal = fc.evaluateExpression(expr, ctx);
                }
            }
            CardEffectiveRows.TabRows tr =
                new CardEffectiveRows.TabRows(a.rows, subtotal, a.colSums);
            if (a.cd.componentId != null) {
                String cid = a.cd.componentId.toString();
                int sort = a.cd.sortOrder == null ? 0 : a.cd.sortOrder;
                // 双键非对称（有意）：裸 cid 用 put（同 componentId 多实例时后者覆盖——Excel 列 tabKey
                // 本就不区分实例，是有损便利键）；cid:sortOrder 用 putIfAbsent（首实例胜，是每实例权威键）。
                out.put(cid, tr);                                     // 裸 componentId（Excel 列 tabKey 约定）
                out.putIfAbsent(cid + TABKEY_SORT_SEP + sort, tr);   // componentId:sortOrder（CardRef 约定）
            }
        }
        return out;
    }

    private static List<Map<String, Object>> parseRows(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> r =
                MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            return r != null ? r : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 对每个数值列求和；非数值/空白列跳过（不进结果）。 */
    static Map<String, BigDecimal> columnSums(List<Map<String, Object>> rows) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            for (Map.Entry<String, Object> e : row.entrySet()) {
                BigDecimal v = toBig(e.getValue());
                if (v != null) sums.merge(e.getKey(), v, BigDecimal::add);
            }
        }
        return sums;
    }

    private static BigDecimal toBig(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    private static final class TabAcc {
        final QuotationLineComponentData cd;
        final List<Map<String, Object>> rows;
        final Map<String, BigDecimal> colSums;
        final Meta meta;
        TabAcc(QuotationLineComponentData cd, List<Map<String, Object>> rows,
               Map<String, BigDecimal> colSums, Meta meta) {
            this.cd = cd; this.rows = rows; this.colSums = colSums; this.meta = meta;
        }
    }
}
