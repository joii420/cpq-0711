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
class CardRowWhereTest {
    @Inject CardFormulaEvaluator evaluator;
    @Test void picks_field_from_row_matching_condition() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = new QuotationLineComponentData();
        d.componentId = UUID.fromString(comp); d.sortOrder = 0; d.subtotal = BigDecimal.ZERO;
        d.rowData = "[{\"工序\":\"酸洗\",\"加工费\":2},{\"工序\":\"电镀\",\"加工费\":9}]";
        var refs = Map.<String,Object>of("加工.加工费(工序=电镀)",
            Map.of("tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
                   "cond", "c0=='电镀'", "cols", Map.of("c0","工序")));
        Map<String,Object> colMap = new LinkedHashMap<>();
        colMap.put("col_key", "A");
        colMap.put("source_type", "CARD_FORMULA");
        colMap.put("formula", "=[加工.加工费(工序=电镀)]");
        colMap.put("refs", refs);
        List<Map<String,Object>> cols = List.of(colMap);
        var out = evaluator.evaluateColumns(cols, List.of(d), null, "P1", null);
        assertEquals(0, new BigDecimal("9").compareTo(new BigDecimal(out.get("A").toString())));
    }
}
