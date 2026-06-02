package com.cpq.quotation.card;

import com.cpq.quotation.service.card.CardRef;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardRefTest {
    @Test void subtotal_ref() {
        CardRef r = CardRef.fromMap(java.util.Map.of("tab", "TC1", "field", "__subtotal__"));
        assertEquals("TC1", r.tab); assertTrue(r.isSubtotal()); assertFalse(r.isAggregateSource());
    }
    @Test void field_first_row_ref() {
        CardRef r = CardRef.fromMap(java.util.Map.of("tab", "TC1", "field", "proc_fee"));
        assertEquals("proc_fee", r.field); assertEquals(CardRef.Mode.FIRST_ROW, r.mode);
    }
    @Test void row_where_ref_with_cols() {
        CardRef r = CardRef.fromMap(java.util.Map.of(
            "tab", "TC1", "field", "加工费", "mode", "ROW_WHERE", "cond", "c0=='电镀'",
            "cols", java.util.Map.of("c0", "工序")));
        assertEquals(CardRef.Mode.ROW_WHERE, r.mode);
        assertEquals("c0=='电镀'", r.cond);
        assertEquals("工序", r.cols.get("c0"));
    }
    @Test void aggregate_source_ref_has_no_field() {
        CardRef r = CardRef.fromMap(java.util.Map.of("tab", "TC1"));
        assertTrue(r.isAggregateSource());
    }
}
