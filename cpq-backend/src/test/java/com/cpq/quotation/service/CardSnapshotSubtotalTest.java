package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CardSnapshotSubtotalTest {

    @Inject CardSnapshotService svc;
    private static final ObjectMapper M = new ObjectMapper();

    private static final String SNAPSHOT = """
        [ { "componentId":"c1", "componentCode":"C1", "tabName":"投料",
            "componentType":"NORMAL", "sortOrder":1,
            "fields":[ {"name":"金额","field_type":"INPUT_NUMBER","is_subtotal":true,"sort_order":1} ],
            "formulas":[], "formula_assignments":[] } ]
        """;

    @Test
    void assembledTabCarriesSubtotal() throws Exception {
        JsonNode snapshot = M.readTree(SNAPSHOT);
        var baseRowsByComp = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.node.ArrayNode>();
        var baseRows = M.createArrayNode();
        var r = M.createObjectNode();
        r.set("driverRow", M.createObjectNode());
        var bdv = M.createObjectNode(); bdv.put("金额", 7); r.set("basicDataValues", bdv);
        baseRows.add(r);
        baseRowsByComp.put("c1", baseRows);

        JsonNode root = M.readTree(
            svc.assembleTabsWithFormulaResultsForTest(snapshot, baseRowsByComp, null));
        JsonNode tab0 = root.path("tabs").get(0);
        assertTrue(tab0.has("subtotal"), "值快照 tab 应带 subtotal");
    }
}
