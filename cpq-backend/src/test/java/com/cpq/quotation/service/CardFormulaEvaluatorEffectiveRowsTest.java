package com.cpq.quotation.service;

import com.cpq.quotation.service.card.CardDataProvider;
import com.cpq.quotation.service.card.CardEffectiveRows;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 CARD_FORMULA 取数源=有效行时，B 列条件聚合命中"类型"列。 */
@QuarkusTest
class CardFormulaEvaluatorEffectiveRowsTest {

    @Inject CardFormulaEvaluator evaluator;

    @Test
    void cardFormulaHitsTypeColumnFromEffectiveRows() {
        Map<String, CardEffectiveRows.TabRows> eff = new HashMap<>();
        // 两行：非银点类(含量=200,单价=75) 和 银点类(含量=10,单价=5)
        eff.put("compE:2", new CardEffectiveRows.TabRows(List.of(
            new LinkedHashMap<>(Map.of("类型", "非银点类", "含量", 200, "单价", 75)),
            new LinkedHashMap<>(Map.of("类型", "银点类",   "含量", 10,  "单价", 5))
        ), null));
        CardDataProvider provider = CardDataProvider.fromEffectiveRows(eff);

        // refs：元素 是聚合源（无 field 键 → isAggregateSource() = true），cols 给列别名
        Map<String, Object> bRefs = Map.of("元素", Map.of(
            "tab", "compE:2",
            "cols", Map.of("c0", "类型", "c1", "含量", "c2", "单价")));
        Map<String, Object> colB = new LinkedHashMap<>();
        colB.put("col_key", "B");
        colB.put("source_type", "CARD_FORMULA");
        colB.put("formula", "SUM_OVER([元素] WHERE c0=='非银点类', c1+c2)");
        colB.put("refs", bRefs);

        Map<String, Object> out = evaluator.evaluateColumns(
            List.of(colB), provider, null, "P1", null);

        // 非银点类：含量=200, 单价=75 → c1+c2 = 275
        assertEquals(0, new java.math.BigDecimal("275").compareTo(
            new java.math.BigDecimal(out.get("B").toString())));
    }
}
