package com.cpq.engine.formula;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯 JUnit 单测（无 @QuarkusTest / DB）：task-0803 Task5⑦ —— 旧版 token 求值路径
 * （{@link FormulaCalculationService#buildJexlExpression}）遇到 tree_ref/tree_attr 必须显式按
 * 0 处理（不落进 default 分支被静默忽略拼出语法不完整表达式），与 {@code FormulaCalculator}
 * 拿不到树上下文时的兜底口径（返 0）对齐。
 *
 * <p>{@code buildJexlExpression} 是 package-private，同包直接 new 调用不需要 CDI/DB。
 */
@DisplayName("FormulaCalculationService — tree_ref/tree_attr 兜底按 0 处理（非静默算错）")
class FormulaCalculationServiceTreeTokenTest {

    private final FormulaCalculationService svc = new FormulaCalculationService();

    @Test
    @DisplayName("单独一个 tree_ref token → 求值得 0（不抛异常）")
    void treeRefToken_evaluatesToZero() {
        List<Map<String, Object>> tokens = List.of(
            Map.of("type", "tree_ref", "dir", "PARENT", "agg", "NONE",
                   "targetExpr", List.of(Map.of("type", "field", "value", "累计用量")))
        );
        String jexlExpr = svc.buildJexlExpression(tokens, new HashMap<>(), new HashMap<>(), null);
        // 期望是一个合法的、能求值出 0 的表达式（"0B"），而非空串/半截表达式。
        assertEquals("0B", jexlExpr);
    }

    @Test
    @DisplayName("单独一个 tree_attr token → 求值得 0（不抛异常）")
    void treeAttrToken_evaluatesToZero() {
        List<Map<String, Object>> tokens = List.of(
            Map.of("type", "tree_attr", "attr", "LVL")
        );
        String jexlExpr = svc.buildJexlExpression(tokens, new HashMap<>(), new HashMap<>(), null);
        assertEquals("0B", jexlExpr);
    }

    @Test
    @DisplayName("tree_ref 参与四则运算 → 整体仍能正确求值（0 参与加法不影响其余项）")
    void treeRefToken_inArithmetic_evaluatesCorrectly() {
        Map<String, Object> fieldTok = new HashMap<>();
        fieldTok.put("type", "field");
        fieldTok.put("value", "单价");
        Map<String, Object> opTok = new HashMap<>();
        opTok.put("type", "operator");
        opTok.put("value", "+");
        Map<String, Object> treeTok = new HashMap<>();
        treeTok.put("type", "tree_ref");
        treeTok.put("dir", "CHILD");
        treeTok.put("agg", "SUM");
        treeTok.put("targetExpr", List.of(Map.of("type", "field", "value", "用量")));

        List<Map<String, Object>> tokens = List.of(fieldTok, opTok, treeTok);
        Map<String, Object> rowData = new HashMap<>();
        rowData.put("单价", new java.math.BigDecimal("10"));

        String jexlExpr = svc.buildJexlExpression(tokens, rowData, new HashMap<>(), null);
        assertEquals("10B+0B", jexlExpr);
    }
}
