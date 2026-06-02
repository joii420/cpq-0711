package com.cpq.quotation.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.CardFormulaEvaluator;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CardFormulaEvaluatorTest {
    @Inject CardFormulaEvaluator evaluator;

    private QuotationLineComponentData tab(String comp, int sort, String json, String sub) {
        var d = new QuotationLineComponentData();
        d.componentId = UUID.fromString(comp); d.sortOrder = sort; d.rowData = json; d.subtotal = new BigDecimal(sub);
        return d;
    }
    private Map<String,Object> col(String key, String formula, Map<String,Object> refs) {
        return new LinkedHashMap<>(Map.of("col_key", key, "source_type", "CARD_FORMULA",
                "formula", formula, "refs", refs));
    }

    @Test void subtotal_ref_plus_aggregate_then_column_ref() {
        var comp = "33333333-3333-3333-3333-333333333333";
        var tabs = List.of(tab(comp, 0,
            "[{\"工序\":\"电镀\",\"加工费\":3},{\"工序\":\"酸洗\",\"加工费\":2}]", "10"));
        var refsA = Map.<String,Object>of(
            "投料.小计", Map.of("tab", comp+":0", "field", "__subtotal__"),
            "加工", Map.of("tab", comp+":0", "cols", Map.of("c0","工序","c1","加工费")));
        var cols = List.of(
            col("A", "=[投料.小计] + SUM_OVER([加工] WHERE c0=='电镀', c1)", refsA), // 10 + 3 = 13
            col("B", "=[A] * 2", Map.of())                                            // 26
        );
        Map<String,Object> out = evaluator.evaluateColumns(cols, tabs, null, "P1", null);
        assertEquals(0, new BigDecimal("13").compareTo(new BigDecimal(out.get("A").toString())));
        assertEquals(0, new BigDecimal("26").compareTo(new BigDecimal(out.get("B").toString())));
    }

    @Test void single_value_empty_shows_dash() {
        var comp = "44444444-4444-4444-4444-444444444444";
        var tabs = List.of(tab(comp, 0, "[]", "0"));
        var refs = Map.<String,Object>of("加工.加工费", Map.of("tab", comp+":0", "field", "加工费"));
        var cols = List.of(col("A", "=[加工.加工费]", refs));
        Map<String,Object> out = evaluator.evaluateColumns(cols, tabs, null, "P1", null);
        assertEquals("—", out.get("A"));
    }
}
