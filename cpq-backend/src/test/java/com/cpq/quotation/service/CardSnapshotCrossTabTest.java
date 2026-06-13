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

    /**
     * T4/T5 — cross_tab 列 is_subtotal 时，来料 tab 的 subtotalByColumn 必须等于
     * PASS2 resolved 行的列值之和，不能是 PASS1（crossTabRows 为空）的 0。
     *
     * <p>场景：
     * <ul>
     *   <li>Tab WGJ（外购件，源）：4 行，费用=0.05/0.2/0.002/0.007（is_subtotal=true）。
     *   <li>Tab LI（来料，引用方）：公式"材料费"=SUM([WGJ.费用], match=[])，is_subtotal=true；
     *       宿主只有 1 行（无 match 条件，KSUM 全聚合）。
     * </ul>
     * 期望：LI 的 subtotalByColumn["材料费"] ≈ 0.259（= 0.05+0.2+0.002+0.007）。
     * 修复前 PASS1 crossTabRows 为空 → 0；修复后从 resolved 回填 → 0.259。
     */
    @Test
    void crossTabColumnSubtotalBackfilledFromResolvedRows() throws Exception {
        // ── Tab WGJ（外购件，源）──
        ObjectNode tabWGJ = M.createObjectNode();
        tabWGJ.put("componentId", "WGJ");
        tabWGJ.put("componentCode", "WGJ");
        tabWGJ.put("componentType", "NORMAL");
        tabWGJ.put("tabName", "外购件");
        ArrayNode fieldsWGJ = tabWGJ.putArray("fields");
        // 料件（文本 key 字段）
        ObjectNode fLJ = M.createObjectNode();
        fLJ.put("name", "料件"); fLJ.put("field_type", "INPUT_TEXT");
        fieldsWGJ.add(fLJ);
        // 费用（is_subtotal=true）
        ObjectNode fFY = M.createObjectNode();
        fFY.put("name", "费用"); fFY.put("field_type", "INPUT_NUMBER"); fFY.put("is_subtotal", true);
        fieldsWGJ.add(fFY);
        tabWGJ.putArray("formulas");
        tabWGJ.putArray("formula_assignments");

        // ── Tab LI（来料，引用方）──
        ObjectNode tabLI = M.createObjectNode();
        tabLI.put("componentId", "LI");
        tabLI.put("componentCode", "LI");
        tabLI.put("componentType", "NORMAL");
        tabLI.put("tabName", "来料");
        ArrayNode fieldsLI = tabLI.putArray("fields");
        // 材料费（FORMULA, is_subtotal=true）
        ObjectNode fCF = M.createObjectNode();
        fCF.put("name", "材料费"); fCF.put("field_type", "FORMULA"); fCF.put("is_subtotal", true);
        fieldsLI.add(fCF);
        // 公式：材料费 = SUM([WGJ.费用])，match=[] 表示全聚合（KSUM 语义）
        ArrayNode formulasLI = tabLI.putArray("formulas");
        ObjectNode fml = M.createObjectNode();
        fml.put("fieldName", "材料费");
        ArrayNode expr = fml.putArray("expression");
        ObjectNode tok = M.createObjectNode();
        tok.put("type", "cross_tab_ref");
        tok.put("source", "WGJ");
        tok.put("target", "费用");
        tok.put("agg", "SUM");
        tok.putArray("match"); // 空 match = 全聚合
        expr.add(tok);
        formulasLI.add(fml);
        tabLI.putArray("formula_assignments");

        // snapshot：WGJ 在前，LI 在后（都是 NORMAL，顺序依赖不影响，拓扑序会排 WGJ 先算）
        ArrayNode snapshot = M.createArrayNode();
        snapshot.add(tabWGJ);
        snapshot.add(tabLI);

        // baseRows：WGJ 4 行费用值
        Map<String, ArrayNode> baseRowsByComp = new LinkedHashMap<>();
        ArrayNode wgjRows = M.createArrayNode();
        for (double fee : new double[]{0.05, 0.2, 0.002, 0.007}) {
            ObjectNode row = M.createObjectNode();
            ObjectNode dr = M.createObjectNode(); dr.put("料件", "料9"); dr.put("费用", fee);
            row.set("driverRow", dr);
            row.set("basicDataValues", M.createObjectNode());
            wgjRows.add(row);
        }
        baseRowsByComp.put("WGJ", wgjRows);

        // LI：1 宿主行（无 driver，公式全聚合源行）
        ArrayNode liRows = M.createArrayNode();
        ObjectNode liRow = M.createObjectNode();
        liRow.set("driverRow", M.createObjectNode());
        liRow.set("basicDataValues", M.createObjectNode());
        liRows.add(liRow);
        baseRowsByComp.put("LI", liRows);

        String json = cardSnapshotService.assembleTabsWithFormulaResultsForTest(
            snapshot, baseRowsByComp, null);

        JsonNode root = M.readTree(json);
        JsonNode tabs = root.path("tabs");
        assertEquals(2, tabs.size());

        // 找来料 tab
        JsonNode liTab = null;
        for (JsonNode t : tabs) {
            if ("LI".equals(t.path("componentId").asText())) { liTab = t; break; }
        }
        assertNotNull(liTab, "来料 tab 必须存在");

        // 来料 formulaResults[0].values.材料费 应 ≈ 0.259（每行 cross_tab SUM）
        JsonNode liFormula = liTab.path("formulaResults");
        assertEquals(1, liFormula.size(), "来料应有 1 行 formulaResults");
        double materialsPerRow = liFormula.get(0).path("values").path("材料费").asDouble(-1);
        assertEquals(0.259, materialsPerRow, 1e-9,
            "来料每行材料费应 = SUM(WGJ.费用) = 0.05+0.2+0.002+0.007=0.259");

        // ★ 核心断言：来料 subtotalByColumn["材料费"] 必须从 resolved 回填，不能是 PASS1 的 0
        JsonNode byCol = liTab.path("subtotalByColumn");
        assertTrue(byCol.has("材料费"),
            "来料 tab 应有 subtotalByColumn.材料费（is_subtotal 列）");
        double colSubtotal = byCol.path("材料费").asDouble(-1);
        assertEquals(0.259, colSubtotal, 1e-4,
            "来料 subtotalByColumn[材料费] 应 = 0.259（来自 PASS2 resolved 行之和，不能是 PASS1 的 0）");
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
