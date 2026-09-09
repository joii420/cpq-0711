package com.cpq.repair260908;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-16</b>（阴性 · 结构）—— 问题说明 ⑥ 节原文：
 *
 * <blockquote>
 * 前置：3 个 {@code COST_BASIC} 的 {@code builder_*} 视图
 * （{@code builder_a515014e6ed3} / {@code builder_32ab8212df6c} / {@code builder_9291b050b6a9}）。
 * 操作：重编译后读 {@code sql_template}。<br>
 * 断言（<b>结构断言</b>，🚫 不许用行数验，实测当前 0 行差异）：
 * {@code NARROW} 桥的子查询 {@code WHERE} 含 {@code <桥别名>.customer_no = :customerCode}；
 * 同时<b>外层锚点</b>（{@code ds_cost_basic_*}，无该列）<b>不得</b>出现该谓词。
 * </blockquote>
 *
 * <h3>🚨 为什么必须是结构断言 —— 拿数据验会得到一个恒绿判据</h3>
 * 问题说明 §B-1b 实测：{@code ds_quote_material} 里 16 个料号跨客户，但
 * {@code count(distinct production_no) > 1} 的组数 = <b>0</b>；且 {@code x IN (SELECT …)} 是
 * <b>集合成员判定</b>，子查询里多出重复值不会让外层翻倍。
 * ⇒ 加不加这条谓词，<b>行数一个都不差</b>。用行数验它 = 改前改后都一样 = <b>零证据，不是弱证据</b>。
 * <p>本类因此<b>一行数据都不查</b>，只断言产物的语法结构。
 *
 * <h3>🚨 作用域：只管「桥子查询」，与 AC-2b 严格分工</h3>
 * 并发会话用 {@code FROM\s+(ds_quote_\w+)} 抽主表，这 3 条视图匹到的正是<b>桥子查询里</b>的
 * {@code ds_quote_material dqm}，外层 {@code FROM v_ds_cost_basic_*} 反而没匹上 ——
 * <b>用错误判据碰巧碰到了正确位置</b>。
 * 本类用 {@link SqlShape#subBlocks} 显式取<b>子查询块</b>，
 * {@link Ac2bAnchorCustomerPredicateTest} 用 {@link SqlShape#outerBlocks} 取<b>外层块</b>，
 * 两者不共用「主表」这个概念，所以不可能再次歪打正着。
 *
 * <h3>本片写入面 = 空</h3>
 */
@QuarkusTest
class Ac16CostBasicBridgeTest extends S1CompileTestBase {

    /** {@code NARROW} 桥的签名：子查询自己的 WHERE <b>全文</b>里消费 {@code :total_material_no}。 */
    private static boolean isBridge(SqlShape.Block b) {
        return b.from() != null && b.whereFull() != null && b.whereFull().contains(":total_material_no");
    }

    // ═══════════════ AC-16① 桥子查询必须带客户谓词 ═══════════════

    @Test
    @DisplayName("AC-16①: 3 个 COST_BASIC 视图的每一处 NARROW 桥子查询，WHERE 都必须含 <桥别名>.customer_no = :customerCode")
    void ac16a_bridgeSubqueryCarriesCustomerPredicate() {
        Map<String, ViewRow> byName = new LinkedHashMap<>();
        builderViews().forEach(v -> byName.put(v.viewName(), v));

        List<String> err = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        int bridgesChecked = 0;

        for (String name : AC16_VIEWS) {
            ViewRow v = byName.get(name);
            assertNotNull(v, notReady("AC-16", "AC 原文点名的视图 " + name + " 在库里不存在", "取数配置器"));
            String sql = recompile(v);

            List<SqlShape.Block> bridges = SqlShape.subBlocks(sql).stream()
                    .filter(Ac16CostBasicBridgeTest::isBridge)
                    .filter(b -> hasCustomerNo(b.from().table()))   // 判据 = 桥 target 物理表含 customer_no
                    .toList();

            // 🚨 空跑防护：认不出桥 ⇒ 下面的 for 循环 0 次 ⇒ 断言从未执行，却报绿
            assertFalse(bridges.isEmpty(), notReady("AC-16①",
                    name + " 里没认出任何「target 表含 customer_no」的 NARROW 桥子查询。"
                            + "\n  2026-09-08 实测：builder_a515014e6ed3 / builder_9291b050b6a9 各 1 处，"
                            + "builder_32ab8212df6c 有 **2 处**（UNION 两分支，别名 dqm / dqm2）。"
                            + "\n  🚫 认不出就是断言空跑，绝不能当通过。产物=\n" + sql, null));

            for (SqlShape.Block b : bridges) {
                bridgesChecked++;
                String alias = b.from().alias();
                String where = b.whereTop() == null ? "" : b.whereTop();
                report.put(name + " / 桥 " + b.from().table() + " " + alias, where);
                if (!SqlShape.hasCustomerCodePredicate(where, alias)) {
                    err.add("\n  " + name + ": 桥子查询 `FROM " + b.from().table() + " " + alias
                            + "` 的 WHERE 缺 `" + alias + ".customer_no = :customerCode`（B-1b）。"
                            + "\n    实得 WHERE(本层)=" + where
                            + "\n    🔑 该子查询把销售料号解析成生产料号；内层不过滤客户 ⇒ "
                            + "缺陷① 在核价侧以**桥接形态**存在（D-2b）。"
                            + "\n    ⚠️ 这条**不会**表现为行数差异（IN 是集合成员判定，且实测 0 行影响），"
                            + "所以只有结构断言能抓住它。");
                }
            }

            // 反向：桥别名不许出现在外层 WHERE 里（那说明谓词加错了层）
            for (SqlShape.Block ob : SqlShape.outerBlocks(sql)) {
                for (SqlShape.Block b : bridges) {
                    if (SqlShape.hasCustomerCodePredicate(ob.whereTop(), b.from().alias())) {
                        err.add("\n  " + name + ": 桥别名 `" + b.from().alias()
                                + "` 的客户谓词出现在**外层** WHERE 上 —— 加错层了，外层看不见这个别名，"
                                + "真实执行会报 `missing FROM-clause entry`。外层 WHERE=" + ob.whereTop());
                    }
                }
            }
        }

        dump("AC-16① 桥子查询 WHERE 实录", report);
        assertTrue(bridgesChecked >= 4, notReady("AC-16①",
                "只检查到 " + bridgesChecked + " 处桥，少于实测的 4 处（1 + 2 + 1）。"
                        + "\n  🚨 builder_32ab8212df6c 的 UNION 根分支有第 2 处桥（别名 dqm2）；"
                        + "只验第一处会让第二处的漏加静默通过。", null));
        System.out.println("[AC-16①] 共检查 " + bridgesChecked + " 处桥子查询");
        assertEquals("", String.join("", err), "AC-16① 不符：" + String.join("", err));
    }

    // ═══════════════ AC-16② 外层锚点不得出现该谓词 ═══════════════

    @Test
    @DisplayName("AC-16②: 3 个 COST_BASIC 视图的外层锚点（无 customer_no 列）不得出现客户谓词 —— 否则执行期报 column does not exist")
    void ac16b_outerAnchorMustNotCarryPredicate() {
        Map<String, ViewRow> byName = new LinkedHashMap<>();
        builderViews().forEach(v -> byName.put(v.viewName(), v));

        List<String> err = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        int outerChecked = 0;

        for (String name : AC16_VIEWS) {
            ViewRow v = byName.get(name);
            assertNotNull(v, notReady("AC-16②", "视图 " + name + " 不存在", "取数配置器"));

            // 前提自证：这三个视图的锚点确实没有 customer_no 列（否则本条的前提就不成立）
            assertFalse(v.anchorHasCustomerNo(),
                    "AC-16② 前提不成立：" + name + " 的锚点 " + v.anchorTable()
                            + " 竟然含 customer_no 列。⚠️ 那 AC-16② 的『不得出现』就不再正确 —— 请报主线，"
                            + "🚫 不要自行放宽判据。");

            for (SqlShape.Block b : SqlShape.outerBlocks(recompile(v))) {
                if (b.from() == null) {
                    continue;
                }
                outerChecked++;
                String alias = b.from().alias();
                String where = b.whereTop() == null ? "" : b.whereTop();
                report.put(name + " / 外层 " + b.from().table() + " " + alias, where);
                if (SqlShape.mentionsCustomerNo(where, alias)) {
                    err.add("\n  " + name + ": 外层锚点 `" + b.from().table() + " " + alias
                            + "` 的 WHERE 出现了 `" + alias + ".customer_no`，但该表**没有这一列**"
                            + "（information_schema 实测：" + columnsOf(b.from().table()).size()
                            + " 列，无 customer_no）。"
                            + "\n    🚨 真实执行会报 `column \"customer_no\" does not exist` ——"
                            + "backtask.md 硬约束 1：判据按**列存在性**，🚫 不按方言、不按视图数量。"
                            + "\n    实得 WHERE(本层)=" + where);
                }
            }
        }

        dump("AC-16② 外层锚点 WHERE 实录", report);
        assertTrue(outerChecked >= 4, notReady("AC-16②",
                "只检查到 " + outerChecked + " 个外层块，少于实测的 4 个"
                        + "（a515014e6ed3:1 + 32ab8212df6c:2(UNION) + 9291b050b6a9:1）—— 断言覆盖不足", null));
        System.out.println("[AC-16②] 共检查 " + outerChecked + " 个外层块");
        assertEquals("", String.join("", err), "AC-16② 不符：" + String.join("", err));
    }

    // ═══════════════ 观察项：非桥子查询（NOT EXISTS）—— 报主线，本轮不判红 ═══════════════

    @Test
    @DisplayName("AC-16 观察🔎: 根分支的 NOT EXISTS 子查询若落在含 customer_no 的表上，B-1b 判据是否覆盖它？（只记录，不判红）")
    void ac16_observe_nonBridgeSubqueriesOnCustomerTables() {
        Map<String, Object> obs = new LinkedHashMap<>();
        for (ViewRow v : builderViews()) {
            for (SqlShape.Block b : SqlShape.subBlocks(recompile(v))) {
                if (b.from() == null || isBridge(b)) {
                    continue;
                }
                if (hasCustomerNo(b.from().table())) {
                    obs.put(v.viewName() + " / 非桥子查询 " + b.from().table() + " " + b.from().alias(),
                            "WHERE=" + b.whereTop()
                                    + " ｜ 含客户谓词=" + SqlShape.hasCustomerCodePredicate(
                                    b.whereTop(), b.from().alias()));
                }
            }
        }
        dump("AC-16 观察 · 落在含 customer_no 表上的非桥子查询", obs);
        System.out.println("""
                🔎 报主线（不判红，因为 AC 原文没约束它）：
                   QUOTE 侧 3 个 BOM 视图的根分支里有 `NOT EXISTS (SELECT 1 FROM ds_quote_material_bom … )`。
                   ds_quote_material_bom **含 customer_no**。该子查询判断「本料号有没有父边」——
                   若不带客户过滤，某成品在**别的客户**下有 BOM 边，就会被判成「非树根」而从根分支里消失。
                   B-1b 的判据写的是「桥的 target 物理表含 customer_no」，NOT EXISTS 不是桥 ⇒ 落在判据之外。
                   👉 请主线裁决：这属于 E-1 的覆盖范围，还是本期明确不做。🚫 我不自行扩 AC。
                """);
        assertTrue(true);   // 观察项，恒绿；结论走报告，不走断言
    }

    // ═══════════════ 证伪设计（test.md §4）═══════════════
    //  · 注释掉 B-1b 在 NARROW 桥子查询里追加的谓词
    //      → AC-16① 报红（4 处桥全部缺谓词），AC-16② 仍绿（分工正确）。
    //      ⚠️ 这正是 test.md §4 点名的那条：AC-16 是结构断言，数据上 0 行差异，**只有结构断言能红**。
    //  · 把 B-1b 的谓词错加到外层锚点（模拟并发线「按 FROM\s+(ds_quote_\w+) 抽主表」的做法）
    //      → AC-16② 报红（外层 ds_cost_basic_material 没有 customer_no 列）。
    //  · 只给 builder_32ab8212df6c 的**第一处**桥加谓词、漏掉 UNION 根分支的 dqm2
    //      → AC-16① 报红（bridgesChecked=4，其中 1 处缺）—— 这条专门防「只验第一处」的漏网。
}
