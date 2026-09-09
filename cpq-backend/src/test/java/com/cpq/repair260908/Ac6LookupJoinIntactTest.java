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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-6</b>（阳性 · 反向回归）—— 问题说明 ⑥ 节原文：
 *
 * <blockquote>
 * 前置：{@code task-260908} 新增的 46 条 LOOKUP 边。操作：重编译后比对 {@code LEFT JOIN} 子句。<br>
 * 断言：仍是 {@code LEFT JOIN}，<b>{@code ON} 子句逐字不变</b>；
 * {@code WHERE} 里<b>不得</b>新增针对这些 JOIN 目标别名的 {@code customer_no} 谓词。
 * </blockquote>
 *
 * <h3>为什么这条是本次最容易被漏掉的</h3>
 * B-1 的判据是「物理表含 {@code customer_no} 列 ⇒ 加谓词」。
 * {@code ds_quote_material} <b>含</b>该列，而它在多个费用类视图里是 <b>JOIN 目标</b>（不是 FROM 锚点）。
 * 一旦实现把判据施加到 JOIN 目标上，{@code LEFT JOIN} 就会被 {@code WHERE} 里的谓词
 * <b>隐式收成 INNER</b>（NULL 侧行被 WHERE 滤掉），<b>静默丢行</b>、不报错。
 * ⇒ 这条 AC 守的是一个<b>无声故障</b>，判据必须打在 JOIN 目标别名上，而不是「有没有 customer_no 字样」。
 *
 * <h3>🚫 不断言「46 条」</h3>
 * 2026-09-08 实测 {@code semantic_edge} 的 LOOKUP 边已是 <b>47</b> 条（并发线在动语义图）。
 * 共库纪律：🚫 不做全局计数断言。本类断言的是<b>产物里每一条 JOIN 的形态</b>，
 * 边数变化只会让比对多/少一条，并在失败信息里说明是基线过期而不是回归。
 *
 * <h3>⚠️ JOIN 顺序按<b>多重集</b>比，不按<b>序列</b>比</h3>
 * 边的遍历顺序来自 {@code semantic_edge} 的 PG 堆序（同 AC-4 的字段错位机制），
 * 并发 UPDATE 会让 JOIN 换位置。AC-6 要守的是「还是 LEFT / ON 逐字不变」，
 * <b>不是</b>「JOIN 的先后次序不变」。按序列比会产生一种<b>红得像回归的假红</b>。
 * ⇒ 排序后比多重集；顺序差异只打印为 INFO。
 */
@QuarkusTest
class Ac6LookupJoinIntactTest extends S1CompileTestBase {

    // ═══════════════ AC-6① JOIN 子句逐字不变 ═══════════════

