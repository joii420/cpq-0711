package com.cpq.quotation.service.tabjoin;

import com.cpq.quotation.service.card.CardDataProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯 JUnit 单测（无 @QuarkusTest / DB）：task-0803 Task5⑥ —— Excel 列模型（TAB_JOIN_FORMULA /
 * {@link TabJoinPlanEvaluator#evaluateColumn}）遇到 BOM 父子取值（tree_ref/tree_attr）时必须
 * 显式抛 {@link IllegalStateException}，不能静默返 0（静默少算比报错更危险）。
 *
 * <p>与既有 {@code TabJoinPlanEvaluatorColumnV2Test} 的 KSUM/多 source 拦截用例同款风格。
 */
@DisplayName("TabJoinPlanEvaluator — Excel 列模型显式拒绝 tree_ref/tree_attr（闸⑥）")
class TabJoinPlanEvaluatorTreeTokenTest {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();

    /** 空 provider：异常应在触达 provider 之前就抛出（与 KSUM/多 source 检查同款前置拦截顺序）。 */
    private CardDataProvider emptyProvider() {
        return new CardDataProvider(List.of());
    }

    private Map<String, Object> col(String expr) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("expression", expr);
        c.put("tabs", List.of());
        return c;
    }

    @Test
    @DisplayName("col.type=tree_ref → 抛 IllegalStateException（不是 500 吞掉，不是静默返 0）")
    void tabjoin_rejects_tree_ref_column_type() {
        Map<String, Object> treeRefCol = col("[投料.金额]");
        treeRefCol.put("type", "tree_ref");
        Exception e = assertThrows(IllegalStateException.class,
                () -> ev.evaluateColumn(treeRefCol, emptyProvider()));
        String msg = e.getMessage();
        assertTrue(msg != null && msg.contains("tree_ref") && msg.contains("Excel 列模型"),
                "期望错误消息含 tree_ref 与 Excel 列模型，实际: " + msg);
    }

    @Test
    @DisplayName("col.type=tree_attr → 抛 IllegalStateException")
    void tabjoin_rejects_tree_attr_column_type() {
        Map<String, Object> treeAttrCol = col("[投料.金额]");
        treeAttrCol.put("type", "tree_attr");
        Exception e = assertThrows(IllegalStateException.class,
                () -> ev.evaluateColumn(treeAttrCol, emptyProvider()));
        String msg = e.getMessage();
        assertTrue(msg != null && msg.contains("tree_attr") && msg.contains("Excel 列模型"),
                "期望错误消息含 tree_attr 与 Excel 列模型，实际: " + msg);
    }

    @Test
    @DisplayName("对照: 无 type 标记（普通 TAB_JOIN_FORMULA 列）不受影响 —— 空 tabs 求值得 0，不抛")
    void plainColumn_withoutTreeType_notAffected() {
        Map<String, Object> plain = col("[投料.金额]");
        assertDoesNotThrow(() -> ev.evaluateColumn(plain, emptyProvider()));
    }
}
