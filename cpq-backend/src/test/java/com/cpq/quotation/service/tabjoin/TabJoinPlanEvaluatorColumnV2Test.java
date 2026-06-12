package com.cpq.quotation.service.tabjoin;

import com.cpq.quotation.service.card.CardDataProvider;
import com.cpq.quotation.service.card.CardEffectiveRows;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorColumnV2Test {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();

    private CardDataProvider provider() {
        Map<String, CardEffectiveRows.TabRows> eff = new LinkedHashMap<>();
        eff.put("T投:0", new CardEffectiveRows.TabRows(
            List.of(Map.of("物料编码","M1","金额",new BigDecimal("100")),
                    Map.of("物料编码","M2","金额",new BigDecimal("60"))),
            new BigDecimal("160"), Map.of("金额", new BigDecimal("160"))));
        eff.put("T加:1", new CardEffectiveRows.TabRows(
            List.of(Map.of("物料编码","M1","工时",new BigDecimal("4")),
                    Map.of("物料编码","M3","工时",new BigDecimal("5"))), null));
        eff.put("T回:2", new CardEffectiveRows.TabRows(
            List.of(Map.of("物料编码","M1","工序","电镀","回料金额",new BigDecimal("30")),
                    Map.of("物料编码","M1","工序","酸洗","回料金额",new BigDecimal("9"))),
            new BigDecimal("39")));
        return CardDataProvider.fromEffectiveRows(eff);
    }

    private Map<String,Object> col(String expr) {
        Map<String,Object> c = new LinkedHashMap<>();
        c.put("expression", expr);
        c.put("tabs", List.of(
            Map.of("alias","投料","tabKey","T投:0","rowKeyFields",List.of("物料编码")),
            Map.of("alias","加工","tabKey","T加:1","rowKeyFields",List.of("物料编码")),
            Map.of("alias","回料","tabKey","T回:2","rowKeyFields",List.of("物料编码","工序"))));
        return c;
    }

    @Test void detail_term_plus_tab_total() {
        BigDecimal v = ev.evaluateColumn(col("[投料.金额] * [加工.工时] + [回料(总计)]"), provider());
        assertEquals(0, new BigDecimal("439").compareTo(v), "got "+v);
    }
    @Test void column_total() {
        BigDecimal v = ev.evaluateColumn(col("[投料.金额(总计)]"), provider());
        assertEquals(0, new BigDecimal("160").compareTo(v));
    }

    @Test void pure_totals_no_detail() {
        // 纯页签总计令牌，无明细行参与对齐：投料页签总计160 + 回料页签总计39 = 199
        BigDecimal v = ev.evaluateColumn(col("[投料(总计)] + [回料(总计)]"), provider());
        assertEquals(0, new BigDecimal("199").compareTo(v), "got " + v);
    }

    @Test void column_total_miss_zero() {
        // 加工页签没有 subtotalByColumn 数据 → 应退化为 0
        BigDecimal v = ev.evaluateColumn(col("[加工.工时(总计)]"), provider());
        assertEquals(0, new BigDecimal("0").compareTo(v), "got " + v);
    }

    // ── Task 9: Excel 模型 B 显式拦截 KSUM / 多 source ───────────────────────

    @Test void tabjoin_rejects_ksum_token_not_silent() {
        // projectToHostKey=true → KSUM 降维，Excel 列模型不支持，必须抛而非静默返 0
        Map<String, Object> ksumCol = col("[投料.金额]");
        ksumCol.put("projectToHostKey", true);
        Exception e = assertThrows(IllegalStateException.class,
                () -> ev.evaluateColumn(ksumCol, provider()));
        String msg = e.getMessage();
        assertTrue(msg != null && (msg.contains("KSUM") || msg.contains("Excel 列模型")),
                "期望错误消息含 KSUM 或 Excel 列模型，实际: " + msg);
    }

    @Test void tabjoin_rejects_multi_source_token() {
        // sources.size >= 2 → 多 source 链式 SUM，Excel 列模型不支持，必须抛而非静默返 0
        Map<String, Object> multiSrcCol = col("[投料.金额]");
        multiSrcCol.put("sources", List.of(
                Map.of("source", "cid-A", "match", List.of()),
                Map.of("source", "cid-B", "match", List.of())));
        Exception e = assertThrows(IllegalStateException.class,
                () -> ev.evaluateColumn(multiSrcCol, provider()));
        String msg = e.getMessage();
        assertTrue(msg != null && (msg.contains("source") || msg.contains("Excel 列模型")),
                "期望错误消息含 source 或 Excel 列模型，实际: " + msg);
    }
}
