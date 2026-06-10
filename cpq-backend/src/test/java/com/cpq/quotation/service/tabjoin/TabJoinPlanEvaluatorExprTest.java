package com.cpq.quotation.service.tabjoin;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorExprTest {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();

    private Map<String, Object> w(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
    private void assertBD(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "got " + actual);
    }

    @Test
    void sum_of_single_column() {
        List<Map<String, Object>> rows = List.of(w("投料.金额", 100), w("投料.金额", 50));
        assertBD("150", ev.evalGroupExpression("SUM([投料.金额])", rows, Map.of()));
    }

    @Test
    void sum_of_row_level_product() {
        List<Map<String, Object>> rows = List.of(
            w("投料.单价", 10, "加工.工时", 2),
            w("投料.单价", 10, "加工.工时", 3));
        assertBD("50", ev.evalGroupExpression("SUM([投料.单价] * [加工.工时])", rows, Map.of()));
    }

    @Test
    void two_aggregates_plus_bare_field_first_row() {
        List<Map<String, Object>> rows = List.of(
            w("投料.单价", 10, "投料.数量", 2, "投料.金额", 20),
            w("投料.单价", 5, "投料.数量", 4, "投料.金额", 20));
        assertBD("100", ev.evalGroupExpression(
            "SUM([投料.单价]*[投料.数量]) + SUM([投料.金额]) + [投料.金额]", rows, Map.of()));
    }

    @Test
    void avg_min_max_count() {
        List<Map<String, Object>> rows = List.of(w("a.x", 2), w("a.x", 4), w("a.x", 6));
        assertBD("4", ev.evalGroupExpression("AVG([a.x])", rows, Map.of()));
        assertBD("2", ev.evalGroupExpression("MIN([a.x])", rows, Map.of()));
        assertBD("6", ev.evalGroupExpression("MAX([a.x])", rows, Map.of()));
        assertBD("3", ev.evalGroupExpression("COUNT([a.x])", rows, Map.of()));
    }

    @Test
    void subtotal_token_uses_subtotals_map() {
        List<Map<String, Object>> rows = List.of(w("投料.金额", 1));
        Map<String, BigDecimal> subs = Map.of("投料.小计", new BigDecimal("999"));
        assertBD("999", ev.evalGroupExpression("[投料.小计]", rows, subs));
    }

    @Test
    void missing_field_is_zero_and_div_by_zero_is_one() {
        List<Map<String, Object>> rows = List.of(w("a.x", 10));
        assertBD("10", ev.evalGroupExpression("[a.x] / [a.y]", rows, Map.of()));
    }
}
