package com.cpq.priceadjust.service;

import com.cpq.costing.service.ComparisonViewService;
import com.cpq.priceadjust.dto.ComparisonColumnDef;

import java.math.BigDecimal;

/**
 * task-0729 B4.3 · 比对差异/着色算法（后端新建）。
 *
 * <p>取值算法（{@link #getColumnValue}）逐行移植自 {@code comparisonMapping.ts:84-101}
 * 的 {@code getColumnValue}；差异算法（{@link #computeDiff}）移植自同文件 104-107 行的
 * {@code computeDiff}。着色判定（{@link #classifyDiff}）在前端"红/橙/无色"三态基础上
 * 按 backtask B4.3 扩出 MISSING/STALE 两态（前端没有这两态概念，是后端预算阶段特有的
 * 判定，不是从前端照搬）：
 * <pre>
 * RED     ⟸ 差异（成本差额）&lt; 0                    计入 breached_count
 * MISSING ⟸ 任一侧取不到值                        计入 breached_count（E4）
 * AMBER   ⟸ 0 ≤ 差异 &lt; threshold                  计入 amber_count
 * STALE   ⟸ componentId/metric 因模板改版失效       不计入 breached/amber
 * NORMAL  ⟸ 其余
 * </pre>
 *
 * <p>纯函数，不发起任何 DB/网络调用，便于对齐 {@code comparisonMapping.test.ts} 的黄金用例。
 */
public final class ComparisonColumnEvaluator {

    public static final String RED = "RED";
    public static final String AMBER = "AMBER";
    public static final String NORMAL = "NORMAL";
    public static final String MISSING = "MISSING";
    public static final String STALE = "STALE";
    private static final String TAB_TOTAL_KEY = "__TAB_TOTAL__";

    private ComparisonColumnEvaluator() {}

    /**
     * 取某列某侧的原始数值（逐行移植 comparisonMapping.ts:84-101）：
     * PRODUCT_TOTAL → side.productTotal；TAB_PAIR → side.tabs[componentId].tabTotal
     * （metric===__TAB_TOTAL__）或 .subtotals[metric]。取不到任一层级 → null。
     */
    public static BigDecimal getColumnValue(ComparisonViewService.SideValues side, ComparisonColumnDef col, boolean isQuoteSide) {
        if (side == null) return null;
        if (col.isProductTotal()) return side.productTotal;

        String componentId = isQuoteSide ? col.quoteComponentId : col.costingComponentId;
        String metric = isQuoteSide ? col.quoteMetric : col.costingMetric;
        if (componentId == null || metric == null) return null;

        ComparisonViewService.TabVal tab = side.tabs != null ? side.tabs.get(componentId) : null;
        if (tab == null) return null;

        if (TAB_TOTAL_KEY.equals(metric)) return tab.tabTotal;
        return tab.subtotals != null ? tab.subtotals.get(metric) : null;
    }

    /**
     * 该列在给定 side 是否仍能定位到 componentId（STALE 判定用）。
     * kind=PRODUCT_TOTAL 恒真（不依赖 componentId，跨模板通用，backtask B4.3「比对列定位」）。
     */
    public static boolean componentExists(ComparisonViewService.SideValues side, ComparisonColumnDef col, boolean isQuoteSide) {
        if (col.isProductTotal()) return true;
        String componentId = isQuoteSide ? col.quoteComponentId : col.costingComponentId;
        if (componentId == null) return false;
        return side != null && side.tabs != null && side.tabs.containsKey(componentId);
    }

    /** 差异值 = 报价值 − 核价值；任一侧取不到 → null（comparisonMapping.ts:104-107 逐行移植）。 */
    public static BigDecimal computeDiff(BigDecimal quoteVal, BigDecimal costingVal) {
        if (quoteVal == null || costingVal == null) return null;
        return quoteVal.subtract(costingVal);
    }

    /** 前端三态着色判定（comparisonMapping.ts:110-115 逐行移植）：diff&lt;0 红；否则 diff&lt;threshold 橙；否则无色。 */
    public static String classifyDiffThreeState(BigDecimal diff, BigDecimal threshold) {
        if (diff == null) return "none";
        BigDecimal th = threshold != null ? threshold : BigDecimal.ZERO;
        if (diff.signum() < 0) return "red";
        if (diff.compareTo(th) < 0) return "orange";
        return "none";
    }

    /** 单列评估结果：取值 + 差异 + 后端五态着色 + missingSide。 */
    public static final class ColumnEval {
        public BigDecimal quoteVal;
        public BigDecimal costingVal;
        public BigDecimal diff;
        public String status;
        /** status=MISSING 时说明缺哪一侧：QUOTE/COSTING/BOTH */
        public String missingSide;
    }

    /** 后端五态评估入口：先判 STALE，再判 MISSING，最后按前端同款红/橙/无色规则映射到 RED/AMBER/NORMAL。 */
    public static ColumnEval evaluate(
            ComparisonViewService.SideValues quoteSide, ComparisonViewService.SideValues costingSide, ComparisonColumnDef col) {
        ColumnEval r = new ColumnEval();

        boolean quoteExists = componentExists(quoteSide, col, true);
        boolean costingExists = componentExists(costingSide, col, false);
        if (!quoteExists || !costingExists) {
            r.status = STALE;
            return r;
        }

        r.quoteVal = getColumnValue(quoteSide, col, true);
        r.costingVal = getColumnValue(costingSide, col, false);
        if (r.quoteVal == null || r.costingVal == null) {
            r.status = MISSING;
            if (r.quoteVal == null && r.costingVal == null) r.missingSide = "BOTH";
            else r.missingSide = r.quoteVal == null ? "QUOTE" : "COSTING";
            return r;
        }

        r.diff = computeDiff(r.quoteVal, r.costingVal);
        BigDecimal th = col.threshold != null ? col.threshold : BigDecimal.ZERO;
        if (r.diff.signum() < 0) r.status = RED;
        else if (r.diff.compareTo(th) < 0) r.status = AMBER;
        else r.status = NORMAL;
        return r;
    }
}
