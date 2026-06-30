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
    /**
     * BL-0017 哨兵列名：`<code|name>#__amount_total__` = 该页签金额列(is_amount && is_subtotal)之和，
     * 专供 `[页签(总计)]`（component_subtotal token value=此哨兵）。加性键，裸键不变。
     * ⚠️ 必须与前端 {@code tabTotalLines.AMOUNT_TOTAL_KEY} 一致。
     */
    public static final String AMOUNT_TOTAL_KEY = "__amount_total__";

    private ComponentDataEffectiveRows() {}

    /** 组件元数据（由调用方从 Component 实体加载，保持本类 DB-free）。 */
    public static final class Meta {
        public final String code;
        public final String name;
        public final String componentType;
        /** 组件 formulas JSON 数组（SUBTOTAL 组件求总计用），可为 null。 */
        public final JsonNode formulas;
        /** BL-0017：金额列名集（is_amount && is_subtotal），供哨兵键 Σ金额列；为 null/空则金额总计=0。 */
        public final java.util.Set<String> amountCols;
        public Meta(String code, String name, String componentType, JsonNode formulas) {
            this(code, name, componentType, formulas, java.util.Set.of());
        }
        public Meta(String code, String name, String componentType, JsonNode formulas,
                    java.util.Set<String> amountCols) {
            this.code = code;
            this.name = name;
            this.componentType = componentType;
            this.formulas = formulas;
            this.amountCols = amountCols != null ? amountCols : java.util.Set.of();
        }
    }

    /**
     * BL-0017：从组件 fields JSON 字符串抽取金额列名集（is_amount && is_subtotal）。
     * 供 Meta 构造点（ExcelViewService / LineDiscountService）填充 amountCols。
     * null/空/坏 JSON → 空集（该页签金额总计 = 0）。
     */
    public static java.util.Set<String> amountColsFromFieldsJson(String fieldsJson) {
        if (fieldsJson == null || fieldsJson.isBlank()) return java.util.Set.of();
        try {
            return amountColsFromFields(MAPPER.readTree(fieldsJson));
        } catch (Exception ignore) { /* 坏 fields → 空集（金额总计=0） */ }
        return java.util.Set.of();
    }

    /** BL-0017：从组件 fields JsonNode 抽取金额列名集（is_amount && is_subtotal）。null/非数组 → 空集。 */
    public static java.util.Set<String> amountColsFromFields(JsonNode fields) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (fields == null || !fields.isArray()) return out;
        for (JsonNode f : fields) {
            if (f.path("is_amount").asBoolean(false) && f.path("is_subtotal").asBoolean(false)) {
                String n = f.path("name").asText("");
                if (!n.isBlank()) out.add(n);
            }
        }
        return out;
    }

    public static Map<String, CardEffectiveRows.TabRows> compute(
            List<QuotationLineComponentData> cdList,
            Map<UUID, Meta> metaById,
            FormulaCalculator fc) {
        return compute(cdList, metaById, Map.of(), fc);
    }

    /**
     * 重载：额外合成「无 cd 记录的 SUBTOTAL 组件」的 TabRows。
     *
     * <p>背景：新配置（CONFIGURED）的报价单里 {@code ConfigureSnapshotService} 不为 SUBTOTAL
     * 组件建 component_data 记录（它无 driver 行）→ 旧 compute 仅遍历 cdList → 该 SUBTOTAL
     * 页签缺席 → Excel 列 {@code [报价小计(总计)]} 解析为 0（产品小计=0 残留 bug）。
     *
     * <p>本重载对 {@code extraSubtotalMetas} 里每个 SUBTOTAL 组件：合成 0 明细行的 TabRows，
     * 用 Pass 1 已从 NORMAL 页签构建的 componentSubtotals 跑公式求总计，双键登记
     * （裸 componentId + componentId:sortOrder），令 tabKey 引用得以命中。
     *
     * @param extraSubtotalMetas componentId → Meta（SUBTOTAL 类型，须含 formulas）；
     *        已在 cdList 中出现的组件会跳过，避免重复处理。
     */
    public static Map<String, CardEffectiveRows.TabRows> compute(
            List<QuotationLineComponentData> cdList,
            Map<UUID, Meta> metaById,
            Map<UUID, Meta> extraSubtotalMetas,
            FormulaCalculator fc) {
        return computeScaled(cdList, metaById, extraSubtotalMetas, fc, null, 1.0);
    }

    /**
     * 折扣重算：对 discountCode 命中的页签（meta.code 或 meta.name 相等）列和乘 discountScale，
     * 再跑 SUBTOTAL 公式，返回该 SUBTOTAL 页签的折后小计。
     * discountCode=null 或 scale=1.0 → 等价无折扣（返原产品小计 S0）。
     */
    public static BigDecimal subtotalWithDiscount(
            List<QuotationLineComponentData> cdList,
            Map<UUID, Meta> metaById,
            UUID subtotalComponentId,
            FormulaCalculator fc,
            String discountCode,
            double discountScale) {
        Map<String, CardEffectiveRows.TabRows> tabs =
            computeScaled(cdList, metaById, Map.of(), fc, discountCode, discountScale);
        CardEffectiveRows.TabRows tr = subtotalComponentId != null
            ? tabs.get(subtotalComponentId.toString()) : null;
        BigDecimal s = tr != null ? tr.subtotal : null;
        return s != null ? s.setScale(4, java.math.RoundingMode.HALF_UP)
                         : java.math.BigDecimal.ZERO.setScale(4);
    }

    /**
     * 内部实现：在 Pass1 列求和时对命中页签（discountCode 匹配 meta.code 或 meta.name）的每列值
     * 乘 discountScale，再走 Pass2/Pass3 重算 SUBTOTAL 公式。
     * discountCode=null → 缩放不触发，与原 compute 完全等价。
     */
    private static Map<String, CardEffectiveRows.TabRows> computeScaled(
            List<QuotationLineComponentData> cdList,
            Map<UUID, Meta> metaById,
            Map<UUID, Meta> extraSubtotalMetas,
            FormulaCalculator fc,
            String discountCode,
            double discountScale) {
        Map<String, CardEffectiveRows.TabRows> out = new LinkedHashMap<>();
        Map<UUID, Meta> extras = extraSubtotalMetas != null ? extraSubtotalMetas : Map.of();
        boolean noCd = cdList == null || cdList.isEmpty();
        if (noCd && extras.isEmpty()) return out;
        Map<UUID, Meta> metas = metaById != null ? metaById : Map.of();

        // Pass 1：解析行 + 列求和；构建全局 componentSubtotals（code#col 与 name#col，避免同名列 费用 串值）
        List<TabAcc> accs = new ArrayList<>();
        Map<String, Double> componentSubtotals = new HashMap<>();
        Set<UUID> presentInCd = new HashSet<>();
        for (QuotationLineComponentData cd : (noCd ? List.<QuotationLineComponentData>of() : cdList)) {
            if (cd == null) continue;
            if (cd.componentId != null) presentInCd.add(cd.componentId);
            List<Map<String, Object>> rows = parseRows(cd.rowData);
            Map<String, BigDecimal> colSums = columnSums(rows);
            Meta meta = cd.componentId != null ? metas.get(cd.componentId) : null;
            accs.add(new TabAcc(cd, rows, colSums, meta));
            if (meta != null) {
                // BL-0017：累加金额列(is_amount)之和（用缩放后 v，与列键口径一致），登记哨兵键。
                double amountTotal = 0.0;
                for (Map.Entry<String, BigDecimal> e : colSums.entrySet()) {
                    // double 受限于 FormulaCalculator.RowContext.componentSubtotals 的 Map<String,Double> 契约；
                    // 列和本身仍是 BigDecimal（见 subtotalByColumn），勿擅自改回 BigDecimal 破坏契约。
                    double v = e.getValue().doubleValue();
                    // 按列折扣：discountCode = `code#列名`（或 `name#列名`）→ 仅缩放该列；
                    // 兼容旧整组件格式（discountCode = code/name 无 #）→ 缩放该组件全部列。
                    boolean hit = discountCode != null && (
                        discountCode.equals(meta.code + SUBTOTAL_KEY_SEP + e.getKey())
                        || discountCode.equals(meta.name + SUBTOTAL_KEY_SEP + e.getKey())
                        || discountCode.equals(meta.code)
                        || discountCode.equals(meta.name));
                    if (hit) v = v * discountScale;
                    if (meta.code != null) componentSubtotals.put(meta.code + SUBTOTAL_KEY_SEP + e.getKey(), v);
                    if (meta.name != null) componentSubtotals.put(meta.name + SUBTOTAL_KEY_SEP + e.getKey(), v);
                    if (meta.amountCols.contains(e.getKey())) amountTotal += v;
                }
                // BL-0017 哨兵键（加性，不动裸键）：`<code|name>#__amount_total__` = Σ金额列。
                if (meta.code != null) componentSubtotals.put(meta.code + SUBTOTAL_KEY_SEP + AMOUNT_TOTAL_KEY, amountTotal);
                if (meta.name != null) componentSubtotals.put(meta.name + SUBTOTAL_KEY_SEP + AMOUNT_TOTAL_KEY, amountTotal);
            }
        }

        // Pass 2：算 subtotal（SUBTOTAL → 公式求值；其余 → 沿用持久化值）+ 双键装配
        for (TabAcc a : accs) {
            BigDecimal subtotal = a.cd.subtotal;
            if (a.meta != null && COMPONENT_TYPE_SUBTOTAL.equals(a.meta.componentType)) {
                BigDecimal evaluated = evaluateSubtotalFormula(a.meta, componentSubtotals, fc);
                if (evaluated != null) subtotal = evaluated;
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

        // Pass 3：合成无 cd 记录的 SUBTOTAL 组件（新配置报价单产品小计=0 修复）。
        // 0 明细行（AP-51：纯公式总计），公式对 Pass 1 的 componentSubtotals 求值，双键登记。
        for (Map.Entry<UUID, Meta> e : extras.entrySet()) {
            UUID cid = e.getKey();
            Meta meta = e.getValue();
            if (cid == null || meta == null) continue;
            if (presentInCd.contains(cid)) continue;            // 已有 cd → 避免重复处理
            if (!COMPONENT_TYPE_SUBTOTAL.equals(meta.componentType)) continue;
            BigDecimal subtotal = evaluateSubtotalFormula(meta, componentSubtotals, fc);
            if (subtotal == null) subtotal = BigDecimal.ZERO;
            CardEffectiveRows.TabRows tr =
                new CardEffectiveRows.TabRows(List.of(), subtotal, Map.of());
            String cidStr = cid.toString();
            out.put(cidStr, tr);                                // 裸 componentId（tabKey 命中 [报价小计(总计)]）
            out.putIfAbsent(cidStr + TABKEY_SORT_SEP + 0, tr);  // componentId:sortOrder（CardRef 约定）
        }
        return out;
    }

    /**
     * SUBTOTAL 组件取首个公式为总计求值；无公式/空表达式 → null（调用方退回持久化值或 0）。
     */
    private static BigDecimal evaluateSubtotalFormula(
            Meta meta, Map<String, Double> componentSubtotals, FormulaCalculator fc) {
        if (meta.formulas == null || !meta.formulas.isArray() || meta.formulas.size() == 0) return null;
        JsonNode expr = meta.formulas.get(0).path(EXPR_KEY);
        if (!expr.isArray() || expr.size() == 0) return null;
        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.componentSubtotals = componentSubtotals;
        return fc.evaluateExpression(expr, ctx);
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
    public static Map<String, BigDecimal> columnSums(List<Map<String, Object>> rows) {
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
