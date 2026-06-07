package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CrossTabComponentOrderTest {

    @Test void noDeps_keepsInputOrder() {
        var order = CrossTabComponentOrder.topoOrder(List.of("A", "B"), Map.of());
        assertEquals(List.of("A", "B"), order);
    }

    @Test void bDependsOnA_aFirst() {
        var deps = Map.of("B", Set.of("A"));
        var order = CrossTabComponentOrder.topoOrder(List.of("B", "A"), deps);
        assertTrue(order.indexOf("A") < order.indexOf("B"));
    }

    @Test void cycle_throws() {
        var deps = Map.of("A", Set.of("B"), "B", Set.of("A"));
        assertThrows(BusinessException.class,
            () -> CrossTabComponentOrder.topoOrder(List.of("A", "B"), deps));
    }

    @Test void chain_aThenBThenC() {
        var deps = Map.of("C", Set.of("B"), "B", Set.of("A"));
        var order = CrossTabComponentOrder.topoOrder(List.of("C", "B", "A"), deps);
        assertEquals(List.of("A", "B", "C"), order);
    }

    @Test void extractDeps_fromFormulaTokens() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var bFormulas = om.readTree(
            "[{\"expression\":[{\"type\":\"cross_tab_ref\",\"source\":\"A\"}]}]");
        Set<String> deps = CrossTabComponentOrder.extractSourceRefs(bFormulas);
        assertEquals(Set.of("A"), deps);
    }
}
