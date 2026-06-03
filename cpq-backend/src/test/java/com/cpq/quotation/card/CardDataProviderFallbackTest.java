package com.cpq.quotation.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.card.CardDataProvider;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CardDataProviderFallbackTest {
    private QuotationLineComponentData tab(String comp, int sort, String json, String sub) {
        var d = new QuotationLineComponentData();
        d.componentId = UUID.fromString(comp); d.sortOrder = sort; d.rowData = json; d.subtotal = new BigDecimal(sub);
        return d;
    }

    @Test void falls_back_to_sortOrder_when_componentId_differs() {
        var dataComp = "d18ac7e4-24e9-4f87-867c-6350dd6067fe";
        var refComp  = "b3359f70-f830-40f5-ad0f-938d1ce3970c";
        var p = new CardDataProvider(List.of(tab(dataComp, 2, "[{\"类型\":\"非银点类\",\"含量\":3}]", "9")));
        assertEquals(1, p.rowsOf(refComp + ":2").size());
        assertEquals("非银点类", p.rowsOf(refComp + ":2").get(0).get("类型"));
        assertEquals(new BigDecimal("9"), p.subtotalOf(refComp + ":2"));
    }

    @Test void exact_match_still_preferred() {
        var c = "d18ac7e4-24e9-4f87-867c-6350dd6067fe";
        var p = new CardDataProvider(List.of(tab(c, 2, "[{\"含量\":5}]", "5")));
        assertEquals(new BigDecimal("5"), p.subtotalOf(c + ":2"));
    }

    @Test void unknown_sortOrder_returns_empty() {
        var p = new CardDataProvider(List.of(tab("d18ac7e4-24e9-4f87-867c-6350dd6067fe", 2, "[]", "0")));
        assertTrue(p.rowsOf("b3359f70-f830-40f5-ad0f-938d1ce3970c:9").isEmpty());
    }
}
