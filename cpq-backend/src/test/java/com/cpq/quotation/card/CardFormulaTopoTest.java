package com.cpq.quotation.card;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.service.CardFormulaEvaluator;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CardFormulaTopoTest {
    @Test void orders_b_after_a() {
        Map<String,String> f = new LinkedHashMap<>();
        f.put("B", "=[A] * 1.13");
        f.put("A", "=SUM_OVER([加工] WHERE c0=='电镀', c1)");
        List<String> order = CardFormulaEvaluator.topoOrder(f);
        assertTrue(order.indexOf("A") < order.indexOf("B"));
    }
    @Test void card_ref_with_dot_is_not_a_column_dep() {
        Map<String,String> f = new LinkedHashMap<>();
        f.put("A", "=[投料.小计] + 1");
        assertEquals(List.of("A"), CardFormulaEvaluator.topoOrder(f));
    }
    @Test void detects_cycle() {
        Map<String,String> f = new LinkedHashMap<>();
        f.put("A", "=[B] + 1");
        f.put("B", "=[A] + 1");
        assertThrows(BusinessException.class, () -> CardFormulaEvaluator.topoOrder(f));
    }

    @Test void column_rhs_in_where_is_a_column_dep() {
        // B 的 WHERE 条件引用列 A（公式文本里没有 [A]）→ A 必须排在 B 前
        Map<String,String> f = new LinkedHashMap<>();
        f.put("B", "=[投料.关联(条件)]");
        f.put("A", "=[投料.小计]");
        Map<String, Map<String,Object>> refs = new LinkedHashMap<>();
        refs.put("B", Map.of("投料.关联(条件)", Map.of(
            "tab", "t:0", "field", "关联", "mode", "ROW_WHERE",
            "cols", Map.of("c0", "子件号"),
            "condRows", List.of(Map.of("left", "子件号", "op", "eq", "logic", "and",
                "rhs", Map.of("type", "column", "value", "A"))))));
        refs.put("A", Map.of());
        List<String> order = CardFormulaEvaluator.topoOrder(f, refs);
        assertTrue(order.indexOf("A") < order.indexOf("B"));
    }

    @Test void column_rhs_cycle_is_detected() {
        Map<String,String> f = new LinkedHashMap<>();
        f.put("A", "=[投料.字段(条件)]");
        f.put("B", "=[加工.字段(条件)]");
        Map<String, Map<String,Object>> refs = new LinkedHashMap<>();
        refs.put("A", Map.of("投料.字段(条件)", Map.of("tab","t:0","field","x","mode","ROW_WHERE",
            "cols", Map.of("c0","k"),
            "condRows", List.of(Map.of("left","k","op","eq","logic","and",
                "rhs", Map.of("type","column","value","B"))))));
        refs.put("B", Map.of("加工.字段(条件)", Map.of("tab","t:1","field","y","mode","ROW_WHERE",
            "cols", Map.of("c0","k"),
            "condRows", List.of(Map.of("left","k","op","eq","logic","and",
                "rhs", Map.of("type","column","value","A"))))));
        assertThrows(BusinessException.class, () -> CardFormulaEvaluator.topoOrder(f, refs));
    }
}
