package com.cpq.quotation;

import com.cpq.quotation.service.FormulaCalculator;
import com.cpq.quotation.service.FormulaCalculator.RowContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 Task 2 — FormulaCalculator 纯单元测试（不依赖 Quarkus 容器）。
 *
 * <p>1:1 复刻前端 {@code formulaEngine.ts} 口径：token 拼算术串 → 求值；
 * {@code ×→*} {@code ÷→/}；4 位小数 HALF_UP；缺值/解析异常/除零 → 0。
 * token 取值来源与前端一致（field/component_subtotal/previous_row_subtotal/path/gvar）。
 */
@DisplayName("FormulaCalculatorTest — Task 2 公式引擎搬后端")
public class FormulaCalculatorTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private JsonNode json(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private double eval(String tokensJson, RowContext ctx) {
        return calc.evaluateExpression(json(tokensJson), ctx).doubleValue();
    }

    // ======================================================================
    // Layer 1 — evaluateExpression
    // ======================================================================

    @Test
    @DisplayName("T1: field 算术 — 含量/100*单价*(1+损耗/100)*数量（真实 token 形状）")
    void t1_fieldArithmetic() {
        // 取自 DB 真实公式「材料成本(CNY)」的 token 形状
        String tokens = "["
            + "{\"type\":\"field\",\"value\":\"含量\"},"
            + "{\"type\":\"operator\",\"label\":\"÷\",\"value\":\"/\"},"
            + "{\"type\":\"number\",\"value\":\"100\"},"
            + "{\"type\":\"operator\",\"label\":\"×\",\"value\":\"*\"},"
            + "{\"type\":\"field\",\"value\":\"单价\"},"
            + "{\"type\":\"operator\",\"label\":\"×\",\"value\":\"*\"},"
            + "{\"type\":\"bracket_open\",\"value\":\"(\"},"
            + "{\"type\":\"number\",\"value\":\"1\"},"
            + "{\"type\":\"operator\",\"value\":\"+\"},"
            + "{\"type\":\"field\",\"value\":\"损耗\"},"
            + "{\"type\":\"operator\",\"label\":\"÷\",\"value\":\"/\"},"
            + "{\"type\":\"number\",\"value\":\"100\"},"
            + "{\"type\":\"bracket_close\",\"value\":\")\"},"
            + "{\"type\":\"operator\",\"label\":\"×\",\"value\":\"*\"},"
            + "{\"type\":\"field\",\"value\":\"数量\"}"
            + "]";
        RowContext ctx = new RowContext();
        ctx.fieldValues.put("含量", 80.0);
        ctx.fieldValues.put("单价", 5000.0);
        ctx.fieldValues.put("损耗", 3.0);
        ctx.fieldValues.put("数量", 2.0);
        // 80/100*5000*(1+3/100)*2 = 0.8*5000*1.03*2 = 8240
        assertEquals(8240.0, eval(tokens, ctx), 1e-9);
    }

    @Test
    @DisplayName("T2: 运算符 — value 已是 */ 直接用；value 为 ×/÷ Unicode 时映射为 */")
    void t2_operatorMapping() {
        RowContext ctx = new RowContext();
        ctx.fieldValues.put("a", 3.0);
        ctx.fieldValues.put("b", 4.0);
        // value 为 Unicode × → 映射 *
        String mul = "[{\"type\":\"field\",\"value\":\"a\"},"
            + "{\"type\":\"operator\",\"value\":\"\\u00d7\"},"
            + "{\"type\":\"field\",\"value\":\"b\"}]";
        assertEquals(12.0, eval(mul, ctx), 1e-9);
        // value 为 Unicode ÷ → 映射 /
        String div = "[{\"type\":\"number\",\"value\":\"10\"},"
            + "{\"type\":\"operator\",\"value\":\"\\u00f7\"},"
            + "{\"type\":\"number\",\"value\":\"4\"}]";
        assertEquals(2.5, eval(div, new RowContext()), 1e-9);
    }

    @Test
    @DisplayName("T3: 缺失 field → 0（不报错，按 0 参与运算）")
    void t3_missingFieldZero() {
        String tokens = "[{\"type\":\"field\",\"value\":\"x\"},"
            + "{\"type\":\"operator\",\"value\":\"*\"},"
            + "{\"type\":\"number\",\"value\":\"5\"}]";
        assertEquals(0.0, eval(tokens, new RowContext()), 1e-9);
    }

    @Test
    @DisplayName("T4: component_subtotal 取值优先级 component_code ?? tab_name ?? value")
    void t4_componentSubtotalPriority() {
        // 只命中 tab_name（component_code 不在 map 中）
        String tokens = "[{\"type\":\"component_subtotal\",\"component_code\":\"C1\","
            + "\"tab_name\":\"小计\",\"value\":\"小计\"}]";
        RowContext ctx = new RowContext();
        ctx.componentSubtotals.put("小计", 7.0);
        assertEquals(7.0, eval(tokens, ctx), 1e-9);

        // component_code 优先
        RowContext ctx2 = new RowContext();
        ctx2.componentSubtotals.put("C1", 11.0);
        ctx2.componentSubtotals.put("小计", 7.0);
        assertEquals(11.0, eval(tokens, ctx2), 1e-9);

        // 都没命中 → 0
        assertEquals(0.0, eval(tokens, new RowContext()), 1e-9);
    }

    @Test
    @DisplayName("T5: previous_row_subtotal — 传入上行小计优先；行0未传走 fallback_component_code；都无→0")
    void t5_previousRowSubtotal() {
        // 工序累加: 上道/成材率 + 单价
        String tokens = "[{\"type\":\"previous_row_subtotal\",\"fallback_component_code\":\"ELE\"},"
            + "{\"type\":\"operator\",\"value\":\"/\"},"
            + "{\"type\":\"field\",\"value\":\"成材率\"},"
            + "{\"type\":\"operator\",\"value\":\"+\"},"
            + "{\"type\":\"field\",\"value\":\"单价\"}]";
        // 传入上一行小计 200 → 200/2+5 = 105
        RowContext ctx = new RowContext();
        ctx.previousRowSubtotal = 200.0;
        ctx.fieldValues.put("成材率", 2.0);
        ctx.fieldValues.put("单价", 5.0);
        assertEquals(105.0, eval(tokens, ctx), 1e-9);

        // 行 0 未传 → fallback ELE=100 → 100/2+5 = 55
        RowContext ctx0 = new RowContext();
        ctx0.componentSubtotals.put("ELE", 100.0);
        ctx0.fieldValues.put("成材率", 2.0);
        ctx0.fieldValues.put("单价", 5.0);
        assertEquals(55.0, eval(tokens, ctx0), 1e-9);

        // 行 0 且无 fallback 命中 → previous=0 → 0/2+5 = 5
        RowContext ctxNone = new RowContext();
        ctxNone.fieldValues.put("成材率", 2.0);
        ctxNone.fieldValues.put("单价", 5.0);
        assertEquals(5.0, eval(tokens, ctxNone), 1e-9);
    }

    @Test
    @DisplayName("T6: 4 位小数 HALF_UP")
    void t6_rounding() {
        // 2/3 = 0.6666... → 0.6667
        String t = "[{\"type\":\"number\",\"value\":\"2\"},"
            + "{\"type\":\"operator\",\"value\":\"/\"},"
            + "{\"type\":\"number\",\"value\":\"3\"}]";
        assertEquals(0.6667, eval(t, new RowContext()), 1e-9);
    }

    @Test
    @DisplayName("T7: 除零 → 0（金额安全，对齐 Step1 契约）")
    void t7_divByZeroZero() {
        String t = "[{\"type\":\"number\",\"value\":\"5\"},"
            + "{\"type\":\"operator\",\"value\":\"/\"},"
            + "{\"type\":\"number\",\"value\":\"0\"}]";
        assertEquals(0.0, eval(t, new RowContext()), 1e-9);
    }

    @Test
    @DisplayName("T8: 解析异常（尾随运算符）→ 0")
    void t8_malformedZero() {
        String t = "[{\"type\":\"number\",\"value\":\"5\"},"
            + "{\"type\":\"operator\",\"value\":\"*\"}]";
        assertEquals(0.0, eval(t, new RowContext()), 1e-9);
    }

    @Test
    @DisplayName("T9: path / global_variable token 从 basicDataValues 取值（{path} / @gvar:CODE）")
    void t9_pathAndGvar() {
        // path token: basicDataValues["{mat_part.unit_weight}"] = 12.5
        String pathTok = "[{\"type\":\"path\",\"path\":\"mat_part.unit_weight\"},"
            + "{\"type\":\"operator\",\"value\":\"*\"},"
            + "{\"type\":\"number\",\"value\":\"2\"}]";
        RowContext ctx = new RowContext();
        ctx.basicDataValues.put("{mat_part.unit_weight}", 12.5);
        assertEquals(25.0, eval(pathTok, ctx), 1e-9);

        // global_variable token: 优先 @gvar:CODE
        String gvTok = "[{\"type\":\"global_variable\",\"code\":\"EXCHANGE_RATE\","
            + "\"path\":\"v_x[a='b'].rate\"}]";
        RowContext ctx2 = new RowContext();
        ctx2.basicDataValues.put("@gvar:EXCHANGE_RATE", 6.8);
        assertEquals(6.8, eval(gvTok, ctx2), 1e-9);
    }

    // ======================================================================
    // Layer 2-4 — calculate / computeTabSubtotal / computeRowKey
    // ======================================================================

    @Test
    @DisplayName("T10: computeRowKey — 按 rowKeyFields 从 driverRow 拼复合键（不含顺序列）")
    void t10_computeRowKey() {
        JsonNode rkf = json("[\"子件\",\"元素\"]");
        JsonNode driverRow = json("{\"子件\":\"P1\",\"元素\":\"Ag\",\"seq_no\":1}");
        assertEquals("P1||Ag", calc.computeRowKey(rkf, driverRow));
    }

    @Test
    @DisplayName("T11: calculate — 多行 driver，每行用各自 basicDataValues 算 FORMULA，formulaResults 按 rowKey 索引")
    void t11_calculatePerRow() {
        // fields: 单价(BASIC_DATA from {v.price}), 数量(FIXED_VALUE content=2), 金额(FORMULA 单价*数量)
        JsonNode fields = json("["
            + "{\"name\":\"单价\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.price\"},"
            + "{\"name\":\"数量\",\"fieldType\":\"FIXED_VALUE\",\"defaultValue\":\"2\"},"
            + "{\"name\":\"金额\",\"fieldType\":\"FORMULA\"}"
            + "]");
        JsonNode formulas = json("[{\"name\":\"金额\",\"expression\":["
            + "{\"type\":\"field\",\"value\":\"单价\"},"
            + "{\"type\":\"operator\",\"value\":\"*\"},"
            + "{\"type\":\"field\",\"value\":\"数量\"}]}]");
        JsonNode rkf = json("[\"material_no\"]");
        JsonNode baseRows = json("["
            + "{\"driverRow\":{\"material_no\":\"M1\"},\"basicDataValues\":{\"{v.price}\":10}},"
            + "{\"driverRow\":{\"material_no\":\"M2\"},\"basicDataValues\":{\"{v.price}\":15}}"
            + "]");
        JsonNode editRows = json("[]");

        JsonNode fr = calc.calculate(fields, formulas, rkf, baseRows, editRows,
            new HashMap<>(), new HashMap<>(), new HashMap<>());
        assertTrue(fr.isArray());
        assertEquals(2, fr.size());

        Map<String, JsonNode> byKey = new HashMap<>();
        for (JsonNode r : fr) byKey.put(r.path("rowKey").asText(), r);
        assertEquals(20.0, byKey.get("M1").path("values").path("金额").asDouble(), 1e-9);
        assertEquals(30.0, byKey.get("M2").path("values").path("金额").asDouble(), 1e-9);
    }

    @Test
    @DisplayName("T12: calculate — editRows 按 rowKey 覆盖可编辑字段值参与公式重算")
    void t12_calculateEditOverride() {
        JsonNode fields = json("["
            + "{\"name\":\"单价\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.price\"},"
            + "{\"name\":\"数量\",\"fieldType\":\"INPUT_NUMBER\",\"defaultValue\":\"2\"},"
            + "{\"name\":\"金额\",\"fieldType\":\"FORMULA\"}"
            + "]");
        JsonNode formulas = json("[{\"name\":\"金额\",\"expression\":["
            + "{\"type\":\"field\",\"value\":\"单价\"},"
            + "{\"type\":\"operator\",\"value\":\"*\"},"
            + "{\"type\":\"field\",\"value\":\"数量\"}]}]");
        JsonNode rkf = json("[\"material_no\"]");
        JsonNode baseRows = json("["
            + "{\"driverRow\":{\"material_no\":\"M1\"},\"basicDataValues\":{\"{v.price}\":10}}"
            + "]");
        // 用户把数量改成 5 → 金额 = 10 * 5 = 50（非默认 2 的 20）
        JsonNode editRows = json("[{\"rowKey\":\"M1\",\"values\":{\"数量\":5}}]");

        JsonNode fr = calc.calculate(fields, formulas, rkf, baseRows, editRows,
            new HashMap<>(), new HashMap<>(), new HashMap<>());
        assertEquals(1, fr.size());
        assertEquals(50.0, fr.get(0).path("values").path("金额").asDouble(), 1e-9);
    }

    @Test
    @DisplayName("T13: calculate — previous_row_subtotal 跨行累加（按行序，上行 is_subtotal 传下行）")
    void t13_calculatePrevRowAccumulate() {
        // 工序 tab: 小计(is_subtotal, FORMULA) = 上道小计/成材率 + 单价
        JsonNode fields = json("["
            + "{\"name\":\"成材率\",\"fieldType\":\"FIXED_VALUE\",\"defaultValue\":\"2\"},"
            + "{\"name\":\"单价\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.up\"},"
            + "{\"name\":\"小计\",\"fieldType\":\"FORMULA\",\"isSubtotal\":true}"
            + "]");
        JsonNode formulas = json("[{\"name\":\"小计\",\"expression\":["
            + "{\"type\":\"previous_row_subtotal\",\"fallback_component_code\":\"ELE\"},"
            + "{\"type\":\"operator\",\"value\":\"/\"},"
            + "{\"type\":\"field\",\"value\":\"成材率\"},"
            + "{\"type\":\"operator\",\"value\":\"+\"},"
            + "{\"type\":\"field\",\"value\":\"单价\"}]}]");
        JsonNode rkf = json("[\"process_code\"]");
        // 行0: ELE fallback=100 → 100/2 + 10 = 60
        // 行1: prev=60 → 60/2 + 20 = 50
        JsonNode baseRows = json("["
            + "{\"driverRow\":{\"process_code\":\"P0\"},\"basicDataValues\":{\"{v.up}\":10}},"
            + "{\"driverRow\":{\"process_code\":\"P1\"},\"basicDataValues\":{\"{v.up}\":20}}"
            + "]");
        Map<String, Double> compSub = new HashMap<>();
        compSub.put("ELE", 100.0);

        JsonNode fr = calc.calculate(fields, formulas, rkf, baseRows, json("[]"),
            compSub, new HashMap<>(), new HashMap<>());
        Map<String, JsonNode> byKey = new HashMap<>();
        for (JsonNode r : fr) byKey.put(r.path("rowKey").asText(), r);
        assertEquals(60.0, byKey.get("P0").path("values").path("小计").asDouble(), 1e-9);
        assertEquals(50.0, byKey.get("P1").path("values").path("小计").asDouble(), 1e-9);
    }

    @Test
    @DisplayName("T14: computeTabSubtotal — 跨行累加 is_subtotal 字段之和")
    void t14_computeTabSubtotal() {
        JsonNode fields = json("["
            + "{\"name\":\"成材率\",\"fieldType\":\"FIXED_VALUE\",\"defaultValue\":\"2\"},"
            + "{\"name\":\"单价\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.up\"},"
            + "{\"name\":\"小计\",\"fieldType\":\"FORMULA\",\"isSubtotal\":true}"
            + "]");
        JsonNode formulas = json("[{\"name\":\"小计\",\"expression\":["
            + "{\"type\":\"previous_row_subtotal\",\"fallback_component_code\":\"ELE\"},"
            + "{\"type\":\"operator\",\"value\":\"/\"},"
            + "{\"type\":\"field\",\"value\":\"成材率\"},"
            + "{\"type\":\"operator\",\"value\":\"+\"},"
            + "{\"type\":\"field\",\"value\":\"单价\"}]}]");
        JsonNode rkf = json("[\"process_code\"]");
        JsonNode baseRows = json("["
            + "{\"driverRow\":{\"process_code\":\"P0\"},\"basicDataValues\":{\"{v.up}\":10}},"
            + "{\"driverRow\":{\"process_code\":\"P1\"},\"basicDataValues\":{\"{v.up}\":20}}"
            + "]");
        Map<String, Double> compSub = new HashMap<>();
        compSub.put("ELE", 100.0);
        // 60 + 50 = 110
        assertEquals(110.0,
            calc.computeTabSubtotal(fields, formulas, rkf, baseRows, json("[]"), compSub).doubleValue(),
            1e-9);
    }
}
