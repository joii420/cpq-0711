package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CardSnapshotResolvedRowsTest {

    @Inject CardSnapshotService svc;
    private static final ObjectMapper M = new ObjectMapper();

    // 组件: 元素(BASIC_DATA $ys_view.元素) / 含量(BASIC_DATA $ys_view.含量) /
    //       类型(BASIC_DATA $ys_view.material_type, 别名≠字段名)
    private static final String SNAPSHOT = """
      [ { "componentId":"c1","componentCode":"C1","tabName":"元素","componentType":"NORMAL","sortOrder":1,
          "fields":[ {"name":"元素","field_type":"BASIC_DATA","basic_data_path":"$ys_view.元素","sort_order":1},
                     {"name":"含量","field_type":"BASIC_DATA","basic_data_path":"$ys_view.含量","sort_order":2},
                     {"name":"类型","field_type":"BASIC_DATA","basic_data_path":"$ys_view.material_type","sort_order":3} ],
          "formulas":[], "formula_assignments":[] } ]
      """;

    @Test
    void tabCarriesResolvedRowsKeyedByFieldName() throws Exception {
        JsonNode snapshot = M.readTree(SNAPSHOT);
        var baseRowsByComp = new java.util.LinkedHashMap<String, ArrayNode>();
        ArrayNode baseRows = M.createArrayNode();
        // 一行 Cu: driverRow 含 material_type 标量; basicDataValues path 键
        var r = M.createObjectNode();
        r.set("driverRow", M.readTree("{\"元素\":\"Cu\",\"含量\":70,\"material_type\":\"非银点类\"}"));
        r.set("basicDataValues", M.readTree(
          "{\"{$ys_view.元素}\":\"Cu\",\"{$ys_view.含量}\":70,\"{$ys_view.material_type}\":\"非银点类\"}"));
        baseRows.add(r);
        baseRowsByComp.put("c1", baseRows);

        JsonNode root = M.readTree(
            svc.assembleTabsWithFormulaResultsForTest(snapshot, baseRowsByComp, null));
        JsonNode tab0 = root.path("tabs").get(0);
        JsonNode resolved = tab0.path("resolvedRows");
        assertTrue(resolved.isArray() && resolved.size() == 1, "应有与 baseRows 同序的 resolvedRows");
        JsonNode row0 = resolved.get(0);
        // 按字段名落键: 类型(不是 material_type)
        assertEquals("非银点类", row0.path("类型").asText());
        assertEquals("Cu", row0.path("元素").asText());
        assertEquals(70, row0.path("含量").asInt());
        assertFalse(row0.has("material_type"), "只按字段名, 不泄漏 SQL 别名");
    }
}
