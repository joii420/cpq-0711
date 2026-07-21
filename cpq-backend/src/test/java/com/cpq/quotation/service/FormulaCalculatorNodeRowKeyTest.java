package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0721 B10 — 树上行键含节点维度单测。
 *
 * <p>DAG 重复子件场景（现网实例 3110520789 同挂 2120011658/2120011659 两父件下）：
 * 同一料号出现在不同 {@code __nodeId} 时，行键必须按节点消歧，不能靠"#序号"（序号依赖数组顺序，
 * 刷新/位置变化会错位）。
 *
 * <p><b>核价侧零回归（AC-10）</b>：{@code deleted==null} 时（核价侧固定传参约定）行键计算
 * 与改造前逐位相同——即便 baseRow 携带 {@code __nodeId}（核价侧渲染引擎同源），仍走"#序号"
 * 撞键消歧的旧逻辑。
 */
class FormulaCalculatorNodeRowKeyTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private JsonNode j(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static final String FIELDS = "["
        + "{\"name\":\"单价\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.up\"}"
        + "]";
    private static final String RKF = "[\"material_no\"]";

    /** 同料号 X 出现在两个不同节点(DAG 重复子件)，均无系统列以外的差异 */
    private static final String BASEROWS_DAG_SAME_PART = "["
        + "{\"driverRow\":{\"material_no\":\"3110520789\"},\"basicDataValues\":{},"
        + " \"__nodeId\":\"3120018220/2120011658/3110520789\"},"
        + "{\"driverRow\":{\"material_no\":\"3110520789\"},\"basicDataValues\":{},"
        + " \"__nodeId\":\"3120018220/2120011659/3110520789\"}"
        + "]";

    @Test
    void quoteSide_dagSameMaterialDifferentNode_rowKeysDiffer() {
        // deleted 传非 null(空列表)即"报价侧"信号
        ArrayNode result = calc.calculate(
            j(FIELDS), j("[]"), null, j(RKF),
            j(BASEROWS_DAG_SAME_PART), j("[]"), Map.of(), Map.of(), Map.of(), Map.of(),
            List.of(), List.of("material_no"));

        assertEquals(2, result.size());
        String rk0 = result.get(0).path("rowKey").asText("");
        String rk1 = result.get(1).path("rowKey").asText("");
        assertNotEquals(rk0, rk1, "DAG 重复子件的两个节点行键必须不同(按 __nodeId 消歧)");
        assertTrue(rk0.contains("2120011658"), "行键须含节点路径: " + rk0);
        assertTrue(rk1.contains("2120011659"), "行键须含节点路径: " + rk1);
        // 不应退化为 "#序号"撞键消歧格式(节点维度已消歧,不该出现 #0/#1)
        assertFalse(rk0.endsWith("#0"), "节点已消歧,不应再落回 #序号: " + rk0);
        assertFalse(rk1.endsWith("#1"), "节点已消歧,不应再落回 #序号: " + rk1);
    }

    @Test
    void costingSide_deletedNull_zeroRegression_fallsBackToSeqSuffix() {
        // deleted=null 是核价侧固定信号(buildCostingCardValues 四参入口),即便 baseRow 有
        // __nodeId(核价侧同一渲染引擎产出),行键计算须与改造前逐位相同——按内容撞键 + #序号消歧。
        ArrayNode result = calc.calculate(
            j(FIELDS), j("[]"), null, j(RKF),
            j(BASEROWS_DAG_SAME_PART), j("[]"), Map.of(), Map.of(), Map.of(), Map.of(),
            null, null);

        assertEquals(2, result.size());
        String rk0 = result.get(0).path("rowKey").asText("");
        String rk1 = result.get(1).path("rowKey").asText("");
        // 两行内容(仅 material_no)完全相同 -> 撞键 -> #序号消歧,不含 __nodeId
        assertEquals("3110520789#0", rk0);
        assertEquals("3110520789#1", rk1);
    }

    @Test
    void quoteSide_flatTabWithoutNodeId_behavesLikeBeforeB10() {
        // 非树页签(无 __nodeId)：即便 deleted!=null(报价侧)，行键计算不受影响(向后兼容)。
        String flatRows = "["
            + "{\"driverRow\":{\"material_no\":\"A\"},\"basicDataValues\":{}},"
            + "{\"driverRow\":{\"material_no\":\"B\"},\"basicDataValues\":{}}"
            + "]";
        ArrayNode result = calc.calculate(
            j(FIELDS), j("[]"), null, j(RKF),
            j(flatRows), j("[]"), Map.of(), Map.of(), Map.of(), Map.of(),
            List.of(), List.of("material_no"));
        assertEquals("A", result.get(0).path("rowKey").asText(""));
        assertEquals("B", result.get(1).path("rowKey").asText(""));
    }
}
