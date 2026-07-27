package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0727 B0 — F-1 口径核查：{@code FormulaCalculator.computeRows}（B10 已给树行 effKey 加
 * {@code __nodeId::} 前缀）与 {@code RowDataMaterializer}（未加前缀）之间的漂移是否真实存在、
 * 是否真的导致 FORMULA 叶子列取不到值。
 *
 * <p><b>场景</b>：DAG 重复子件 —— 同一料号出现在两个不同 {@code __nodeId} 下，driverRow 内容
 * 完全相同（现网实例 3110520789 同挂两父件下的最简化版本）。{@code deleted} 传非 null（哪怕空
 * 列表）即"报价侧"信号，触发 B10 的 nodeId 前缀分支。
 *
 * <p><b>核查结论（写在此处，供 PR 说明引用）</b>：证实。
 * {@link RowDataMaterializer} 内部用于回查 {@code frByKey}/{@code editByKey} 的
 * {@code effKeys} 未走 nodeId 前缀，而 {@link FormulaCalculator#calculate} 产出的
 * {@code formulaResults[].rowKey} 已走前缀（B10）——两者不对齐，{@code frByKey.get(effKey)}
 * 逐行 miss，FORMULA 叶子列在扁平 {@code row_data} 里整列缺失（{@link FormulaCalculator
 * #resolveRowByFieldName} 对 formulaValues==null 的 FORMULA 字段直接跳过 put，不写出 key）。
 * 本测试类先以“修复后应有的正确行为”断言；对照修复前代码会在 {@code assertTrue(r0.has(...))}
 * 处失败，修复后通过（结果已记录于 PR 说明）。
 */
class EffKeyNodeIdAlignmentTest {

    private static final ObjectMapper M = new ObjectMapper();

    private JsonNode j(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static final String COMPONENTS_SNAPSHOT = "[{"
        + "\"componentId\":\"11111111-1111-1111-1111-111111111111\","
        + "\"componentCode\":\"BOMTAB\",\"componentType\":\"NORMAL\",\"tabName\":\"BOM\","
        + "\"fields\":["
        + "  {\"name\":\"料号\",\"field_type\":\"INPUT_TEXT\"},"
        + "  {\"name\":\"单价\",\"field_type\":\"INPUT_NUMBER\"},"
        + "  {\"name\":\"用量\",\"field_type\":\"INPUT_NUMBER\"},"
        + "  {\"name\":\"材料成本\",\"field_type\":\"FORMULA\",\"formula_name\":\"材料成本\"}"
        + "],"
        + "\"formulas\":[{\"name\":\"材料成本\",\"expression\":["
        + "  {\"type\":\"field\",\"value\":\"单价\"},"
        + "  {\"type\":\"operator\",\"value\":\"×\"},"
        + "  {\"type\":\"field\",\"value\":\"用量\"}"
        + "]}]"
        + "}]";

    /** 两行同料号(992)、同 driverRow 内容，分别挂在两个不同的树节点下（DAG 重复子件）。 */
    private static final String SNAPSHOT_ROWS_DAG_DUP = "["
        + "{\"driverRow\":{\"料号\":\"992\",\"单价\":10,\"用量\":2},\"basicDataValues\":{},"
        + " \"__nodeId\":\"S-3120014539/992\"},"
        + "{\"driverRow\":{\"料号\":\"992\",\"单价\":10,\"用量\":2},\"basicDataValues\":{},"
        + " \"__nodeId\":\"S-80011/992\"}"
        + "]";

    @Test
    void quoteSideTreeTab_formulaLeaf_mustBePresentOnBothDagDuplicateRows() throws Exception {
        JsonNode componentsSnapshot = j(COMPONENTS_SNAPSHOT);
        JsonNode snapshotRows = j(SNAPSHOT_ROWS_DAG_DUP);
        JsonNode rowKeyFields = j("[\"料号\"]");

        RowDataMaterializer materializer = new RowDataMaterializer(new FormulaCalculator());

        // deleted 传非 null 空列表 = "报价侧"信号（buildCardValues 恒传真实墓碑 Map，即使当前无删除）。
        ArrayNode out = materializer.materializeComponentRows(
            componentsSnapshot, "BOMTAB", snapshotRows,
            /* editRows */ null, rowKeyFields,
            Map.of(), Map.of(),
            /* deleted */ List.of(),
            /* rowKeyFieldNames */ List.of("料号"));

        assertEquals(2, out.size(), "两行 DAG 重复子件都应保留（无墓碑，均不应被过滤）");

        JsonNode r0 = out.get(0);
        JsonNode r1 = out.get(1);

        assertTrue(r0.has("材料成本"),
            "行0（节点 S-3120014539/992）的 FORMULA 叶子列必须算出并写入 row_data —— "
            + "若此处失败，证明 RowDataMaterializer 的 effKey 未按 __nodeId 前缀对齐 "
            + "FormulaCalculator.calculate 产出的 formulaResults[].rowKey（B0 核查证实的漂移）");
        assertTrue(r1.has("材料成本"),
            "行1（节点 S-80011/992）的 FORMULA 叶子列必须算出并写入 row_data（同上）");

        assertEquals(0, new java.math.BigDecimal("20").compareTo(r0.get("材料成本").decimalValue()),
            "材料成本 = 10 × 2 = 20");
        assertEquals(0, new java.math.BigDecimal("20").compareTo(r1.get("材料成本").decimalValue()),
            "材料成本 = 10 × 2 = 20（两节点内容相同，值也应相同）");
    }

    /** 对照组：核价侧固定 deleted=null，即便 baseRow 携带 __nodeId 也不应触发前缀分支（零回归）。 */
    @Test
    void costingSideDeletedNull_zeroRegression_effKeyUnprefixed() throws Exception {
        JsonNode componentsSnapshot = j(COMPONENTS_SNAPSHOT);
        JsonNode snapshotRows = j(SNAPSHOT_ROWS_DAG_DUP);
        JsonNode rowKeyFields = j("[\"料号\"]");

        RowDataMaterializer materializer = new RowDataMaterializer(new FormulaCalculator());

        // deleted=null → 核价侧固定信号，不触发前缀分支（即便同 rowKeyFields 内容相同也按旧 #序号消歧）。
        ArrayNode out = materializer.materializeComponentRows(
            componentsSnapshot, "BOMTAB", snapshotRows, Map.of());

        assertEquals(2, out.size());
        // 核价侧零回归：两行内容相同、无 nodeId 前缀参与 —— FORMULA 叶子应仍能正确算出
        // （核价侧走的是"#序号"撞键消歧路径，同样能唯一区分两行，不应受本次改动影响）。
        assertTrue(out.get(0).has("材料成本"), "核价侧零回归：FORMULA 叶子仍应算出");
        assertTrue(out.get(1).has("材料成本"), "核价侧零回归：FORMULA 叶子仍应算出");
    }

    /**
     * 核查②：修复后 RowDataMaterializer 与 FormulaCalculator.calculate 的 rowKey 是否逐字节一致
     * （不是"碰巧都能取到值"，而是同一份 effKey）。直接对比 calculate 产出的 formulaResults[].rowKey
     * 与 materialize 内部理应命中的 effKey——用单行(无撞键)+唯一 nodeId 场景，effKey 应恰好等于
     * "nodeId::料号"，两条产出路径必须字面相等。
     */
    @Test
    void formulaResultsRowKey_and_materializerEffKey_areByteIdentical() throws Exception {
        String singleRowSnapshot = "["
            + "{\"driverRow\":{\"料号\":\"P1\",\"单价\":7,\"用量\":3},\"basicDataValues\":{},"
            + " \"__nodeId\":\"N1/N2\"}"
            + "]";
        JsonNode componentsSnapshot = j(COMPONENTS_SNAPSHOT);
        JsonNode snapshotRows = j(singleRowSnapshot);
        JsonNode rowKeyFields = j("[\"料号\"]");
        JsonNode fields = componentsSnapshot.get(0).path("fields");
        JsonNode formulas = componentsSnapshot.get(0).path("formulas");

        FormulaCalculator calc = new FormulaCalculator();
        ArrayNode formulaResults = calc.calculate(
            fields, formulas, null, rowKeyFields, snapshotRows, j("[]"),
            Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of("料号"));
        String rowKeyFromCalculate = formulaResults.get(0).path("rowKey").asText("");
        assertEquals("N1/N2::P1", rowKeyFromCalculate, "calculate 产出的 rowKey 应为 nodeId::内容键");

        RowDataMaterializer materializer = new RowDataMaterializer(calc);
        ArrayNode out = materializer.materializeComponentRows(
            componentsSnapshot, "BOMTAB", snapshotRows,
            /* editRows */ null, rowKeyFields, Map.of(), Map.of(),
            /* deleted */ List.of(), /* rowKeyFieldNames */ List.of("料号"));
        assertTrue(out.get(0).has("材料成本"),
            "materialize 必须用与 calculate 逐字节相同的 effKey(" + rowKeyFromCalculate + ") 命中 frByKey");
        assertEquals(0, new java.math.BigDecimal("21").compareTo(out.get(0).get("材料成本").decimalValue()),
            "材料成本 = 7 × 3 = 21");
    }

    /**
     * 核查③：旧键回退——存量单据的 editRows.rowKey 是改造前（无 nodeId 前缀）写入的，B0 对齐后
     * RowDataMaterializer 内部查表键变成带前缀的新格式；若不做旧键回退，历史编辑值会集体读不到
     * （INPUT 列静默丢失用户曾经填过的值）。本测试验证：editRows 用旧格式(不带前缀)存的 rowKey，
     * 新账仍能通过回退命中，编辑值正确反映到 INPUT 列与依赖它的 FORMULA 叶子列。
     */
    @Test
    void legacyEditRowsKey_withoutNodeIdPrefix_stillReadableAfterB0Fix() throws Exception {
        String singleRowSnapshot = "["
            + "{\"driverRow\":{\"料号\":\"P1\",\"单价\":10,\"用量\":2},\"basicDataValues\":{},"
            + " \"__nodeId\":\"N1/N2\"}"
            + "]";
        JsonNode componentsSnapshot = j(COMPONENTS_SNAPSHOT);
        JsonNode snapshotRows = j(singleRowSnapshot);
        JsonNode rowKeyFields = j("[\"料号\"]");
        // 存量格式：rowKey 是旧口径(不带 nodeId 前缀)，即改造前 RowDataMaterializer/前端产出的裸内容键。
        JsonNode legacyEditRows = j("[{\"rowKey\":\"P1\",\"values\":{\"单价\":99}}]");

        RowDataMaterializer materializer = new RowDataMaterializer(new FormulaCalculator());
        ArrayNode out = materializer.materializeComponentRows(
            componentsSnapshot, "BOMTAB", snapshotRows,
            legacyEditRows, rowKeyFields, Map.of(), Map.of(),
            /* deleted */ List.of(), /* rowKeyFieldNames */ List.of("料号"));

        assertEquals(1, out.size());
        JsonNode r0 = out.get(0);
        assertEquals(0, new java.math.BigDecimal("99").compareTo(r0.get("单价").decimalValue()),
            "旧格式(无 nodeId 前缀)editRows 的编辑值必须通过回退查找命中，INPUT 列反映编辑后的 99"
            + "（B0 授权范围内已修复：RowDataMaterializer 自身对 editByKey 的查表有旧键回退）");
        // 已知边界（本测试如实记录，未在 B0 授权范围内修复，因当前全库 0 实例受影响）：
        // FormulaCalculator.calculate 内部对 editRows 的合并查表（computeRows:832 一行
        // editByKey.containsKey(effKey)）没有同款旧键回退——它是 calculate() 自己的内部状态，
        // 不属于 B0 backtask 明确列出的"两处查表"（buildResolvedRows / RowDataMaterializer 对
        // frByKey/editByKey 的回查）。故 calculate() 产出的 formulaResults 仍按未编辑的原始
        // 单价(10)计算 FORMULA 值，materialize 层再把这个"未感知编辑"的 frValues 透传出来——
        // 同一行出现"INPUT 列显示编辑后的 99，但依赖它的 FORMULA 列仍是按 10 算出的 20"的
        // 局部不一致。技术总监复核时请留意：这只在"树页签 + 配了 FORMULA 列 + 存量(旧格式)编辑值"
        // 三者同时成立时才会显形——技术总监裁决 10.1 已确认全库 3 个 BOM 树组件的 FORMULA 字段数
        // 均为 0，故当前无实例受影响；若后续给树页签配 FORMULA 列，需要另开 backtask 把同款回退
        // 补进 computeRows 内部的 editByKey 查找。
        assertEquals(0, new java.math.BigDecimal("20").compareTo(r0.get("材料成本").decimalValue()),
            "已知边界：FORMULA 叶子当前仍按未编辑的原始单价(10)算出 20，不反映旧格式编辑值——"
            + "见上方注释，非本次 B0 授权修复范围，当前全库 0 实例受影响");
    }
}
