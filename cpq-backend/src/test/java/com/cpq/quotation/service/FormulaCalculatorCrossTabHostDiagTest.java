package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260911 · B-4（诊断信号 D-a）+ B-1 叠加口径的后端侧单测。
 *
 * <p>为什么不放共享夹具：夹具只对拍<b>数值</b>（两端 harness 都只断言 expected），
 * 诊断文案与「键缺失 vs 键在值为空」的区分是后端内部契约，塞进夹具会给前端 harness
 * 强加它并不消费的字段。数值面的对拍仍由 {@code cross-tab-cases.json} 的
 * {@code repair-260911 *} 用例组负责。
 *
 * <p>AC 对应：AC-10（取不到匹配键 → 可见诊断而非静默 0）、AC-5（真为空不算命中且<b>不</b>报诊断）。
 */
class FormulaCalculatorCrossTabHostDiagTest {

    private final FormulaCalculator calc = new FormulaCalculator();
    private final ObjectMapper om = new ObjectMapper();

    private JsonNode sumToken(String bField) {
        return om.createArrayNode().add(om.createObjectNode()
                .put("type", "cross_tab_ref")
                .put("source", "A")
                .put("sourceLabel", "材质元素")
                .put("target", "元素成本")
                .put("agg", "SUM")
                .set("match", om.createArrayNode().add(
                        om.createObjectNode().put("a", "生产料号").put("b", bField))));
    }

    private static List<Map<String, Object>> aRows() {
        return List.of(
                Map.of("生产料号", "P1", "元素成本", new BigDecimal("100")),
                Map.of("生产料号", "P1", "元素成本", new BigDecimal("200")));
    }

    private FormulaCalculator.RowContext ctxWith(Map<String, Object> hostRow) {
        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.crossTabRows = Map.of("A", aRows());
        ctx.currentRowRaw = new HashMap<>(hostRow);
        ctx.hostRowForMatch = new HashMap<>(hostRow);
        ctx.outDiag = new HashMap<>();
        return ctx;
    }

    /** AC-10：匹配键在宿主行整个键都不存在（例：公式引用了一个不存在的字段名）→ 0 + 可见诊断。 */
    @Test
    void missingHostMatchKey_yieldsZeroAndDiagnostic() {
        FormulaCalculator.RowContext ctx = ctxWith(Map.of("生产料号", "P1"));

        BigDecimal v = calc.evaluateExpression(sumToken("不存在的字段"), ctx);

        assertEquals(0, v.compareTo(BigDecimal.ZERO), "取不到匹配键时数值仍为 0（返回值不变）");
        String diag = ctx.outDiag.get("crossTabError");
        assertTrue(diag != null && !diag.isBlank(), "必须写入 crossTabError 诊断，实际=" + diag);
        assertTrue(diag.contains("材质元素"), "诊断须指明是哪个源页签，实际=" + diag);
        assertTrue(diag.contains("不存在的字段"), "诊断须指明是哪个匹配键，实际=" + diag);
    }

    /** AC-5：键存在但值为空（用户显式清空 / 业务上真为空）→ 0，且<b>不</b>报诊断（那是正常语义）。 */
    @Test
    void blankButPresentHostMatchKey_yieldsZeroWithoutDiagnostic() {
        FormulaCalculator.RowContext ctx = ctxWith(Map.of("生产料号", ""));

        BigDecimal v = calc.evaluateExpression(sumToken("生产料号"), ctx);

        assertEquals(0, v.compareTo(BigDecimal.ZERO), "两侧空不得判等，必须是 0");
        assertFalse(ctx.outDiag.containsKey("crossTabError"),
                "键在、值为空是正常业务语义，不该报诊断，实际=" + ctx.outDiag);
    }

    /** 对照：键取得到 → 正常命中，且不写任何诊断。 */
    @Test
    void resolvableHostMatchKey_hitsAndNoDiagnostic() {
        FormulaCalculator.RowContext ctx = ctxWith(Map.of("生产料号", "P1"));

        BigDecimal v = calc.evaluateExpression(sumToken("生产料号"), ctx);

        assertEquals(0, v.compareTo(new BigDecimal("300")), "100+200=300，实际=" + v);
        assertFalse(ctx.outDiag.containsKey("crossTabError"), "命中时不该有诊断");
    }

    /** {@code outDiag == null}（生产默认）→ 求值路径不得因诊断而抛错，返回值与旧行为一致。 */
    @Test
    void nullDiagBag_isTolerated() {
        FormulaCalculator.RowContext ctx = ctxWith(Map.of("生产料号", "P1"));
        ctx.outDiag = null;

        BigDecimal v = calc.evaluateExpression(sumToken("不存在的字段"), ctx);

        assertEquals(0, v.compareTo(BigDecimal.ZERO));
    }

