package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Plan 3c：详情/核价视图源(快照 formulaResults)按条件正确冻结条件公式结果。 */
@QuarkusTest
class CardSnapshotConditionalTest {

    @Inject CardSnapshotService svc;
    private static final ObjectMapper M = new ObjectMapper();

    // 加工费：类型==车削 → 单价*1.2；默认 → 单价。
    private static final String SNAPSHOT = """
        [ { "componentId":"c1", "componentCode":"C1", "tabName":"工序", "componentType":"NORMAL", "sortOrder":1,
            "fields":[
              {"name":"类型","field_type":"INPUT","sort_order":1},
              {"name":"单价","field_type":"INPUT_NUMBER","sort_order":2},
              {"name":"加工费","field_type":"FORMULA","sort_order":3,
               "conditional_formula":{
                 "rules":[{"when":{"kind":"leaf","left":"类型","op":"eq","rhs":{"type":"literal","value":"车削"}},"formula":"f_turn"}],
                 "default":"f_base"}}
            ],
            "formulas":[
              {"name":"f_turn","expression":[{"type":"field","value":"单价"},{"type":"operator","value":"*"},{"type":"number","value":"1.2"}]},
              {"name":"f_base","expression":[{"type":"field","value":"单价"}]}
            ],
            "formula_assignments":[] } ]
        """;

    @Test
    void snapshotFreezesConditionalResult() throws Exception {
        JsonNode snapshot = M.readTree(SNAPSHOT);
        var baseRowsByComp = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.node.ArrayNode>();
        var baseRows = M.createArrayNode();
        // 行：类型=车削、单价=100 → 加工费 = 120
        var r = M.createObjectNode();
        var dr = M.createObjectNode(); dr.put("类型", "车削"); dr.put("单价", 100); r.set("driverRow", dr);
        r.set("basicDataValues", M.createObjectNode());
        baseRows.add(r);
        baseRowsByComp.put("c1", baseRows);

        JsonNode root = M.readTree(svc.assembleTabsWithFormulaResultsForTest(snapshot, baseRowsByComp, null));
        JsonNode tab0 = root.path("tabs").get(0);
        JsonNode fr = tab0.path("formulaResults");
        assertTrue(fr.isArray() && fr.size() >= 1, "应有 formulaResults");
        double v = fr.get(0).path("values").path("加工费").asDouble();
        assertEquals(120.0, v, 1e-9, "车削行条件命中 f_turn=单价*1.2=120");
    }
}
