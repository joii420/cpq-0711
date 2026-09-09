package com.cpq.repair260908;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-2b</b>（阴性 · 结构）—— 问题说明 ⑥ 节原文：
 *
 * <blockquote>
 * 28 个 {@code builder_*} 视图重编译后，逐个扫 {@code sql_template}：
 * 凡锚点物理表<b>含</b> {@code customer_no} 列者，{@code WHERE} 必含
 * {@code <锚点别名>.customer_no = :customerCode}；<b>不含</b>该列者<b>不得</b>出现该谓词。
 * 表名匹配用标识符边界 {@code (?<![A-Za-z0-9_])<表名>(?![A-Za-z0-9_])}
 * （{@code ds_quote_material} 是 {@code ds_quote_material_bom} 的前缀）。
 * </blockquote>
 *
 * <h3>🚨 本类的作用域：只管「外层锚点」</h3>
 * {@code AC-2b} 管<b>外层 FROM</b>，{@code AC-16}（{@link Ac16CostBasicBridgeTest}）管<b>桥子查询</b>。
 * 两者判据<b>不共用同一个「主表」概念</b> —— 并发会话正是因为把两者混成一个正则，
 * 才出现「外层真表一个没匹上、匹到的全是桥子查询里的 {@code ds_quote_material}」，
 * <b>用错误判据碰巧碰到了正确位置</b>。判据的正确性由 {@link SqlShapeSelfProofTest} 每轮自证。
 *
 * <h3>🚫 共库纪律：不做全局计数断言</h3>
 * 🚫 不写「共 28 条」。断言的是：
 * ① 交接清单点名的 28 个我<b>都扫到了</b>且分类一致（背靠背比对）；
 * ② 不变量对<b>所有</b>扫到的 {@code builder_*}（含并发线新建的）都成立。
 * 并发线新建视图只会让 ② 多验几个，不会把我打红。
 *
 * <h3>本片写入面 = 空</h3>
 * 只 SELECT + 调 compile（不落库）。
 */
@QuarkusTest
class Ac2bAnchorCustomerPredicateTest extends S1CompileTestBase {

    // ═══════════════ 背靠背比对：我的锚点判据 vs 交接清单的 28 个 ═══════════════

    @Test
    @DisplayName("AC-2b 前置: 锚点判据与 `材料-并发线交接-260908.md §3` 的 28 个清单背靠背 —— 不一致说明有一边判据错了")
    void ac2bPre_anchorClassificationMatchesHandoff() {
        List<ViewRow> all = builderViews();
        assertFalse(all.isEmpty(), notReady("AC-2b",
                "库里一个 builder_* 视图都没有 —— 整条 AC 会空跑", "取数配置器"));

        Map<String, ViewRow> byName = new LinkedHashMap<>();
        all.forEach(v -> byName.put(v.viewName(), v));

        List<String> missing = new ArrayList<>();
        List<String> noAnchor = new ArrayList<>();
        Map<String, Object> hasCust = new LinkedHashMap<>();
        Map<String, Object> noCust = new LinkedHashMap<>();
        for (String n : HANDOFF_28) {
            ViewRow v = byName.get(n);
            if (v == null) {
                missing.add(n);
                continue;
            }
            if (v.anchorTable() == null) {
                noAnchor.add(n + "(dialect=" + v.dialect() + "/tab=" + v.tabType()
                        + "/variant='" + v.variantKey() + "')");
                continue;
            }
            (v.anchorHasCustomerNo() ? hasCust : noCust).put(n, v.anchorTable());
        }
        dump("AC-2b 锚点含 customer_no（应加谓词）", hasCust);
        dump("AC-2b 锚点不含 customer_no（不得加谓词）", noCust);
        System.out.println("   扫到 builder_* 共 " + all.size() + " 个（仅供参考，🚫 不作断言）");

        assertTrue(missing.isEmpty(), notReady("AC-2b",
                "交接清单点名的视图在库里查不到：" + missing
                        + "\n  ⇒ 要么被并发线删了/改名了，要么清单过期。两种都得先弄清，"
                        + "否则「我扫到的都合格」只是因为没扫到它们。", null));
        assertTrue(noAnchor.isEmpty(), notReady("AC-2b",
                "以下视图按 (dialect, tabType, variantKey) 回查不到语义图锚点：" + noAnchor
                        + "\n  🚨 锚点为 null 会让『按锚点判断要不要加谓词』这条判据**因为查不到而静默跳过**，"
                        + "\n     这是最危险的假绿形态。注意 semantic_tab_view.variant_key 存的是空串不是 NULL。", null));

        // 🔑 与交接清单的结论对照：3 个 COST_BASIC 锚点无 customer_no，其余 25 个有
        assertEquals(Set.copyOf(AC16_VIEWS), new LinkedHashSet<>(noCust.keySet()),
                "AC-2b: 「锚点不含 customer_no」的集合应恰是 3 个 COST_BASIC 视图（交接清单 §2 独立复核："
                        + "ds_cost_* 共 55 张表带 customer_no 的 = 0）。实得=" + noCust.keySet());
        assertEquals(HANDOFF_28.size() - AC16_VIEWS.size(), hasCust.size(),
                "AC-2b: 交接清单 28 个里应有 " + (HANDOFF_28.size() - AC16_VIEWS.size())
                        + " 个锚点含 customer_no，实得 " + hasCust.size() + " 个：" + hasCust.keySet());
    }

