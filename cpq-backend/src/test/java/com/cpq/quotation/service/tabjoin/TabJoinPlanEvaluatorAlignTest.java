package com.cpq.quotation.service.tabjoin;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorAlignTest {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();
    private Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void single_tab_prefixed() {
        var tabs = Map.of("投料", List.of(row("物料编码","M1","金额",100)));
        var wide = ev.alignByRowKey(List.of("物料编码"), tabs);
        assertEquals(1, wide.size());
        assertEquals(100, wide.get(0).get("投料.金额"));
    }

    @Test
    void full_outer_union_missing_absent() {
        var tabs = new LinkedHashMap<String, List<Map<String,Object>>>();
        tabs.put("投料", List.of(row("物料编码","M1","金额",100), row("物料编码","M2","金额",60)));
        tabs.put("加工", List.of(row("物料编码","M1","工时",4), row("物料编码","M3","工时",5)));
        var wide = ev.alignByRowKey(List.of("物料编码"), tabs);
        assertEquals(3, wide.size(), "并集 M1/M2/M3");
        var m1 = wide.stream().filter(r -> "M1".equals(str(r.get("投料.物料编码")))||"M1".equals(str(r.get("加工.物料编码")))).findFirst().orElseThrow();
        assertEquals(100, m1.get("投料.金额"));
        assertEquals(4, m1.get("加工.工时"));
        var m2 = wide.stream().filter(r -> "M2".equals(str(r.get("投料.物料编码")))).findFirst().orElseThrow();
        assertEquals(60, m2.get("投料.金额"));
        assertNull(m2.get("加工.工时"), "M2 加工缺失→字段不存在");
        var m3 = wide.stream().filter(r -> "M3".equals(str(r.get("加工.物料编码")))).findFirst().orElseThrow();
        assertNull(m3.get("投料.金额"));
        assertEquals(5, m3.get("加工.工时"));
    }

    @Test
    void composite_rowkey() {
        var tabs = new LinkedHashMap<String, List<Map<String,Object>>>();
        tabs.put("回料", List.of(row("物料编码","M1","工序","电镀","回料金额",30),
                                 row("物料编码","M1","工序","酸洗","回料金额",9)));
        var wide = ev.alignByRowKey(List.of("物料编码","工序"), tabs);
        assertEquals(2, wide.size(), "复合行键两行不合并");
    }

    /** 行键值含空格时不能与相邻令牌拼出相同 key（分隔符为 \\u0001，业务值不含）。 */
    @Test
    void rowkey_value_with_space_no_collision() {
        // "M 1" 与 "M1" 是两个不同物料，不应合并为一行
        var tabs = Map.of("投料", List.of(
                row("物料编码", "M 1", "金额", 10),
                row("物料编码", "M1",  "金额", 20)));
        var wide = ev.alignByRowKey(List.of("物料编码"), tabs);
        assertEquals(2, wide.size(), "值含空格的行键不能与其他行键撞合并");
    }

    private static String str(Object o){ return o==null?null:o.toString(); }
}
