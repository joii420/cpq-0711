package com.cpq.quotation.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.card.CardDataProvider;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CardDataProviderTest {
    private QuotationLineComponentData tab(String compId, int sort, String rowJson, String sub) {
        QuotationLineComponentData d = new QuotationLineComponentData();
        d.componentId = UUID.fromString(compId);
        d.sortOrder = sort;
        d.rowData = rowJson;
        d.subtotal = new BigDecimal(sub);
        return d;
    }
    @Test void rows_and_subtotal_by_tab_key() {
        var c1 = "11111111-1111-1111-1111-111111111111";
        var tabs = List.of(tab(c1, 0, "[{\"proc\":\"电镀\",\"fee\":3},{\"proc\":\"酸洗\",\"fee\":2}]", "5"));
        CardDataProvider p = new CardDataProvider(tabs);
        String key = c1 + ":0";
        assertEquals(2, p.rowsOf(key).size());
        assertEquals("电镀", p.rowsOf(key).get(0).get("proc"));
        assertEquals(new BigDecimal("5"), p.subtotalOf(key));
    }
    @Test void missing_tab_returns_empty_and_null() {
        CardDataProvider p = new CardDataProvider(List.of());
        assertTrue(p.rowsOf("nope:0").isEmpty());
        assertNull(p.subtotalOf("nope:0"));
    }
}
