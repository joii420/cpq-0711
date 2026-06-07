package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 1.4 — CardSnapshotService 按组件拓扑序计算 + 已算行存储，使 cross_tab_ref 跨页签引用生效。
 *
 * <p>构造两个 NORMAL tab：A（源，有 子件/单重），B（引用方，重量=cross_tab_ref source=A target=单重
 * match[{a:子件,b:子件}] agg=NONE）。<b>B 在 snapshot 顺序里排在 A 之前</b>，以证明拓扑序真正重排了
 * 计算次序（不是依赖输入顺序）。
 *
 * <p>断言：B 行 重量 == 0.8（P1 在 A 的 单重）；且 tabs 输出仍按原 snapshot 顺序（B 在前 A 在后）。
 */
@QuarkusTest
class CardSnapshotCrossTabTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Inject CardSnapshotService cardSnapshotService;

    /** 一个字段定义节点。 */
    private ObjectNode field(String name, String type) {
        ObjectNode f = M.createObjectNode();
        f.put("name", name);
        f.put("field_type", type);
        return f;
    }

    /** 一个 cross_tab_ref FORMULA 字段（单 token）。 */
    private ObjectNode crossTabFormulaField(String name) {
        ObjectNode f = field(name, "FORMULA");
        return f;
    }

    /** 一个 driver baseRow：driverRow={字段:值...}, basicDataValues={}. */
    private ObjectNode baseRow(Map<String, Object> driver) {
        ObjectNode row = M.createObjectNode();
        ObjectNode dr = M.createObjectNode();
        for (var e : driver.entrySet()) {
            if (e.getValue() instanceof Number n) dr.put(e.getKey(), n.doubleValue());
            else dr.put(e.getKey(), String.valueOf(e.getValue()));
        }
        row.set("driverRow", dr);
        row.set("basicDataValues", M.createObjectNode());
        return row;
    }

    @Test
    void crossTabRefResolvesAcrossTabsRegardlessOfSnapshotOrder() throws Exception {
        // ── Tab B（引用方），排在前面 ──
        ObjectNode tabB = M.createObjectNode();
        tabB.put("componentId", "B");
        tabB.put("componentCode", "COMP-B");
        tabB.put("componentType", "NORMAL");
        tabB.put("tabName", "TabB");
        ArrayNode fieldsB = tabB.putArray("fields");
        fieldsB.add(field("子件", "INPUT_TEXT"));
        fieldsB.add(crossTabFormulaField("重量"));
        // formulas: 重量 = [{ type:cross_tab_ref, source:A, target:单重, agg:NONE, match:[{a:子件,b:子件}] }]
        ArrayNode formulasB = tabB.putArray("formulas");
        ObjectNode fmlB = M.createObjectNode();
        fmlB.put("fieldName", "重量");
        ArrayNode exprB = fmlB.putArray("expression");
        ObjectNode tok = M.createObjectNode();
        tok.put("type", "cross_tab_ref");
        tok.put("source", "A");
        tok.put("target", "单重");
        tok.put("agg", "NONE");
        ArrayNode match = tok.putArray("match");
        ObjectNode pair = M.createObjectNode();
        pair.put("a", "子件");
        pair.put("b", "子件");
        match.add(pair);
        exprB.add(tok);
        formulasB.add(fmlB);
        tabB.putArray("formula_assignments");

        // ── Tab A（源），排在后面 ──
        ObjectNode tabA = M.createObjectNode();
        tabA.put("componentId", "A");
        tabA.put("componentCode", "COMP-A");
        tabA.put("componentType", "NORMAL");
        tabA.put("tabName", "TabA");
        ArrayNode fieldsA = tabA.putArray("fields");
        fieldsA.add(field("子件", "INPUT_TEXT"));
        fieldsA.add(field("单重", "INPUT_NUMBER"));
        tabA.putArray("formulas");
        tabA.putArray("formula_assignments");

        // snapshot: B 在前, A 在后
        ArrayNode snapshot = M.createArrayNode();
        snapshot.add(tabB);
        snapshot.add(tabA);

        // baseRows
        Map<String, ArrayNode> baseRowsByComp = new LinkedHashMap<>();
        ArrayNode aRows = M.createArrayNode();
        aRows.add(baseRow(Map.of("子件", "P1", "单重", 0.8)));
        aRows.add(baseRow(Map.of("子件", "P2", "单重", 0.3)));
        baseRowsByComp.put("A", aRows);
        ArrayNode bRows = M.createArrayNode();
        bRows.add(baseRow(Map.of("子件", "P1")));
        baseRowsByComp.put("B", bRows);

        String json = cardSnapshotService.assembleTabsWithFormulaResultsForTest(
            snapshot, baseRowsByComp, null);

        JsonNode root = M.readTree(json);
        JsonNode tabs = root.path("tabs");
        assertEquals(2, tabs.size(), "应输出 2 个 tab");

        // 输出顺序 = 原 snapshot 顺序：B 在前, A 在后（拓扑序不得改变 UI tab 顺序）
        assertEquals("B", tabs.get(0).path("componentId").asText(), "tab0 应是 B（保持原 snapshot 顺序）");
        assertEquals("A", tabs.get(1).path("componentId").asText(), "tab1 应是 A");

        // B 的 formulaResults: 重量 == 0.8（P1 在 A 的 单重）
        JsonNode bTab = tabs.get(0);
        JsonNode bFr = bTab.path("formulaResults");
        assertEquals(1, bFr.size(), "B 应有 1 行 formulaResults");
        double weight = bFr.get(0).path("values").path("重量").asDouble(-1);
        assertEquals(0.8, weight, 1e-9,
            "B 行 重量 应等于 A 中 子件=P1 的 单重=0.8（证明 A 先于 B 计算且已算行可被引用）");
    }
}
