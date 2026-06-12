package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 6: 后端 KSUM 镜像求值单测（TDD 先红后绿）。
 *
 * <p>场景与前端 T5 完全对称：
 * <ul>
 *   <li>元素组件 (ELEM_ID): 2 行 [{料件:'Cu', 单价:2}, {料件:'Ni', 单价:3}]</li>
 *   <li>外购件组件 (WGJ_ID): 2 行 [{来料:'X', 费用:1.0}, {来料:'X', 费用:0.5}]</li>
 * </ul>
 *
 * <p>公式: 外层 SUM(ELEM_ID, match=[], targetExpr=[field(单价) + KSUM_subtoken])
 * <ul>
 *   <li>KSUM_subtoken: projectToHostKey=true, source=WGJ_ID, match=[], agg=SUM, targetExpr=[field(费用)]</li>
 *   <li>Cu行: 2+1.5=3.5; Ni行: 3+1.5=4.5; 外层 SUM=8</li>
 * </ul>
 *
 * <p>多 source 广播（§4.3）：
 * <ul>
 *   <li>驱动=ELEM_ID, 更粗 source=MAT_ID, token.sources 长度≥2</li>
 *   <li>按 s.match 把更粗 source 列注入 aFieldValues（低优先），驱动行列覆盖（高优先）</li>
 *   <li>SUM=25（驱动行0: 2+10=12，驱动行1: 3+10=13）</li>
 * </ul>
 */
class FormulaCalculatorKsumTest {

    private static final String ELEM_ID = "elem-comp-id";
    private static final String WGJ_ID  = "wgj-comp-id";
    private static final String MAT_ID  = "mat-id";

    private final FormulaCalculator calc = new FormulaCalculator();
    private final ObjectMapper om = new ObjectMapper();

    // ── 辅助：构造 RowContext ─────────────────────────────────────────────────

    private FormulaCalculator.RowContext ctxWithCrossTab(
            Map<String, List<Map<String, Object>>> crossTabRows) {
        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.crossTabRows = crossTabRows;
        ctx.currentRowRaw = new HashMap<>();
        return ctx;
    }

    /** 外层 SUM token: source=ELEM_ID, match=[], targetExpr=[field(单价) + KSUM_subtoken]. */
    private JsonNode outerSumToken(JsonNode ksumSubToken) throws Exception {
        // 构造 targetExpr: [field(单价), op(+), KSUM_subtoken]
        String outerJson = "[{"
            + "\"type\":\"cross_tab_ref\","
            + "\"source\":\"" + ELEM_ID + "\","
            + "\"sourceLabel\":\"元素\","
            + "\"agg\":\"SUM\","
            + "\"match\":[],"
            + "\"targetExpr\":["
            +   "{\"type\":\"field\",\"value\":\"单价\"},"
            +   "{\"type\":\"operator\",\"value\":\"+\"},"
            +   ksumSubToken.toString()
            + "]}]";
        return om.readTree(outerJson);
    }

    /** KSUM 子 token: projectToHostKey=true, source=WGJ_ID, match=[], agg=SUM, targetExpr=[field(费用)]. */
    private JsonNode ksumSumSubToken() throws Exception {
        return om.readTree("{"
            + "\"type\":\"cross_tab_ref\","
            + "\"projectToHostKey\":true,"
            + "\"source\":\"" + WGJ_ID + "\","
            + "\"sourceLabel\":\"外购件\","
            + "\"agg\":\"SUM\","
            + "\"match\":[],"
            + "\"targetExpr\":[{\"type\":\"field\",\"value\":\"费用\"}]"
            + "}");
    }

    /** KAVG 子 token: 与 KSUM 同但 agg=AVG（空集 → I-2 null）. */
    private JsonNode kavgSubToken() throws Exception {
        return om.readTree("{"
            + "\"type\":\"cross_tab_ref\","
            + "\"projectToHostKey\":true,"
            + "\"source\":\"" + WGJ_ID + "\","
            + "\"sourceLabel\":\"外购件\","
            + "\"agg\":\"AVG\","
            + "\"match\":[],"
            + "\"targetExpr\":[{\"type\":\"field\",\"value\":\"费用\"}]"
            + "}");
    }

    // ── 正常数据 ─────────────────────────────────────────────────────────────