    // ═══════════════ AC-2b 主断言 ═══════════════

    @Test
    @DisplayName("AC-2b: 重编译后逐个扫 —— 锚点含 customer_no ⇒ 外层 WHERE 必含客户谓词；不含 ⇒ 必不含")
    void ac2b_customerPredicateFollowsAnchorColumn() {
        List<ViewRow> all = builderViews();
        assertFalse(all.isEmpty(), notReady("AC-2b", "库里一个 builder_* 视图都没有", "取数配置器"));

        List<String> err = new ArrayList<>();
        int checkedShould = 0;
        int checkedShouldNot = 0;

        for (ViewRow v : all) {
            String sql = recompile(v);
            List<SqlShape.Block> outer = SqlShape.outerBlocks(sql);
            if (outer.isEmpty()) {
                err.add("\n  " + v.viewName() + ": 解析不出任何外层 SELECT 块（产物形状意外），SQL=\n" + sql);
                continue;
            }
            // 锚点块 = 第一个外层块（UNION 的其余分支单列，见下一条用例）
            SqlShape.Block anchorBlock = outer.get(0);
            String alias = anchorBlock.from() == null ? null : anchorBlock.from().alias();
            String table = anchorBlock.from() == null ? null : anchorBlock.from().table();
            String where = anchorBlock.whereTop() == null ? "" : anchorBlock.whereTop();

            // 🔑 锚点权威来自语义图；同时用**标识符边界**核对产物 FROM 与它一致
            if (v.anchorTable() != null && !SqlShape.containsIdentifier(table, v.anchorTable())) {
                err.add("\n  " + v.viewName() + ": 语义图锚点=" + v.anchorTable()
                        + " 但产物外层 FROM=" + table + " —— 两者对不上，本条判据打在了错的对象上");
                continue;
            }

            boolean present = SqlShape.hasCustomerCodePredicate(where, alias);
            if (v.anchorHasCustomerNo()) {
                checkedShould++;
                if (!present) {
                    err.add("\n  " + v.viewName() + "(" + v.dialect() + "/" + v.tabType() + "/"
                            + v.variantKey() + "): 锚点 " + table + " 含 customer_no，"
                            + "外层 WHERE 应含 `" + alias + ".customer_no = :customerCode`，实得 WHERE(本层)=" + where);
                }
            } else {
                checkedShouldNot++;
                if (present || SqlShape.mentionsCustomerNo(where, alias)) {
                    err.add("\n  " + v.viewName() + "(" + v.dialect() + "/" + v.tabType()
                            + "): 锚点 " + table + " **没有** customer_no 列，外层 WHERE 不得出现该谓词，"
                            + "否则真实执行会报 `column \"customer_no\" does not exist`。实得 WHERE(本层)=" + where);
                }
            }
        }

        // 🚨 空跑防护：两侧都必须真的验到过对象，否则「没有错误」只是「没有检查」
        assertTrue(checkedShould > 0, notReady("AC-2b",
                "正向侧（应加谓词）一个视图都没验到 —— 断言从未执行", null));
        assertTrue(checkedShouldNot > 0, notReady("AC-2b",
                "反向侧（不得加谓词）一个视图都没验到 —— 反向断言从未执行，"
                        + "『不含 customer_no 的表没被误加』这一半等于没验", null));
        System.out.println("[AC-2b] 正向验了 " + checkedShould + " 个，反向验了 " + checkedShouldNot + " 个");

        assertEquals("", String.join("", err), "AC-2b 不符（共 " + err.size() + " 处）：" + String.join("", err));
    }

