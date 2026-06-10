package com.cpq.quotation.service.tabjoin;

import com.cpq.quotation.service.card.CardDataProvider;
import com.cpq.quotation.service.card.CardEffectiveRows;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorColumnTest {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();

    private CardDataProvider provider() {
        Map<String, CardEffectiveRows.TabRows> eff = new LinkedHashMap<>();
        eff.put("T1:0", new CardEffectiveRows.TabRows(
            List.of(Map.of("物料编码", "M1", "金额", new BigDecimal("100"))), new BigDecimal("100")));
        eff.put("T2:1", new CardEffectiveRows.TabRows(
            List.of(Map.of("物料编码", "M1", "工时", new BigDecimal("4"))), null));
        eff.put("T3:2", new CardEffectiveRows.TabRows(
            List.of(Map.of("回料金额", new BigDecimal("96"))), null));
        return CardDataProvider.fromEffectiveRows(eff);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cfg() {
        Map<String, Object> g1 = new LinkedHashMap<>();
        g1.put("ref", "组1"); g1.put("main_tab", "投料");
        g1.put("tabs", List.of(Map.of("alias", "投料", "tabKey", "T1:0"),
                               Map.of("alias", "加工", "tabKey", "T2:1")));
        g1.put("joins", List.of(Map.of("left_tab", "加工", "left_cols", List.of("物料编码"),
                                       "right_tab", "投料", "right_cols", List.of("物料编码"), "type", "INNER")));
        g1.put("where", List.of());
        g1.put("agg_expression", "SUM([投料.金额])");

        Map<String, Object> g2 = new LinkedHashMap<>();
        g2.put("ref", "组2"); g2.put("main_tab", "回料");
        g2.put("tabs", List.of(Map.of("alias", "回料", "tabKey", "T3:2")));
        g2.put("joins", List.of()); g2.put("where", List.of());
        g2.put("agg_expression", "SUM([回料.回料金额])");

        Map<String, Object> col = new LinkedHashMap<>();
        col.put("final_expression", "组1 + 组2 - 100");
        col.put("groups", List.of(g1, g2));
        return col;
    }

    @Test
    void evaluates_groups_and_final() {
        TabJoinPlanEvaluator.Result res = ev.evaluateColumn(cfg(), provider());
        assertEquals(0, new BigDecimal("100").compareTo(res.groupValues().get("组1")));
        assertEquals(0, new BigDecimal("96").compareTo(res.groupValues().get("组2")));
        assertEquals(0, new BigDecimal("96").compareTo(res.finalValue()));
    }
}
