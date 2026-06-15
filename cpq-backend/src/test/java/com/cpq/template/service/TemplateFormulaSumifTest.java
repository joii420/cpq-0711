package com.cpq.template.service;

import com.cpq.formula.predicate.ConditionPredicateParser;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 8: EXCEL 线 SUMIF 族条件聚合单元测试。
 *
 * <p>通过包内可测方法 {@link TemplateFormulaService#aggregateWithPredicate} 验证：
 * <ul>
 *   <li>predicate 过滤 + SUM 聚合（SUMIF）</li>
 *   <li>predicate 过滤 + COUNT（COUNTIF，无 valueExpr）</li>
 *   <li>valueExpr 含 [页签.字段] 括号记法剥壳（核心 Bug 修复）</li>
 * </ul>
 * 不依赖 DB / DataLoader / GlobalVariableService — rows 由测试直接构造传入。
 * 可用 {@code new TemplateFormulaService()} 直接测，因为 aggregateWithPredicate
 * 内部只调 evalRowExpression / aggregate / toBigDecimal，均不依赖 @Inject 字段。
 */
class TemplateFormulaSumifTest {

    private final TemplateFormulaService svc = new TemplateFormulaService();

    @Test
    void sumif_filters_rows_single_value() {
        var rows = List.<Map<String, Object>>of(
            Map.of("类型", "管理费", "金额", "10"),
            Map.of("类型", "运费",  "金额", "5"),
            Map.of("类型", "管理费", "金额", "7"));
        var pred = new ConditionPredicateParser().parse("[A.类型] = '管理费'");
        BigDecimal r = svc.aggregateWithPredicate("SUMIF", rows, pred, "金额");
        assertEquals(0, new BigDecimal("17").compareTo(r),
            "SUMIF 应只对 类型=管理费 的两行求和: 10+7=17，实际=" + r);
    }

    @Test
    void countif_counts_matching_rows() {
        var rows = List.<Map<String, Object>>of(
            Map.of("类型", "管理费"),
            Map.of("类型", "运费"),
            Map.of("类型", "管理费"));
        var pred = new ConditionPredicateParser().parse("[A.类型] = '管理费'");
        BigDecimal r = svc.aggregateWithPredicate("COUNTIF", rows, pred, null);
        assertEquals(0, new BigDecimal("2").compareTo(r),
            "COUNTIF 应计 类型=管理费 行数=2，实际=" + r);
    }

    /**
     * 核心 Bug 修复：valueExpr 使用用户真实语法 [页签A.金额]（带括号+页签前缀）。
     *
     * <p>修复前：JEXL 把 [页签A.金额] 当数组/中文 tokenize 失败 → 整行返 null → 空集 → 0。
     * 修复后：stripFieldRefs 剥壳 [页签A.金额] → 金额 → evalValueExpr 直接 row.get("金额") → 17。
     */
    @Test
    void sumif_bracketed_value_expr() {
        var rows = List.<Map<String, Object>>of(
            Map.of("类型", "管理费", "金额", "10"),
            Map.of("类型", "运费",  "金额", "5"),
            Map.of("类型", "管理费", "金额", "7"));
        // 用户真实语法：valueExpr 带 [页签.字段] 括号记法
        var pred = new ConditionPredicateParser().parse("[页签A.类型] = '管理费'");
        BigDecimal r = svc.aggregateWithPredicate("SUMIF", rows, pred, "[页签A.金额]");
        assertEquals(0, new BigDecimal("17").compareTo(r),
            "SUMIF([页签A.类型]='管理费', [页签A.金额]) 应得 17，实际=" + r);
    }

    /**
     * 核心 Bug 修复：valueExpr 含 [页签.字段] 的复合算术表达式。
     *
     * <p>例如 [页签A.金额] * [页签A.数量]，修复前中文算术均 tokenize 失败 → 0。
     * 修复后：stripFieldRefs → "金额 * 数量" → JEXL 替换中文占位符后正确求值。
     */
    @Test
    void sumif_bracketed_arithmetic() {
        // 金额: 10, 数量: 2 → 乘积 20；金额: 7, 数量: 3 → 乘积 21；运费行被过滤
        var rows = List.<Map<String, Object>>of(
            Map.of("类型", "管理费", "金额", "10", "数量", "2"),
            Map.of("类型", "运费",  "金额", "5",  "数量", "1"),
            Map.of("类型", "管理费", "金额", "7",  "数量", "3"));
        var pred = new ConditionPredicateParser().parse("[页签A.类型] = '管理费'");
        BigDecimal r = svc.aggregateWithPredicate("SUMIF", rows, pred, "[页签A.金额] * [页签A.数量]");
        assertEquals(0, new BigDecimal("41").compareTo(r),
            "SUMIF([页签A.类型]='管理费', [页签A.金额]*[页签A.数量]) 应得 20+21=41，实际=" + r);
    }

    /**
     * stripFieldRefs 纯函数验证：各种括号记法均正确剥壳。
     */
    @Test
    void stripFieldRefs_various_forms() {
        // [页签.字段] → 字段
        assertEquals("金额", svc.stripFieldRefs("[页签A.金额]"));
        // [字段] 无点 → 整体
        assertEquals("金额", svc.stripFieldRefs("[金额]"));
        // 复合算术
        assertEquals("金额 * 数量", svc.stripFieldRefs("[页签A.金额] * [页签A.数量]"));
        // 无括号（裸字段名）→ 原样
        assertEquals("金额", svc.stripFieldRefs("金额"));
        // 宿主页签引用 → 剥壳（EXCEL/小计线无宿主行时 evalValueExpr 取不到值按0/缺省处理，见 spec §9 P0-5）
        assertEquals("数量", svc.stripFieldRefs("[宿主页签.数量]"));
        // null → null
        assertNull(svc.stripFieldRefs(null));
    }
}
