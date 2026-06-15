package com.cpq.formula.predicate;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static com.cpq.formula.predicate.ConditionPredicate.*;
import static org.junit.jupiter.api.Assertions.*;

class ConditionPredicateEvaluatorTest {
    private final ConditionPredicateEvaluator ev = new ConditionPredicateEvaluator();

    private boolean eval(ConditionPredicate p, Map<String,Object> arow, Map<String,Object> host) {
        return ev.test(p, arow, host);
    }

    @Test void null_predicate_is_true() {
        assertTrue(eval(null, Map.of(), Map.of()));
    }

    @Test void source_field_eq_literal_text() {
        var p = new Comparison(CmpOp.EQ, new SourceField("类型"), new Literal("管理费"));
        assertTrue(eval(p, Map.of("类型","管理费"), Map.of()));
        assertFalse(eval(p, Map.of("类型","运费"), Map.of()));
    }

    @Test void numeric_eq_ignores_format() {
        var p = new Comparison(CmpOp.EQ, new SourceField("n"), new Literal("1000"));
        assertTrue(eval(p, Map.of("n", "1000.0"), Map.of()));
    }

    @Test void source_vs_host_field_eq() {
        var p = new Comparison(CmpOp.EQ, new SourceField("a"), new HostField("b"));
        assertTrue(eval(p, Map.of("a","X"), Map.of("b","X")));
        assertFalse(eval(p, Map.of("a","X"), Map.of("b","Y")));
    }

    @Test void blank_makes_eq_false_and_ne_true() {
        var eq = new Comparison(CmpOp.EQ, new SourceField("a"), new Literal("x"));
        var ne = new Comparison(CmpOp.NE, new SourceField("a"), new Literal("x"));
        var blankRow = new java.util.HashMap<String,Object>(); blankRow.put("a", null);
        assertFalse(eval(eq, blankRow, Map.of()));
        assertTrue(eval(ne, blankRow, Map.of()));
    }

    @Test void gt_numeric_only() {
        var p = new Comparison(CmpOp.GT, new SourceField("金额"), new Literal("1000"));
        assertTrue(eval(p, Map.of("金额","1500"), Map.of()));
        assertFalse(eval(p, Map.of("金额","500"), Map.of()));
        assertFalse(eval(p, Map.of("金额","非数字"), Map.of())); // 不可解析 → false
    }

    @Test void and_or_nesting() {
        var c1 = new Comparison(CmpOp.EQ, new SourceField("类型"), new Literal("管理费"));
        var c2 = new Comparison(CmpOp.GT, new SourceField("金额"), new Literal("1000"));
        var and = new Bool(BoolOp.AND, java.util.List.of(c1, c2));
        var or  = new Bool(BoolOp.OR,  java.util.List.of(c1, c2));
        assertTrue(eval(and, Map.of("类型","管理费","金额","1500"), Map.of()));
        assertFalse(eval(and, Map.of("类型","管理费","金额","500"), Map.of()));
        assertTrue(eval(or, Map.of("类型","运费","金额","1500"), Map.of()));
        assertFalse(eval(or, Map.of("类型","运费","金额","500"), Map.of()));
    }

    // —— ConditionPredicateJson round-trip ——
    @Test void json_parse_comparison() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"op\":\"=\",\"lhs\":{\"kind\":\"sourceField\",\"field\":\"类型\"}," +
            "\"rhs\":{\"kind\":\"literal\",\"value\":\"管理费\"}}");
        var p = ConditionPredicateJson.fromJson(json);
        assertTrue(ev.test(p, Map.of("类型","管理费"), Map.of()));
    }

    @Test void json_parse_bool_tree() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"bool\":\"AND\",\"children\":[" +
            "{\"op\":\">\",\"lhs\":{\"kind\":\"sourceField\",\"field\":\"金额\"},\"rhs\":{\"kind\":\"literal\",\"value\":\"1000\"}}]}");
        var p = ConditionPredicateJson.fromJson(json);
        assertTrue(ev.test(p, Map.of("金额","1500"), Map.of()));
    }

    @Test void json_null_or_missing_is_null_predicate() {
        assertNull(ConditionPredicateJson.fromJson(null));
    }

    // —— 空 children 边界 ——
    @Test void and_empty_children_is_true() {
        var p = new Bool(BoolOp.AND, java.util.List.of());
        assertTrue(eval(p, Map.of(), Map.of()), "AND 无子条件应为 true（空合取）");
    }

    @Test void or_empty_children_is_false() {
        var p = new Bool(BoolOp.OR, java.util.List.of());
        assertFalse(eval(p, Map.of(), Map.of()), "OR 无子条件应为 false（空析取）");
    }
}
