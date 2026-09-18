package com.cpq.quotation.service.tabjoin;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.service.ExcelViewService;
import com.cpq.quotation.service.card.CardDataProvider;
import com.cpq.quotation.service.card.CardEffectiveRows;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** repair-260916 B-1 / B-2: "(小计)" suffix in Excel TAB_JOIN_FORMULA text (evaluator + save-time validation). */
class TabJoinSubtotalSuffixTest {

    private static final String MSG_EVAL =
        "「(小计)」要写在列名后面，如 [页签.列(小计)]；整页签合计请写 [页签(总计)]";

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();

    // ── B-1 parseTok ──────────────────────────────────────────────────
    @Test void parseTok_subtotalSuffix_isColumnScalar() {
        var t = TabJoinPlanEvaluator.parseTok(" 物料.材料成本(小计) ");
        assertTrue(t.total());
        assertEquals("物料", t.alias());
        assertEquals("材料成本", t.column());
        assertEquals("物料.材料成本(小计)", t.raw());
    }

    @Test void parseTok_totalSuffix_unchanged() {
        var col = TabJoinPlanEvaluator.parseTok("物料.材料成本(总计)");
        assertTrue(col.total());
        assertEquals("材料成本", col.column());
        var tab = TabJoinPlanEvaluator.parseTok("物料(总计)");
        assertTrue(tab.total());
        assertNull(tab.column());
        var det = TabJoinPlanEvaluator.parseTok("物料.材料成本");
        assertFalse(det.total());
    }

    @Test void parseTok_subtotalWithoutColumn_rejectedWithExactMessage() {
        var e1 = assertThrows(IllegalArgumentException.class, () -> TabJoinPlanEvaluator.parseTok("物料(小计)"));
        assertEquals(MSG_EVAL, e1.getMessage());
        var e2 = assertThrows(IllegalArgumentException.class, () -> TabJoinPlanEvaluator.parseTok("物料.(小计)"));
        assertEquals(MSG_EVAL, e2.getMessage());
    }

    private CardDataProvider provider() {
        Map<String, CardEffectiveRows.TabRows> eff = new LinkedHashMap<>();
        eff.put("T:0", new CardEffectiveRows.TabRows(
            List.of(Map.of("k", "a", "m", new BigDecimal("2"), "q", new BigDecimal("3")),
                    Map.of("k", "b", "m", new BigDecimal("5"), "q", new BigDecimal("7"))),
            new BigDecimal("100"), Map.of("m", new BigDecimal("40"))));   // subtotal ≠ row sum on purpose
        return CardDataProvider.fromEffectiveRows(eff);
    }

