package com.cpq.quotation.service;

import com.cpq.common.PrecisionPolicy;
import com.cpq.common.exception.FormulaCycleException;
import com.cpq.quotation.rowkey.DeletedRowKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
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
    // task-0801 B2：十进制化 —— 不再带 scale(4)，避免把 4 位截断传染给整条求值链（呈现边界由 B5 统一规整到 6 位）。
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final com.cpq.formula.predicate.ConditionPredicateEvaluator predicateEval =
            new com.cpq.formula.predicate.ConditionPredicateEvaluator();

    /** 单行公式求值上下文（对齐 formulaEngine.ts evaluateExpression 的多个参数）。 */
    public static class RowContext {
        /** field / datasource_field token 取值：字段名 → 数值 */
        public Map<String, Double> fieldValues = new HashMap<>();
        /**
         * per-field source 分桶（D3 加固）：source componentId → {列名 → 数值}。
         * 在多源 cross_tab_ref 的 targetRowValue 中填充，供 field token 带 source 时优先取值，
         * 防止不同 source 同名列按名合并后串值。field token 无 source 时回退 fieldValues。
         */
        public Map<String, Map<String, Double>> bySource = new HashMap<>();
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
        /**
         * repair-0803：<b>宿主行</b>已算字段值（字段名 → 数值），供 {@code b_field} 在
         * {@code currentRowRaw} 键缺失时回落。
         *
         * <p>为什么不复用 {@code fieldValues}：在 targetExpr 子上下文里 {@code fieldValues}
         * 装的是<b>被聚合源页签当前行</b>的列（{@code targetRowValue} 从 arow 灌入），
         * 而 {@code b_field} 语义恒为「宿主行的列」。两者必须分开，否则 b_field 会串到源页签列上。
         *
         * <p>顶层求值时本字段与 {@code fieldValues} 指向<b>同一个 map</b>（随公式逐个算完即时更新，
         * 故 b_field 能取到刚算出的宿主公式值）；targetRowValue 构造子上下文时原样透传本字段。
         * 未设置（默认空 map）→ b_field 回落取不到 → 行为与修复前一致（零破坏）。
         */
        public Map<String, Double> hostFieldValues = new HashMap<>();
        /** cross_tab_ref：同卡片已算行存储（组件标识→行表，行=字段名→已算值）。 */
        public Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();

        /**
         * task-0803：BOM 树求值上下文。
         * {@code null} = 非树页签、或该页签公式未使用父子 token —— 此时 {@code tree_ref}/{@code tree_attr}
         * 求值一律返 0（需求 §4.3.8 闸 ⑤ 兜底，防存量脏数据静默算错）。
         */
        public TreeEvalContext tree = null;
        /** task-0803：本行在 baseRows 中的下标；{@code tree != null} 时有效，否则 -1。 */
        public int rowIndex = -1;
    }

    /**
     * task-0803：BOM 树页签的整页签求值上下文。
     *
     * <p>与 {@code baseRows} 下标一一对应；{@code rowContexts} 在单元格拓扑求值过程中被逐格填充，
     * 因此 {@code PGET}/{@code C*} 读到的父/子行值，一定是拓扑序保证已算好的那一份。
     *
     * @param relations      父子关系（按 {@code __nodeId} 认边，已排除墓碑）
     * @param rowContexts    各行求值上下文（可变，随求值填充）
     * @param resolvedRaw    各行「已解析原始值」视图（字段名 → 原始值），判「有值」用
     * @param formulaColumns 公式列名集合 —— 需求 §4.3.4 判据 6：公式列恒有值
     */
    public record TreeEvalContext(com.cpq.quotation.service.formula.TreeRelations relations,
                                  List<RowContext> rowContexts,
                                  List<Map<String, Object>> resolvedRaw,
                                  java.util.Set<String> formulaColumns) {}

    // ======================================================================
    // Layer 1 — evaluateExpression（单公式 token 数组 → BigDecimal）
    // ======================================================================

    public BigDecimal evaluateExpression(JsonNode tokens, RowContext ctx) {
        if (tokens == null || !tokens.isArray() || tokens.size() == 0) return ZERO;
        RowContext c = ctx != null ? ctx : new RowContext();
        try {
            StringBuilder expr = new StringBuilder();
            for (JsonNode token : tokens) {
                appendToken(expr, token, c);
            }
            // task-0801 B2：ArithParser 全程 BigDecimal 精确运算，此处不再 setScale(4) 截断
            // （呈现边界由调用方 / B5 统一规整到 6 位）。除零语义由 ArithParser 内部经
            // PrecisionPolicy.divide() 兜底为 ZERO（不抛异常，api.md G-9），故此处不再需要
            // Double.isNaN/isInfinite 判断。
            return new ArithParser(expr.toString()).parse();
        } catch (Exception e) {
            return ZERO; // 解析异常 → 0（对齐前端 try/catch）
        }
    }

    private void appendToken(StringBuilder expr, JsonNode token, RowContext ctx) {
        String type = token.path("type").asText("");
        switch (type) {
            case "field": {
                String fieldName = token.path("value").asText("");
                String fieldSource = token.path("source").asText(null);
                double fieldVal = 0.0;
                // D3 per-field source：token 带 source 时优先从 bySource 分桶取值（防同名串值）；
                // source 缺失或桶里找不到时回退 fieldValues（兼容无 source 的存量 token）。
                if (fieldSource != null && !fieldSource.isEmpty()) {
                    Map<String, Double> bucket = ctx.bySource.get(fieldSource);
                    if (bucket != null && bucket.containsKey(fieldName)) {
                        fieldVal = bucket.get(fieldName);
                    } else {
                        fieldVal = ctx.fieldValues.getOrDefault(fieldName, 0.0);
                    }
                } else {
                    fieldVal = ctx.fieldValues.getOrDefault(fieldName, 0.0);
                }
                expr.append(numStr(fieldVal));
                break;
            }
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
                // 列名（value 字段）：用于构造 "${key}#${col}" 列小计键（与前端 formulaEngine 对齐）。
                // component_subtotal token 可能携带 component_code / tab_name / value 三种 key 形式。
                // 优先级（与前端 formulaEngine.ts component_subtotal 分支 1:1 对齐）：
                //   1. "${component_code}#${value}" 列小计键
                //   2. "${tab_name}#${value}"       列小计键
                //   3. component_code               组件总小计（回退）
                //   4. tab_name                     组件总小计（回退）
                //   5. value                        组件总小计（最终回退，兼容旧 token 形状）
                //   6. 0（缺失兜底）
                String colName = asTextOrNull(token, "value");
                String compCode = asTextOrNull(token, "component_code");
                String tabName  = asTextOrNull(token, "tab_name");
                Double v = firstNonNull(
                    // 列小计键（优先）
                    (compCode != null && colName != null)
                        ? ctx.componentSubtotals.get(compCode + "#" + colName) : null,
                    (tabName  != null && colName != null)
                        ? ctx.componentSubtotals.get(tabName  + "#" + colName) : null,
                    // 组件总小计（回退）
                    ctx.componentSubtotals.get(compCode),
                    ctx.componentSubtotals.get(tabName),
                    ctx.componentSubtotals.get(colName));
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
                // repair-0803：宿主行原始值优先；<b>键缺失</b>时回落已算字段值。
                // 理由：FORMULA 字段的结果只回填 fieldValues（computeRows 尾部），从不写回
                // currentRowRaw；fillInputDefaultSourceByFieldName 也只补 INPUT_ 类型。
                // 故不回落时，targetExpr 内引用本页签公式列恒取 0（静默少算）。
                // 键存在但为空串 = 用户显式置空 → 尊重置空、不回落（与 fillInputDefaultSourceByFieldName
                // 的「仅键缺失才补」口径对称）。
                Object raw = ctx.currentRowRaw.get(n);
                Double v = (raw != null) ? toNumber(raw) : ctx.hostFieldValues.get(n);
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
                if (v == null) {
                    // I-2: KAVG/KMAX/KMIN 空集 → null → 直接抛异常 → 外层 try/catch → 整表达式塌 0
                    // 对齐前端 `expr += '(null.x)'` 行为。
                    // task-0801 B2 十进制化后注意：原实现注入字面量 "(0/0)"，依赖 double 除零产生
                    // NaN 再由 evaluateExpression 的 Double.isNaN 检测触发降级；BigDecimal 化后
                    // 除零改由 PrecisionPolicy.divide() 优雅返回 ZERO（不抛异常，api.md G-9），
                    // "(0/0)" 这条路已不再能触发降级 —— 故直接 throw，与下面 FormulaErrorMarker
                    // 分支手法一致，语义（整表达式塌 0）完全不变。
                    throw new IllegalStateException("cross_tab_ref empty aggregate (I-2 KAVG/KMAX/KMIN)");
                }
                if (v instanceof FormulaErrorMarker) {
                    throw new IllegalStateException("cross_tab_ref multi/non-numeric");
                }
                Double n = (v instanceof Number num) ? num.doubleValue() : toNumber(v);
                expr.append(numStr(n != null ? n : 0.0));
                break;
            }
            // task-0803：BOM 页签父子取值。求值结果当字面量追加（带括号防负数与前一个运算符粘连）。
            case "tree_ref": {
                expr.append('(').append(evalTreeRef(token, ctx).toPlainString()).append(')');
                break;
            }
            case "tree_attr": {
                expr.append('(').append(evalTreeAttr(token, ctx).toPlainString()).append(')');
                break;
            }
            default:
                // 未知 token 忽略（对齐前端 switch 不命中分支）
                break;
        }
    }

    // ======================================================================
    // task-0803 — BOM 页签父子取值（tree_ref / tree_attr）
    // ======================================================================

    /**
     * {@code tree_ref} 求值：{@code dir=PARENT} 取直接父行（PGET）；{@code dir=CHILD} 聚合直接子行（C* 族）。
     *
     * <p>边界口径（需求 §4.3.3，全部返 0，无例外）：根行 PGET → 0；叶子行 C* → 0；
     * 子行全部无值 → 0；拿不到树上下文（非树页签 / 存量脏数据）→ 0。
     */
    private BigDecimal evalTreeRef(JsonNode token, RowContext ctx) {
        TreeEvalContext t = ctx.tree;
        if (t == null || ctx.rowIndex < 0 || ctx.rowIndex >= t.rowContexts().size()) return ZERO;
        JsonNode targetExpr = token.path("targetExpr");
        if (!targetExpr.isArray() || targetExpr.size() == 0) return ZERO;

        String dir = token.path("dir").asText("");
        if ("PARENT".equals(dir)) {
            int p = t.relations().parentOf(ctx.rowIndex);
            if (p < 0) return ZERO;                                  // 根行无父 → 0
            return evaluateExpression(targetExpr, t.rowContexts().get(p));
        }
        if (!"CHILD".equals(dir)) return ZERO;

        List<Integer> kids = t.relations().childrenOf(ctx.rowIndex);
        if (kids.isEmpty()) return ZERO;                              // 叶子行 → 0

        java.util.Set<String> names = collectFieldNames(targetExpr);
        List<BigDecimal> nums = new ArrayList<>(kids.size());
        for (int c : kids) {
            if (!hasValueForAgg(t, c, names)) continue;               // 空值不参与（§4.3.4）
            nums.add(evaluateExpression(targetExpr, t.rowContexts().get(c)));
        }
        if (nums.isEmpty()) return ZERO;                              // 子行全无值 → 0
        return aggregateTreeNums(token.path("agg").asText("SUM"), nums);
    }

    /** {@code tree_attr} 求值：层级 / 是否叶子 / 是否根。拿不到树上下文 → 0。 */
    private BigDecimal evalTreeAttr(JsonNode token, RowContext ctx) {
        TreeEvalContext t = ctx.tree;
        if (t == null || ctx.rowIndex < 0) return ZERO;
        return switch (token.path("attr").asText("")) {
            case "LVL" -> BigDecimal.valueOf(t.relations().lvl(ctx.rowIndex));
            case "IS_LEAF" -> t.relations().isLeaf(ctx.rowIndex) ? BigDecimal.ONE : ZERO;
            case "IS_ROOT" -> t.relations().isRoot(ctx.rowIndex) ? BigDecimal.ONE : ZERO;
            default -> ZERO;
        };
    }

    /** 收集表达式里所有 {@code field} token 的列名（判「有值」用）。 */
    private static java.util.Set<String> collectFieldNames(JsonNode expr) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (expr == null || !expr.isArray()) return out;
        for (JsonNode tk : expr) {
            if ("field".equals(tk.path("type").asText(""))) {
                String v = tk.path("value").asText("");
                if (!v.isEmpty()) out.add(v);
            }
        }
        return out;
    }

    /**
     * 需求 §4.3.4「有值」判据（实现版）：
     * <ol>
     *   <li>判据 5：表达式无 {@code field} token（纯常量/树属性）→ 有值（{@code CSUM(1)} = 子行数）</li>
     *   <li>判据 6：引用的是<b>公式列</b> → 恒有值（公式列总能算出数，哪怕 0）。
     *       <b>已明示接受的取舍</b>：聚合公式列时 spine 补位空行仍会参与（2026-08-03 用户裁决）</li>
     *   <li>判据 3：任一引用列在该行有非空原始值 → 有值（数值 0 也算有值）</li>
     *   <li>判据 2：引用列全部取不到值 → 无值，跳过该子行</li>
     * </ol>
     */
    private static boolean hasValueForAgg(TreeEvalContext t, int rowIdx, java.util.Set<String> names) {
        if (names.isEmpty()) return true;
        Map<String, Object> raw = (rowIdx >= 0 && rowIdx < t.resolvedRaw().size())
            ? t.resolvedRaw().get(rowIdx) : Map.of();
        for (String n : names) {
            if (t.formulaColumns().contains(n)) return true;
            if (!isBlank(raw.get(n))) return true;
        }
        return false;
    }

    /**
     * C* 族聚合。
     *
     * <p>🔒 <b>口径必须与 cross_tab_ref 的聚合分支保持一致</b>（本类内 {@code aggregateHits} 的
     * {@code switch (agg)}）：BigDecimal 全程、{@code PrecisionPolicy.sum} 累加、AVG 走
     * {@code DIVISION_SCALE + HALF_UP}、MAX/MIN 按 {@code compareTo} 选值。
     * 这里<b>刻意另写一份而非抽取共用</b> —— cross_tab_ref 那段是热路径且语义含 {@code ERR} 分流，
     * 抽取会改动它的代码路径，违反本任务的零回归门禁。改动任一处时请同步核对另一处。
     * 差异仅一处：本方法支持 {@code COUNT}（返回有值子行数），cross_tab_ref 那边 COUNT 走别的路径。
     */
    private static BigDecimal aggregateTreeNums(String agg, List<BigDecimal> nums) {
        if (nums == null || nums.isEmpty()) return ZERO;
        return switch (agg) {
            case "SUM" -> PrecisionPolicy.sum(nums);
            case "AVG" -> PrecisionPolicy.sum(nums).divide(BigDecimal.valueOf(nums.size()),
                PrecisionPolicy.DIVISION_SCALE, java.math.RoundingMode.HALF_UP);
            case "MAX" -> nums.stream().max(BigDecimal::compareTo).orElse(ZERO);
            case "MIN" -> nums.stream().min(BigDecimal::compareTo).orElse(ZERO);
            case "COUNT" -> BigDecimal.valueOf(nums.size());
            default -> ZERO;
        };
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

    /**
     * cross_tab_ref 求值。返回 Number / String（NONE 文本）/ ERR / {@code null}（KAVG/KMAX/KMIN 空集，I-2）。
     *
     * <p><b>KSUM 分支</b>（{@code projectToHostKey=true}）：按宿主行（ctx.currentRowRaw）过滤 hits，
     * 塌缩成标量后广播到调用方（targetRowValue 内嵌求值）。match 允许空（全量塌缩）。
     * 决策 K 空集分流（I-1/I-2）在统一 hits.isEmpty()→ZERO 之前判：
     * <ul>
     *   <li>KSUM/KCOUNT 空集 → 0（静默，I-1）</li>
     *   <li>KAVG/KMAX/KMIN 空集 → {@code null}（I-2，整外层塌 0 + outDiag；由 appendToken 注入非法表达式）</li>
     * </ul>
     *
     * <p><b>外层分支</b>（存量路径，N=1 无嵌套退化，零变化）：match 须非空（防御兜底）。
     */
    Object evalCrossTab(JsonNode token, RowContext ctx) {
        boolean proj = token.path("projectToHostKey").asBoolean(false);

        // 防御：match 字段必须是数组（v4-C 防御；validator 漏网兜底）。
        // match=[] 全量匹配在 KSUM（proj=true）和多 source 场景（外层 match=[]）中均合法；
        // 旧规则"非 KSUM + 空 match → ERR"已被 KSUM 特性放开：外层也可 match=[] 全量。
        // 保留的防御：match 字段不是数组时（missing/非法结构）→ ERR。
        if (!token.path("match").isArray()) {
            return ERR;
        }

        String source = token.path("source").asText("");
        String agg = token.path("agg").asText("NONE").toUpperCase();
        List<Map<String, Object>> rows = ctx.crossTabRows.getOrDefault(source, List.of());

        // hits 过滤：KSUM 按 match⋈ctx.currentRowRaw；外层同旧；再叠加可选 predicate（SUMIF 族）
        com.cpq.formula.predicate.ConditionPredicate predicate =
                com.cpq.formula.predicate.ConditionPredicateJson.fromJson(
                        token.has("predicate") ? token.get("predicate") : null);
        List<Map<String, Object>> hits = new ArrayList<>();
        JsonNode matchNode = token.path("match");
        boolean hasMatch = matchNode.isArray() && matchNode.size() > 0;
        for (Map<String, Object> arow : rows) {
            boolean ok = true;
            if (hasMatch) {
                for (JsonNode pair : matchNode) {
                    Object av = arow.get(pair.path("a").asText(""));
                    Object bv = ctx.currentRowRaw.get(pair.path("b").asText(""));
                    if (isBlank(av) || isBlank(bv) || !valEquals(av, bv)) { ok = false; break; }
                }
            }
            if (ok && predicate != null) {
                ok = predicateEval.test(predicate, arow, ctx.currentRowRaw);
            }
            if (ok) hits.add(arow);
        }

        if ("COUNT".equals(agg)) return java.math.BigDecimal.valueOf(hits.size());
        if ("NONE".equals(agg)) {
            // ① NONE 旁路：保留原始"零变化"行为（不受 proj 影响）
            if (hits.isEmpty()) return java.math.BigDecimal.ZERO;
            if (hits.size() > 1) return ERR;
            return targetRowValue(hits.get(0), token, ctx);
        }

        // 【I-1/I-2 决策 K 空集分流】—— 在统一"空集→ZERO"之前
        if (hits.isEmpty()) {
            if (proj && ("AVG".equals(agg) || "MAX".equals(agg) || "MIN".equals(agg))) {
                // I-2: KAVG/KMAX/KMIN 空集 → null → appendToken 注入非法表达式 → 外层 try/catch → 0
                return null;
            }
            // I-1: KSUM/外层 SUM/AVG/... 空集 → 0（保持旧行为不变）
            return java.math.BigDecimal.ZERO;
        }

        // task-0801 B4-2（审计追加发现）：原实现 toNumber()（→Double）逐项收集后再
        // nums.stream().mapToDouble().sum()/average()/max()/min() 是双重转换 + double 累加
        // （BigDecimal targetRowValue → double → 再累加 → BigDecimal.valueOf 包回），SUM 多项时
        // 会在 double 二进制精度上产生可见误差（如 2.26+4.52 不精确等于 6.78）。旧代码靠
        // evaluateExpression 顶层 setScale(4) 掩盖，B2 去掉该截断后原样冒出。改走 BigDecimal
        // 全程收集 + PrecisionPolicy 累加/除法，MAX/MIN 直接按 BigDecimal 比较选值。
        List<BigDecimal> nums = new ArrayList<>(hits.size());
        for (Map<String, Object> h : hits) {
            Object rv = targetRowValue(h, token, ctx);
            if (rv instanceof FormulaErrorMarker) return ERR;  // 多 source 广播 multiSrcHitErr
            BigDecimal n = toBigNumber(rv);
            if (n == null) return ERR;
            nums.add(n);
        }
        switch (agg) {
            case "SUM": return PrecisionPolicy.sum(nums);
            case "AVG": return nums.isEmpty() ? BigDecimal.ZERO
                : PrecisionPolicy.sum(nums).divide(BigDecimal.valueOf(nums.size()),
                    PrecisionPolicy.DIVISION_SCALE, java.math.RoundingMode.HALF_UP);  // 死分支保留
            case "MAX": return nums.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            case "MIN": return nums.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            default: return ERR;
        }
    }

    /**
     * 取匹配 A 行的目标值: 有 targetExpr → 在 (A行 field + B行 b_field + B 上下文 gvar) 求值; 否则 arow[target]。
     *
     * <p>§4.3 多 source 广播：当 token 含 {@code sources}（长度≥2）时，在放入驱动行 arow 列之前，
     * 先按 {@code sources[1..]} 更粗 source 的 match 命中对应行，把其数值列低优先级注入
     * aFieldValues；驱动行 arow 的列高优先级覆盖同名项。
     * 0命中→该粗 source 列不注入（默认 0）；>1命中→标 multiSrcHitErr→返 null（→ ERR →整项塌0）。
     *
     * <p>C1 sub 透传：sub 继承外层所有 RowContext 字段（包括 componentSubtotals / quotationFields /
     * productAttributes / previousRowSubtotal），使 targetExpr 内嵌的 KSUM/component_subtotal 等
     * token 可正确取值。
     */
    private Object targetRowValue(Map<String, Object> arow, JsonNode token, RowContext ctx) {
        JsonNode te = token.path("targetExpr");
        if (te.isArray() && te.size() > 0) {
            RowContext sub = new RowContext();

            // §4.3 多 source 广播（sources 长度≥2 时触发）
            boolean multiSrcHitErr = false;
            JsonNode sourcesNode = token.path("sources");
            if (sourcesNode.isArray() && sourcesNode.size() >= 2) {
                // D3 per-field source 分桶：source componentId → {列名 → 数值}
                // 各 source 的数值列独立存桶，供 field token 带 source 时精确取值，防同名串值。
                Map<String, Map<String, Double>> bySource = new HashMap<>();

                // 步骤1: 先放更粗 source 的列（低优先级，进 fieldValues 也进 bySource 桶）
                for (int si = 1; si < sourcesNode.size(); si++) {
                    JsonNode s = sourcesNode.get(si);
                    String coarseSource = s.path("source").asText("");
                    JsonNode coarseMatchNode = s.path("match");
                    List<Map<String, Object>> coarseRows =
                        ctx.crossTabRows.getOrDefault(coarseSource, List.of());

                    // 用 s.match 命中：coarseRow[p.a] 对应粗 source 列，arow[p.b] 对应驱动行公共行键
                    List<Map<String, Object>> coarseHits = new ArrayList<>();
                    for (Map<String, Object> cr : coarseRows) {
                        boolean ok = true;
                        if (coarseMatchNode.isArray()) {
                            for (JsonNode p : coarseMatchNode) {
                                Object av = cr.get(p.path("a").asText(""));
                                Object bv = arow.get(p.path("b").asText(""));
                                if (isBlank(av) || isBlank(bv) || !valEquals(av, bv)) { ok = false; break; }
                            }
                        }
                        if (ok) coarseHits.add(cr);
                    }

                    if (coarseHits.size() == 0) {
                        // 0 命中 → 该粗 source 的列不注入（其 field 取值缺省 → 项=0）
                        continue;
                    }
                    if (coarseHits.size() > 1) {
                        // >1 命中 → 粗 source 行键非唯一 → 标记错误
                        multiSrcHitErr = true;
                        continue;
                    }
                    // 恰好 1 命中 → 把该行所有数值列并入 sub.fieldValues（低优先级）并填充 bySource 桶
                    Map<String, Double> coarseBucket = bySource.computeIfAbsent(coarseSource, k -> new HashMap<>());
                    for (Map.Entry<String, Object> e : coarseHits.get(0).entrySet()) {
                        Double n = toNumber(e.getValue());
                        if (n != null) {
                            sub.fieldValues.put(e.getKey(), n);
                            coarseBucket.put(e.getKey(), n);
                        }
                    }
                }
                // 步骤2: 放驱动行 arow 的列（高优先级，覆盖粗 source 同名列的 fieldValues，
                //        但 bySource[sources[0]] 独立保存驱动行真实值，不被覆盖）
                String primarySource = sourcesNode.get(0).path("source").asText("");
                Map<String, Double> primaryBucket = bySource.computeIfAbsent(primarySource, k -> new HashMap<>());
                for (Map.Entry<String, Object> e : arow.entrySet()) {
                    Double n = toNumber(e.getValue());
                    if (n != null) {
                        sub.fieldValues.put(e.getKey(), n);   // 高优先级覆盖（现有逻辑不变）
                        primaryBucket.put(e.getKey(), n);     // D3: 驱动行进 primary source 桶
                    }
                }
                // D3: 把 bySource 分桶注入 sub，供 appendToken field 分支按 source 取值
                sub.bySource = bySource;
            } else {
                // N=1 退化路径（无 sources / 纯 KSUM 容器）: 零变化旧逻辑
                for (Map.Entry<String, Object> e : arow.entrySet()) {
                    Double n = toNumber(e.getValue());
                    if (n != null) sub.fieldValues.put(e.getKey(), n);
                }
            }

            // 多 source >1 命中错误 → 返 ERR（→ appendToken 注入非法表达式 → 整外层塌 0）
            if (multiSrcHitErr) return ERR;

            // C1: 透传所有上下文字段（使 targetExpr 内嵌 KSUM/component_subtotal/quotation_field 等可求值）
            // 对齐前端 mergedRow = {...hostRow, ...ar}: 宿主行打底 + 驱动行 arow 覆盖同名列。
            // KSUM 子 token 带非空 match 时，match 的 b 键从 sub.currentRowRaw 取值；
            // 若只传外层宿主行（旧逻辑），则 b 键取宿主行而非驱动行 arow，与前端行为分叉。
            java.util.Map<String, Object> mergedCurrentRow =
                ctx.currentRowRaw != null
                    ? new java.util.HashMap<>(ctx.currentRowRaw)
                    : new java.util.HashMap<>();
            mergedCurrentRow.putAll(arow);   // arow 高优先，覆盖同名宿主列（与前端 {...hostRow, ...ar} 一致）
            sub.currentRowRaw = mergedCurrentRow;
            // repair-0803：宿主已算字段值原样透传（不并进 sub.fieldValues —— 那里装的是源页签行的列，
            // 混入会让 targetExpr 内的 field token 串到宿主列上）。供 b_field 键缺失时回落。
            sub.hostFieldValues = ctx.hostFieldValues;
            sub.basicDataValues = ctx.basicDataValues;
            sub.crossTabRows = ctx.crossTabRows;
            sub.componentSubtotals = ctx.componentSubtotals;   // C1 新增
            sub.quotationFields = ctx.quotationFields;          // C1 新增
            sub.productAttributes = ctx.productAttributes;      // C1 新增
            sub.previousRowSubtotal = ctx.previousRowSubtotal;  // C1 新增（inner 白名单已禁该 token）
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
        // 零破坏：旧 10 参调用 → 不过滤（deleted=null，rowKeyFieldNames=null）
        return calculate(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
            componentSubtotals, quotationFields, productAttributes, crossTabRows, null, null);
    }

    /**
     * calculate 新重载（带墓碑过滤）：在 10 参重载基础上增加 deleted + rowKeyFieldNames 参数，
     * 供报价侧漏斗按永久删除行双命中剔除（head 不变量：唯一化后过滤，fps 用完整 baseRows 计算）。
     *
     * <p>零破坏：旧 10 参签名 delegate 到此，传 {@code null, null} = 不过滤；核价侧与所有既有
     * 调用方行为完全不变。
     *
     * @param deleted          墓碑列表（null 或空 → 不过滤，全行保留）
     * @param rowKeyFieldNames rowKeyFields 节点解出的字段名列表（与 deleted 配套；null 则不过滤）
     */
    public ArrayNode calculate(JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
                               JsonNode rowKeyFields,
                               JsonNode baseRows, JsonNode editRows,
                               Map<String, Double> componentSubtotals,
                               Map<String, Double> quotationFields,
                               Map<String, Double> productAttributes,
                               Map<String, List<Map<String, Object>>> crossTabRows,
                               List<DeletedRowKeys.Tombstone> deleted,
                               List<String> rowKeyFieldNames) {
        return calculate(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
            componentSubtotals, quotationFields, productAttributes, crossTabRows, deleted, rowKeyFieldNames,
            null, null);
    }

    /**
     * B1：calculate 带 per-call computeRows 复用缓存重载（PASS2 入口）。{@code cache}/{@code cacheKey}
     * 非空且该 tab memo-eligible（不读 componentSubtotals/crossTabRows）时，同次 assemble 内
     * PASS1/PASS2 共用一份 computeRows 结果。旧 12 参签名 delegate（cache=null）→ 行为完全不变。
     */
    public ArrayNode calculate(JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
                               JsonNode rowKeyFields,
                               JsonNode baseRows, JsonNode editRows,
                               Map<String, Double> componentSubtotals,
                               Map<String, Double> quotationFields,
                               Map<String, Double> productAttributes,
                               Map<String, List<Map<String, Object>>> crossTabRows,
                               List<DeletedRowKeys.Tombstone> deleted,
                               List<String> rowKeyFieldNames,
                               RowCache cache, String cacheKey) {
        ArrayNode out = MAPPER.createArrayNode();
        List<RowResult> rows = computeRowsCached(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
            componentSubtotals, quotationFields, productAttributes, crossTabRows, deleted, rowKeyFieldNames,
            cache, cacheKey);
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
        // task-0801 B2：BigDecimal 精确累加，不再 setScale(4) 截断（呈现边界由 B5 统一规整）。
        return PrecisionPolicy.sum(byCol.values());
    }

    /** 逐列求和：每个 is_subtotal 列 → 该列各行结果之和。Plan 2-核心：多小计列。零破坏：旧签名不过滤。 */
    public Map<String, BigDecimal> computeTabSubtotalsByColumn(
            JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
            JsonNode rowKeyFields, JsonNode baseRows, JsonNode editRows,
            Map<String, Double> componentSubtotals) {
        return computeTabSubtotalsByColumn(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
            componentSubtotals, null, null);
    }

    /**
     * 逐列求和（带墓碑过滤新重载）：在旧签名基础上增加 deleted + rowKeyFieldNames，
     * 报价侧 PASS 1 小计也需要反映永久删除的行（过滤后行数才是正确小计基数）。
     *
     * @param deleted          墓碑列表（null 或空 → 不过滤，旧路径零变化）
     * @param rowKeyFieldNames rowKeyFields 节点解出的字段名列表（供 rowFingerprint 提取 driverRow 键值）
     */
    public Map<String, BigDecimal> computeTabSubtotalsByColumn(
            JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
            JsonNode rowKeyFields, JsonNode baseRows, JsonNode editRows,
            Map<String, Double> componentSubtotals,
            List<DeletedRowKeys.Tombstone> deleted, List<String> rowKeyFieldNames) {
        return computeTabSubtotalsByColumn(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
            componentSubtotals, deleted, rowKeyFieldNames, null, null);
    }

    /**
     * B1：computeTabSubtotalsByColumn 带 per-call computeRows 复用缓存重载（PASS1 入口）。
     * 旧 9 参签名 delegate（cache=null）→ 行为完全不变。
     */
    public Map<String, BigDecimal> computeTabSubtotalsByColumn(
            JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
            JsonNode rowKeyFields, JsonNode baseRows, JsonNode editRows,
            Map<String, Double> componentSubtotals,
            List<DeletedRowKeys.Tombstone> deleted, List<String> rowKeyFieldNames,
            RowCache cache, String cacheKey) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        List<String> subtotalFields = findSubtotalFieldNames(fields);
        if (subtotalFields.isEmpty()) return out;
        List<RowResult> rows = computeRowsCached(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
            componentSubtotals, new HashMap<>(), new HashMap<>(), Map.of(), deleted, rowKeyFieldNames,
            cache, cacheKey);
        for (String sf : subtotalFields) {
            // task-0801 B2：累加过程改 BigDecimal 精确求和（原 double += 几十行累加会有中间误差），
            // 不再 setScale(4) 截断；仅在写回 out（BigDecimal 结果 map，供 componentSubtotals
            // 落值/公式引用）时保留全精度，呈现边界由 B5 统一规整。
            BigDecimal sum = BigDecimal.ZERO;
            for (RowResult rr : rows) {
                // FORMULA 字段优先取 formulaValues；INPUT_NUMBER/FIXED_VALUE/BASIC_DATA 等
                // 输入型字段的值在 fieldValues 里，formulaValues 中无此键，回退读 fieldValues。
                Double v = rr.formulaValues.containsKey(sf)
                    ? rr.formulaValues.get(sf)
                    : rr.fieldValues.get(sf);
                if (v != null) sum = sum.add(PrecisionPolicy.of(v.doubleValue()));
            }
            out.put(sf, sum);
        }
        return out;
    }

    private static class RowResult {
        final String rowKey;
        final Map<String, Double> formulaValues;
        /** 非 FORMULA 字段（INPUT_NUMBER/FIXED_VALUE/BASIC_DATA/DATA_SOURCE 等）的收集值，
         *  用于 computeTabSubtotalsByColumn 对输入型 is_subtotal 列的累加。 */
        final Map<String, Double> fieldValues;
        RowResult(String rowKey, Map<String, Double> formulaValues, Map<String, Double> fieldValues) {
            this.rowKey = rowKey;
            this.formulaValues = formulaValues;
            this.fieldValues = fieldValues != null ? fieldValues : Map.of();
        }
    }

    // ======================================================================
    // B1: per-call computeRows 复用缓存（仅 assembleTabsWithFormulaResults 单次调用内）
    // ======================================================================

    /**
     * per-call computeRows 复用缓存：仅在一次 {@code assembleTabsWithFormulaResults} 调用内，
     * 为「不读 componentSubtotals/crossTabRows」的 tab 复用 computeRows 结果。
     * <b>局部对象、单线程使用 → 线程安全</b>（不放进程级缓存，守 expand 层非并发约束）。
     *
     * <p><b>复用正确性前提</b>：同一 {@code cacheKey}（=componentId）的多次调用，除
     * componentSubtotals/crossTabRows 外的入参必须恒定。assemble 内成立：PASS1/PASS2 对同一
     * 组件传相同 fields/baseRows/editRows/deleted/rkfNames，且 quotationFields/productAttributes 恒空。
     */
    public static final class RowCache {
        /** cacheKey(=componentId) → 该 tab 在本次 assemble 内的 computeRows 结果（仅 eligible tab 存）。 */
        private final Map<String, List<RowResult>> rows = new HashMap<>();
        /** cacheKey → eligibility（首次 isMemoEligible 结果缓存，避免重复扫公式树）。 */
        private final Map<String, Boolean> eligible = new HashMap<>();
    }

    /** 新建一个 per-call 复用缓存（供 CardSnapshotService.assembleTabsWithFormulaResults 在调用起点创建）。 */
    public RowCache newRowCache() {
        return new RowCache();
    }

    /**
     * tab 是否 memo-eligible：其 computeRows 结果<b>不依赖</b> componentSubtotals / crossTabRows，
     * 即同次 assemble 内 PASS1/PASS2（这两者是仅有的变化入参）的结果恒等可复用。
     * <p>判据：公式 / 赋值 / 条件分支里<b>不出现</b>以下任一会读这两个 map 的 token：
     * <ul>
     *   <li>{@code component_subtotal} —— 读 componentSubtotals；
     *   <li>{@code previous_row_subtotal} —— 行 0 fallback 分支按 {@code fallback_component_code}
     *       读 {@code componentSubtotals.get(fb)}（评审发现的边界路径，必须计入）；
     *   <li>{@code cross_tab_ref} —— 读 crossTabRows（含其 targetExpr 内嵌求值的 C1 子上下文透传）。
     * </ul>
     * 保守：疑似即判不可复用（只少加速、绝不错值）。
     */
    private boolean isMemoEligible(JsonNode formulas, JsonNode formulaAssignments) {
        return !containsTokenType(formulas, "component_subtotal", "cross_tab_ref", "previous_row_subtotal")
            && !containsTokenType(formulaAssignments, "component_subtotal", "cross_tab_ref", "previous_row_subtotal");
    }

    /** 递归扫 JSON 树：是否存在任一对象节点 {@code "type"} 属于给定集合（覆盖嵌套表达式/条件/赋值）。 */
    private boolean containsTokenType(JsonNode node, String... types) {
        if (node == null || node.isNull()) return false;
        if (node.isObject()) {
            JsonNode t = node.get("type");
            if (t != null && t.isTextual()) {
                String tv = t.asText();
                for (String ty : types) if (ty.equals(tv)) return true;
            }
            for (JsonNode child : node) {
                if (containsTokenType(child, types)) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsTokenType(child, types)) return true;
            }
        }
        return false;
    }

    /**
     * computeRows 的缓存包装：cache/cacheKey 为空或 tab 不 eligible → 直接实算（行为不变）；
     * eligible → 同 cacheKey 首次实算后存缓存，后续命中复用同一份只读 {@code List<RowResult>}
     * （构造后不被任何调用方 mutate，PASS1/PASS2 均只读迭代）。
     */
    private List<RowResult> computeRowsCached(JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
                                              JsonNode rowKeyFields,
                                              JsonNode baseRows, JsonNode editRows,
                                              Map<String, Double> componentSubtotals,
                                              Map<String, Double> quotationFields,
                                              Map<String, Double> productAttributes,
                                              Map<String, List<Map<String, Object>>> crossTabRows,
                                              List<DeletedRowKeys.Tombstone> deleted,
                                              List<String> rowKeyFieldNames,
                                              RowCache cache, String cacheKey) {
        if (cache == null || cacheKey == null) {
            return computeRows(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
                componentSubtotals, quotationFields, productAttributes, crossTabRows, deleted, rowKeyFieldNames);
        }
        Boolean elig = cache.eligible.get(cacheKey);
        if (elig == null) {
            elig = isMemoEligible(formulas, formulaAssignments);
            cache.eligible.put(cacheKey, elig);
        }
        if (!elig) {
            return computeRows(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
                componentSubtotals, quotationFields, productAttributes, crossTabRows, deleted, rowKeyFieldNames);
        }
        // eligible ⟹ 结果不依赖 componentSubtotals/crossTabRows，故 PASS1(空 crossTab/累加中 subtotal)
        // 与 PASS2(真实 crossTab/全量 subtotal) 即使传值不同，也共享同一 cacheKey 的结果，安全。
        List<RowResult> cached = cache.rows.get(cacheKey);
        if (cached != null) return cached;
        List<RowResult> fresh = computeRows(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
            componentSubtotals, quotationFields, productAttributes, crossTabRows, deleted, rowKeyFieldNames);
        cache.rows.put(cacheKey, fresh);
        return fresh;
    }

    /**
     * 逐行求值核心（calculate + computeTabSubtotal 共用）。AP-51：行数权威 = baseRows（driver 展开结果）。
     * 零破坏：旧 10 参签名 delegate 到新 12 参重载，传 null,null = 不过滤。
     */
    private List<RowResult> computeRows(JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
                                        JsonNode rowKeyFields,
                                        JsonNode baseRows, JsonNode editRows,
                                        Map<String, Double> componentSubtotals,
                                        Map<String, Double> quotationFields,
                                        Map<String, Double> productAttributes,
                                        Map<String, List<Map<String, Object>>> crossTabRows) {
        return computeRows(fields, formulas, formulaAssignments, rowKeyFields, baseRows, editRows,
            componentSubtotals, quotationFields, productAttributes, crossTabRows, null, null);
    }

    /**
     * 逐行求值核心（带墓碑过滤新重载）。AP-51：行数权威 = baseRows（driver 展开结果）。
     *
     * <p><b>头号不变量（AP-54）</b>：effKey 永远基于「完整」driver 展开集唯一化；过滤在唯一化之后，
     * 按墓碑双命中剔除整行。迭代下标 idx 仍走完整集（命中则 continue，绝不重排）。
     * fps 用同一份完整 baseRows 的 driverRow 计算，与 keepMask 传入的 effKeys 等长。
     *
     * @param deleted          墓碑列表（null 或空 → 不过滤）
     * @param rowKeyFieldNames rowKeyFields 节点解出的字段名列表（供 rowFingerprint 提取 driverRow 键值）
     */
    private List<RowResult> computeRows(JsonNode fields, JsonNode formulas, JsonNode formulaAssignments,
                                        JsonNode rowKeyFields,
                                        JsonNode baseRows, JsonNode editRows,
                                        Map<String, Double> componentSubtotals,
                                        Map<String, Double> quotationFields,
                                        Map<String, Double> productAttributes,
                                        Map<String, List<Map<String, Object>>> crossTabRows,
                                        List<DeletedRowKeys.Tombstone> deleted,
                                        List<String> rowKeyFieldNames) {
        List<RowResult> out = new ArrayList<>();
        if (baseRows == null || !baseRows.isArray()) return out;

        // editRows 按 rowKey 索引（AP-54：业务键对齐，不用下标）
        Map<String, JsonNode> editByKey = indexEditRows(editRows);

        // 行键唯一化预扫(撞键→#序号)：先算全部 raw effKey 再消歧，保证 editRows 逐行绑定(修末值×行数塌缩)。
        // 与前端 buildUniqueRowKeys / 快照 / CardSnapshotService 同序同口径。
        //
        // task-0721 B10（树上行键含节点维度）：树上同一料号可能出现在多个节点（DAG 重复子件，
        // 如现网实例 3110520789 同挂 2120011658/2120011659 两个父件下），若行键仅按
        // rowKeyFields 计算内容值，两个节点的同料号行会撞出相同 rawKey → uniquifyRowKeys 只能靠
        // "#序号"消歧，序号又依赖数组顺序，一旦顺序因刷新/位置变化就会错位删/改错节点的行。
        // 故行键 = __nodeId ⊕ rowKeyFields 计算值，节点维度天然消歧，#序号退化为"真撞键"兜底。
        //
        // 生效条件仅限报价侧树页签：baseRow 携带 __nodeId（BomTreeRenderService.treeRowNode 写入，
        // 仅树页签有此列）且 deleted != null（报价侧信号——buildCardValues 传真实墓碑 Map，即使
        // 空列表也非 null；核价侧 buildCostingCardValues 四参入口固定传 delByComp=null，spec §3.7
        // 隔离）。核价侧 baseRows 同样携带 __nodeId（同一渲染引擎），但 deleted==null 时本分支不生效
        // → effKey 计算与改造前逐位相同 → AC-10 核价侧零回归门禁不受影响。
        List<String> rawKeys = buildRawRowKeys(rowKeyFields, fields, baseRows, deleted);
        List<String> effKeys = uniquifyRowKeys(rawKeys);

        // driver 默认行永久删除：先唯一化(上方)，再按墓碑双命中过滤；fps/nodeIds 用完整 baseRows 计算
        // （守头号不变量）。keep==null 表示不过滤（deleted 为 null/空 → 核价侧及所有旧调用方零影响）。
        // repair-0727 B2：nodeIds 逐行从 baseRow 顶层 __nodeId 取值（不在 driverRow 里）。
        boolean[] keep = null;
        if (deleted != null && !deleted.isEmpty()) {
            List<String> fps = new ArrayList<>(baseRows.size());
            List<String> nodeIds = new ArrayList<>(baseRows.size());
            for (JsonNode br : baseRows) {
                fps.add(DeletedRowKeys.rowFingerprint(rowKeyFieldNames, br.path("driverRow")));
                JsonNode nid = br.get("__nodeId");
                nodeIds.add((nid != null && !nid.isNull()) ? nid.asText(null) : null);
            }
            keep = DeletedRowKeys.keepMask(effKeys, fps, nodeIds, deleted);
        }

        // 公式字段拓扑序（依赖先算），与前端 computeAllFormulas 一致
        List<FormulaField> formulaFields = collectFormulaFields(fields, formulas, formulaAssignments);
        List<String> order = topoOrder(formulaFields);

        // ── task-0803 路由 ───────────────────────────────────────────────────
        // 只有「行集是树」**且**「公式真用了父子 token」才走单元格拓扑求值。
        // 任一不满足 → 下方原逐行路径**一字不动**（零回归门禁）。
        if (com.cpq.quotation.service.formula.TreeRelations.isTreeRows(baseRows)
                && usesTreeTokens(formulaFields)) {
            return computeRowsCellTopo(fields, baseRows, effKeys, keep, editByKey, formulaFields,
                order, componentSubtotals, quotationFields, productAttributes, crossTabRows);
        }

        Map<String, Double> prevRowValues = null;  // Plan 2b：上一行全量公式值（按字段名）
        int idx = 0;
        for (JsonNode baseRow : baseRows) {
            // driver 默认行永久删除：idx 仍随完整集递增（effKeys.get(idx) 对齐完整集），命中则 continue（不重排）
            if (keep != null && !keep[idx]) { idx++; continue; }

            String effKey = effKeys.get(idx);
            JsonNode basicDataValues = baseRow.path("basicDataValues");

            // task-0803：上下文构建抽成 buildRowEvalCtx，与单元格拓扑路径共用（防两条路径逻辑漂移）
            RowEvalCtx re = buildRowEvalCtx(fields, baseRow, effKey, editByKey,
                componentSubtotals, quotationFields, productAttributes, crossTabRows);
            RowContext ctx = re.ctx();
            Map<String, Double> fieldValues = re.fieldValues();

            // 按拓扑序求值，结果回填 fieldValues 供下游公式引用
            Map<String, Double> results = new LinkedHashMap<>();
            for (String name : order) {
                FormulaField ff = findByName(formulaFields, name);
                if (ff == null) continue;
                // Plan 2b：previous_row_subtotal = 上一行本列值；无则 null → token 走 fallback。
                ctx.previousRowSubtotal = (prevRowValues == null) ? null : prevRowValues.get(name);
                // Plan 3a：条件字段先按规则选表达式。
                JsonNode expr = ff.isConditional() ? selectConditionalExpr(ff, ctx, fields, basicDataValues) : ff.expression;
                double val = expr != null ? evaluateExpression(expr, ctx).doubleValue() : 0.0;
                results.put(name, val);
                ctx.fieldValues.put(name, val);
            }

            out.add(new RowResult(effKey, results, fieldValues));

            // Plan 2b：本行全量公式值传下行，各列下一行按本列取 prev。
            prevRowValues = results;
            idx++;
        }
        return out;
    }

    // ======================================================================
    // task-0803 — 单行上下文构建（两条求值路径共用）
    // ======================================================================

    /**
     * task-0803：BOM 树页签的单元格级拓扑求值。
     *
     * <p>与原逐行路径的区别只在<b>求值顺序</b>：行上下文构建、条件公式选表达式、结果回填
     * 全部复用同一套代码（{@link #buildRowEvalCtx} / {@link #selectConditionalExpr}）。
     *
     * <p><b>行下标口径</b>：全程按<b>完整</b> {@code baseRows} 下标（含被墓碑过滤的行），
     * 与 {@code effKeys} 对齐；只在最后产出 {@link RowResult} 时按 {@code keep} 过滤。
     * 这是 AP-54「渲染用过滤子集、写回用原集合」的同款纪律 —— 树关系必须建在完整下标上，
     * 否则父子边全错位。
     *
     * <p><b>PREV 不支持</b>：BOM 页签禁用 {@code previous_row_subtotal}（需求 §4.3.7，
     * 树上「上一行」语义模糊），故此路径恒置 {@code previousRowSubtotal = null}。
     */
    private List<RowResult> computeRowsCellTopo(JsonNode fields, JsonNode baseRows,
            List<String> effKeys, boolean[] keep, Map<String, JsonNode> editByKey,
            List<FormulaField> formulaFields, List<String> order,
            Map<String, Double> componentSubtotals, Map<String, Double> quotationFields,
            Map<String, Double> productAttributes,
            Map<String, List<Map<String, Object>>> crossTabRows) {

        int n = baseRows.size();
        int cols = order.size();

        // 1. 全量建行上下文（含被墓碑过滤的行，保持下标一一对应）
        List<RowContext> ctxs = new ArrayList<>(n);
        List<Map<String, Object>> resolvedRaw = new ArrayList<>(n);
        List<Map<String, Double>> fieldValuesByRow = new ArrayList<>(n);
        List<JsonNode> bdvByRow = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            RowEvalCtx re = buildRowEvalCtx(fields, baseRows.get(i), effKeys.get(i), editByKey,
                componentSubtotals, quotationFields, productAttributes, crossTabRows);
            ctxs.add(re.ctx());
            fieldValuesByRow.add(re.fieldValues());
            resolvedRaw.add(re.ctx().currentRowRaw);
            bdvByRow.add(re.basicDataValues());
        }

        // 2. 树关系：被 keep 过滤掉的行 = 墓碑，既不作父也不作子
        java.util.Set<String> deadNodeIds = new java.util.HashSet<>();
        if (keep != null) {
            for (int i = 0; i < n; i++) {
                if (keep[i]) continue;
                JsonNode nid = baseRows.get(i).get("__nodeId");
                if (nid != null && !nid.isNull() && !nid.asText("").isEmpty()) {
                    deadNodeIds.add(nid.asText());
                }
            }
        }
        com.cpq.quotation.service.formula.TreeRelations relations =
            com.cpq.quotation.service.formula.TreeRelations.of(baseRows, deadNodeIds);

        // 3. 树上下文回填（rowContexts 随求值逐格填充，故 PGET/C* 读到的一定是拓扑序保证已算好的）
        java.util.Set<String> formulaCols = new java.util.LinkedHashSet<>(order);
        TreeEvalContext tree = new TreeEvalContext(relations, ctxs, resolvedRaw, formulaCols);
        for (int i = 0; i < n; i++) {
            ctxs.get(i).tree = tree;
            ctxs.get(i).rowIndex = i;
        }

        // 4. 建单元格依赖图
        //    行内边用 buildFormulaDeps 的**精确**列依赖，不能用「按 order 串成链」的偷懒做法 ——
        //    链边会在双向混用时造出假环（反例：R.y=CSUM(C.x) + C.w=PGET(R.z)，
        //    若 y 早于 z、w 早于 x，链边把它们连成环，而精确图不会）。
        Map<String, Integer> colIdx = new HashMap<>();
        for (int c = 0; c < cols; c++) colIdx.put(order.get(c), c);
        Map<String, List<String>> intraDeps = buildFormulaDeps(formulaFields, formulaCols);

        com.cpq.quotation.service.formula.CellGraph g =
            new com.cpq.quotation.service.formula.CellGraph(n, cols);
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < cols; c++) {
                String col = order.get(c);
                for (String d : intraDeps.getOrDefault(col, List.of())) {
                    Integer dc = colIdx.get(d);
                    if (dc != null) g.addEdge(r, dc, r, c);
                }
                for (TreeDep td : treeDepsOfField(findByName(formulaFields, col))) {
                    if ("PARENT".equals(td.dir())) {
                        int p = relations.parentOf(r);
                        if (p < 0) continue;                       // 根行：边不成立（PGET 返 0）
                        for (String rc : td.cols()) {
                            Integer dc = colIdx.get(rc);
                            if (dc != null) g.addEdge(p, dc, r, c);
                        }
                    } else if ("CHILD".equals(td.dir())) {
                        for (int kid : relations.childrenOf(r)) {
                            for (String rc : td.cols()) {
                                Integer dc = colIdx.get(rc);
                                if (dc != null) g.addEdge(kid, dc, r, c);
                            }
                        }
                    }
                }
            }
        }

        // 5. 按 cell 拓扑序求值
        com.cpq.quotation.service.formula.CellGraph.Result topo = g.topoOrder();
        List<Map<String, Double>> resultsByRow = new ArrayList<>(n);
        for (int i = 0; i < n; i++) resultsByRow.add(new LinkedHashMap<>());

        for (com.cpq.quotation.service.formula.CellGraph.Cell cell : topo.order()) {
            String col = order.get(cell.col());
            FormulaField ff = findByName(formulaFields, col);
            if (ff == null) continue;
            RowContext ctx = ctxs.get(cell.row());
            ctx.previousRowSubtotal = null;                        // BOM 页签禁 PREV
            JsonNode expr = ff.isConditional()
                ? selectConditionalExpr(ff, ctx, fields, bdvByRow.get(cell.row()))
                : ff.expression;
            double val = expr != null ? evaluateExpression(expr, ctx).doubleValue() : 0.0;
            resultsByRow.get(cell.row()).put(col, val);
            ctx.fieldValues.put(col, val);
        }

        // 6. 环上（及其下游）cell → 0，环外照常求值（不是整页签炸）
        for (com.cpq.quotation.service.formula.CellGraph.Cell cell : topo.cycles()) {
            String col = order.get(cell.col());
            resultsByRow.get(cell.row()).put(col, 0.0);
            ctxs.get(cell.row()).fieldValues.put(col, 0.0);
        }

        // 7. 产出：只对未被墓碑过滤的行；列序按 order 保持稳定（与原路径一致）
        List<RowResult> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep != null && !keep[i]) continue;
            Map<String, Double> ordered = new LinkedHashMap<>();
            for (String col : order) {
                Double v = resultsByRow.get(i).get(col);
                if (v != null) ordered.put(col, v);
            }
            out.add(new RowResult(effKeys.get(i), ordered, fieldValuesByRow.get(i)));
        }
        return out;
    }

    /** 单行求值上下文的构建产物。 */
    private record RowEvalCtx(RowContext ctx, Map<String, Double> fieldValues, JsonNode basicDataValues) {}

    /** task-0803：一处 {@code tree_ref} 引用（方向 + 它引用的列名集合），用于建跨行依赖边。 */
    private record TreeDep(String dir, java.util.Set<String> cols) {}

    /** 扫一条表达式里的 {@code tree_ref} token。 */
    private static List<TreeDep> treeDepsOf(JsonNode expr) {
        List<TreeDep> out = new ArrayList<>();
        if (expr == null || !expr.isArray()) return out;
        for (JsonNode tk : expr) {
            if ("tree_ref".equals(tk.path("type").asText(""))) {
                out.add(new TreeDep(tk.path("dir").asText(""), collectFieldNames(tk.path("targetExpr"))));
            }
        }
        return out;
    }

    /** 扫一个公式字段（含条件公式的每条规则与默认分支）里的全部 {@code tree_ref}。 */
    private static List<TreeDep> treeDepsOfField(FormulaField ff) {
        List<TreeDep> out = new ArrayList<>();
        if (ff == null) return out;
        if (ff.isConditional()) {
            if (ff.rules != null) for (CondRule r : ff.rules) out.addAll(treeDepsOf(r.expression));
            out.addAll(treeDepsOf(ff.defaultExpression));
        } else {
            out.addAll(treeDepsOf(ff.expression));
        }
        return out;
    }

    /** 该表达式是否含 {@code tree_attr}（不产生依赖边，但需要树上下文才能求值）。 */
    private static boolean hasTreeAttr(JsonNode expr) {
        if (expr == null || !expr.isArray()) return false;
        for (JsonNode tk : expr) {
            if ("tree_attr".equals(tk.path("type").asText(""))) return true;
        }
        return false;
    }

    /**
     * 该页签的公式是否真的用到了父子 token。
     *
     * <p>🔒 <b>路由判据的一半</b>：只有「行集是树」<b>且</b>「公式真用了父子 token」才走单元格拓扑；
     * 任一不满足 → 原逐行路径<b>一字不动</b>（零回归门禁）。
     */
    private static boolean usesTreeTokens(List<FormulaField> ffs) {
        if (ffs == null) return false;
        for (FormulaField ff : ffs) {
            if (!treeDepsOfField(ff).isEmpty()) return true;
            if (ff.isConditional()) {
                if (ff.rules != null) for (CondRule r : ff.rules) if (hasTreeAttr(r.expression)) return true;
                if (hasTreeAttr(ff.defaultExpression)) return true;
            } else if (hasTreeAttr(ff.expression)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构建某一行的 {@link RowContext}。
     *
     * <p>task-0803 从 {@code computeRows} 循环体抽出，供「逐行顺序求值」（原路径）与
     * 「单元格拓扑求值」（BOM 树路径）共用 —— 两条路径若各建一份，
     * 单位换算时机、default_source 回填这类微妙顺序早晚会漂移。
     */
    private RowEvalCtx buildRowEvalCtx(JsonNode fields, JsonNode baseRow, String effKey,
                                       Map<String, JsonNode> editByKey,
                                       Map<String, Double> componentSubtotals,
                                       Map<String, Double> quotationFields,
                                       Map<String, Double> productAttributes,
                                       Map<String, List<Map<String, Object>>> crossTabRows) {
        JsonNode driverRow = baseRow.path("driverRow");
        JsonNode basicDataValues = baseRow.path("basicDataValues");

        JsonNode editValues = (effKey != null && editByKey.containsKey(effKey))
            ? editByKey.get(effKey).path("values") : null;

        // mergedRow = driverRow + editRows（编辑覆盖）
        Map<String, JsonNode> mergedRow = mergeRow(driverRow, editValues);

        // Layer 2: 字段值收集（AP-37 每 field_type）
        Map<String, Double> fieldValues = collectFieldValues(fields, mergedRow, basicDataValues);

        RowContext ctx = new RowContext();
        ctx.fieldValues = fieldValues;
        // repair-0803：顶层求值时宿主通道 == fieldValues（同一引用）。逐个公式算完即时写入
        // fieldValues，b_field 因此能取到刚算好的宿主公式值；targetRowValue 会把它透传进子上下文。
        ctx.hostFieldValues = fieldValues;
        ctx.componentSubtotals = componentSubtotals != null ? componentSubtotals : new HashMap<>();
        ctx.quotationFields = quotationFields != null ? quotationFields : new HashMap<>();
        ctx.productAttributes = productAttributes != null ? productAttributes : new HashMap<>();
        ctx.basicDataValues = toBasicDataMap(basicDataValues);
        // cross_tab_ref（Task 1.3）：兄弟组件已算行 + 本行原始合并值（含文本，供匹配键 b 取值）
        ctx.crossTabRows = crossTabRows != null ? crossTabRows : Map.of();
        ctx.currentRowRaw = toRawRowMap(mergedRow);
        fillInputDefaultSourceByFieldName(fields, basicDataValues, ctx.currentRowRaw);

        // 单位换算（修正时机，物化点3）：必须在 collectFieldValues + fillInputDefaultSourceByFieldName 之后做——
        // driver / data-source(default_source $view) 列的值此刻才解析进 fieldValues / currentRowRaw，
        // 顶部对 mergedRow 换算会漏掉它们。用同行已解析单位换算 fieldValues[C] 与 currentRowRaw[C]。
        com.cpq.engine.unit.UnitConversion.convertResolvedRow(fields, fieldValues, ctx.currentRowRaw);

        return new RowEvalCtx(ctx, fieldValues, basicDataValues);
    }

    // ======================================================================
    // rowKey
    // ======================================================================

    /** rowKey = 按 rowKeyFields 从 driverRow 取值用 {@code ||} 拼接；rowKeyFields 空/null → null。
     * 兼容旧调用（CardEffectiveRows 等只有 driverRow，无 fields/basicDataValues 上下文）。*/
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

    /**
     * rowKey（字段感知版）：修复 driverRow 键为视图列别名（如 {@code _料件}）而 rowKeyFields
     * 存字段名（如 {@code 料件}）时直接读 driverRow 取不到值的 bug。
     *
     * <p><b>解析策略（优先级从高到低）</b>：
     * <ol>
     *   <li>直接读 {@code driverRow[fieldName]} — 兼容旧场景（字段名 == 视图列名，如 material_no）</li>
     *   <li>通过 {@link #resolveRowByFieldName} 按字段 defaultSource 解析（INPUT 型字段名 ≠ 视图列别名时）</li>
     * </ol>
     * 全部 key 段解析为空 → 返回 {@code null}（调用方按行号兜底），不返回全分隔符串。
     *
     * @param rowKeyFields    rowKeyFields JSON 数组（字段名列表）
     * @param fields          组件字段定义数组（含 fieldType / defaultSource，供 resolveRowByFieldName 用）
     * @param driverRow       driver 展开的原始行（键可能为视图列别名）
     * @param basicDataValues 该行预查询好的基础数据值（供 default_source 解析）
     */
    public String computeRowKey(JsonNode rowKeyFields, JsonNode fields,
                                JsonNode driverRow, JsonNode basicDataValues) {
        if (rowKeyFields == null || !rowKeyFields.isArray() || rowKeyFields.size() == 0) return null;
        if (rowKeyFields.size() == 1 && "__seq_no__".equals(rowKeyFields.get(0).asText(""))) return null;

        // 懒计算：只在有字段直接读不到时才触发 resolveRowByFieldName（避免性能开销）
        Map<String, Object> resolved = null;

        List<String> parts = new ArrayList<>();
        boolean any = false;
        for (JsonNode k : rowKeyFields) {
            String fieldName = k.asText("");
            String part = "";

            // 1. 优先直接读 driverRow（字段名即为视图列名的场景，如 material_no）
            String direct = pickNonEmpty(driverRow, fieldName);
            if (direct != null) {
                part = direct;
                any = true;
            } else {
                // 2. 直接读不到 → 通过字段定义 defaultSource 解析（如 INPUT 型字段绑 $view._料件）
                if (resolved == null) {
                    resolved = resolveRowByFieldName(fields, driverRow, basicDataValues, null, null);
                }
                Object v = resolved.get(fieldName);
                if (v != null) {
                    part = v.toString();
                    if (!part.isEmpty()) any = true;
                }
            }
            parts.add(part);
        }
        // 全部 key 段为空 → null，让调用方按行号兜底，避免 "||" 假键导致行冲突
        if (!any) return null;
        return String.join("||", parts);
    }

    /**
     * repair-0727 B0：树行 raw effKey 的<b>单一口径</b>产出方法 —— 原先本逻辑内联在
     * {@code computeRows} 里，{@link CardSnapshotService#buildResolvedRows} 与
     * {@link RowDataMaterializer} 各自又抄了一份「不带 nodeId 前缀」的旧版本，三处逐渐漂移
     * （B10 只改了 computeRows 这一处）。B0 核查证实：树页签 + FORMULA 列 + 报价侧信号
     * （{@code deleted != null}）时，未对齐的两处会拿着不同的 key 去 {@code frByKey}/{@code editByKey}
     * 查表，逐行 miss，FORMULA 叶子列静默丢失（不报错，只是取不到值）——见
     * {@code EffKeyNodeIdAlignmentTest}。
     *
     * <p>三处（computeRows / buildResolvedRows / RowDataMaterializer）<b>必须</b>调用本方法产出
     * 同一份 rawKeys，禁止再各自重算。
     *
     * <p>规则（task-0721 B10 定义，本次只是抽取共用，规则本身不变）：rawKey = 按
     * {@link #computeRowKey(JsonNode, JsonNode, JsonNode, JsonNode)} 算出的内容键（空 → 行号兜底）；
     * 若 {@code deleted != null}（报价侧信号，哪怕空列表）且该行顶层携带非空 {@code __nodeId}
     * （树页签行），则以 {@code nodeId + "::" + 内容键} 作为最终 rawKey（节点维度天然消歧 DAG
     * 重复子件，避免仅靠 {@link #uniquifyRowKeys} 的 {@code #序号} 消歧受数组顺序影响错位）。
     * 核价侧固定传 {@code deleted=null} → 本分支不生效，行为与改造前逐位相同（AC-10 零回归）。
     *
     * @param rowKeyFields rowKeyFields 节点（行键字段名数组）
     * @param fields       组件字段定义（供 computeRowKey 字段感知解析）
     * @param baseRows     完整 driver 展开集（未过滤）
     * @param deleted      墓碑列表（null=核价侧信号；非 null 含空列表=报价侧信号）
     * @return 与 baseRows 等长、未唯一化的 rawKey 列表（调用方再喂 {@link #uniquifyRowKeys}）
     */
    public List<String> buildRawRowKeys(JsonNode rowKeyFields, JsonNode fields, JsonNode baseRows,
                                         List<DeletedRowKeys.Tombstone> deleted) {
        List<String> rawKeys = new ArrayList<>();
        if (baseRows == null || !baseRows.isArray()) return rawKeys;
        int pre = 0;
        for (JsonNode baseRow : baseRows) {
            String rk = computeRowKey(rowKeyFields, fields, baseRow.path("driverRow"), baseRow.path("basicDataValues"));
            String base = (rk != null && !rk.isEmpty()) ? rk : String.valueOf(pre);
            JsonNode nodeIdNode = baseRow.get("__nodeId");
            if (deleted != null && nodeIdNode != null && !nodeIdNode.isNull()) {
                String nodeId = nodeIdNode.asText("");
                if (!nodeId.isEmpty()) {
                    base = nodeId + "::" + base;
                }
            }
            rawKeys.add(base);
            pre++;
        }
        return rawKeys;
    }

    /**
     * 行键唯一化：同一组件内出现 ≥2 次的 rowKey 按出现序追加 {@code #<0基序号>}；
     * 出现 1 次的键保持原样（向后兼容，现有非撞键报价单 editRows 仍绑定）。
     *
     * <p>修复撞键（行键字段值为空/重复）导致 editRows 写覆盖/读串行 → resolvedRows
     * 「末值×行数」塌缩。前端 useCardSnapshots.uniquifyRowKeys 逐字节等价。
     * 序号按入参 keys 顺序（= baseRows 数组序，前后端同序）。
     */
    public static java.util.List<String> uniquifyRowKeys(java.util.List<String> keys) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (String k : keys) counts.merge(k, 1, Integer::sum);
        java.util.Map<String, Integer> running = new java.util.HashMap<>();
        java.util.List<String> out = new java.util.ArrayList<>(keys.size());
        for (String k : keys) {
            if (counts.getOrDefault(k, 0) <= 1) { out.add(k); continue; }
            int n = running.merge(k, 1, Integer::sum) - 1;
            out.add(k + "#" + n);
        }
        return out;
    }

    /**
     * 判重专用组合键（字段感知版，实例方法）：当 driverRow 键为视图列别名时，直接读 driverRow 取不到值，
     * 此重载先走 driverRow 直读，再通过 {@link #resolveRowByFieldName} 按字段 defaultSource 解析，
     * 最后回退 rowValues（手填值）。全部 key 段为空 → null（不参与判重）。
     *
     * <p>注意：旧的 3-arg static 重载保留兼容；此重载为实例方法，需通过注入的 bean 调用。
     *
     * @param rowKeyFields    rowKeyFields JSON 数组（字段名列表）
     * @param fields          组件字段定义数组（供 resolveRowByFieldName 解析 defaultSource）
     * @param driverRow       driver 展开的原始行（键可能为视图列别名）
     * @param basicDataValues 该行预查询好的基础数据值
     * @param rowValues       用户手填值（rowData），判重兜底
     */
    public String computeDedupKey(JsonNode rowKeyFields, JsonNode fields,
                                  JsonNode driverRow, JsonNode basicDataValues,
                                  JsonNode rowValues) {
        if (rowKeyFields == null || !rowKeyFields.isArray() || rowKeyFields.size() == 0) return null;
        if (rowKeyFields.size() == 1 && "__seq_no__".equals(rowKeyFields.get(0).asText(""))) return null;

        // 懒计算：只在有字段直接读不到时才触发 resolveRowByFieldName
        Map<String, Object> resolved = null;

        List<String> parts = new ArrayList<>();
        boolean any = false;
        for (JsonNode k : rowKeyFields) {
            String fieldName = k.asText("");
            String part = "";

            // 1. 优先直接读 driverRow（字段名即视图列名的场景）
            String direct = pickNonEmpty(driverRow, fieldName);
            if (direct != null) {
                part = direct;
                any = true;
            } else {
                // 2. 通过字段 defaultSource 解析（如 INPUT 型字段绑 $view._料件）
                if (resolved == null) {
                    resolved = resolveRowByFieldName(fields, driverRow, basicDataValues, null, null);
                }
                Object v = resolved.get(fieldName);
                if (v != null) {
                    String sv = v.toString();
                    if (!sv.isEmpty()) { part = sv; any = true; }
                }
            }

            // 3. 还是空 → 回退 rowValues（用户手填值，判重专用路径）
            if (part.isEmpty()) {
                String rv = pickNonEmpty(rowValues, fieldName);
                if (rv != null) { part = rv; any = true; }
            }
            parts.add(part);
        }
        if (!any) return null;
        return String.join("||", parts);
    }

    /**
     * 判重专用组合键（input-inclusive）：逐字段 driverRow 非空优先，否则取 rowValues。
     * 与 computeRowKey 区别：额外读 rowValues（手填输入字段值），仅用于行键唯一性判重，
     * 不接入 editRows / formula 路径（避开鸡生蛋）。全字段为空 → null（不参与判重）。
     */
    public static String computeDedupKey(JsonNode rowKeyFields, JsonNode driverRow, JsonNode rowValues) {
        if (rowKeyFields == null || !rowKeyFields.isArray() || rowKeyFields.size() == 0) return null;
        if (rowKeyFields.size() == 1 && "__seq_no__".equals(rowKeyFields.get(0).asText(""))) return null;
        java.util.List<String> parts = new java.util.ArrayList<>();
        boolean any = false;
        for (JsonNode k : rowKeyFields) {
            String field = k.asText("");
            String v = pickNonEmpty(driverRow, field);
            if (v == null) v = pickNonEmpty(rowValues, field);
            if (v != null) any = true;
            parts.add(v == null ? "" : v);
        }
        if (!any) return null;
        return String.join("||", parts);
    }

    /** 取 node[field] 文本，缺失/null/空串 → null。 */
    private static String pickNonEmpty(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        if (v == null || v.isMissingNode() || v.isNull()) return null;
        String s = v.asText("");
        return s.isEmpty() ? null : s;
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

            // INPUT_NUMBER：仅"键缺失(从未填/未烘焙)"才兜默认值；显式清空('')视为用户置空 → 按 0 算，
            // 不再回落 default_source / content（与前端 computeAllFormulas 对称：raw===undefined||null 才兜）。
            boolean inputAbsent = rawNode == null || rawNode.isNull();
            if (inputAbsent && "INPUT_NUMBER".equals(fieldType)) {
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

            // ── INPUT_NUMBER / INPUT_TEXT / INPUT ─────────────────────────────
            // spec 2026-08-03「键存在即权威」：editValues 明确含该字段（present 且非 null）
            // = 用户已定值 → 原样落键，**含显式清空 ""**。
            // 改动前这里是 `if (nonEmpty(v)) out.put(...)`，空值被挡掉不落键，导致
            // 「用户清空」与「从未填过」在 row_data 里物理同形 → 前端 bake 判成空格子
            // 重新烘默认值 → 用户删掉的数字重开又回来。
            // 本口径与本类 fillInputDefaultSourceByFieldName(「仅键缺失才补」) 一致。
            if ("INPUT_NUMBER".equals(type) || "INPUT_TEXT".equals(type) || "INPUT".equals(type)) {
                JsonNode editNode = (editValues != null) ? editValues.path(name) : null;
                boolean editHas = editNode != null && !editNode.isMissingNode() && !editNode.isNull();
                if (editHas) {
                    Object uv = unwrapNode(nodeToObject(editNode));
                    // 空值的物理表示统一为 ""：若落成 null，mergeRowDataInputsIntoEdits 的
                    // `!v.isNull()` 会跳过该键 → 下一轮又退化成「键缺失」被回填。
                    out.put(name, uv != null ? uv : "");
                    continue;
                }
                Object v = null;
                if (driverRow != null) v = nodeToObject(driverRow.path(name));
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
        final JsonNode expression;          // 单一模式：表达式；条件模式：null
        final String formulaName;           // 单一模式：表达式所属公式名（仅用于环定位提示）
        final List<CondRule> rules;         // 条件模式：有序规则；单一模式：null
        final JsonNode defaultExpression;   // 条件模式：默认公式表达式
        final String defaultFormulaName;    // 条件模式：默认公式名（仅用于环定位提示）
        FormulaField(String name, JsonNode expression, String formulaName) {
            this.name = name; this.expression = expression; this.formulaName = formulaName;
            this.rules = null; this.defaultExpression = null; this.defaultFormulaName = null;
        }
        FormulaField(String name, List<CondRule> rules, JsonNode defaultExpression, String defaultFormulaName) {
            this.name = name; this.expression = null; this.formulaName = null;
            this.rules = rules; this.defaultExpression = defaultExpression;
            this.defaultFormulaName = defaultFormulaName;
        }
        boolean isConditional() { return rules != null; }
    }

    /** Plan 3a：条件公式单条规则（条件树 → 命中公式表达式）。 */
    private static class CondRule {
        final JsonNode when;        // CondTree
        final JsonNode expression;  // 命中后执行的公式表达式
        final String formulaName;   // 命中公式名（仅用于环定位提示）
        final int ruleIndex;        // 在 conditional_formula.rules 中的原始序号（1-based，用于定位提示）
        CondRule(JsonNode when, JsonNode expression, String formulaName, int ruleIndex) {
            this.when = when; this.expression = expression;
            this.formulaName = formulaName; this.ruleIndex = ruleIndex;
        }
    }

    private List<FormulaField> collectFormulaFields(JsonNode fields, JsonNode formulas,
                                                    JsonNode formulaAssignments) {
        List<FormulaField> out = new ArrayList<>();
        if (fields == null || !fields.isArray()) return out;
        int fullIdx = 0;
        for (JsonNode f : fields) {
            if ("FORMULA".equals(fieldType(f))) {
                String name = fieldName(f);
                // 兼容 snake_case(component.fields) 与 camelCase(快照 structure)。
                JsonNode cf = f.has("conditional_formula") ? f.path("conditional_formula") : f.path("conditionalFormula");
                if (cf.isObject() && cf.path("rules").isArray()) {
                    // Plan 3a 条件模式（优先级最高）
                    List<CondRule> rules = new ArrayList<>();
                    int ruleIdx = 0;
                    for (JsonNode rule : cf.path("rules")) {
                        ruleIdx++;   // 1-based 原始序号：解析不到的规则被跳过也不影响后续编号
                        // BL-0098：先按 formula_id 认，再回落公式名（存量条件公式无 id）。
                        JsonNode fm = condRefFormula(formulas, rule.path("formula_id"), rule.path("formulaId"),
                                                     rule.path("formula"));
                        if (fm != null) {
                            rules.add(new CondRule(rule.path("when"), fm.path("expression"),
                                                   fm.path("name").asText(""), ruleIdx));
                        }
                    }
                    JsonNode defFm = condRefFormula(formulas, cf.path("default_formula_id"),
                                                    cf.path("defaultFormulaId"), cf.path("default"));
                    JsonNode defExpr = defFm != null ? defFm.path("expression") : null;
                    String defName = defFm != null ? defFm.path("name").asText("") : cf.path("default").asText(null);
                    out.add(new FormulaField(name, rules, defExpr, defName));
                } else {
                    ResolvedFormula rf = resolveFormula(f, name, fields, formulas, formulaAssignments, fullIdx);
                    if (rf != null) out.add(new FormulaField(name, rf.expression(), rf.name()));
                }
            }
            fullIdx++;
        }
        return out;
    }

    /** 按公式名取 expression（null/找不到 → null）。Plan 3a。 */
    private JsonNode exprOfFormula(JsonNode formulas, String name) {
        if (name == null || name.isEmpty()) return null;
        JsonNode found = findFormulaByName(formulas, name);
        return found != null ? found.path("expression") : null;
    }

    /**
     * BL-0098：解析条件公式里一处引用（规则分支 / 默认分支）指向的公式对象。
     *
     * <p>优先级：{@code formula_id}（蛇形）→ {@code formulaId}（驼峰，冻结结构用）→ 公式名。
     * <b>绑了 id 但查不到 → 返 null，不回落到名字</b> —— 与普通字段的 {@code resolveFormula}
     * 同款语义：配置漂移（公式被删）不能静默换成别的公式算。
     * 存量条件公式无 id，走名字分支，行为逐位不变。
     */
    private JsonNode condRefFormula(JsonNode formulas, JsonNode idSnake, JsonNode idCamel, JsonNode nameNode) {
        String id = idSnake != null && !idSnake.isMissingNode() && !idSnake.isNull()
            ? idSnake.asText(null) : null;
        if (id == null || id.isEmpty()) {
            id = idCamel != null && !idCamel.isMissingNode() && !idCamel.isNull()
                ? idCamel.asText(null) : null;
        }
        if (id != null && !id.isEmpty()) {
            return findFormulaById(formulas, id);   // 查不到 → null，刻意不回落名字
        }
        String name = nameNode != null ? nameNode.asText(null) : null;
        if (name == null || name.isEmpty()) return null;
        return findFormulaByName(formulas, name);
    }

    /**
     * BL-0098 测试与固化用：条件公式第 {@code ruleIndex} 条规则最终命中的公式名。
     * 解析不到返回 {@code null}。
     */
    public String resolveConditionalRuleFormulaName(JsonNode field, JsonNode formulas, int ruleIndex) {
        if (field == null || formulas == null) return null;
        JsonNode cf = field.has("conditional_formula") ? field.path("conditional_formula")
            : field.path("conditionalFormula");
        JsonNode rules = cf.path("rules");
        if (!rules.isArray() || ruleIndex < 0 || ruleIndex >= rules.size()) return null;
        JsonNode rule = rules.get(ruleIndex);
        JsonNode fm = condRefFormula(formulas, rule.path("formula_id"), rule.path("formulaId"),
                                     rule.path("formula"));
        return fm == null ? null : fm.path("name").asText(null);
    }

    /**
     * BL-0098 测试与固化用：条件公式默认分支最终命中的公式名。解析不到返回 {@code null}。
     */
    public String resolveConditionalDefaultFormulaName(JsonNode field, JsonNode formulas) {
        if (field == null || formulas == null) return null;
        JsonNode cf = field.has("conditional_formula") ? field.path("conditional_formula")
            : field.path("conditionalFormula");
        JsonNode fm = condRefFormula(formulas, cf.path("default_formula_id"), cf.path("defaultFormulaId"),
                                     cf.path("default"));
        return fm == null ? null : fm.path("name").asText(null);
    }

    /** Plan 3a：按行选条件公式表达式（首条命中即停，全不中走默认）。 */
    private JsonNode selectConditionalExpr(FormulaField ff, RowContext ctx, JsonNode fields, JsonNode basicDataValues) {
        // 列值查找：① 原始行（INPUT/编辑，保留正确类型，如字符串"车削"）
        //   ② BASIC_DATA 列按字段名解析其原始值（path 在 basicDataValues 里）
        //   ③ 回退已算 fieldValues（公式列计算结果，数字）。
        java.util.function.Function<String, Object> lookup = col -> {
            Object raw = ctx.currentRowRaw != null ? ctx.currentRowRaw.get(col) : null;
            if (raw != null) return raw;
            Object bd = basicDataRawByName(col, fields, basicDataValues);
            if (bd != null) return bd;
            return ctx.fieldValues.get(col);
        };
        for (CondRule r : ff.rules) {
            if (com.cpq.formula.CondTreeEvaluator.eval(r.when, lookup)) return r.expression;
        }
        return ff.defaultExpression;
    }

    /** 按字段名解析 BASIC_DATA 列的原始值（保留字符串）；非 BASIC_DATA/未命中 → null。Plan 3a。 */
    private Object basicDataRawByName(String col, JsonNode fields, JsonNode basicDataValues) {
        if (fields == null || !fields.isArray()) return null;
        for (JsonNode f : fields) {
            if (!"BASIC_DATA".equals(fieldType(f))) continue;
            if (!col.equals(fieldName(f))) continue;
            String path = basicDataPath(f);
            if (path == null || path.isEmpty()) return null;
            Object v = lookupBdv(basicDataValues, bnfDriverLookupKey(path));
            // 拆包 JsonNode → Java 原值（保留字符串，供条件字符串比较）。
            if (v instanceof JsonNode jn) {
                if (jn.isNull() || jn.isMissingNode()) return null;
                if (jn.isNumber()) return jn.numberValue();
                if (jn.isBoolean()) return jn.booleanValue();
                return jn.asText();
            }
            return v;
        }
        return null;
    }

    /**
     * BL-0098：解析结果带上公式的稳定 id（可能为 null —— 存量公式尚未补 id，
     * 或走位置回退命中了一条没有 id 的公式）。调用方须容忍 null，不得编造。
     */
    private record ResolvedFormula(String name, String id, JsonNode expression) {}

    /**
     * port resolveFormula: 0.field.formula_name 显式 1.formula_assignments[完整字段下标]
     * 2.exact name 3.positional。
     *
     * <p><b>注意</b>：formula_assignments 的 key 是字段在<b>完整 fields 数组</b>中的下标
     * （非 FORMULA-only 位置），与前端 {@code comp.fields.indexOf(field)} 一致。
     */
    private ResolvedFormula resolveFormula(JsonNode field, String fieldName, JsonNode fields,
                                           JsonNode formulas, JsonNode formulaAssignments, int fullFieldIndex) {
        if (formulas == null || !formulas.isArray()) return null;

        // -1. BL-0098 终态：显式 formula_id 绑定（最高优先）。
        //     绑定了但找不到 → 返 null 不 fallback ——语义与下方 formula_name 分支一致：
        //     配置漂移（公式被删）不能静默换成别的公式算，那正是 BL-0098 要根除的行为。
        //     蛇形（component/template 正本）与驼峰（API/quotation_view_structure 冻结结构）都认。
        String formulaId = field.has("formula_id") ? field.path("formula_id").asText(null)
            : field.path("formulaId").asText(null);
        if (formulaId != null && !formulaId.isEmpty()) {
            JsonNode foundById = findFormulaById(formulas, formulaId);
            return foundById != null
                ? new ResolvedFormula(foundById.path("name").asText(""), formulaId,
                                      foundById.path("expression"))
                : null;
        }

        // 0. 显式 formula_name 绑定（最高优先；绑定了但找不到 → null 不 fallback）
        String formulaName = field.has("formula_name") ? field.path("formula_name").asText(null)
            : field.path("formulaName").asText(null);
        if (formulaName != null && !formulaName.isEmpty()) {
            JsonNode found = findFormulaByName(formulas, formulaName);
            return found != null
                ? new ResolvedFormula(formulaName, idOf(found), found.path("expression"))
                : null;
        }

        // 1. 模板级 formula_assignments[完整字段下标] → 公式名
        if (formulaAssignments != null && formulaAssignments.isObject()) {
            JsonNode assigned = formulaAssignments.path(String.valueOf(fullFieldIndex));
            if (!assigned.isMissingNode() && !assigned.isNull()) {
                String assignedName = assigned.asText("");
                if (!assignedName.isEmpty()) {
                    JsonNode found = findFormulaByName(formulas, assignedName);
                    if (found != null) {
                        return new ResolvedFormula(assignedName, idOf(found), found.path("expression"));
                    }
                }
            }
        }

        // 2. 字段名 == 公式名
        JsonNode byName = findFormulaByName(formulas, fieldName);
        if (byName != null) return new ResolvedFormula(fieldName, idOf(byName), byName.path("expression"));

        // 3. positional fallback（FORMULA 字段在 fields 中的相对位置）
        int posIdx = formulaFieldPosition(fields, fieldName);
        if (posIdx >= 0 && posIdx < formulas.size()) {
            JsonNode fm = formulas.get(posIdx);
            return new ResolvedFormula(fm.path("name").asText(""), idOf(fm), fm.path("expression"));
        }
        return null;
    }

    /**
     * repair-0803 B3（BL-0098）：对外暴露「某 FORMULA 字段最终会用哪条公式」的解析口径，
     * 供组件保存期把隐式绑定<b>固化</b>成显式 {@code formula_name}。
     *
     * <p><b>为什么必须复用本方法而不是另写一套</b>：{@link #resolveFormula} 的 4 级回退
     * （显式名 → formula_assignments → 同名 → <b>按位置</b>）是求值期的唯一真相。
     * 固化逻辑若自己实现一遍，两处口径一旦漂移，固化结果就会与实际算法不符 ——
     * 那正是 BL-0098 本身的问题（隐式绑定与用户认知不一致）在另一个层面重演。
     *
     * @param fullFieldIndex 该字段在 {@code fields} 数组中的<b>完整下标</b>（非 FORMULA 字段也计数），
     *                       与 {@code formula_assignments} 的键一致
     * @return 解析到的公式名；解析不到返回 {@code null}（调用方应保持原样不写入）
     */
    public String resolveFormulaNameForField(JsonNode field, JsonNode fields, JsonNode formulas,
                                             JsonNode formulaAssignments, int fullFieldIndex) {
        if (field == null || fields == null || formulas == null) return null;
        ResolvedFormula rf = resolveFormula(field, fieldName(field), fields, formulas,
                formulaAssignments, fullFieldIndex);
        if (rf == null) return null;
        String name = rf.name();
        return (name == null || name.isEmpty()) ? null : name;
    }

    /**
     * BL-0098：对外暴露「某 FORMULA 字段最终会用哪条公式」的**稳定 id**，供组件保存期把隐式绑定
     * <b>固化</b>成显式 {@code formula_id}。
     *
     * <p>与 {@link #resolveFormulaNameForField} 共用同一个 {@link #resolveFormula} 口径 ——
     * 固化逻辑必须复用求值期的唯一真相，自己实现一遍就是 BL-0098 在另一个层面重演。
     *
     * @return 解析到的公式 id；解析不到、或命中的公式尚无 id → {@code null}（调用方应保持原样不写入）
     */
    public String resolveFormulaIdForField(JsonNode field, JsonNode fields, JsonNode formulas,
                                           JsonNode formulaAssignments, int fullFieldIndex) {
        if (field == null || fields == null || formulas == null) return null;
        ResolvedFormula rf = resolveFormula(field, fieldName(field), fields, formulas,
                formulaAssignments, fullFieldIndex);
        return rf == null ? null : rf.id();
    }

    private JsonNode findFormulaByName(JsonNode formulas, String name) {
        for (JsonNode fm : formulas) {
            if (name.equals(fm.path("name").asText(null))) return fm;
        }
        return null;
    }

    /** BL-0098：取公式对象的稳定 id；缺失/空串 → null（不编造）。 */
    private static String idOf(JsonNode formula) {
        if (formula == null) return null;
        String id = formula.path("id").asText(null);
        return (id == null || id.isEmpty()) ? null : id;
    }

    /** BL-0098：按稳定 id 查公式；id 空或查不到 → null。 */
    private JsonNode findFormulaById(JsonNode formulas, String id) {
        if (id == null || id.isEmpty() || formulas == null || !formulas.isArray()) return null;
        for (JsonNode fm : formulas) {
            if (id.equals(fm.path("id").asText(null))) return fm;
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

    /**
     * 公式字段依赖边：依赖目标 + 该引用出现的位置（用于循环引用定位提示）。
     *
     * @param via            完整可读来源（如「公式「X」」「条件规则2命中的公式「Y」」），供文本渲染
     * @param viaFormulaName 该来源对应的公式名（repair-0803 结构化载荷用；条件判断条件等无公式时为 null）
     */
    private record DepEdge(String to, String via, String viaFormulaName) {}

    private static String nzFormula(String s) { return s == null || s.isEmpty() ? "未命名公式" : s; }

    /** 把表达式里引用本组件公式字段的 token 收成依赖边。Plan 3a + repair-0803。 */
    private void addExprFieldDeps(JsonNode expr, java.util.Set<String> nameSet, List<DepEdge> acc,
                                  String via, String viaFormulaName) {
        addExprFieldDeps(expr, nameSet, acc, via, viaFormulaName, false);
    }

    /**
     * repair-0803：递归收集依赖，使 {@code SUM(...)} 的 targetExpr 内引用宿主字段也进算序。
     *
     * <p><b>哪些 token 算「引用宿主公式字段」</b>：
     * <ul>
     *   <li>任意层级的 {@code b_field} —— 其语义恒为「宿主行的列」（求值读 currentRowRaw，
     *       repair-0803 后回落 fieldValues），故一律计入。</li>
     *   <li><b>仅顶层</b>的 {@code field} —— 顶层 field 取宿主行字段值；而 targetExpr 内的
     *       {@code field} 指的是<b>被聚合源页签</b>的列（求值时从 arow 灌进子上下文的 fieldValues），
     *       与宿主同名纯属巧合，<b>不得</b>计为宿主依赖，否则会凭空建边甚至误报环。</li>
     * </ul>
     *
     * <p><b>递归边界</b>：遇 {@code projectToHostKey=true}（KSUM 子 token）即止 —— 其 inner
     * 白名单本就拒 {@code b_field}（见 TokenMappabilityValidator.KSUM_INNER_ALLOWED_TYPES），
     * 内部只可能是被聚合页签自己的列，继续下探无意义。
     *
     * @param inTargetExpr 当前是否位于某个 cross_tab_ref 的 targetExpr 内
     */
    private void addExprFieldDeps(JsonNode expr, java.util.Set<String> nameSet, List<DepEdge> acc,
                                  String via, String viaFormulaName, boolean inTargetExpr) {
        if (expr == null || !expr.isArray()) return;
        for (JsonNode t : expr) {
            String type = t.path("type").asText("");
            if ("b_field".equals(type) || (!inTargetExpr && "field".equals(type))) {
                String v = t.path("value").asText("");
                if (nameSet.contains(v)) acc.add(new DepEdge(v, via, viaFormulaName));
            } else if ("cross_tab_ref".equals(type) && !t.path("projectToHostKey").asBoolean(false)) {
                addExprFieldDeps(t.path("targetExpr"), nameSet, acc, via, viaFormulaName, true);
            }
        }
    }

    /**
     * 构建带来源位置的依赖边（并集依赖：条件树列 ∪ 各分支公式列）。
     *
     * <p>同一依赖可能由多个位置产生（如各分支都引用同一字段），此处<b>刻意保留重复</b>——
     * 定位提示要能列出全部引用位置。去重在 {@link #buildFormulaDeps} 做。
     */
    private Map<String, List<DepEdge>> buildFormulaDepEdges(List<FormulaField> formulaFields,
                                                           java.util.Set<String> nameSet) {
        Map<String, List<DepEdge>> out = new LinkedHashMap<>();
        for (FormulaField ff : formulaFields) {
            List<DepEdge> edges = new ArrayList<>();
            if (ff.isConditional()) {
                for (CondRule r : ff.rules) {
                    String tag = "条件规则" + r.ruleIndex;
                    for (String c : com.cpq.formula.CondTreeEvaluator.columns(r.when))
                        if (nameSet.contains(c))
                            edges.add(new DepEdge(c, tag + "的判断条件", r.formulaName));
                    addExprFieldDeps(r.expression, nameSet, edges,
                        tag + "命中的公式「" + nzFormula(r.formulaName) + "」", r.formulaName);
                }
                addExprFieldDeps(ff.defaultExpression, nameSet, edges,
                    "条件默认公式「" + nzFormula(ff.defaultFormulaName) + "」", ff.defaultFormulaName);
            } else {
                addExprFieldDeps(ff.expression, nameSet, edges,
                    "公式「" + nzFormula(ff.formulaName) + "」", ff.formulaName);
            }
            out.put(ff.name, edges);
        }
        return out;
    }

    /** 边表 → 去重邻接表。 */
    private Map<String, List<String>> dedupeEdges(Map<String, List<DepEdge>> edges) {
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (Map.Entry<String, List<DepEdge>> e : edges.entrySet()) {
            java.util.Set<String> uniq = new java.util.LinkedHashSet<>();
            for (DepEdge de : e.getValue()) uniq.add(de.to());
            deps.put(e.getKey(), new ArrayList<>(uniq));
        }
        return deps;
    }

    /**
     * 构建公式字段依赖图（并集依赖：条件树列 ∪ 候选公式列）。Plan 3a/3c 共用。
     *
     * <p><b>必须去重</b>：条件公式的多个分支常引用同一个公式字段（如各分支都 {@code + [来料包装费]}）。
     * 若保留重复，下游 Kahn 消解按 {@code contains()} 判定、每个前驱只减 1，入度永远归不了零——
     * {@link #cyclicFormulaNodes} 会误报循环引用，{@link #topoOrder} 会把该节点错甩进「环兜底」
     * 尾部追加路径导致算序错乱。2026-07-28 COMP-0112「物料成本」即此故障。
     */
    private Map<String, List<String>> buildFormulaDeps(List<FormulaField> formulaFields, java.util.Set<String> nameSet) {
        return dedupeEdges(buildFormulaDepEdges(formulaFields, nameSet));
    }

    /** Plan 3c：返回构成环的公式字段名（空 = 无环）。复用并集依赖图（含条件依赖）。 */
    public List<String> cyclicFormulaNodes(JsonNode fields, JsonNode formulas) {
        List<FormulaField> ffs = collectFormulaFields(fields, formulas, null);
        java.util.Set<String> nameSet = new java.util.HashSet<>();
        for (FormulaField ff : ffs) nameSet.add(ff.name);
        Map<String, List<String>> deps = buildFormulaDeps(ffs, nameSet);
        Map<String, Integer> indeg = new LinkedHashMap<>();
        for (FormulaField ff : ffs) indeg.put(ff.name, deps.get(ff.name).size());
        List<String> queue = new ArrayList<>();
        for (FormulaField ff : ffs) if (indeg.get(ff.name) == 0) queue.add(ff.name);
        int emitted = 0;
        while (!queue.isEmpty()) {
            String cur = queue.remove(0); emitted++;
            for (FormulaField ff : ffs) if (deps.get(ff.name).contains(cur)) {
                indeg.put(ff.name, indeg.get(ff.name) - 1);
                if (indeg.get(ff.name) == 0) queue.add(ff.name);
            }
        }
        if (emitted == ffs.size()) return List.of();
        List<String> cyclic = new ArrayList<>();
        for (FormulaField ff : ffs) if (indeg.get(ff.name) > 0) cyclic.add(ff.name);
        return cyclic;
    }

    /**
     * 返回每个循环引用的<b>可定位</b>描述（空 = 无环）。
     *
     * <p>与 {@link #cyclicFormulaNodes} 的区别：只点名真正落在环上的字段（不含"仅依赖了环"的
     * 无辜下游节点），并对环上每条边说明该引用出自哪个字段的哪条公式 / 哪条条件规则，
     * 让配置员不必逐条公式翻找。格式：
     * <pre>
     * 「物料成本」→「来料管理费」→「物料成本」
     *       ·「物料成本」的 条件规则1命中的公式「非银点类材料成本公式」 中引用了 [来料管理费]
     *       ·「来料管理费」的 公式「来料管理费取值公式」 中引用了 [物料成本]
     * </pre>
     */
    public List<String> describeFormulaCycles(JsonNode fields, JsonNode formulas) {
        List<FormulaField> ffs = collectFormulaFields(fields, formulas, null);
        java.util.Set<String> nameSet = new java.util.HashSet<>();
        for (FormulaField ff : ffs) nameSet.add(ff.name);
        Map<String, List<DepEdge>> edges = buildFormulaDepEdges(ffs, nameSet);
        Map<String, List<String>> deps = dedupeEdges(edges);

        List<String> nodes = new ArrayList<>();
        for (FormulaField ff : ffs) nodes.add(ff.name);

        List<String> out = new ArrayList<>();
        for (java.util.Set<String> scc : stronglyConnected(nodes, deps)) {
            boolean hasCycle = scc.size() > 1;
            if (!hasCycle) {
                String only = scc.iterator().next();
                hasCycle = deps.getOrDefault(only, List.of()).contains(only);   // 自引用
            }
            if (!hasCycle) continue;
            List<String> path = cyclePathIn(scc, deps);
            if (!path.isEmpty()) out.add(renderCycle(path, edges));
        }
        return out;
    }

    /**
     * repair-0803：同 {@link #describeFormulaCycles}，但产出<b>结构化</b>环链路供前端弹抽屉。
     *
     * <p>复用同一套找环逻辑（Tarjan SCC + {@link #cyclePathIn}）与同一份依赖边，
     * 故与文本版、与 {@link #topoOrder} 的算序口径**天然一致**。
     *
     * <p><b>只出名称</b>：节点为字段名、边标注出自哪条公式，全程无 id（AC-11）。
     *
     * @param componentName 组件（页签）名称，注入进每个节点；调用方提供（本类不查库）
     */
    public List<FormulaCycleException.Cycle> describeFormulaCyclesStructured(
            JsonNode fields, JsonNode formulas, String componentName) {
        List<FormulaField> ffs = collectFormulaFields(fields, formulas, null);
        java.util.Set<String> nameSet = new java.util.HashSet<>();
        for (FormulaField ff : ffs) nameSet.add(ff.name);
        Map<String, List<DepEdge>> edges = buildFormulaDepEdges(ffs, nameSet);
        Map<String, List<String>> deps = dedupeEdges(edges);

        // 字段 → 其当前绑定的公式名（条件字段取默认分支公式名，边上再按具体规则覆盖）
        Map<String, String> formulaOf = new HashMap<>();
        List<String> nodes = new ArrayList<>();
        for (FormulaField ff : ffs) {
            nodes.add(ff.name);
            String fn = ff.isConditional() ? ff.defaultFormulaName : ff.formulaName;
            if (fn != null && !fn.isEmpty()) formulaOf.put(ff.name, fn);
        }

        List<FormulaCycleException.Cycle> out = new ArrayList<>();
        for (java.util.Set<String> scc : stronglyConnected(nodes, deps)) {
            boolean hasCycle = scc.size() > 1;
            if (!hasCycle) {
                String only = scc.iterator().next();
                hasCycle = deps.getOrDefault(only, List.of()).contains(only);   // 自引用
            }
            if (!hasCycle) continue;
            List<String> path = cyclePathIn(scc, deps);
            if (path.isEmpty()) continue;
            // cyclePathIn 返回的是<b>显式闭合</b>形态 [A,B,A]（dfsBackTo 末尾 add(target)），
            // 而 api.md §1 约定 nodes 首尾<b>不</b>重复（由前端渲染时自行闭合）→ 此处剥掉末节点。
            // 不剥的话 nodes=[A,B,A] 且 edges 会多绕出一条 A→A 自环。
            List<String> ring = (path.size() > 1 && path.get(0).equals(path.get(path.size() - 1)))
                ? path.subList(0, path.size() - 1)
                : path;

            List<FormulaCycleException.Node> ns = new ArrayList<>();
            for (String f : ring) {
                ns.add(new FormulaCycleException.Node(componentName, f, formulaOf.get(f)));
            }
            List<FormulaCycleException.Edge> es = new ArrayList<>();
            for (int i = 0; i < ring.size(); i++) {
                String from = ring.get(i);
                String to = ring.get((i + 1) % ring.size());   // 末条闭合回首节点
                String viaDesc = null, viaFormula = null;
                for (DepEdge de : edges.getOrDefault(from, List.of())) {
                    if (de.to().equals(to)) { viaDesc = de.via(); viaFormula = de.viaFormulaName(); break; }
                }
                es.add(new FormulaCycleException.Edge(
                    from, to, null, null,
                    viaFormula != null ? viaFormula : formulaOf.get(from),
                    viaDesc != null ? viaDesc : "公式"));
            }
            out.add(new FormulaCycleException.Cycle(
                FormulaCycleException.SCOPE_FIELD, componentName, ns, es));
        }
        return out;
    }

    /** 环路径 + 每条边的引用位置，渲染成可读文本。 */
    private String renderCycle(List<String> path, Map<String, List<DepEdge>> edges) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) sb.append(" → ");
            sb.append("「").append(path.get(i)).append("」");
        }
        for (int i = 0; i + 1 < path.size(); i++) {
            String from = path.get(i), to = path.get(i + 1);
            java.util.Set<String> vias = new java.util.LinkedHashSet<>();
            for (DepEdge de : edges.getOrDefault(from, List.of()))
                if (de.to().equals(to)) vias.add(de.via());
            sb.append("\n      ·「").append(from).append("」的 ")
              .append(vias.isEmpty() ? "公式" : String.join(" / ", vias))
              .append(" 中引用了 [").append(to).append("]");
        }
        return sb.toString();
    }

    /** Tarjan 强连通分量（节点 = 公式字段，规模很小，递归安全）。 */
    private List<java.util.Set<String>> stronglyConnected(List<String> nodes, Map<String, List<String>> deps) {
        Map<String, Integer> index = new HashMap<>(), low = new HashMap<>();
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        java.util.Set<String> onStack = new java.util.HashSet<>();
        int[] counter = {0};
        List<java.util.Set<String>> out = new ArrayList<>();
        for (String n : nodes)
            if (!index.containsKey(n)) sccVisit(n, deps, index, low, stack, onStack, counter, out);
        return out;
    }

    private void sccVisit(String v, Map<String, List<String>> deps,
                          Map<String, Integer> index, Map<String, Integer> low,
                          java.util.Deque<String> stack, java.util.Set<String> onStack,
                          int[] counter, List<java.util.Set<String>> out) {
        index.put(v, counter[0]); low.put(v, counter[0]); counter[0]++;
        stack.push(v); onStack.add(v);
        for (String w : deps.getOrDefault(v, List.of())) {
            if (!index.containsKey(w)) {
                sccVisit(w, deps, index, low, stack, onStack, counter, out);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            java.util.Set<String> comp = new java.util.LinkedHashSet<>();
            String w;
            do { w = stack.pop(); onStack.remove(w); comp.add(w); } while (!w.equals(v));
            out.add(comp);
        }
    }

    /** 在强连通分量内找一条闭合环路径（首尾同名）。 */
    private List<String> cyclePathIn(java.util.Set<String> scc, Map<String, List<String>> deps) {
        String start = scc.iterator().next();
        List<String> path = new ArrayList<>();
        return dfsBackTo(start, start, scc, deps, new java.util.LinkedHashSet<>(), path) ? path : List.of();
    }

    private boolean dfsBackTo(String cur, String target, java.util.Set<String> scc,
                              Map<String, List<String>> deps,
                              java.util.LinkedHashSet<String> path, List<String> out) {
        path.add(cur);
        for (String nxt : deps.getOrDefault(cur, List.of())) {
            if (!scc.contains(nxt)) continue;
            if (nxt.equals(target)) {
                out.addAll(path); out.add(target);   // 闭合
                return true;
            }
            if (path.contains(nxt)) continue;
            if (dfsBackTo(nxt, target, scc, deps, path, out)) return true;
        }
        path.remove(cur);
        return false;
    }

    private List<String> topoOrder(List<FormulaField> formulaFields) {
        // 依赖图：公式 field token 引用的其他公式字段（并集，含条件依赖）
        java.util.Set<String> nameSet = new java.util.HashSet<>();
        for (FormulaField ff : formulaFields) nameSet.add(ff.name);
        Map<String, List<String>> deps = buildFormulaDeps(formulaFields, nameSet);
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

    /**
     * 方案 B（spec 2026-06-13）：宿主行 currentRowRaw 增量补 INPUT 型 default_source
     * (GLOBAL_VARIABLE/BNF_PATH/BASIC_DATA) 按字段名解析的值，供 cross_tab_ref 的 match 键 b 命中。
     * 仅当 currentRowRaw 尚无该字段名（空）时写入——driver/手填值优先，不覆盖。文本保留（unwrapNode）。
     * 源行路径由 resolveRowByFieldName 覆盖，故此处只补宿主行，不动源行。
     */
    private void fillInputDefaultSourceByFieldName(JsonNode fields, JsonNode basicDataValues,
                                                   Map<String, Object> currentRowRaw) {
        if (fields == null || !fields.isArray() || basicDataValues == null) return;
        for (JsonNode f : fields) {
            String type = fieldType(f);
            if (!("INPUT_NUMBER".equals(type) || "INPUT_TEXT".equals(type) || "INPUT".equals(type))) continue;
            String name = fieldName(f);
            if (name.isEmpty()) continue;
            // 键存在(driver/手填/显式清空'')均不补：清空'' 经 toRawRowMap 保留为非 null → 尊重置空，
            // 仅"键缺失"才补 default_source（与前端 currentRowForEval 增量补值口径对称）。
            if (currentRowRaw.get(name) != null) continue;
            JsonNode ds = defaultSource(f);
            if (ds == null) continue;
            String dsType = ds.path("type").asText("");
            Object v = null;
            if ("GLOBAL_VARIABLE".equals(dsType)) {
                v = lookupBdv(basicDataValues, "@gvar:" + ds.path("code").asText(""));
            } else if ("BNF_PATH".equals(dsType) || "BASIC_DATA".equals(dsType)) {
                String p = ds.path("path").asText("");
                if (!p.isEmpty()) v = lookupBdv(basicDataValues, bnfDriverLookupKey(p));
            }
            if (nonEmpty(v)) currentRowRaw.put(name, unwrapNode(v));
        }
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

    /**
     * Object/JsonNode → BigDecimal（数字直取精确转换；字符串走 BigDecimal 精确解析；数组/列表取
     * 首值递归；否则 null）。task-0801 B4-2：供 evalCrossTab 聚合（SUM/AVG/MAX/MIN）使用，
     * 避免先转 Double 再做 double 累加的双重转换损耗（见 evalCrossTab 聚合分支注释）。
     */
    private BigDecimal toBigNumber(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return PrecisionPolicy.of(n);
        if (o instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) return null;
            try { return new BigDecimal(t); } catch (Exception e) { return null; }
        }
        if (o instanceof JsonNode n) {
            if (n.isNull() || n.isMissingNode()) return null;
            if (n.isNumber()) return n.decimalValue();
            if (n.isTextual()) {
                try { return new BigDecimal(n.textValue().trim()); } catch (Exception e) { return null; }
            }
            if (n.isArray()) return n.size() == 0 ? null : toBigNumber(n.get(0));
            return null;
        }
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            return l.isEmpty() ? null : toBigNumber(l.get(0));
        }
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
    // 算术串求值（递归下降，task-0801 B2 十进制化：BigDecimal；
    // 只换数值类型，运算符优先级/一元负号/全角转换/递归结构与改造前逐行对齐，
    // 复刻 new Function('return (expr)')）
    // ======================================================================

    private static class ArithParser {
        private final String s;
        private int i = 0;

        ArithParser(String s) { this.s = s; }

        BigDecimal parse() {
            BigDecimal v = expr();
            skip();
            if (i < s.length()) throw new RuntimeException("trailing: " + s.substring(i));
            return v;
        }

        private void skip() { while (i < s.length() && s.charAt(i) == ' ') i++; }

        private BigDecimal expr() {
            BigDecimal v = term();
            while (true) {
                skip();
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    char op = s.charAt(i++);
                    BigDecimal r = term();
                    // +/- 精确无损（不设 MathContext；十进制加减本就不产生舍入）
                    v = op == '+' ? v.add(r) : v.subtract(r);
                } else break;
            }
            return v;
        }

        private BigDecimal term() {
            BigDecimal v = factor();
            while (true) {
                skip();
                if (i < s.length() && (s.charAt(i) == '*' || s.charAt(i) == '/')) {
                    char op = s.charAt(i++);
                    BigDecimal r = factor();
                    // * 用 MathContext 约束精度/防 scale 无限增长；/ 走 PrecisionPolicy.divide()
                    // （12 位中间精度 + 除零优雅返 0，语义对齐 api.md G-9，不抛异常）。
                    v = op == '*' ? v.multiply(r, PrecisionPolicy.MC)
                                  : PrecisionPolicy.divide(v, r);
                } else break;
            }
            return v;
        }

        private BigDecimal factor() {
            skip();
            if (i >= s.length()) throw new RuntimeException("unexpected eof");
            char c = s.charAt(i);
            if (c == '+') { i++; return factor(); }
            if (c == '-') { i++; return factor().negate(); }
            if (c == '(') {
                i++;
                BigDecimal v = expr();
                skip();
                if (i >= s.length() || s.charAt(i) != ')') throw new RuntimeException("missing )");
                i++;
                return v;
            }
            return number();
        }

        private BigDecimal number() {
            skip();
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (i == start) throw new RuntimeException("expected number at " + i);
            // BigDecimal(String) 十进制精确解析，无 double 中转，无精度损失。
            return new BigDecimal(s.substring(start, i));
        }
    }
}