    /** KSUM 塌缩: Σ费用=1.5, 广播进每元素行 → (2+1.5)+(3+1.5)=8. */
    @Test
    void ksum_collapseToScalar_broadcastToEachDriverRow_sumsTo8() throws Exception {
        List<Map<String, Object>> elemRows = List.of(
            Map.of("料件", "Cu", "单价", 2),
            Map.of("料件", "Ni", "单价", 3));
        List<Map<String, Object>> wgjRows = List.of(
            Map.of("来料", "X", "费用", 1.0),
            Map.of("来料", "X", "费用", 0.5));

        Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();
        crossTabRows.put(ELEM_ID, elemRows);
        crossTabRows.put(WGJ_ID, wgjRows);

        FormulaCalculator.RowContext ctx = ctxWithCrossTab(crossTabRows);
        JsonNode tokens = outerSumToken(ksumSumSubToken());
        BigDecimal result = calc.evaluateExpression(tokens, ctx);

        assertEquals(8.0, result.doubleValue(), 1e-4, "期望 (2+1.5)+(3+1.5)=8");
    }

    // ── 决策 K / I-1: KSUM 空集 → 0 静默 ────────────────────────────────────

    /** I-1: KSUM 空集(WGJ 无行) → scalar=0 静默 → (2+0)+(3+0)=5. */
    @Test
    void ksum_emptySet_I1_returnsZeroSilently_sumsTo5() throws Exception {
        List<Map<String, Object>> elemRows = List.of(
            Map.of("料件", "Cu", "单价", 2),
            Map.of("料件", "Ni", "单价", 3));

        Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();
        crossTabRows.put(ELEM_ID, elemRows);
        crossTabRows.put(WGJ_ID, List.of());  // 空

        FormulaCalculator.RowContext ctx = ctxWithCrossTab(crossTabRows);
        JsonNode tokens = outerSumToken(ksumSumSubToken());
        BigDecimal result = calc.evaluateExpression(tokens, ctx);

        assertEquals(5.0, result.doubleValue(), 1e-4, "KSUM 空集 → 0 静默, 期望 5");
    }

    // ── 决策 K / I-2: KAVG 空集 → null → 整外层塌 0 ─────────────────────────

    /**
     * I-2: KAVG 空集 → evalCrossTab 返 null → appendToken 注入非法表达式 `(null.x)`
     * → evaluateExpression try/catch → 0.
     */
    @Test
    void kavg_emptySet_I2_outerExpressionCollapsesToZero() throws Exception {
        List<Map<String, Object>> elemRows = List.of(
            Map.of("料件", "Cu", "单价", 2),
            Map.of("料件", "Ni", "单价", 3));

        Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();
        crossTabRows.put(ELEM_ID, elemRows);
        crossTabRows.put(WGJ_ID, List.of());  // 空 → KAVG 空集

        FormulaCalculator.RowContext ctx = ctxWithCrossTab(crossTabRows);
        JsonNode tokens = outerSumToken(kavgSubToken());
        BigDecimal result = calc.evaluateExpression(tokens, ctx);

        assertEquals(0.0, result.doubleValue(), 1e-4,
            "KAVG 空集 → I-2 null → 整外层表达式塌 0");
    }

    // ── C1: targetRowValue sub 字段透传 ──────────────────────────────────────

    /**
     * C1: sub RowContext 必须透传 componentSubtotals/quotationFields/productAttributes/previousRowSubtotal.
     *
     * <p>场景: targetExpr 含 component_subtotal token, 其取值来自 ctx.componentSubtotals.
     * 若 sub 未透传 componentSubtotals → 取值 0 → 结果错误 → 本测试先红后绿.
     *
     * <p>外层 SUM token: source=WGJ_ID, match=[], targetExpr=[field(费用) + component_subtotal(COMP_A)].
     * componentSubtotals = {COMP_A → 10.0}.
     * wgjRows = [{费用:3}] → targetRowValue(row) = 3 + 10 = 13.
     * SUM(单行) = 13.
     */
    @Test
    void targetRowValue_sub_propagates_componentSubtotals() throws Exception {
        String wgjSource = "wgj-source";
        List<Map<String, Object>> wgjRows = List.of(
            Map.of("费用", 3.0));

        Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();
        crossTabRows.put(wgjSource, wgjRows);

        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.crossTabRows = crossTabRows;
        ctx.currentRowRaw = new HashMap<>();
        ctx.componentSubtotals = new HashMap<>();
        ctx.componentSubtotals.put("COMP_A", 10.0);

        // token: SUM(wgjSource, match=[], targetExpr=[field(费用) + component_subtotal(COMP_A)])
        JsonNode tokens = om.readTree("[{"
            + "\"type\":\"cross_tab_ref\","
            + "\"source\":\"" + wgjSource + "\","
            + "\"agg\":\"SUM\","
            + "\"match\":[],"
            + "\"targetExpr\":["
            +   "{\"type\":\"field\",\"value\":\"费用\"},"
            +   "{\"type\":\"operator\",\"value\":\"+\"},"
            +   "{\"type\":\"component_subtotal\",\"component_code\":\"COMP_A\"}"
            + "]}]");

        BigDecimal result = calc.evaluateExpression(tokens, ctx);
        assertEquals(13.0, result.doubleValue(), 1e-4,
            "sub 必须透传 componentSubtotals: 3 + 10 = 13");
    }

