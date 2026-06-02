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
}
