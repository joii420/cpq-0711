package com.cpq.template.service;

import com.cpq.common.exception.FormulaCycleException;
import com.cpq.quotation.service.CrossTabComponentOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0803：模板发布期<b>页签级</b>环的结构化载荷（{@code scope=TAB}）——AC-13。
 *
 * <p>驱动 {@link TemplateService#validateCrossTabRefs(List, Map, Map, List, Map)} 的
 * 5 参重载（与 {@link TemplateCrossTabValidateTest} 的 3 参回落用例互补，那边覆盖旧口径
 * 悬空引用/环的既有行为不变；本文件覆盖 repair-0803 新增的<b>列粒度建图 + 结构化 cycles</b>）。
 * 纯输入构造，不打 DB。
 *
 * <p>两个页签互相用 {@code cross_tab_ref}（全量边，不受列类型豁免）引用对方，构成不含糊的
 * 真 TAB 环，规避 QT-20260803-0052 假环的干扰（那个场景验证的是「不应成环」，见
 * {@code CrossTabDepsRealSnapshotReplayTest} / {@code CrossTabComponentOrderTest}）。
 */
class TemplateCrossTabCycleStructuredTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Pattern UUID_LIKE = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-");
    private final TemplateService svc = new TemplateService();

    private static final String PRD = "56c8a517-e770-4429-82c7-72f216daab45"; // 产品
    private static final String MAT = "74c0cede-094e-478c-a8fe-8f0028d538cd"; // 物料

    private static JsonNode formulasCrossTabRef(String formulaName, String source) throws Exception {
        return M.readTree("[{\"name\":\"" + formulaName + "\",\"expression\":["
            + "{\"type\":\"cross_tab_ref\",\"source\":\"" + source + "\",\"agg\":\"SUM\",\"match\":[{\"a\":\"k\",\"b\":\"k\"}]}"
            + "]}]");
    }

    /** AC-13：产品⇄物料互引 → FormulaCycleException，scope=TAB，nodes[].componentName 为中文名。 */
    @Test void ac13_mutualCrossTabRef_producesStructuredTabScopeCycle() throws Exception {
        List<String> compIds = List.of(PRD, MAT);
        Map<String, JsonNode> formulas = new LinkedHashMap<>();
        formulas.put(PRD, formulasCrossTabRef("管理费", MAT));
        formulas.put(MAT, formulasCrossTabRef("v2-原材料成本公式(银点类)", PRD));

        var tabDeps = List.of(
            new CrossTabComponentOrder.TabDep(PRD, "COMP-0160", "产品", formulas.get(PRD), M.readTree("[]")),
            new CrossTabComponentOrder.TabDep(MAT, "COMP-0157", "物料", formulas.get(MAT), M.readTree("[]")));
        Map<String, String> plainNameById = Map.of(PRD, "产品", MAT, "物料");

        FormulaCycleException ex = assertThrows(FormulaCycleException.class,
            () -> svc.validateCrossTabRefs(compIds, formulas, Map.of(), tabDeps, plainNameById));

        assertEquals(1, ex.getCycles().size(), "应恰好 1 个环: " + ex.getCycles());
        var cyc = ex.getCycles().get(0);
        assertEquals(FormulaCycleException.SCOPE_TAB, cyc.scope());

        var compNames = cyc.nodes().stream().map(FormulaCycleException.Node::componentName).toList();
        assertTrue(compNames.contains("产品") && compNames.contains("物料"),
            "nodes[].componentName 应为中文名「产品」「物料」，不是 componentId: " + compNames);
        for (String n : compNames) {
            assertFalse(UUID_LIKE.matcher(n).find(), "componentName 不得是 UUID: " + n);
        }

        // TAB 作用域的 findCyclePath（CrossTabComponentOrder.dfsCycle）不重复闭合节点，
        // 与 FIELD 作用域（FormulaCalculator.cyclePathIn）不同——此处应严格满足「首尾不重复」契约。
        assertEquals(2, cyc.nodes().size(), "2 页签环应恰好 2 个节点，首尾不重复");
        assertEquals(2, cyc.edges().size(), "2 页签环应恰好 2 条边（含 1 条闭合边）");
        for (FormulaCycleException.Edge e : cyc.edges()) {
            assertNotEquals(e.from(), e.to(), "不应出现自环边: " + e);
            assertNotNull(e.viaFormulaName(), "边应带来源公式名: " + e);
        }
    }

    /** AC-11 同款检查搬到 TAB 作用域：message + 结构化载荷全文不得残留 UUID。 */
    @Test void ac13_messageAndCycles_containNoUuid() throws Exception {
        List<String> compIds = List.of(PRD, MAT);
        Map<String, JsonNode> formulas = new LinkedHashMap<>();
        formulas.put(PRD, formulasCrossTabRef("管理费", MAT));
        formulas.put(MAT, formulasCrossTabRef("v2-原材料成本公式(银点类)", PRD));
        var tabDeps = List.of(
            new CrossTabComponentOrder.TabDep(PRD, "COMP-0160", "产品", formulas.get(PRD), M.readTree("[]")),
            new CrossTabComponentOrder.TabDep(MAT, "COMP-0157", "物料", formulas.get(MAT), M.readTree("[]")));
        Map<String, String> plainNameById = Map.of(PRD, "产品", MAT, "物料");

        FormulaCycleException ex = assertThrows(FormulaCycleException.class,
            () -> svc.validateCrossTabRefs(compIds, formulas, Map.of(), tabDeps, plainNameById));

        assertFalse(UUID_LIKE.matcher(ex.getMessage()).find(), "message 不得含 UUID: " + ex.getMessage());
        String cyclesJson = M.writeValueAsString(ex.getCycles());
        assertFalse(UUID_LIKE.matcher(cyclesJson).find(), "cycles 序列化全文不得含 UUID: " + cyclesJson);
    }

    /** 零破坏回归：不传 tabDeps（3 参签名）时仍回落旧口径，既有测试断言不变（对齐 TemplateCrossTabValidateTest）。 */
    @Test void threeParamOverload_stillWorks_whenTabDepsOmitted() throws Exception {
        List<String> compIds = List.of(PRD, MAT);
        Map<String, JsonNode> formulas = new LinkedHashMap<>();
        formulas.put(PRD, formulasCrossTabRef("管理费", MAT));
        formulas.put(MAT, formulasCrossTabRef("v2-原材料成本公式(银点类)", PRD));

        assertThrows(com.cpq.common.exception.BusinessException.class,
            () -> svc.validateCrossTabRefs(compIds, formulas, Map.of()));
    }
}
