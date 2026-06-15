package com.cpq.formula.predicate;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConditionPredicateParserTest {
    private final ConditionPredicateEvaluator ev = new ConditionPredicateEvaluator();

    private boolean run(String cond, Map<String,Object> arow, Map<String,Object> host) {
        return ev.test(new ConditionPredicateParser().parse(cond), arow, host);
    }

    @Test void field_eq_string_literal() {
        assertTrue(run("[页签A.类型] = '管理费'", Map.of("类型","管理费"), Map.of()));
        assertFalse(run("[页签A.类型] = '管理费'", Map.of("类型","运费"), Map.of()));
    }

    @Test void field_eq_field_cross_tab() {
        // 第二个 [..] 默认按 hostField 解析（B 侧）
        assertTrue(run("[页签A.a] = [页签B.b]", Map.of("a","X"), Map.of("b","X")));
    }

    @Test void and_or_with_number_and_parens() {
        var arow = Map.<String,Object>of("类型","管理费","金额","1500");
        assertTrue(run("[A.类型]='管理费' AND [A.金额] > 1000", arow, Map.of()));
        assertTrue(run("([A.类型]='运费') OR [A.金额] >= 1500", arow, Map.of()));
    }

    @Test void ne_operators() {
        assertTrue(run("[A.类型] <> '运费'", Map.of("类型","管理费"), Map.of()));
        assertTrue(run("[A.类型] != '运费'", Map.of("类型","管理费"), Map.of()));
    }

    @Test void malformed_throws() {
        assertThrows(RuntimeException.class, () -> new ConditionPredicateParser().parse("[A.x] = "));
    }

    // —— 嵌套括号求值 ——
    @Test void nested_parens_complex_expr() {
        // ([A.类型]='运费') OR ([A.金额] > 1000 AND [A.金额] < 2000)
        String cond = "([A.类型]='运费') OR ([A.金额] > 1000 AND [A.金额] < 2000)";

        // 类型=运费 → 左 OR 分支命中
        assertTrue(run(cond, Map.of("类型","运费","金额","500"), Map.of()));
        // 类型≠运费，金额1500 在 (1000,2000) 之间 → 右 AND 分支命中
        assertTrue(run(cond, Map.of("类型","管理费","金额","1500"), Map.of()));
        // 类型≠运费，金额2000 不满足 <2000 → 两侧均 false
        assertFalse(run(cond, Map.of("类型","管理费","金额","2000"), Map.of()));
    }

    // —— 错误格式抛异常 ——
    @Test void unclosed_string_throws() {
        assertThrows(RuntimeException.class,
            () -> new ConditionPredicateParser().parse("[A.类型] = '未闭合"),
            "未闭合字符串应抛异常");
    }

    @Test void missing_right_paren_throws() {
        assertThrows(RuntimeException.class,
            () -> new ConditionPredicateParser().parse("([A.类型] = '运费'"),
            "缺右括号应抛异常");
    }
}
