package com.cpq.quotation.service.tabjoin;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorJoinTest {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void single_tab_no_join_returns_prefixed_rows() {
        Map<String, List<Map<String, Object>>> tabs = Map.of(
            "投料", List.of(row("物料编码", "M1", "金额", 100)));
        List<Map<String, Object>> wide = ev.buildWideRows("投料", tabs, List.of());
        assertEquals(1, wide.size());
        assertEquals("M1", wide.get(0).get("投料.物料编码"));
        assertEquals(100, wide.get(0).get("投料.金额"));
    }

    @Test
    void inner_join_one_to_many_fans_out() {
        Map<String, List<Map<String, Object>>> tabs = Map.of(
            "投料", List.of(row("物料编码", "M1", "金额", 100)),
            "加工", List.of(row("物料编码", "M1", "工时", 2),
                            row("物料编码", "M1", "工时", 3),
                            row("物料编码", "M2", "工时", 9)));
        var join = new TabJoinPlanEvaluator.Join("加工", List.of("物料编码"), "投料", List.of("物料编码"));
        List<Map<String, Object>> wide = ev.buildWideRows("投料", tabs, List.of(join));
        assertEquals(2, wide.size(), "M1 投料1行 × 加工2行 = 2 行放大；M2 无投料匹配丢弃");
        assertEquals(100, wide.get(0).get("投料.金额"));
        assertEquals(2, wide.get(0).get("加工.工时"));
        assertEquals(3, wide.get(1).get("加工.工时"));
    }

    @Test
    void inner_join_no_match_drops_row() {
        Map<String, List<Map<String, Object>>> tabs = Map.of(
            "投料", List.of(row("物料编码", "M1")),
            "加工", List.of(row("物料编码", "MX")));
        var join = new TabJoinPlanEvaluator.Join("加工", List.of("物料编码"), "投料", List.of("物料编码"));
        assertTrue(ev.buildWideRows("投料", tabs, List.of(join)).isEmpty());
    }
}