    // ── 多 source 广播（§4.3）─────────────────────────────────────────────────

    /**
     * 多 source 链: SUM([元素.单价] + [来料.组成用量])
     * 驱动=ELEM_ID(料件=A/A), 更粗 source=MAT_ID(料件=A, 组成用量=10).
     * 驱动行0: 2+10=12; 驱动行1: 3+10=13; SUM=25.
     */
    @Test
    void multiSource_coarseSourceBroadcast_sumsTo25() throws Exception {
        List<Map<String, Object>> elemRows = List.of(
            Map.of("料件", "A", "单价", 2),
            Map.of("料件", "A", "单价", 3));
        List<Map<String, Object>> matRows = List.of(
            Map.of("料件", "A", "组成用量", 10));

        Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();
        crossTabRows.put(ELEM_ID, elemRows);
        crossTabRows.put(MAT_ID, matRows);

        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.crossTabRows = crossTabRows;
        ctx.currentRowRaw = new HashMap<>();

        // token with sources: sources[0]=ELEM_ID(驱动), sources[1]=MAT_ID(更粗)
        // match=[]（外层 hits=所有驱动行），targetExpr=[field(单价)+field(组成用量)]
        JsonNode tokens = om.readTree("[{"
            + "\"type\":\"cross_tab_ref\","
            + "\"source\":\"" + ELEM_ID + "\","
            + "\"agg\":\"SUM\","
            + "\"match\":[],"
            + "\"targetExpr\":["
            +   "{\"type\":\"field\",\"value\":\"单价\"},"
            +   "{\"type\":\"operator\",\"value\":\"+\"},"
            +   "{\"type\":\"field\",\"value\":\"组成用量\"}"
            + "],"
            + "\"sources\":["
            +   "{\"source\":\"" + ELEM_ID + "\",\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}]},"
            +   "{\"source\":\"" + MAT_ID  + "\",\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}]}"
            + "]}]");

        BigDecimal result = calc.evaluateExpression(tokens, ctx);
        assertEquals(25.0, result.doubleValue(), 1e-4,
            "多 source 广播: (2+10)+(3+10)=25");
    }

    /** 多 source: 粗 source 0 命中 → 该项=0, 不报错 → SUM=5. */
    @Test
    void multiSource_coarseSource_zeroHit_fieldDefaults0_sumsTo5() throws Exception {
        List<Map<String, Object>> elemRows = List.of(
            Map.of("料件", "A", "单价", 2),
            Map.of("料件", "A", "单价", 3));

        Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();
        crossTabRows.put(ELEM_ID, elemRows);
        crossTabRows.put(MAT_ID, List.of());  // 空 → 0 命中

        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.crossTabRows = crossTabRows;
        ctx.currentRowRaw = new HashMap<>();

        JsonNode tokens = om.readTree("[{"
            + "\"type\":\"cross_tab_ref\","
            + "\"source\":\"" + ELEM_ID + "\","
            + "\"agg\":\"SUM\","
            + "\"match\":[],"
            + "\"targetExpr\":["
            +   "{\"type\":\"field\",\"value\":\"单价\"},"
            +   "{\"type\":\"operator\",\"value\":\"+\"},"
            +   "{\"type\":\"field\",\"value\":\"组成用量\"}"
            + "],"
            + "\"sources\":["
            +   "{\"source\":\"" + ELEM_ID + "\",\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}]},"
            +   "{\"source\":\"" + MAT_ID  + "\",\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}]}"
            + "]}]");

        BigDecimal result = calc.evaluateExpression(tokens, ctx);
        assertEquals(5.0, result.doubleValue(), 1e-4,
            "粗 source 0 命中 → 组成用量不注入=0 → (2+0)+(3+0)=5");
    }

    // ── T6 修复验证: KSUM 子 token 非空 match 按驱动行键过滤（对齐前端 mergedRow）────────────

