package com.cpq.quotation.service;

import com.cpq.formula.predicate.ConditionPredicate;
import com.cpq.formula.predicate.ConditionPredicateEvaluator;
import com.cpq.formula.predicate.ConditionPredicateJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0803（2026-08-04）：SUMIF 族条件按<b>源页签行</b>的树属性过滤。
 *
 * <p>设计要点：谓词求值器 {@code resolve(SourceField)} 就是 {@code arow.get(字段名)}，
 * 所以本特性<b>没有改求值器</b> —— 而是由 {@code CardSnapshotService.injectTreeAttrsForCrossTab}
 * 把「是否叶子/是否根/层级」物化成源行上的普通键。本测试固化这个契约的两端：
 * ① 物化后谓词能正确过滤；② 未物化（非树源页签）时不会误命中。
 */
@DisplayName("SUMIF 条件按源行树属性过滤（task-0803）")
class CrossTabTreeAttrPredicateTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final ConditionPredicateEvaluator eval = new ConditionPredicateEvaluator();

    private static Map<String, Object> row(String no, Object leaf, Object lvl, double amt) {
        Map<String, Object> r = new HashMap<>();
        r.put("料号", no);
        if (leaf != null) r.put("是否叶子", leaf);
        if (lvl != null) r.put("层级", lvl);
        r.put("金额", amt);
        return r;
    }

    private ConditionPredicate p(String json) throws Exception {
        return ConditionPredicateJson.fromJson(M.readTree(json));
    }

    /** 源页签是 BOM 树、属性已物化：只有叶子行命中。 */
    @Test
    @DisplayName("是否叶子=1 只命中叶子行")
    void filtersLeafRows() throws Exception {
        List<Map<String, Object>> src = List.of(
            row("R", 0, 1, 100),      // 根，非叶
            row("A", 0, 2, 40),       // 中间，非叶
            row("A1", 1, 3, 25),      // 叶
            row("A2", 1, 3, 15));     // 叶
        ConditionPredicate pred = p("""
            {"op":"=","lhs":{"kind":"sourceField","field":"是否叶子"},"rhs":{"kind":"literal","value":"1"}}""");

        double sum = 0;
        int hits = 0;
        for (Map<String, Object> r : src) {
            if (eval.test(pred, r, Map.of())) { hits++; sum += (double) r.get("金额"); }
        }
        assertEquals(2, hits, "只应命中两个叶子行");
        assertEquals(40.0, sum, 1e-9, "叶子金额之和 = 25 + 15");
    }

    /** 层级支持数值比较（>=2 排除根）。 */
    @Test
    @DisplayName("层级>=2 排除根行（根 lvl=1）")
    void filtersByLevel() throws Exception {
        List<Map<String, Object>> src = List.of(
            row("R", 0, 1, 100), row("A", 0, 2, 40), row("A1", 1, 3, 25));
        ConditionPredicate pred = p("""
            {"op":">=","lhs":{"kind":"sourceField","field":"层级"},"rhs":{"kind":"literal","value":"2"}}""");

        double sum = 0;
        for (Map<String, Object> r : src) if (eval.test(pred, r, Map.of())) sum += (double) r.get("金额");
        assertEquals(65.0, sum, 1e-9, "根行(lvl=1)必须被排除");
    }

    /**
     * 🔒 守卫：源页签非树（属性未物化）时，条件恒不命中而不是误命中全部。
     * 求值器的 valEquals 对 blank 一律返 false，故缺键 → 不命中。
     * 若哪天有人给 valEquals 加了「缺失视为 0」之类的宽容，本用例会炸。
     */
    @Test
    @DisplayName("非树源页签未物化树属性 → 不命中（不误当作 0/全选）")
    void missingAttrNeverMatches() throws Exception {
        List<Map<String, Object>> src = List.of(
            row("X", null, null, 10), row("Y", null, null, 20));
        ConditionPredicate leaf1 = p("""
            {"op":"=","lhs":{"kind":"sourceField","field":"是否叶子"},"rhs":{"kind":"literal","value":"1"}}""");
        ConditionPredicate leaf0 = p("""
            {"op":"=","lhs":{"kind":"sourceField","field":"是否叶子"},"rhs":{"kind":"literal","value":"0"}}""");
        for (Map<String, Object> r : src) {
            assertFalse(eval.test(leaf1, r, Map.of()), "缺键不应命中 =1");
            assertFalse(eval.test(leaf0, r, Map.of()), "缺键更不应被当成 0 而命中 =0");
        }
    }

    /** 树属性与业务条件 AND 组合。 */
    @Test
    @DisplayName("是否叶子=1 AND 层级=3")
    void combinesWithAnd() throws Exception {
        List<Map<String, Object>> src = List.of(
            row("A1", 1, 3, 25), row("B1", 1, 2, 7), row("A", 0, 2, 40));
        ConditionPredicate pred = p("""
            {"bool":"AND","children":[
              {"op":"=","lhs":{"kind":"sourceField","field":"是否叶子"},"rhs":{"kind":"literal","value":"1"}},
              {"op":"=","lhs":{"kind":"sourceField","field":"层级"},"rhs":{"kind":"literal","value":"3"}}]}""");
        double sum = 0;
        for (Map<String, Object> r : src) if (eval.test(pred, r, Map.of())) sum += (double) r.get("金额");
        assertEquals(25.0, sum, 1e-9, "只有既是叶子又在第 3 层的 A1");
    }
}
