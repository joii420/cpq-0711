package com.cpq.quotation.service.card;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CardDataProviderFromEffectiveRowsTest {

    @Test
    void exactTabKeyHitNoSortFallback() {
        Map<String, CardEffectiveRows.TabRows> eff = new HashMap<>();
        eff.put("b3359f70:2", new CardEffectiveRows.TabRows(
            List.of(Map.of("类型", "非银点类", "含量", 0.5)),
            new BigDecimal("0.5")));

        CardDataProvider p = CardDataProvider.fromEffectiveRows(eff);

        assertTrue(p.hasTab("b3359f70:2"));
        assertEquals("非银点类", p.rowsOf("b3359f70:2").get(0).get("类型"));
        assertEquals(0, new BigDecimal("0.5").compareTo(p.subtotalOf("b3359f70:2")));
        assertFalse(p.hasTab("WRONG:2"));
    }
}