    // ── B-1 叠加口径：仅键缺失才补 ───────────────────────────────────────────

    private JsonNode fields(String json) {
        try {
            return om.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** BASIC_DATA 字段按字段名补进匹配视图（driver 列名 ≠ 字段名的正题）。 */
    @Test
    void hostRowForMatch_fillsBasicDataByFieldName() {
        JsonNode f = fields("[{\"name\":\"生产料号\",\"field_type\":\"BASIC_DATA\","
                + "\"basic_data_path\":\"$v.production_no\"}]");
        JsonNode driverRow = fields("{\"prod_no\":\"P1\"}");
        JsonNode bdv = fields("{\"{$v.production_no}\":\"P1\"}");
        Map<String, Object> raw = new HashMap<>(Map.of("prod_no", "P1"));

        Map<String, Object> view = calc.buildHostRowForMatch(f, driverRow, bdv, null, raw);

        assertEquals("P1", view.get("生产料号"), "字段名键必须补上");
        assertEquals("P1", view.get("prod_no"), "driver 列名键必须保留");
        assertFalse(raw.containsKey("生产料号"), "不得就地污染 currentRowRaw（A0 否决的方案乙）");
    }

    /**
     * B-1 叠加口径的<b>鉴别性</b>守卫：宿主行已有该键且值与按字段名解析出的值<b>不同</b>时，
     * 底层值必须赢。
     *
     * <p>🚨 这条是「仅键缺失才补」唯一真正锁得住的地方，别把它删了改用 {@code ""} 用例代替 ——
     * 证伪实验（2026-09-11 实跑）：把实现改成
     * {@code buildTreeAggPresenceView} 那句 {@code merged.putAll(byFieldName)}，
     * 只有<b>本用例</b>会红；{@code ""} 显式清空的用例<b>依然是绿的</b>，
     * 因为 {@code resolveRowByFieldName} 的 INPUT 分支本身就会把 {@code ""} 原样落键
     * （「键存在即权威」），两种写法结果相同。
     *
     * <p>实务意义：{@code currentRowRaw} 里的值可能已被 {@code UnitConversion.convertResolvedRow}
     * 就地换算过；整体覆盖会把未换算的原值灌回来，让匹配键与该行其它消费方（{@code b_field} /
     * 渲染）看到的值分叉。
     */
    @Test
    void hostRowForMatch_baseValueWinsOverFieldNameResolution() {
        // 字段名恰好等于 driver 列名（"料号"），但 bdv 路径解析出不同的值
        JsonNode f = fields("[{\"name\":\"料号\",\"field_type\":\"BASIC_DATA\","
                + "\"basic_data_path\":\"$v.part_no\"}]");
        JsonNode driverRow = fields("{\"料号\":\"BASE-X\"}");
        JsonNode bdv = fields("{\"{$v.part_no}\":\"RESOLVED-Y\"}");
        Map<String, Object> raw = new HashMap<>(Map.of("料号", "BASE-X"));

        Map<String, Object> view = calc.buildHostRowForMatch(f, driverRow, bdv, null, raw);

        assertEquals("BASE-X", view.get("料号"),
                "键已存在 → 底层值权威，不得被按字段名解析结果覆盖"
                        + "（putAll 整体覆盖会得到 RESOLVED-Y）");
    }

    /** E-7 / AC-6③：宿主行已有该键（含显式清空的 ""）→ 一律不覆盖。 */
    @Test
    void hostRowForMatch_doesNotOverwriteExplicitBlank() {
        JsonNode f = fields("[{\"name\":\"生产料号\",\"field_type\":\"INPUT_TEXT\","
                + "\"default_source\":{\"type\":\"BNF_PATH\",\"path\":\"$v.production_no\"}}]");
        JsonNode driverRow = fields("{\"prod_no\":\"P1\"}");
        JsonNode bdv = fields("{\"{$v.production_no}\":\"P1\"}");
        JsonNode editValues = fields("{\"生产料号\":\"\"}");
        Map<String, Object> raw = new HashMap<>(Map.of("prod_no", "P1", "生产料号", ""));

        Map<String, Object> view = calc.buildHostRowForMatch(f, driverRow, bdv, editValues, raw);

        assertEquals("", view.get("生产料号"),
                "显式清空必须被尊重（putAll 整体覆盖会把它变回 P1 —— 这正是不能照抄 "
                        + "buildTreeAggPresenceView 的原因）");
    }
}
