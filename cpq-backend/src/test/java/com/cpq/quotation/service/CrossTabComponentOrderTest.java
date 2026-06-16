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

    /** QT-1743: component_subtotal 跨组件引用必须被提取为依赖（component_code 优先，否则 tab_name）。 */
    @Test void extractSubtotalRefs_componentCodePreferred() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var formulas = om.readTree(
            "[{\"expression\":[" +
            "{\"type\":\"component_subtotal\",\"value\":\"材料成本\",\"tab_name\":\"材料成本\",\"component_code\":\"COMP-0028\"}," +
            "{\"type\":\"operator\",\"value\":\"+\"}," +
            "{\"type\":\"component_subtotal\",\"value\":\"费用\",\"tab_name\":\"电镀费用\",\"component_code\":\"COMP-0033\"}" +
            "]}]");
        Set<String> refs = CrossTabComponentOrder.extractSubtotalRefs(formulas);
        assertEquals(Set.of("COMP-0028", "COMP-0033"), refs);
    }

    @Test void extractSubtotalRefs_fallbackTabName_whenNoCode() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var formulas = om.readTree(
            "[{\"expression\":[{\"type\":\"component_subtotal\",\"value\":\"x\",\"tab_name\":\"来料\"}]}]");
        Set<String> refs = CrossTabComponentOrder.extractSubtotalRefs(formulas);
        assertEquals(Set.of("来料"), refs);
    }
}
