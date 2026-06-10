package com.cpq.quotation.service.card;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CardRefSubtotalColumnTest {

    @Test
    void legacySubtotal_noColumn() {
        CardRef r = CardRef.fromMap(Map.of("tab", "c1:1", "field", "__subtotal__"));
        assertTrue(r.isSubtotal());
        assertNull(r.subtotalColumn());
    }

    @Test
    void subtotalWithColumn() {
        CardRef r = CardRef.fromMap(Map.of("tab", "c1:1", "field", "__subtotal__:材料费小计"));
        assertTrue(r.isSubtotal());
        assertEquals("材料费小计", r.subtotalColumn());
    }

    @Test
    void normalField_notSubtotal() {
        CardRef r = CardRef.fromMap(Map.of("tab", "c1:1", "field", "单价"));
        assertFalse(r.isSubtotal());
        assertNull(r.subtotalColumn());
    }
}