    /**
     * KSUM 子 token 带非空 match（按驱动行键精确过滤）时，后端与前端行为对称。
     *
     * <p>场景：
     * <ul>
     *   <li>驱动 ELEM_ID: [{料件:'Ag', 单价:2}, {料件:'Ni', 单价:3}]</li>
     *   <li>KSUM 源 WGJ_ID: [{料件:'Ag', 费用:10}, {料件:'Ni', 费用:20}]</li>
     *   <li>KSUM 子 token: match=[{a:'料件', b:'料件'}]（按驱动行的料件字段精确过滤）</li>
     *   <li>ctx.currentRowRaw.料件='X'（宿主行与驱动行不同，旧逻辑会用宿主行 X 去 match → 0 命中 → KSUM=0）</li>
     * </ul>
     *
     * <p>修复前（旧逻辑）：sub.currentRowRaw = ctx.currentRowRaw（料件='X'）
     *   → KSUM match b='料件' 取 X → WGJ 无行 → 空集 I-1 → scalar=0 → 每行 += 0 → SUM=5（错）。
     *
     * <p>修复后（arow 覆盖）：sub.currentRowRaw = {料件:'Ag'|'Ni',...}
     *   → KSUM match 取驱动行 → Ag命中费用10, Ni命中费用20 → SUM=(2+10)+(3+20)=35（正确）。
     */
    @Test
    void ksum_nonEmptyMatch_usesDriverRowKey_notHostRowKey_sumsTo35() throws Exception {
        List<Map<String, Object>> elemRows = List.of(
            Map.of("料件", "Ag", "单价", 2),
            Map.of("料件", "Ni", "单价", 3));
        List<Map<String, Object>> wgjRows = List.of(
            Map.of("料件", "Ag", "费用", 10),
            Map.of("料件", "Ni", "费用", 20));

        Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();
        crossTabRows.put(ELEM_ID, elemRows);
        crossTabRows.put(WGJ_ID, wgjRows);

        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.crossTabRows = crossTabRows;
        // 宿主行 料件='X'（与驱动行 Ag/Ni 不同），旧逻辑会用 X 匹配 WGJ → 0 命中 → KSUM=0
        ctx.currentRowRaw = new HashMap<>(Map.of("料件", "X"));

        // KSUM 子 token: match=[{a:'料件', b:'料件'}]（按驱动行的料件精确过滤）
        JsonNode ksumSubToken = om.readTree("{"
            + "\"type\":\"cross_tab_ref\","
            + "\"projectToHostKey\":true,"
            + "\"source\":\"" + WGJ_ID + "\","
            + "\"sourceLabel\":\"外购件\","
            + "\"agg\":\"SUM\","
            + "\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}],"
            + "\"targetExpr\":[{\"type\":\"field\",\"value\":\"费用\"}]"
            + "}");

        JsonNode tokens = outerSumToken(ksumSubToken);
        BigDecimal result = calc.evaluateExpression(tokens, ctx);

        // 修复后: Ag行=2+10=12, Ni行=3+20=23, SUM=35
        assertEquals(35.0, result.doubleValue(), 1e-4,
            "KSUM 非空 match 必须从驱动行 arow 取 b 键: (2+10)+(3+20)=35");
    }

    /** 多 source: 粗 source >1 命中 → multiSrcHitErr → 整项塌 0. */
    @Test
    void multiSource_coarseSource_multiHit_collapseToZero() throws Exception {
        List<Map<String, Object>> elemRows = List.of(
            Map.of("料件", "A", "单价", 2));
        List<Map<String, Object>> matRows = List.of(
            Map.of("料件", "A", "组成用量", 10),
            Map.of("料件", "A", "组成用量", 20));  // 重复 → 多命中

        Map<String, List<Map<String, Object>>> crossTabRows = new HashMap<>();
        crossTabRows.put(ELEM_ID, elemRows);
        crossTabRows.put(MAT_ID, matRows);

        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.crossTabRows = crossTabRows;
        ctx.currentRowRaw = new HashMap<>();

        JsonNode tokens = om.readTree("[{"
            + "\"type\":\"cross_tab_ref\","
            + "\"source\":\"" + ELEM_ID + "\","
            + "\"agg\":\"SUM\","
            + "\"match\":[],"
            + "\"targetExpr\":["
            +   "{\"type\":\"field\",\"value\":\"单价\"},"
            +   "{\"type\":\"operator\",\"value\":\"+\"},"
            +   "{\"type\":\"field\",\"value\":\"组成用量\"}"
            + "],"
            + "\"sources\":["
            +   "{\"source\":\"" + ELEM_ID + "\",\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}]},"
            +   "{\"source\":\"" + MAT_ID  + "\",\"match\":[{\"a\":\"料件\",\"b\":\"料件\"}]}"
            + "]}]");

        BigDecimal result = calc.evaluateExpression(tokens, ctx);
        assertEquals(0.0, result.doubleValue(), 1e-4,
            "粗 source >1 命中 → multiSrcHitErr → 整项塌 0");
    }
}