    private Map<String, Object> col(String expr) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("expression", expr);
        c.put("tabs", List.of(Map.of("alias", "T", "tabKey", "T:0", "rowKeyFields", List.of("k"))));
        return c;
    }

    @Test void evaluateColumn_subtotalSuffix_readsColumnSubtotal_notRowSum() {
        // (小计) → provider.subtotalOfColumn = 40, bare detail → row sum 7
        assertEquals(0, new BigDecimal("40").compareTo(ev.evaluateColumn(col("[T.m(小计)]"), provider())));
        assertEquals(0, new BigDecimal("7").compareTo(ev.evaluateColumn(col("[T.m]"), provider())));
        assertEquals(0, new BigDecimal("40").compareTo(ev.evaluateColumn(col("[T.m(总计)]"), provider())));
    }

    @Test void evaluateColumn_subtotalSuffix_isScalar_inHasBareDetailAndEvalRow() {
        // term "[T.m(小计)] * 2" has no bare detail → evaluated once: 80 (not 80 per row)
        assertEquals(0, new BigDecimal("80").compareTo(ev.evaluateColumn(col("[T.m(小计)] * 2"), provider())));
        // row-level: SUM([T.q] * [T.m(小计)]) = (3+7)*40 = 400
        assertEquals(0, new BigDecimal("400").compareTo(
            ev.evaluateColumn(col("SUM([T.q] * [T.m(小计)])"), provider())));
        // bare detail term multiplied by scalar: ([T.q] * [T.m(小计)]) per row summed = 400
        assertEquals(0, new BigDecimal("400").compareTo(
            ev.evaluateColumn(col("[T.q] * [T.m(小计)]"), provider())));
    }

    @Test void evaluateColumn_subtotalWithoutColumn_throws() {
        var e = assertThrows(IllegalArgumentException.class,
            () -> ev.evaluateColumn(col("[T(小计)]"), provider()));
        assertEquals(MSG_EVAL, e.getMessage());
    }

    @Test void evaluateColumn_missingColumnSubtotal_isZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(ev.evaluateColumn(col("[T.q(小计)]"), provider())));
    }

    // ── B-2 validateTabJoinConfig ─────────────────────────────────────
    private List<Map<String, Object>> parse(String j) throws Exception {
        return new ObjectMapper().readValue(j, new TypeReference<>() {});
    }

    @Test void validate_subtotalSuffix_passes() throws Exception {
        var cols = parse("""
          [{"col_key":"col_1","source_type":"TAB_JOIN_FORMULA","expression":"[物料.材料成本(小计)]",
            "tabs":[{"alias":"物料","tabKey":"x","rowKeyFields":["销售料号","料号"]}]}]""");
        assertDoesNotThrow(() -> ExcelViewService.validateTabJoinConfig(cols));
    }

    @Test void validate_subtotalWithoutColumn_400_exactMessage() throws Exception {
        var cols = parse("""
          [{"col_key":"col_9","source_type":"TAB_JOIN_FORMULA","expression":"[物料(小计)]",
            "tabs":[{"alias":"物料","tabKey":"x","rowKeyFields":["销售料号","料号"]}]}]""");
        var e = assertThrows(BusinessException.class, () -> ExcelViewService.validateTabJoinConfig(cols));
        assertEquals(400, e.getCode());
        assertEquals("页签连表公式列 col_9 的「(小计)」要写在列名后面，如 [页签.列(小计)]", e.getMessage());
    }

    @Test void validate_subtotalSuffix_notInRowKeyClassCheck() throws Exception {
        // AC-14f third body: (小计) of 物料 + bare detail of a tab with different row keys → OK
        var cols = parse("""
          [{"col_key":"col_2","source_type":"TAB_JOIN_FORMULA","expression":"[物料.材料成本(小计)] + [其他页签.X]",
            "tabs":[{"alias":"物料","tabKey":"x","rowKeyFields":["销售料号","料号"]},
                    {"alias":"其他页签","tabKey":"y","rowKeyFields":["工序"]}]}]""");
        assertDoesNotThrow(() -> ExcelViewService.validateTabJoinConfig(cols));
    }

    @Test void validate_subtotalSuffix_stillRequiresDeclaredTab() throws Exception {
        var cols = parse("""
          [{"col_key":"col_3","source_type":"TAB_JOIN_FORMULA","expression":"[未知.材料成本(小计)]",
            "tabs":[{"alias":"物料","tabKey":"x","rowKeyFields":["销售料号"]}]}]""");
        var e = assertThrows(BusinessException.class, () -> ExcelViewService.validateTabJoinConfig(cols));
        assertTrue(e.getMessage().contains("引用了未声明的页签: 未知"), e.getMessage());
    }

    @Test void validate_bareDetailCrossRowKeyClass_stillRejected() throws Exception {
        var cols = parse("""
          [{"col_key":"col_4","source_type":"TAB_JOIN_FORMULA","expression":"[物料.材料成本] + [其他页签.X]",
            "tabs":[{"alias":"物料","tabKey":"x","rowKeyFields":["销售料号","料号"]},
                    {"alias":"其他页签","tabKey":"y","rowKeyFields":["工序"]}]}]""");
        assertThrows(BusinessException.class, () -> ExcelViewService.validateTabJoinConfig(cols));
    }
}
