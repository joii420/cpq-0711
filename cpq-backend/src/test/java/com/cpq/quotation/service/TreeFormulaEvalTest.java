package com.cpq.quotation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cpq.quotation.service.formula.TreeRelations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * task-0803：{@code tree_ref}（PGET / C* 族）与 {@code tree_attr} 的求值语义。
 *
 * <p>纯 JUnit（不用 {@code @QuarkusTest}）—— BL-0095。
 * 覆盖需求 §8.1 的 AC-1~AC-9、AC-14。
 */
class TreeFormulaEvalTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 三层树：根 n1 → 子 n2/n3 → 孙 n4（挂 n2 下）。 */
    private JsonNode threeLevelRows() {
        return json("""
            [
              {"__nodeId":"n1","__parentId":null,"__lvl":0},
              {"__nodeId":"n2","__parentId":"n1","__lvl":1},
              {"__nodeId":"n3","__parentId":"n1","__lvl":1},
              {"__nodeId":"n4","__parentId":"n2","__lvl":2}
            ]
            """);
    }

    /**
     * 造一个树求值上下文。
     *
     * @param rawPerRow      每行的「已解析原始值」（字段名 → 值），判「有值」用
     * @param valuePerRow    每行的字段数值（供 field token 取值）
     * @param formulaColumns 公式列名集合（判据 6：公式列恒有值）
     */
    private FormulaCalculator.TreeEvalContext ctxOf(JsonNode rows,
                                                    List<Map<String, Object>> rawPerRow,
                                                    List<Map<String, Double>> valuePerRow,
                                                    Set<String> formulaColumns) {
        TreeRelations rel = TreeRelations.of(rows, null);
        List<FormulaCalculator.RowContext> rcs = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            FormulaCalculator.RowContext rc = new FormulaCalculator.RowContext();
            rc.fieldValues = new LinkedHashMap<>(valuePerRow.get(i));
            rc.currentRowRaw = new LinkedHashMap<>(rawPerRow.get(i));
            rc.rowIndex = i;
            rcs.add(rc);
        }
        FormulaCalculator.TreeEvalContext t =
            new FormulaCalculator.TreeEvalContext(rel, rcs, rawPerRow, formulaColumns);
        for (FormulaCalculator.RowContext rc : rcs) rc.tree = t;
        return t;
    }

    private Map<String, Object> raw(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private Map<String, Double> vals(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1] == null ? null : ((Number) kv[i + 1]).doubleValue());
        }
        return m;
    }

    private JsonNode pget(String col) {
        return json("""
            [{"type":"tree_ref","dir":"PARENT","agg":"NONE",
              "targetExpr":[{"type":"field","value":"%s"}]}]
            """.formatted(col));
    }

    private JsonNode cagg(String agg, String col) {
        return json("""
            [{"type":"tree_ref","dir":"CHILD","agg":"%s",
              "targetExpr":[{"type":"field","value":"%s"}]}]
            """.formatted(agg, col));
    }

    private JsonNode attr(String a) {
        return json("[{\"type\":\"tree_attr\",\"attr\":\"%s\"}]".formatted(a));
    }

    // ───────────────────────── PGET ─────────────────────────

    @Test
    @DisplayName("AC-1: PGET 取直接父行的列值")
    void pgetTakesParentValue() {
        JsonNode rows = threeLevelRows();
        var t = ctxOf(rows,
            List.of(raw("成本", 100), raw("成本", 10), raw("成本", 20), raw("成本", 1)),
            List.of(vals("成本", 100), vals("成本", 10), vals("成本", 20), vals("成本", 1)),
            Set.of());
        assertEquals(100.0, calc.evaluateExpression(pget("成本"), t.rowContexts().get(1)).doubleValue(),
            1e-9, "n2 的父是 n1，成本 100");
        assertEquals(10.0, calc.evaluateExpression(pget("成本"), t.rowContexts().get(3)).doubleValue(),
            1e-9, "n4 的父是 n2，成本 10");
    }

    @Test
    @DisplayName("AC-2: PGET 在根行返回 0")
    void pgetOnRootIsZero() {
        JsonNode rows = threeLevelRows();
        var t = ctxOf(rows,
            List.of(raw("成本", 100), raw("成本", 10), raw("成本", 20), raw("成本", 1)),
            List.of(vals("成本", 100), vals("成本", 10), vals("成本", 20), vals("成本", 1)),
            Set.of());
        assertEquals(0.0, calc.evaluateExpression(pget("成本"), t.rowContexts().get(0)).doubleValue(), 1e-9);
    }

    // ───────────────────────── C* 族 ─────────────────────────

    @Test
    @DisplayName("AC-3: CSUM 只算直接子行，不含孙辈")
    void csumDirectChildrenOnly() {
        JsonNode rows = threeLevelRows();
        var t = ctxOf(rows,
            List.of(raw("成本", 0), raw("成本", 10), raw("成本", 20), raw("成本", 999)),
            List.of(vals("成本", 0), vals("成本", 10), vals("成本", 20), vals("成本", 999)),
            Set.of());
        assertEquals(30.0, calc.evaluateExpression(cagg("SUM", "成本"), t.rowContexts().get(0)).doubleValue(),
            1e-9, "根的直接子是 n2(10)+n3(20)=30，不含孙 n4(999)");
    }

    @Test
    @DisplayName("AC-7: 叶子行的 C* 族全部返回 0")
    void aggOnLeafIsZero() {
        JsonNode rows = threeLevelRows();
        var t = ctxOf(rows,
            List.of(raw("成本", 1), raw("成本", 1), raw("成本", 1), raw("成本", 1)),
            List.of(vals("成本", 1), vals("成本", 1), vals("成本", 1), vals("成本", 1)),
            Set.of());
        var leaf = t.rowContexts().get(2);   // n3 无子
        for (String agg : List.of("SUM", "AVG", "MAX", "MIN", "COUNT")) {
            assertEquals(0.0, calc.evaluateExpression(cagg(agg, "成本"), leaf).doubleValue(), 1e-9,
                agg + " 在叶子行应返 0");
        }
    }

    @Test
    @DisplayName("AC-4/AC-5: 空值不参与 —— CAVG 分母只数有值行，CMIN 不被空行拉成 0")
    void emptyChildrenExcluded() {
        // 根 n1 有三个直接子：值 5 / 空 / 8
        JsonNode rows = json("""
            [
              {"__nodeId":"n1","__parentId":null,"__lvl":0},
              {"__nodeId":"a","__parentId":"n1","__lvl":1},
              {"__nodeId":"b","__parentId":"n1","__lvl":1},
              {"__nodeId":"c","__parentId":"n1","__lvl":1}
            ]
            """);
        var t = ctxOf(rows,
            List.of(raw(), raw("单价", 5), raw(), raw("单价", 8)),      // b 行整行无值
            List.of(vals(), vals("单价", 5), vals(), vals("单价", 8)),
            Set.of());                                                  // 单价不是公式列
        var root = t.rowContexts().get(0);
        assertEquals(13.0, calc.evaluateExpression(cagg("SUM", "单价"), root).doubleValue(), 1e-9);
        assertEquals(6.5, calc.evaluateExpression(cagg("AVG", "单价"), root).doubleValue(), 1e-9,
            "分母只数有值的 2 行 → 13/2");
        assertEquals(5.0, calc.evaluateExpression(cagg("MIN", "单价"), root).doubleValue(), 1e-9,
            "空行不参与，最小值是 5 而不是 0");
        assertEquals(2.0, calc.evaluateExpression(cagg("COUNT", "单价"), root).doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("AC-6: 数值 0 是有值 —— CMIN 应返 0 而非跳过")
    void zeroCountsAsValue() {
        JsonNode rows = json("""
            [
              {"__nodeId":"n1","__parentId":null,"__lvl":0},
              {"__nodeId":"a","__parentId":"n1","__lvl":1},
              {"__nodeId":"b","__parentId":"n1","__lvl":1},
              {"__nodeId":"c","__parentId":"n1","__lvl":1}
            ]
            """);
        var t = ctxOf(rows,
            List.of(raw(), raw("单价", 0), raw("单价", 5), raw("单价", 8)),
            List.of(vals(), vals("单价", 0), vals("单价", 5), vals("单价", 8)),
            Set.of());
        var root = t.rowContexts().get(0);
        assertEquals(0.0, calc.evaluateExpression(cagg("MIN", "单价"), root).doubleValue(), 1e-9);
        assertEquals(3.0, calc.evaluateExpression(cagg("COUNT", "单价"), root).doubleValue(), 1e-9,
            "0 算有值，分母是 3");
    }

    @Test
    @DisplayName("判据 6: 公式列恒有值 —— 空行的公式列照样参与（2026-08-03 裁决）")
    void formulaColumnAlwaysHasValue() {
        JsonNode rows = json("""
            [
              {"__nodeId":"n1","__parentId":null,"__lvl":0},
              {"__nodeId":"a","__parentId":"n1","__lvl":1},
              {"__nodeId":"b","__parentId":"n1","__lvl":1}
            ]
            """);
        // b 行整行无取数值，但「成本」是公式列且已算出 0
        var t = ctxOf(rows,
            List.of(raw(), raw("成本", 10), raw()),
            List.of(vals(), vals("成本", 10), vals("成本", 0)),
            Set.of("成本"));
        var root = t.rowContexts().get(0);
        assertEquals(5.0, calc.evaluateExpression(cagg("AVG", "成本"), root).doubleValue(), 1e-9,
            "公式列恒有值 → 分母 2 → 10/2=5（明示接受的取舍，需求 §4.3.4 判据 6）");
    }

    @Test
    @DisplayName("判据 5: targetExpr 无 field token —— 所有子行都算有值（CSUM(1)=子行数）")
    void constantExprCountsAllChildren() {
        JsonNode rows = threeLevelRows();
        var t = ctxOf(rows,
            List.of(raw(), raw(), raw(), raw()),
            List.of(vals(), vals(), vals(), vals()),
            Set.of());
        JsonNode constExpr = json("""
            [{"type":"tree_ref","dir":"CHILD","agg":"SUM","targetExpr":[{"type":"number","value":"1"}]}]
            """);
        assertEquals(2.0, calc.evaluateExpression(constExpr, t.rowContexts().get(0)).doubleValue(), 1e-9,
            "根有 2 个直接子");
    }

    @Test
    @DisplayName("AC-14: targetExpr 支持表达式 —— CSUM(用量 × 单价)")
    void aggOverExpression() {
        JsonNode rows = json("""
            [
              {"__nodeId":"n1","__parentId":null,"__lvl":0},
              {"__nodeId":"a","__parentId":"n1","__lvl":1},
              {"__nodeId":"b","__parentId":"n1","__lvl":1}
            ]
            """);
        var t = ctxOf(rows,
            List.of(raw(), raw("用量", 2, "单价", 3), raw("用量", 4, "单价", 5)),
            List.of(vals(), vals("用量", 2, "单价", 3), vals("用量", 4, "单价", 5)),
            Set.of());
        JsonNode expr = json("""
            [{"type":"tree_ref","dir":"CHILD","agg":"SUM","targetExpr":[
                {"type":"field","value":"用量"},{"type":"operator","value":"*"},{"type":"field","value":"单价"}]}]
            """);
        assertEquals(26.0, calc.evaluateExpression(expr, t.rowContexts().get(0)).doubleValue(), 1e-9,
            "2*3 + 4*5 = 26");
    }

    // ───────────────────────── 树属性 ─────────────────────────

    @Test
    @DisplayName("AC-8: 树属性 —— 层级 / 是否叶子 / 是否根")
    void treeAttributes() {
        JsonNode rows = threeLevelRows();
        var t = ctxOf(rows,
            List.of(raw(), raw(), raw(), raw()),
            List.of(vals(), vals(), vals(), vals()),
            Set.of());
        assertEquals(0.0, calc.evaluateExpression(attr("LVL"), t.rowContexts().get(0)).doubleValue(), 1e-9);
        assertEquals(2.0, calc.evaluateExpression(attr("LVL"), t.rowContexts().get(3)).doubleValue(), 1e-9);
        assertEquals(1.0, calc.evaluateExpression(attr("IS_ROOT"), t.rowContexts().get(0)).doubleValue(), 1e-9);
        assertEquals(0.0, calc.evaluateExpression(attr("IS_ROOT"), t.rowContexts().get(1)).doubleValue(), 1e-9);
        assertEquals(0.0, calc.evaluateExpression(attr("IS_LEAF"), t.rowContexts().get(0)).doubleValue(), 1e-9);
        assertEquals(1.0, calc.evaluateExpression(attr("IS_LEAF"), t.rowContexts().get(2)).doubleValue(), 1e-9);
    }

    // ───────────────────────── 兜底 ─────────────────────────

    @Test
    @DisplayName("AC-20: 拿不到树上下文（非树页签）→ 返 0，不抛异常")
    void noTreeContextReturnsZero() {
        FormulaCalculator.RowContext plain = new FormulaCalculator.RowContext();
        assertEquals(0.0, calc.evaluateExpression(pget("成本"), plain).doubleValue(), 1e-9);
        assertEquals(0.0, calc.evaluateExpression(cagg("SUM", "成本"), plain).doubleValue(), 1e-9);
        assertEquals(0.0, calc.evaluateExpression(attr("LVL"), plain).doubleValue(), 1e-9);
    }
}
