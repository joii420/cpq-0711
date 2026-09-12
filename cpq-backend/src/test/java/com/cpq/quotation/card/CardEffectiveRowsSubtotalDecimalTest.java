package com.cpq.quotation.card;

import com.cpq.quotation.service.card.CardDataProvider;
import com.cpq.quotation.service.card.CardEffectiveRows;
import com.cpq.quotation.service.tabjoin.TabJoinPlanEvaluator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260911 <b>第二根因 R2</b> 的守护测试：卡片值快照里的 {@code tab.subtotal} /
 * {@code tab.subtotalByColumn.*} 是 <b>JSON 字符串</b>（写侧 {@code CardSnapshotService} 自
 * {@code fd83cac1}（task-0810 十进制精度契约）起用 {@code PrecisionPolicy.toPlainDecimalString}
 * 落盘），而读侧 {@code CardEffectiveRows.parse} 原本用 {@code JsonNode.decimalValue()} ——
 * Jackson 对 {@code TextNode} <b>静默返回 {@code BigDecimal.ZERO}</b>，于是核价 Excel 的
 * {@code [页签(总计)]} 引用恒 0。
 *
 * <p>⚠️ R1（双键登记，见 {@code EffectiveRowsKeyContractTest}）与 R2 是 <b>互为必要条件</b> 的
 * 两个根因：只修任何一个，Excel 四列都仍然是 0。{@link #endToEnd_bareTabKey_plus_stringSubtotal_yieldsRealValue()}
 * 同时覆盖两者，是最接近 AC-1 的单测断言。
 */
class CardEffectiveRowsSubtotalDecimalTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String CID = "1e41ecb6-b663-42a1-b8da-122961699d26";

    private static JsonNode json(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Map<String, CardEffectiveRows.TabRows> parse(String tabJson) {
        return CardEffectiveRows.parse(
            json("{\"tabs\":[" + tabJson + "]}"),
            json("[{\"componentId\":\"" + CID + "\",\"sortOrder\":1,\"fields\":[]}]"),
            c -> null);
    }

    @Test
    @DisplayName("subtotal 是十进制字符串（现网落盘形态）→ 必须读出真值，不得静默变 0")
    void stringSubtotal_isRead() {
        var tr = parse("{\"componentId\":\"" + CID + "\",\"subtotal\":\"489985\","
            + "\"resolvedRows\":[{\"金额\":489985}]}").get(CID);

        assertNotNull(tr.subtotal, "字符串 subtotal 不应读成 null");
        assertEquals(0, new BigDecimal("489985").compareTo(tr.subtotal),
            "JsonNode.decimalValue() 对 TextNode 静默返回 ZERO —— 读侧必须容忍字符串（R2）");
    }

    @Test
    @DisplayName("带小数的字符串 subtotal 精度不丢（5438667.5 / 5438673.3）")
    void stringSubtotal_keepsScale() {
        assertEquals(0, new BigDecimal("5438667.5").compareTo(
            parse("{\"componentId\":\"" + CID + "\",\"subtotal\":\"5438667.5\"}").get(CID).subtotal));
        assertEquals(0, new BigDecimal("5438673.3").compareTo(
            parse("{\"componentId\":\"" + CID + "\",\"subtotal\":\"5438673.3\"}").get(CID).subtotal));
    }

    @Test
    @DisplayName("subtotalByColumn 的列值同样是字符串 → 每一列都要读出真值（[页签.列名(总计)] 引用）")
    void stringSubtotalByColumn_isRead() {
        var tr = parse("{\"componentId\":\"" + CID + "\",\"subtotal\":\"489985\","
            + "\"subtotalByColumn\":{\"元素成本\":\"489985\",\"损耗\":\"0.138114345655\"}}").get(CID);

        assertEquals(0, new BigDecimal("489985").compareTo(tr.subtotalByColumn.get("元素成本")));
        assertEquals(0, new BigDecimal("0.138114345655").compareTo(tr.subtotalByColumn.get("损耗")),
            "12 位小数不得被截断");
    }

    @Test
    @DisplayName("数字型 subtotal 行为不变（向后兼容 task-0810 之前的老快照）")
    void numericSubtotal_unchanged() {
        var tr = parse("{\"componentId\":\"" + CID + "\",\"subtotal\":489985,"
            + "\"subtotalByColumn\":{\"元素成本\":489985}}").get(CID);

        assertEquals(0, new BigDecimal("489985").compareTo(tr.subtotal));
        assertEquals(0, new BigDecimal("489985").compareTo(tr.subtotalByColumn.get("元素成本")));
    }

    @Test
    @DisplayName("缺失 / JSON null 的 subtotal 仍是 null（不是 0）—— 不许改动既有语义")
    void missingSubtotal_staysNull() {
        assertNull(parse("{\"componentId\":\"" + CID + "\"}").get(CID).subtotal,
            "缺失 subtotal 必须保持 null（下游 TabJoinPlanEvaluator 自己兜 ZERO）");
        assertNull(parse("{\"componentId\":\"" + CID + "\",\"subtotal\":null}").get(CID).subtotal,
            "JSON null 必须保持 null");
        assertTrue(parse("{\"componentId\":\"" + CID + "\",\"subtotalByColumn\":{\"元素成本\":null}}")
                .get(CID).subtotalByColumn.isEmpty(),
            "null 列值仍应被跳过，不落进 subtotalByColumn");
    }

    @Test
    @DisplayName("空串 / 非法数字 → ZERO 且不抛（一个坏页签不能炸掉整张卡片的 Excel 值）")
    void blankOrIllegal_yieldsZeroAndNeverThrows() {
        assertEquals(0, BigDecimal.ZERO.compareTo(
            parse("{\"componentId\":\"" + CID + "\",\"subtotal\":\"\"}").get(CID).subtotal));
        assertEquals(0, BigDecimal.ZERO.compareTo(
            parse("{\"componentId\":\"" + CID + "\",\"subtotal\":\"   \"}").get(CID).subtotal));
        assertEquals(0, BigDecimal.ZERO.compareTo(
            parse("{\"componentId\":\"" + CID + "\",\"subtotal\":\"abc\"}").get(CID).subtotal));
        assertEquals(0, BigDecimal.ZERO.compareTo(
            parse("{\"componentId\":\"" + CID + "\",\"subtotal\":true}").get(CID).subtotal));
        assertEquals(0, BigDecimal.ZERO.compareTo(
            parse("{\"componentId\":\"" + CID + "\",\"subtotalByColumn\":{\"元素成本\":\"n/a\"}}")
                .get(CID).subtotalByColumn.get("元素成本")));
    }

    // ---- R1 + R2 合体：最接近 AC-1 的单测断言 ----------------------------------------

    @Test
    @DisplayName("裸 tabKey + 字符串 subtotal → TAB_JOIN 列 [页签(总计)] 求出真值（R1+R2 缺一即 0）")
    void endToEnd_bareTabKey_plus_stringSubtotal_yieldsRealValue() {
        // 与现网 `核价通用1` 的 col_1 配置同形：tabKey 是裸 componentId、表达式是 [别名(总计)]
        Map<String, Object> col = Map.of(
            "col_key", "col_1",
            "source_type", "TAB_JOIN_FORMULA",
            "expression", "[材质元素(总计)]",
            "tabs", List.of(Map.of("alias", "材质元素", "tabKey", CID, "rowKeyFields", List.of())));

        Map<String, CardEffectiveRows.TabRows> eff =
            parse("{\"componentId\":\"" + CID + "\",\"subtotal\":\"489985\","
                + "\"resolvedRows\":[{\"金额\":489985}]}");
        CardDataProvider provider = CardDataProvider.fromEffectiveRows(eff);

        BigDecimal v = new TabJoinPlanEvaluator().evaluateColumn(col, provider);

        assertEquals(0, new BigDecimal("489985").compareTo(v),
            "R1（裸键登记）与 R2（字符串 subtotal 可读）任一缺失，这里都会得到 0");
    }
}
