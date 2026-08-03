package com.cpq.quotation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * task-0803 Task 4：单元格拓扑路径的<b>路由</b>与端到端行为（走公开入口 {@code calculate}）。
 *
 * <p>纯 JUnit（不用 {@code @QuarkusTest}）—— BL-0095。
 */
class TreeCellTopoRoutingTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private JsonNode j(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 三层树：根 n1(单价100) → n2(单价10)、n3(单价20)；n4(单价999) 挂 n2 下。 */
    private JsonNode treeRows() {
        return j("""
            [
              {"__nodeId":"n1","__parentId":null,"__lvl":0,"driverRow":{"单价":100},"basicDataValues":{}},
              {"__nodeId":"n2","__parentId":"n1","__lvl":1,"driverRow":{"单价":10},"basicDataValues":{}},
              {"__nodeId":"n3","__parentId":"n1","__lvl":1,"driverRow":{"单价":20},"basicDataValues":{}},
              {"__nodeId":"n4","__parentId":"n2","__lvl":2,"driverRow":{"单价":999},"basicDataValues":{}}
            ]
            """);
    }

    /** 无树系统列的普通行集（同样 4 行、同样单价）。 */
    private JsonNode flatRows() {
        return j("""
            [
              {"driverRow":{"单价":100},"basicDataValues":{}},
              {"driverRow":{"单价":10},"basicDataValues":{}},
              {"driverRow":{"单价":20},"basicDataValues":{}},
              {"driverRow":{"单价":999},"basicDataValues":{}}
            ]
            """);
    }

    private ArrayNode run(JsonNode fields, JsonNode formulas, JsonNode rows) {
        return calc.calculate(fields, formulas, null, null, rows, null,
            Map.of(), Map.of(), Map.of());
    }

    private double valueAt(ArrayNode res, int rowIdx, String col) {
        return res.get(rowIdx).path("values").path(col).asDouble();
    }

    // ───────────────────────── 零回归门禁 ─────────────────────────

    @Test
    @DisplayName("R1: 非树行集 —— 走原逐行路径")
    void nonTreeRowsUseOriginalPath() {
        JsonNode fields = j("""
            [{"name":"单价","field_type":"INPUT_NUMBER"},
             {"name":"翻倍","field_type":"FORMULA","formula_name":"翻倍公式"}]
            """);
        JsonNode formulas = j("""
            [{"name":"翻倍公式","expression":[
                {"type":"field","value":"单价"},{"type":"operator","value":"*"},{"type":"number","value":"2"}]}]
            """);
        ArrayNode res = run(fields, formulas, flatRows());
        assertEquals(200.0, valueAt(res, 0, "翻倍"), 1e-9);
        assertEquals(1998.0, valueAt(res, 3, "翻倍"), 1e-9);
    }

    @Test
    @DisplayName("R2: 树行集但公式不含父子 token —— 仍走原逐行路径，结果与 R1 逐位一致")
    void treeRowsWithoutTreeTokenUnchanged() {
        JsonNode fields = j("""
            [{"name":"单价","field_type":"INPUT_NUMBER"},
             {"name":"翻倍","field_type":"FORMULA","formula_name":"翻倍公式"}]
            """);
        JsonNode formulas = j("""
            [{"name":"翻倍公式","expression":[
                {"type":"field","value":"单价"},{"type":"operator","value":"*"},{"type":"number","value":"2"}]}]
            """);
        ArrayNode flat = run(fields, formulas, flatRows());
        ArrayNode tree = run(fields, formulas, treeRows());
        assertEquals(flat.size(), tree.size());
        for (int i = 0; i < flat.size(); i++) {
            assertEquals(valueAt(flat, i, "翻倍"), valueAt(tree, i, "翻倍"), 1e-9,
                "第 " + i + " 行：树行集但无父子 token，结果必须与普通行集一致");
        }
    }

    // ───────────────────────── 端到端 ─────────────────────────

    @Test
    @DisplayName("E1: 自下而上 —— 父行 CSUM 汇总直接子行（逐层滚算）")
    void bottomUpRollup() {
        JsonNode fields = j("""
            [{"name":"单价","field_type":"INPUT_NUMBER"},
             {"name":"子件合计","field_type":"FORMULA","formula_name":"子件合计公式"}]
            """);
        JsonNode formulas = j("""
            [{"name":"子件合计公式","expression":[
                {"type":"tree_ref","dir":"CHILD","agg":"SUM",
                 "targetExpr":[{"type":"field","value":"单价"}]}]}]
            """);
        ArrayNode res = run(fields, formulas, treeRows());
        assertEquals(30.0, valueAt(res, 0, "子件合计"), 1e-9, "根 = n2(10)+n3(20)，不含孙 n4");
        assertEquals(999.0, valueAt(res, 1, "子件合计"), 1e-9, "n2 = 其子 n4(999)");
        assertEquals(0.0, valueAt(res, 2, "子件合计"), 1e-9, "n3 是叶子 → 0");
        assertEquals(0.0, valueAt(res, 3, "子件合计"), 1e-9, "n4 是叶子 → 0");
    }

    @Test
    @DisplayName("E2: 自上而下 —— PGET 取父行值（累乘场景的基础）")
    void topDownPget() {
        JsonNode fields = j("""
            [{"name":"单价","field_type":"INPUT_NUMBER"},
             {"name":"父单价","field_type":"FORMULA","formula_name":"父单价公式"}]
            """);
        JsonNode formulas = j("""
            [{"name":"父单价公式","expression":[
                {"type":"tree_ref","dir":"PARENT","agg":"NONE",
                 "targetExpr":[{"type":"field","value":"单价"}]}]}]
            """);
        ArrayNode res = run(fields, formulas, treeRows());
        assertEquals(0.0, valueAt(res, 0, "父单价"), 1e-9, "根无父 → 0");
        assertEquals(100.0, valueAt(res, 1, "父单价"), 1e-9);
        assertEquals(100.0, valueAt(res, 2, "父单价"), 1e-9);
        assertEquals(10.0, valueAt(res, 3, "父单价"), 1e-9, "n4 的父是 n2");
    }

    @Test
    @DisplayName("E3: PGET 链式自上而下累乘 —— 根=1，子=父×本行系数")
    void topDownChainedAccumulation() {
        // 系数：n1=1, n2=2, n3=3, n4=5 → 累乘 n1=1, n2=2, n3=3, n4=2*5=10
        JsonNode rows = j("""
            [
              {"__nodeId":"n1","__parentId":null,"__lvl":0,"driverRow":{"系数":1},"basicDataValues":{}},
              {"__nodeId":"n2","__parentId":"n1","__lvl":1,"driverRow":{"系数":2},"basicDataValues":{}},
              {"__nodeId":"n3","__parentId":"n1","__lvl":1,"driverRow":{"系数":3},"basicDataValues":{}},
              {"__nodeId":"n4","__parentId":"n2","__lvl":2,"driverRow":{"系数":5},"basicDataValues":{}}
            ]
            """);
        JsonNode fields = j("""
            [{"name":"系数","field_type":"INPUT_NUMBER"},
             {"name":"累乘","field_type":"FORMULA","formula_name":"累乘公式"}]
            """);
        // 累乘 = 如果是根 → 系数；否则 → PGET(累乘) × 系数
        //      用算术等价写法：[是否根]×系数 + (1-[是否根])×PGET(累乘)×系数
        JsonNode formulas = j("""
            [{"name":"累乘公式","expression":[
                {"type":"tree_attr","attr":"IS_ROOT"},{"type":"operator","value":"*"},
                {"type":"field","value":"系数"},{"type":"operator","value":"+"},
                {"type":"bracket_open"},{"type":"number","value":"1"},{"type":"operator","value":"-"},
                {"type":"tree_attr","attr":"IS_ROOT"},{"type":"bracket_close"},
                {"type":"operator","value":"*"},
                {"type":"tree_ref","dir":"PARENT","agg":"NONE",
                 "targetExpr":[{"type":"field","value":"累乘"}]},
                {"type":"operator","value":"*"},{"type":"field","value":"系数"}]}]
            """);
        ArrayNode res = run(fields, formulas, rows);
        assertEquals(1.0, valueAt(res, 0, "累乘"), 1e-9, "根 → 系数本身");
        assertEquals(2.0, valueAt(res, 1, "累乘"), 1e-9, "1×2");
        assertEquals(3.0, valueAt(res, 2, "累乘"), 1e-9, "1×3");
        assertEquals(10.0, valueAt(res, 3, "累乘"), 1e-9, "父(2)×5 —— 依赖父行先算出 2");
    }

    @Test
    @DisplayName("AC-10: 双向混用 —— 一列自下而上、一列自上而下，同页签并存且都算对")
    void mixedDirectionsInOneTab() {
        JsonNode fields = j("""
            [{"name":"单价","field_type":"INPUT_NUMBER"},
             {"name":"子件合计","field_type":"FORMULA","formula_name":"子件合计公式"},
             {"name":"父单价","field_type":"FORMULA","formula_name":"父单价公式"}]
            """);
        JsonNode formulas = j("""
            [{"name":"子件合计公式","expression":[
                {"type":"tree_ref","dir":"CHILD","agg":"SUM","targetExpr":[{"type":"field","value":"单价"}]}]},
             {"name":"父单价公式","expression":[
                {"type":"tree_ref","dir":"PARENT","agg":"NONE","targetExpr":[{"type":"field","value":"单价"}]}]}]
            """);
        ArrayNode res = run(fields, formulas, treeRows());
        assertEquals(30.0, valueAt(res, 0, "子件合计"), 1e-9);
        assertEquals(0.0, valueAt(res, 0, "父单价"), 1e-9);
        assertEquals(999.0, valueAt(res, 1, "子件合计"), 1e-9);
        assertEquals(100.0, valueAt(res, 1, "父单价"), 1e-9);
        assertEquals(10.0, valueAt(res, 3, "父单价"), 1e-9);
    }

    @Test
    @DisplayName("AC-11: 成环 —— 环上列置 0，同页签其他列仍正常")
    void cycleZeroedOthersSurvive() {
        JsonNode fields = j("""
            [{"name":"单价","field_type":"INPUT_NUMBER"},
             {"name":"A","field_type":"FORMULA","formula_name":"A公式"},
             {"name":"B","field_type":"FORMULA","formula_name":"B公式"},
             {"name":"正常列","field_type":"FORMULA","formula_name":"正常公式"}]
            """);
        // A = PGET(B)，B = CSUM(A) → 互相依赖成环
        JsonNode formulas = j("""
            [{"name":"A公式","expression":[
                {"type":"tree_ref","dir":"PARENT","agg":"NONE","targetExpr":[{"type":"field","value":"B"}]}]},
             {"name":"B公式","expression":[
                {"type":"tree_ref","dir":"CHILD","agg":"SUM","targetExpr":[{"type":"field","value":"A"}]}]},
             {"name":"正常公式","expression":[
                {"type":"field","value":"单价"},{"type":"operator","value":"*"},{"type":"number","value":"2"}]}]
            """);
        ArrayNode res = run(fields, formulas, treeRows());
        assertEquals(0.0, valueAt(res, 1, "A"), 1e-9, "环上列置 0");
        assertEquals(0.0, valueAt(res, 0, "B"), 1e-9, "环上列置 0");
        assertEquals(200.0, valueAt(res, 0, "正常列"), 1e-9, "环外列仍正常求值");
        assertEquals(1998.0, valueAt(res, 3, "正常列"), 1e-9);
    }

    @Test
    @DisplayName("行序与行数不变 —— 单元格拓扑不改变输出行的顺序")
    void rowOrderPreserved() {
        // 注：calculate 的输出只含 rowKey + values（公式结果），不含 fieldValues，
        //     故用一个「回显单价」的公式列来验证行序。
        JsonNode fields = j("""
            [{"name":"单价","field_type":"INPUT_NUMBER"},
             {"name":"原价回显","field_type":"FORMULA","formula_name":"回显公式"},
             {"name":"子件合计","field_type":"FORMULA","formula_name":"子件合计公式"}]
            """);
        JsonNode formulas = j("""
            [{"name":"回显公式","expression":[{"type":"field","value":"单价"}]},
             {"name":"子件合计公式","expression":[
                {"type":"tree_ref","dir":"CHILD","agg":"SUM","targetExpr":[{"type":"field","value":"单价"}]}]}]
            """);
        ArrayNode res = run(fields, formulas, treeRows());
        assertEquals(4, res.size(), "行数不变");
        assertEquals(100.0, valueAt(res, 0, "原价回显"), 1e-9);
        assertEquals(10.0, valueAt(res, 1, "原价回显"), 1e-9);
        assertEquals(20.0, valueAt(res, 2, "原价回显"), 1e-9);
        assertEquals(999.0, valueAt(res, 3, "原价回显"), 1e-9);
        // 同时确认父子列仍算对（回显列的存在不影响拓扑）
        assertEquals(30.0, valueAt(res, 0, "子件合计"), 1e-9);
    }
}