    @Test
    @DisplayName("AC-6①: 重编译后每个视图的 JOIN 集合（类型 + 表 + 别名 + ON 原文）与库内基线逐字一致")
    void ac6a_joinClausesUnchanged() {
        String fp0 = graphFingerprint();
        List<ViewRow> all = builderViews();
        assertFalse(all.isEmpty(), notReady("AC-6", "库里一个 builder_* 视图都没有", "取数配置器"));

        List<String> err = new ArrayList<>();
        List<String> orderInfo = new ArrayList<>();
        List<String> outOfScope = new ArrayList<>();
        int totalJoins = 0;
        int viewsWithJoin = 0;

        for (ViewRow v : all) {
            String baseline = v.storedSql();
            if (baseline == null || baseline.isBlank()) {
                err.add("\n  " + v.viewName() + ": 库内 sql_template 为空，没有基线可比");
                continue;
            }
            // 🚨 基线新鲜度：库内模板若已是「改动后」的，比出来必然全绿 = 假绿
            if (!baselineHasOnlyJoinCustomerCode(baseline)) {
                String why = v.viewName() + "（" + v.dialect() + "/" + v.tabType() + "）"
                        + "库内 sql_template 的 **WHERE** 里已含 :customerCode ⇒ 它是**改动后**编译出来的，"
                        + "没有『改动前』基线可比。";
                if (HANDOFF_28.contains(v.viewName())) {
                    // 在册视图变成这样 ⇒ B-6 已经跑过，本条 AC 的比对前提没了，必须硬失败
                    err.add("\n  🔴 " + why + "\n     它在交接清单 28 个之内 ⇒ B-6 已执行，"
                            + "AC-6 的『改动前 vs 改动后』前提不再成立，本条判『未验证』。"
                            + "\n     🚫 拿改动后比改动后必然全绿，那是假绿不是通过。");
                } else {
                    // 🚫 共库纪律：并发线新建的视图天生就是改动后产物，把它算进来会红得像业务回归
                    outOfScope.add(v.viewName() + "(" + v.dialect() + "/" + v.tabType()
                            + "，created 于我方基线快照之后)");
                }
                continue;
            }

            List<String> before = new ArrayList<>(SqlShape.joinFingerprint(baseline));
            List<String> after = new ArrayList<>(SqlShape.joinFingerprint(recompile(v)));
            totalJoins += before.size();
            if (!before.isEmpty()) {
                viewsWithJoin++;
            }
            if (!before.equals(after)) {
                orderInfo.add(v.viewName());
            }
            List<String> b = new ArrayList<>(before);
            List<String> a = new ArrayList<>(after);
            b.sort(String::compareTo);
            a.sort(String::compareTo);
            if (!b.equals(a)) {
                err.add("\n  " + v.viewName() + ": JOIN 子句变了。"
                        + "\n    改动前=" + b + "\n    改动后=" + a
                        + "\n    🔑 若差异是 `LEFT JOIN` → `INNER JOIN`，那正是 AC-6 要拦的静默丢行；"
                        + "若差异是 ON 子句被改，说明 ensureLeftJoin() 被动过（backtask.md 硬约束 2 明令不许）。");
            }
        }

        // 🚨 空跑防护：必须真的比对过 JOIN，否则「没有差异」只是「没有 JOIN 可比」
        assertTrue(viewsWithJoin > 0, notReady("AC-6",
                "扫到的视图里一条 JOIN 都没有 —— AC-6 整条空跑。"
                        + "\n  2026-09-08 实测：至少 builder_a71947b68d50 / builder_9cc11850f425 / "
                        + "builder_196aadeeb89f 各有 1 条 LEFT JOIN。", null));
        System.out.println("[AC-6①] 比对了 " + viewsWithJoin + " 个含 JOIN 的视图，共 " + totalJoins + " 条 JOIN");
        if (!outOfScope.isEmpty()) {
            System.out.println("[AC-6① INFO] 以下视图**不在 AC-6 作用域**：它们由并发线在我方基线快照之后新建，"
                    + "库内模板本身就是改动后产物，没有『改动前』可比 ⇒ 排除出比对，🚫 不判红。"
                    + "\n              " + outOfScope
                    + "\n              ✅ 顺带的正向证据：新视图存盘时锚点上**已带**客户谓词 ⇒ "
                    + "B-1 在 save 路径上也生效了，不只在 compile 路径。"
                    + "\n              （AC-2b / AC-16 / AC-17(b) 是无基线的结构断言，仍然覆盖它们。）");
        }
        if (!orderInfo.isEmpty()) {
            System.out.println("[AC-6① INFO] 以下视图 JOIN **顺序**变了但集合一致（多半是 semantic_edge 堆序漂移，"
                    + "🚫 不判红）：" + orderInfo);
        }
        assertGraphStable("AC-6①", fp0);
        assertEquals("", String.join("", err), "AC-6① JOIN 子句发生变化：" + String.join("", err));
    }

    // ═══════════════ AC-6② WHERE 不得针对 JOIN 目标别名新增客户谓词 ═══════════════

