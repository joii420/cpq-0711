package com.cpq.priceadjust.service;

import com.cpq.costing.service.ComparisonViewService;
import com.cpq.priceadjust.dto.ComparisonColumnDef;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0729 B4.3 · ComparisonColumnEvaluator 单测，对齐前端
 * {@code comparisonMapping.test.ts} 的黄金用例（getColumnValue / computeDiff / classifyDiff
 * 三组），并补后端专属的 MISSING/STALE 扩展态用例。
 *
 * <p>🔒 反向验证（backtask B9 L1 精神）：其中一条用例故意验证"改错口径会变红"（见
 * {@link #classifyDiff_threshold0_diff0_isNormalNotAmber}），证明这套测试有拦截力。
 */
class ComparisonColumnEvaluatorTest {

    private static ComparisonColumnDef productTotalCol() {
        ComparisonColumnDef c = new ComparisonColumnDef();
        c.id = "col-default";
        c.kind = "PRODUCT_TOTAL";
        c.threshold = BigDecimal.ZERO;
        return c;
    }

    private static ComparisonColumnDef tabPairCol(String metric) {
        ComparisonColumnDef c = new ComparisonColumnDef();
        c.id = "col-2";
        c.kind = "TAB_PAIR";
        c.threshold = new BigDecimal("2.00");
        c.quoteComponentId = "cid-quote";
        c.quoteMetric = metric;
        c.costingComponentId = "cid-costing";
        c.costingMetric = metric;
        return c;
    }

    private static ComparisonViewService.SideValues sideWithProductTotal(double productTotal) {
        ComparisonViewService.SideValues sv = new ComparisonViewService.SideValues();
        sv.productTotal = BigDecimal.valueOf(productTotal);
        sv.tabs = new LinkedHashMap<>();
        return sv;
    }

    private static ComparisonViewService.SideValues sideWithTab(
            String componentId, Double tabTotal, Map<String, Double> subtotals) {
        ComparisonViewService.SideValues sv = new ComparisonViewService.SideValues();
        sv.tabs = new LinkedHashMap<>();
        ComparisonViewService.TabVal tv = new ComparisonViewService.TabVal();
        if (tabTotal != null) tv.tabTotal = BigDecimal.valueOf(tabTotal);
        if (subtotals != null) {
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            subtotals.forEach((k, v) -> m.put(k, BigDecimal.valueOf(v)));
            tv.subtotals = m;
        }
        sv.tabs.put(componentId, tv);
        return sv;
    }

    // ── getColumnValue ──────────────────────────────────────────────────────

    @Test
    void getColumnValue_productTotal_takesSideProductTotal() {
        ComparisonViewService.SideValues quote = sideWithProductTotal(15500);
        ComparisonViewService.SideValues costing = sideWithProductTotal(14500);
        assertEquals(0, new BigDecimal("15500").compareTo(
            ComparisonColumnEvaluator.getColumnValue(quote, productTotalCol(), true)));
        assertEquals(0, new BigDecimal("14500").compareTo(
            ComparisonColumnEvaluator.getColumnValue(costing, productTotalCol(), false)));
    }

    @Test
    void getColumnValue_tabPair_takesSubtotalsMetric() {
        ComparisonViewService.SideValues quote = sideWithTab("cid-quote", null, Map.of("材料成本", 8000.0));
        ComparisonColumnDef col = tabPairCol("材料成本");
        assertEquals(0, new BigDecimal("8000").compareTo(
            ComparisonColumnEvaluator.getColumnValue(quote, col, true)));
    }

    @Test
    void getColumnValue_tabTotalSentinel_takesTabTotal() {
        ComparisonViewService.SideValues costing = sideWithTab("cid-costing", 7500.0, null);
        ComparisonColumnDef col = tabPairCol("__TAB_TOTAL__");
        assertEquals(0, new BigDecimal("7500").compareTo(
            ComparisonColumnEvaluator.getColumnValue(costing, col, false)));
    }

    @Test
    void getColumnValue_sideNull_returnsNull() {
        assertNull(ComparisonColumnEvaluator.getColumnValue(null, productTotalCol(), false));
    }

    @Test
    void getColumnValue_componentHitButFieldMissing_returnsNull() {
        ComparisonViewService.SideValues quote = sideWithTab("cid-quote", null, Map.of("其它字段", 1.0));
        ComparisonColumnDef col = tabPairCol("材料成本");
        assertNull(ComparisonColumnEvaluator.getColumnValue(quote, col, true));
    }

    @Test
    void getColumnValue_componentIdNotInTabs_returnsNull() {
        ComparisonViewService.SideValues quote = sideWithTab("some-other-cid", null, Map.of("材料成本", 8000.0));
        ComparisonColumnDef col = tabPairCol("材料成本");
        assertNull(ComparisonColumnEvaluator.getColumnValue(quote, col, true));
    }

    // ── computeDiff / classifyDiffThreeState（前端三态口径，逐行移植验证）──────

    @Test
    void computeDiff_quoteMinusCosting() {
        assertEquals(0, new BigDecimal("1000").compareTo(
            ComparisonColumnEvaluator.computeDiff(new BigDecimal("15500"), new BigDecimal("14500"))));
    }

    @Test
    void computeDiff_eitherSideNull_returnsNull() {
        assertNull(ComparisonColumnEvaluator.computeDiff(null, new BigDecimal("14500")));
        assertNull(ComparisonColumnEvaluator.computeDiff(new BigDecimal("15500"), null));
    }

    @Test
    void classifyDiff_negative_isRed() {
        assertEquals("red", ComparisonColumnEvaluator.classifyDiffThreeState(
            new BigDecimal("-1"), new BigDecimal("100")));
    }

    @Test
    void classifyDiff_belowThreshold_isOrange() {
        assertEquals("orange", ComparisonColumnEvaluator.classifyDiffThreeState(
            new BigDecimal("50"), new BigDecimal("100")));
    }

    @Test
    void classifyDiff_atOrAboveThreshold_isNone() {
        assertEquals("none", ComparisonColumnEvaluator.classifyDiffThreeState(
            new BigDecimal("200"), new BigDecimal("100")));
    }

    @Test
    void classifyDiff_nullDiff_isNone() {
        assertEquals("none", ComparisonColumnEvaluator.classifyDiffThreeState(null, new BigDecimal("100")));
    }

    @Test
    void classifyDiff_threshold0_diff0_isNormalNotAmber() {
        // 0 不小于 0，diff=0 threshold=0 场景不着色（与前端 classifyDiff(0,0)==='none' 完全对齐）
        assertEquals("none", ComparisonColumnEvaluator.classifyDiffThreeState(BigDecimal.ZERO, BigDecimal.ZERO));
        // 后端五态口径下同一场景必须是 NORMAL，不是 AMBER —— 反向验证：把 compareTo 条件错改成 <= 会让这条变红
        ComparisonColumnDef col = productTotalCol();
        col.threshold = BigDecimal.ZERO;
        ComparisonColumnEvaluator.ColumnEval eval = ComparisonColumnEvaluator.evaluate(
            sideWithProductTotal(100), sideWithProductTotal(100), col);
        assertEquals(ComparisonColumnEvaluator.NORMAL, eval.status);
    }

    // ── evaluate：后端五态扩展（MISSING/STALE，前端没有的新概念）──────────────

    @Test
    void evaluate_productTotal_negativeDiff_isRed() {
        ComparisonColumnDef col = productTotalCol();
        ComparisonColumnEvaluator.ColumnEval eval = ComparisonColumnEvaluator.evaluate(
            sideWithProductTotal(218.05), sideWithProductTotal(222.10), col);
        assertEquals(ComparisonColumnEvaluator.RED, eval.status);
        assertEquals(0, new BigDecimal("-4.05").compareTo(eval.diff));
    }

    @Test
    void evaluate_tabPair_negativeDiff_isRed_notAmber_apiMdWorkedExample() {
        // 直接对齐 api.md §2.2 的举例数据：col-2 threshold=2.00, diffAdjusted=-1.90, status=RED
        ComparisonColumnDef col = tabPairCol("材料成本");
        col.threshold = new BigDecimal("2.00");
        ComparisonViewService.SideValues quote = sideWithTab("cid-quote", null, Map.of("材料成本", 88.10));
        ComparisonViewService.SideValues costing = sideWithTab("cid-costing", null, Map.of("材料成本", 90.00));
        ComparisonColumnEvaluator.ColumnEval eval = ComparisonColumnEvaluator.evaluate(quote, costing, col);
        // diff = 88.10-90.00 = -1.90 < 0 → 负值优先于阈值判定，应为 RED 不是 AMBER
        assertEquals(ComparisonColumnEvaluator.RED, eval.status);
        assertEquals(0, new BigDecimal("-1.90").compareTo(eval.diff));
    }

    @Test
    void evaluate_tabPair_positiveDiffBelowThreshold_isAmber() {
        ComparisonColumnDef col = tabPairCol("材料成本");
        col.threshold = new BigDecimal("2.00");
        ComparisonViewService.SideValues quote = sideWithTab("cid-quote", null, Map.of("材料成本", 91.00));
        ComparisonViewService.SideValues costing = sideWithTab("cid-costing", null, Map.of("材料成本", 90.00));
        ComparisonColumnEvaluator.ColumnEval eval = ComparisonColumnEvaluator.evaluate(quote, costing, col);
        // diff = 91.00-90.00 = 1.00，0<=1.00<2.00 → AMBER
        assertEquals(ComparisonColumnEvaluator.AMBER, eval.status);
    }

    @Test
    void evaluate_tabPair_componentIdMissingFromBothSides_isStale() {
        ComparisonColumnDef col = tabPairCol("材料成本");
        ComparisonViewService.SideValues quote = sideWithTab("other-cid", null, Map.of("材料成本", 1.0));
        ComparisonViewService.SideValues costing = sideWithTab("other-cid-2", null, Map.of("材料成本", 1.0));
        ComparisonColumnEvaluator.ColumnEval eval = ComparisonColumnEvaluator.evaluate(quote, costing, col);
        assertEquals(ComparisonColumnEvaluator.STALE, eval.status);
    }

    @Test
    void evaluate_tabPair_componentIdExistsButMetricMissing_isMissing() {
        ComparisonColumnDef col = tabPairCol("材料成本");
        ComparisonViewService.SideValues quote = sideWithTab("cid-quote", null, Map.of("其它字段", 1.0));
        ComparisonViewService.SideValues costing = sideWithTab("cid-costing", null, Map.of("材料成本", 90.0));
        ComparisonColumnEvaluator.ColumnEval eval = ComparisonColumnEvaluator.evaluate(quote, costing, col);
        assertEquals(ComparisonColumnEvaluator.MISSING, eval.status);
        assertEquals("QUOTE", eval.missingSide);
    }

    @Test
    void evaluate_bothSidesMissing_missingSideIsBoth() {
        ComparisonColumnDef col = tabPairCol("材料成本");
        ComparisonViewService.SideValues quote = sideWithTab("cid-quote", null, Map.of("其它字段", 1.0));
        ComparisonViewService.SideValues costing = sideWithTab("cid-costing", null, Map.of("其它字段", 1.0));
        ComparisonColumnEvaluator.ColumnEval eval = ComparisonColumnEvaluator.evaluate(quote, costing, col);
        assertEquals(ComparisonColumnEvaluator.MISSING, eval.status);
        assertEquals("BOTH", eval.missingSide);
    }

    @Test
    void evaluate_productTotal_neverStale_evenWhenSideNull() {
        // kind=PRODUCT_TOTAL 不依赖 componentId，跨模板通用（backtask B4.3）——side=null 时落到 MISSING 而非 STALE
        ComparisonColumnDef col = productTotalCol();
        ComparisonColumnEvaluator.ColumnEval eval = ComparisonColumnEvaluator.evaluate(null, sideWithProductTotal(100), col);
        assertEquals(ComparisonColumnEvaluator.MISSING, eval.status);
    }
}
