package com.cpq.quotation;

import com.cpq.quotation.service.FormulaCalculator;
import com.cpq.quotation.service.FormulaCalculator.RowContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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

        JsonNode fr = calc.calculate(fields, formulas, null, rkf, baseRows, editRows,
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

        JsonNode fr = calc.calculate(fields, formulas, null, rkf, baseRows, editRows,
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

        JsonNode fr = calc.calculate(fields, formulas, null, rkf, baseRows, json("[]"),
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
            calc.computeTabSubtotal(fields, formulas, null, rkf, baseRows, json("[]"), compSub).doubleValue(),
            1e-9);
    }

    @Test
    @DisplayName("T15: formula_assignments 按完整字段下标绑定（字段名≠公式名，positional 会错配）")
    void t15_formulaAssignmentsBinding() {
        // 完整 fields: [x(FIXED idx0), B(FORMULA idx1), C(FORMULA idx2)]
        JsonNode fields = json("["
            + "{\"name\":\"x\",\"fieldType\":\"FIXED_VALUE\",\"defaultValue\":\"10\"},"
            + "{\"name\":\"B\",\"fieldType\":\"FORMULA\"},"
            + "{\"name\":\"C\",\"fieldType\":\"FORMULA\"}"
            + "]");
        // 公式顺序与字段顺序故意错位：formulas[0]=fC(x*3), formulas[1]=fB(x+1)
        JsonNode formulas = json("["
            + "{\"name\":\"fC\",\"expression\":[{\"type\":\"field\",\"value\":\"x\"},"
            + "{\"type\":\"operator\",\"value\":\"*\"},{\"type\":\"number\",\"value\":\"3\"}]},"
            + "{\"name\":\"fB\",\"expression\":[{\"type\":\"field\",\"value\":\"x\"},"
            + "{\"type\":\"operator\",\"value\":\"+\"},{\"type\":\"number\",\"value\":\"1\"}]}"
            + "]");
        // formula_assignments 键为完整字段下标：B(idx1)→fB, C(idx2)→fC
        JsonNode assignments = json("{\"1\":\"fB\",\"2\":\"fC\"}");
        JsonNode rkf = json("[\"k\"]");
        JsonNode baseRows = json("[{\"driverRow\":{\"k\":\"r1\"},\"basicDataValues\":{}}]");

        JsonNode fr = calc.calculate(fields, formulas, assignments, rkf, baseRows, json("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>());
        assertEquals(1, fr.size());
        JsonNode values = fr.get(0).path("values");
        // 正确绑定: B=fB=x+1=11, C=fC=x*3=30（positional 会得 B=30,C=11）
        assertEquals(11.0, values.path("B").asDouble(), 1e-9);
        assertEquals(30.0, values.path("C").asDouble(), 1e-9);
    }

    // ======================================================================
    // T16: computeTabSubtotalsByColumn — INPUT_NUMBER is_subtotal 列小计
    // ======================================================================

    @Test
    @DisplayName("T16: computeTabSubtotalsByColumn — is_subtotal 的 INPUT_NUMBER 列两行值累加（非 FORMULA，当前恒 0 = bug）")
    void t16_subtotalInputNumberColumn() {
        // 场景：汇率(INPUT_NUMBER, is_subtotal=true) 两行用户输入 7.12 和 3.0，无 FORMULA 列
        // 期望小计 = 7.12 + 3.0 = 10.12；bug 状态下返回 0
        JsonNode fields = json("["
            + "{\"name\":\"exchange_rate\",\"fieldType\":\"INPUT_NUMBER\",\"isSubtotal\":true}"
            + "]");
        JsonNode formulas = json("[]");
        JsonNode rkf = json("[\"row_id\"]");
        JsonNode baseRows = json("["
            + "{\"driverRow\":{\"row_id\":\"R1\",\"exchange_rate\":7.12},\"basicDataValues\":{}},"
            + "{\"driverRow\":{\"row_id\":\"R2\",\"exchange_rate\":3.0},\"basicDataValues\":{}}"
            + "]");

        Map<String, java.math.BigDecimal> result = calc.computeTabSubtotalsByColumn(
            fields, formulas, null, rkf, baseRows, json("[]"), new HashMap<>());

        assertTrue(result.containsKey("exchange_rate"), "exchange_rate 列应出现在小计结果中");
        assertEquals(10.12, result.get("exchange_rate").doubleValue(), 1e-9,
            "INPUT_NUMBER is_subtotal 列应累加行值 7.12+3.0=10.12");
    }

    // ======================================================================
    // Phase4 Task6 — 防漂移红线: 与前端 formulaEngine 共享样本逐分对账
    // ======================================================================

    @Test
    @DisplayName("Reconcile: 共享样本(formula-reconcile-cases.json) 后端 FormulaCalculator == 前端 formulaEngine 逐分一致")
    void reconcileFixture_frontendBackendParity() throws Exception {
        // 唯一权威样本由前端 vitest(formulaReconcile.test.ts) 与本测试同读；任一引擎漂移 → 一侧变红。
        Path fixture = Path.of("..", "cpq-frontend", "src", "utils", "__fixtures__", "formula-reconcile-cases.json");
        assertTrue(Files.exists(fixture), "共享对账样本缺失: " + fixture.toAbsolutePath());
        JsonNode root = M.readTree(Files.readString(fixture));
        JsonNode cases = root.path("cases");
        assertTrue(cases.isArray() && cases.size() > 0, "样本非空");

        for (JsonNode cse : cases) {
            String name = cse.path("name").asText("");
            RowContext ctx = new RowContext();
            putDoubles(ctx.fieldValues, cse.path("fieldValues"));
            putDoubles(ctx.componentSubtotals, cse.path("componentSubtotals"));
            putDoubles(ctx.productAttributes, cse.path("productAttributes"));
            putDoubles(ctx.quotationFields, cse.path("quotationFields"));
            JsonNode bdv = cse.path("basicDataValues");
            if (bdv.isObject()) bdv.fields().forEachRemaining(e ->
                ctx.basicDataValues.put(e.getKey(),
                    e.getValue().isNumber() ? e.getValue().numberValue() : e.getValue().asText()));
            JsonNode prev = cse.path("previousRowSubtotal");
            ctx.previousRowSubtotal = (prev.isNull() || prev.isMissingNode()) ? null : prev.asDouble();

            double actual = calc.evaluateExpression(cse.path("tokens"), ctx).doubleValue();
            double expected = cse.path("expected").asDouble();
            // 后端 setScale(4, HALF_UP)；逐分一致 tolerance 1e-9
            assertEquals(expected, actual, 1e-9, "reconcile 漂移: " + name);
        }
    }

    // ======================================================================
    // T17-T18: computeRowKey(rkf, fields, driverRow, basicDataValues) 新重载
    // 外购件场景：row_key_fields=["料件","要素"]，driverRow 键为 _料件/_要素（视图列别名）
    // ======================================================================

    @Test
    @DisplayName("T17: computeRowKey 4-arg — driverRow 键为 _前缀视图列时，通过字段 defaultSource 解析出正确 rowKey")
    void t17_computeRowKey_withFieldsAndBdv() {
        // 外购件字段：料件/要素 都是 INPUT_TEXT，default_source=BASIC_DATA path=$wgj_view._料件
        JsonNode fields = json("["
            + "{\"name\":\"料件\",\"fieldType\":\"INPUT_TEXT\","
            + " \"defaultSource\":{\"type\":\"BASIC_DATA\",\"path\":\"$wgj_view._料件\"}},"
            + "{\"name\":\"要素\",\"fieldType\":\"INPUT_TEXT\","
            + " \"defaultSource\":{\"type\":\"BASIC_DATA\",\"path\":\"$wgj_view._要素\"}},"
            + "{\"name\":\"费用\",\"fieldType\":\"INPUT_NUMBER\"}"
            + "]");
        JsonNode rkf = json("[\"料件\",\"要素\"]");
        // driverRow 键是视图列名（_前缀），字段名"料件"在 driverRow 中不存在
        JsonNode driverRow = json("{\"_料件\":\"料9\",\"_要素\":\"加工费\",\"_费用\":0.05}");
        // basicDataValues 按 BNF key "{$wgj_view._料件}" 存储解析好的值
        JsonNode basicDataValues = json("{\"{$wgj_view._料件}\":\"料9\",\"{$wgj_view._要素}\":\"加工费\"}");

        String key = calc.computeRowKey(rkf, fields, driverRow, basicDataValues);
        assertEquals("料9||加工费", key,
            "通过 fields defaultSource 解析应拼出正确 rowKey，而非空串 '||'");
    }

    @Test
    @DisplayName("T18: computeRowKey 4-arg — 全字段解析为空时返回 null（调用方按行号兜底）")
    void t18_computeRowKey_allEmptyReturnsNull() {
        JsonNode fields = json("["
            + "{\"name\":\"料件\",\"fieldType\":\"INPUT_TEXT\","
            + " \"defaultSource\":{\"type\":\"BASIC_DATA\",\"path\":\"$wgj_view._料件\"}},"
            + "{\"name\":\"要素\",\"fieldType\":\"INPUT_TEXT\","
            + " \"defaultSource\":{\"type\":\"BASIC_DATA\",\"path\":\"$wgj_view._要素\"}}"
            + "]");
        JsonNode rkf = json("[\"料件\",\"要素\"]");
        JsonNode driverRow = json("{}");
        JsonNode basicDataValues = json("{}");

        String key = calc.computeRowKey(rkf, fields, driverRow, basicDataValues);
        assertNull(key, "全部 key 字段解析为空时应返回 null，让调用方按行号兜底");
    }

    private void putDoubles(Map<String, Double> target, JsonNode node) {
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> target.put(e.getKey(), e.getValue().asDouble()));
        }
    }
}
