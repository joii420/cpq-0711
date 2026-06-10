package com.cpq.quotation.service.tabjoin;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorWhereTest {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();

    private Map<String, Object> wrow(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
    private TabJoinPlanEvaluator.Cond c(String col, String op, String val, String logic) {
        return new TabJoinPlanEvaluator.Cond(col, op, val, logic);
    }

    @Test
    void eq_filter() {
        List<Map<String, Object>> rows = List.of(
            wrow("投料.类型", "主料", "投料.金额", 100),
            wrow("投料.类型", "辅料", "投料.金额", 5));
        var out = ev.applyWhere(rows, List.of(c("投料.类型", "=", "主料", "AND")));
        assertEquals(1, out.size());
        assertEquals(100, out.get(0).get("投料.金额"));
    }

    @Test
    void gt_lt_numeric() {
        List<Map<String, Object>> rows = List.of(
            wrow("投料.数量", 3), wrow("投料.数量", 10), wrow("投料.数量", 20));
        assertEquals(1, ev.applyWhere(rows, List.of(c("投料.数量", ">", "15", "AND"))).size());
        assertEquals(1, ev.applyWhere(rows, List.of(c("投料.数量", "<", "5", "AND"))).size());
    }

    @Test
    void contains_not_contains() {
        List<Map<String, Object>> rows = List.of(
            wrow("加工.工序", "电镀前处理"), wrow("加工.工序", "酸洗"));
        assertEquals(1, ev.applyWhere(rows, List.of(c("加工.工序", "包含", "电镀", "AND"))).size());
        assertEquals(1, ev.applyWhere(rows, List.of(c("加工.工序", "不包含", "电镀", "AND"))).size());
    }

    @Test
    void and_or_combination() {
        List<Map<String, Object>> rows = List.of(
            wrow("投料.类型", "主料", "投料.数量", 1),
            wrow("投料.类型", "辅料", "投料.数量", 99),
            wrow("投料.类型", "主料", "投料.数量", 99));
        var out = ev.applyWhere(rows, List.of(
            c("投料.类型", "=", "主料", "AND"), c("投料.数量", ">", "50", "AND")));
        assertEquals(1, out.size());
        var out2 = ev.applyWhere(rows, List.of(
            c("投料.类型", "=", "辅料", "AND"), c("投料.数量", ">", "50", "OR")));
        assertEquals(2, out2.size());
    }
}