    // ═══════════════ AC-2b(根分支)：UNION ALL 根分支（D-7 升格为正式判据 / B-1c(a)） ═══════════════

    @Test
    @DisplayName("AC-2b(根分支): UNION ALL 根分支的外层 FROM 同样按锚点判 —— 漏加则 BOM 页签树根行仍跨客户"
            + "（2026-09-08 D-7 裁决：由本片的派生观察升格为 AC-2b 正式判据，实现见 B-1c(a)）")
    void ac2bDerived_unionRootBranchAlsoNarrowed() {
        List<String> err = new ArrayList<>();
        int checked = 0;
        for (ViewRow v : builderViews()) {
            List<SqlShape.Block> outer = SqlShape.outerBlocks(recompile(v));
            for (int i = 1; i < outer.size(); i++) {          // i=0 是锚点块，上一条已验
                SqlShape.Block b = outer.get(i);
                if (b.from() == null) {
                    continue;
                }
                String table = b.from().table();
                if (!hasCustomerNo(table)) {
                    continue;                                  // 该分支的表没有客户列，本条不适用
                }
                checked++;
                if (!SqlShape.hasCustomerCodePredicate(b.whereTop(), b.from().alias())) {
                    err.add("\n  " + v.viewName() + " 的 UNION 第 " + (i + 1) + " 分支：FROM " + table
                            + " " + b.from().alias() + " 含 customer_no，但该分支 WHERE 没有客户谓词。"
                            + "\n      实得 WHERE(本层)=" + b.whereTop()
                            + "\n      🔑 该分支是「本单闭包里无父边的成品自身」（树根行）。漏加 ⇒ 树根行仍会串别家客户，"
                            + "而 E-1『任一页签不再出现其他客户的行』要求它也收窄。");
                }
            }
        }
        System.out.println("[AC-2b 派生] 验到 " + checked + " 个含 customer_no 的 UNION 非锚点分支");
        assertTrue(checked > 0, notReady("AC-2b 派生",
                "没扫到任何含 customer_no 的 UNION 非锚点分支 —— 本条空跑。"
                        + "\n  2026-09-08 实测应有 3 个（QUOTE 侧 3 个 BOM 视图的根分支 FROM ds_quote_material）", null));
        assertEquals("", String.join("", err),
                "AC-2b 派生断言不符：" + String.join("", err)
                        + "\n\n  🔑 D-7 实测（QT-20260908-0624 的 BOM 页签根分支）：现状 8 行 | 加客户谓词后 4 行 | 串进来的别家根行 4 行。"
                        + "\n     ⇒ 本条不是理论隐患，是活故障；实现见 B-1c(a)。");
    }

    // ═══════════════ 证伪设计（test.md §4）═══════════════
    //  注释掉 applyFullScope 里新增的客户谓词后：
    //   · ac2b_customerPredicateFollowsAnchorColumn 正向侧 25 个全红（当前实测 withPred=0，
    //     即「改动前」状态本身就是全红态 —— 这一条的『红』已在改动前实证过，不是推测）。
    //  反向证伪（判据不能恒红）：
    //   · 若把谓词无差别加到全部 28 个（含 3 个 COST_BASIC），反向侧 3 个立刻红，
    //     且真实执行会报 column "customer_no" does not exist。
}