    @Test
    @DisplayName("AC-6②: WHERE 里不得出现针对任何 JOIN 目标别名的 customer_no 谓词 —— 那会把 LEFT JOIN 隐式收成 INNER")
    void ac6b_noCustomerPredicateOnJoinAliases() {
        List<ViewRow> all = builderViews();
        List<String> err = new ArrayList<>();
        Map<String, Object> observed = new LinkedHashMap<>();
        int checkedAliases = 0;
        int aliasesOnCustomerTables = 0;

        for (ViewRow v : all) {
            String sql = recompile(v);
            for (SqlShape.Block b : SqlShape.blocks(sql)) {
                String where = b.whereTop() == null ? "" : b.whereTop();
                String fromAlias = b.from() == null ? null : b.from().alias();
                for (SqlShape.Rel j : b.joins()) {
                    if (j.alias() == null || j.alias().equals(fromAlias)) {
                        continue;
                    }
                    checkedAliases++;
                    if (hasCustomerNo(j.table())) {
                        aliasesOnCustomerTables++;
                        observed.put(v.viewName() + " / JOIN " + j.table() + " " + j.alias(),
                                "该表含 customer_no ⇒ 是最可能被 B-1 误伤的对象");
                    }
                    if (SqlShape.hasCustomerCodePredicate(where, j.alias())) {
                        err.add("\n  " + v.viewName() + ": WHERE 里出现了针对 **JOIN 目标别名** `" + j.alias()
                                + "`（" + j.joinType() + " " + j.table() + "）的客户谓词。"
                                + "\n    🚨 " + j.joinType() + " 的 NULL 侧行会被这条 WHERE 滤掉 ⇒ "
                                + "等价于收成 INNER JOIN，**静默丢行、不报错**。"
                                + "\n    backtask.md 硬约束 2：🚫 不许碰 ensureLeftJoin()；客户维度形态③走列对列连接键。"
                                + "\n    实得 WHERE(本层)=" + where);
                    }
                    // ON 上的 :customerCode 是**允许且既有**的（形态③），此处只记录不判红
                    if (j.onClause() != null && j.onClause().contains(":customerCode")) {
                        observed.put(v.viewName() + " / ON(" + j.alias() + ")",
                                "ON 上带 :customerCode —— 既有形态，合法，🚫 不得被误读成『主表已加谓词』");
                    }
                }
            }
        }

        dump("AC-6② JOIN 目标观察", observed);
        assertTrue(checkedAliases > 0, notReady("AC-6②",
                "一个 JOIN 别名都没检查到 —— 反向断言空跑", null));
        assertTrue(aliasesOnCustomerTables > 0, notReady("AC-6②",
                "没有任何 JOIN 目标落在含 customer_no 的表上 ⇒ 本条的鉴别力为 0（恒绿）。"
                        + "\n  2026-09-08 实测：builder_a71947b68d50 JOIN ds_quote_customer_part、"
                        + "builder_9cc11850f425 JOIN ds_quote_material，两者都含 customer_no。"
                        + "\n  🚫 恒绿判据不算通过。", null));
        System.out.println("[AC-6②] 检查了 " + checkedAliases + " 个 JOIN 别名，其中 "
                + aliasesOnCustomerTables + " 个落在含 customer_no 的表上（真正的风险面）");
        assertEquals("", String.join("", err), "AC-6② 不符：" + String.join("", err));
    }

    // ═══════════════ 证伪设计（test.md §4）═══════════════
    //  · 把 B-1 的谓词从「只发锚点」放宽到「所有含 customer_no 的关系」（含 JOIN 目标）：
    //      → AC-6② 立刻报红（builder_9cc11850f425 的 dqm、builder_a71947b68d50 的 dqcp）。
    //  · 把 ensureLeftJoin() 改成 INNER：
    //      → AC-6① 立刻报红（joinFingerprint 的 joinType 段不同；量具自证 gauge3 已证明比较器能认出）。
    //  · 只改 JOIN 顺序（模拟 semantic_edge 堆序漂移）：
    //      → AC-6① **不红**，只打印 INFO —— 判据在合法变化时不红，这一半同样要证。

    private void assertGraphStable(String ac, String before) {
        String after = graphFingerprint();
        if (!before.equals(after)) {
            assertTrue(false, concurrentGraphWrite(ac, before, after,
                    "JOIN 顺序/内容可能随 semantic_edge 堆序漂移"));
        }
    }

    /** 基线里 {@code :customerCode} 只出现在 JOIN 的 ON 上（既有形态③）⇒ 仍是合法的「改动前」基线。 */
    private static boolean baselineHasOnlyJoinCustomerCode(String baselineSql) {
        for (SqlShape.Block b : SqlShape.blocks(baselineSql)) {
            String where = b.whereTop() == null ? "" : b.whereTop();
            if (where.contains(":customerCode")) {
                return false;
            }
        }
        return true;
    }
}
