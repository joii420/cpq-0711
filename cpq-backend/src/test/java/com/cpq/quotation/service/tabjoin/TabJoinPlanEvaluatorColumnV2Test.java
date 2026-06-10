package com.cpq.quotation.service.tabjoin;

import com.cpq.quotation.service.card.CardDataProvider;
import com.cpq.quotation.service.card.CardEffectiveRows;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorColumnV2Test {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();

    private CardDataProvider provider() {
        Map<String, CardEffectiveRows.TabRows> eff = new LinkedHashMap<>();
        eff.put("T投:0", new CardEffectiveRows.TabRows(
            List.of(Map.of("物料编码","M1","金额",new BigDecimal("100")),
                    Map.of("物料编码","M2","金额",new BigDecimal("60"))),
            new BigDecimal("160"), Map.of("金额", new BigDecimal("160"))));
        eff.put("T加:1", new CardEffectiveRows.TabRows(
            List.of(Map.of("物料编码","M1","工时",new BigDecimal("4")),
                    Map.of("物料编码","M3","工时",new BigDecimal("5"))), null));
        eff.put("T回:2", new CardEffectiveRows.TabRows(
            List.of(Map.of("物料编码","M1","工序","电镀","回料金额",new BigDecimal("30")),
                    Map.of("物料编码","M1","工序","酸洗","回料金额",new BigDecimal("9"))),
            new BigDecimal("39")));
        return CardDataProvider.fromEffectiveRows(eff);
    }

    private Map<String,Object> col(String expr) {
        Map<String,Object> c = new LinkedHashMap<>();
        c.put("expression", expr);
        c.put("tabs", List.of(
            Map.of("alias","投料","tabKey","T投:0","rowKeyFields",List.of("物料编码")),
            Map.of("alias","加工","tabKey","T加:1","rowKeyFields",List.of("物料编码")),
            Map.of("alias","回料","tabKey","T回:2","rowKeyFields",List.of("物料编码","工序"))));
        return c;
    }

    @Test void detail_term_plus_tab_total() {
        BigDecimal v = ev.evaluateColumn(col("[投料.金额] * [加工.工时] + [回料(总计)]"), provider());
        assertEquals(0, new BigDecimal("439").compareTo(v), "got "+v);
    }
    @Test void column_total() {
        BigDecimal v = ev.evaluateColumn(col("[投料.金额(总计)]"), provider());
        assertEquals(0, new BigDecimal("160").compareTo(v));
    }
}
