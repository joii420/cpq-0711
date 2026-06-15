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
}
