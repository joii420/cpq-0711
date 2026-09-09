package com.cpq.repair260908;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260908 · S-1 的<b>证伪实验</b>（{@code test.md §4}，强制，每片至少一个）。
 *
 * <h3>为什么必须有：首次 PASS 证明不了判据接上了</h3>
 * {@code AC-2b} / {@code AC-16} / {@code AC-17(b)} 在改动后的产物上全绿。
 * 但「全绿」有两种成因：<b>判据在工作且没发现问题</b>，或者<b>判据根本没在工作</b>。
 * 两者在报告里长得一模一样 —— {@code task-260907} 一天三次假绿都属后者。
 * ⇒ 必须给判据喂一份<b>已知有故障</b>的输入，确认它<b>硬失败</b>。
 *
 * <h3>🚫 为什么不改实现来做还原实验</h3>
 * {@code testing.md §4.4} 的标准做法是「把修复注释掉重跑」，但那要改
 * {@code SemanticCompiler.java} —— 越过了「测试员不写业务代码」那条线
 * （主线 2026-09-08 明令：要改实现做证伪先报备）。
 * <p>⇒ 改用<b>等价且不碰实现</b>的做法：故障样本用<b>真实的改动前编译产物</b>
 * （{@code ./证据/改动前产物-28视图-260908/}，2026-09-08 18:50 从共享库取，
 * 实测外层客户谓词数 = 0）。把同一套判据打在它们身上，<b>必须全部报红</b>。
 *
 * <p>🔑 这比注释代码更强的一点：它是<b>常驻</b>的。判据哪天被改坏成恒真，
 * 本类当轮就红，而不是等到下一次有人想起来做还原实验。
 *
 * <h3>🚨 存档不可删</h3>
 * {@code B-6} 执行后库里的 {@code sql_template} 变成改动后产物，那批改动前原文
 * <b>再也取不回来</b>。存档目录缺失 ⇒ 本类硬失败判「未验证」，🚫 不许静默跳过。
 *
 * <h3>本类零依赖</h3>
 * 不启 Quarkus、不连库、不写任何数据。
 */
class S1FalsificationTest {

    private static Path faultDir() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            Path p = cur.resolve("dev-docs").resolve("task-260819-取数配置器")
                    .resolve("repair-260908-页签重复行与跨客户串号")
                    .resolve("证据").resolve("改动前产物-28视图-260908");
            if (Files.isDirectory(p)) {
                return p;
            }
            cur = cur.getParent();
        }
        throw new AssertionError("🔴【环境前置未就绪，非产品缺陷】找不到改动前产物存档目录 "
                + "`dev-docs/…/证据/改动前产物-28视图-260908/`（cwd=" + Path.of("").toAbsolutePath() + "）。"
                + "\n  ⚠️ 没有故障样本 = 证伪实验做不了 = 上面那些判据的『全绿』无法与『没在工作』区分。"
                + "\n  🚫 本条判『未验证』，不得当成通过。"
                + "\n  🔑 该存档 B-6 执行后无法重建，若被误删请从 git 历史恢复。");
    }

    private static Map<String, String> faultSamples() {
        Path dir = faultDir();
        Map<String, String> out = new LinkedHashMap<>();
        try (var s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                String fn = p.getFileName().toString();
                if (fn.endsWith(".sql")) {
                    out.put(fn.substring(0, fn.length() - 4), Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            throw new AssertionError("读改动前产物存档失败：" + dir, e);
        }
        // 🚨 空跑防护：样本为空 ⇒ 下面每个循环都跑 0 次 ⇒ 「判据全部报红」自动成立，零证据
        assertFalse(out.isEmpty(), "🔴 故障样本目录为空 —— 证伪实验会空跑并假绿。目录=" + dir);
        assertTrue(out.size() >= 28, "🔴 故障样本只有 " + out.size() + " 个，少于采集时的 28 个 —— "
                + "存档被删过？证伪覆盖不完整。目录=" + dir);
        return out;
    }

    // ═══════════════ 证伪① AC-2b 的判据在故障输入上必须红 ═══════════════

    @Test
    @DisplayName("证伪①: 把 AC-2b 的判据打在**改动前产物**上 —— 25 个锚点含 customer_no 的视图必须全部报『缺客户谓词』")
    void falsify1_ac2bJudgeFiresOnPreFixArtifacts() {
        // 锚点含 customer_no 的表（information_schema 权威结论已由 AC-2b 前置用例在库上验过，
        // 这里为了保持本类「不连库」而按名单，名单本身来自那条已通过的用例的输出）
        List<String> customerTables = List.of(
                "ds_quote_material", "ds_quote_material_bom", "ds_quote_element_bom",
                "ds_quote_annual_discount", "ds_quote_assembly_fee", "ds_quote_assembly_fee_annual",
                "ds_quote_finished_other_fee", "ds_quote_incoming_annual", "ds_quote_incoming_fixed_fee",
                "ds_quote_incoming_other_fee", "ds_quote_incoming_recovery", "ds_quote_plating_fee",
                "ds_quote_self_process_fee", "ds_quote_sub_component_fee");

        List<String> shouldHaveFired = new ArrayList<>();
        List<String> didNotFire = new ArrayList<>();
        for (Map.Entry<String, String> e : faultSamples().entrySet()) {
            for (SqlShape.Block b : SqlShape.outerBlocks(e.getValue())) {
                if (b.from() == null || !customerTables.contains(b.from().table())) {
                    continue;
                }
                shouldHaveFired.add(e.getKey() + "/" + b.from().alias());
                if (SqlShape.hasCustomerCodePredicate(b.whereTop(), b.from().alias())) {
                    didNotFire.add(e.getKey() + "/" + b.from().alias() + " WHERE=" + b.whereTop());
                }
            }
        }
        System.out.println("[证伪①] 改动前产物里，应当被 AC-2b 判为『缺客户谓词』的外层块 = "
                + shouldHaveFired.size() + " 个");

        // 🚨 判据必须有作用对象
        assertTrue(shouldHaveFired.size() >= 28, "证伪①: 只找到 " + shouldHaveFired.size()
                + " 个作用对象（应 ≥28：25 个锚点 + 3 个 UNION 根分支）—— 证伪覆盖不足，"
                + "实得=" + shouldHaveFired);
        // 🚨 判据必须在故障输入上全部报红
        assertEquals(List.of(), didNotFire, "证伪①🚨 **判据没红 = 白测**："
                + "以下改动前产物的外层块本该被判『缺客户谓词』，判据却认为它合格：" + didNotFire
                + "\n  ⇒ AC-2b 在改动后产物上的『全绿』不能证明判据在工作，本条作废重写。");
    }

    // ═══════════════ 证伪② AC-16 的判据在故障输入上必须红 ═══════════════

    @Test
    @DisplayName("证伪②🚨: 把 AC-16 的判据打在**改动前产物**上 —— 4 处 NARROW 桥必须全部报『缺客户谓词』"
            + "（这正是 test.md §4 点名的那条：数据上 0 行差异，只有结构断言能红）")
    void falsify2_ac16JudgeFiresOnPreFixArtifacts() {
        List<String> bridges = new ArrayList<>();
        List<String> didNotFire = new ArrayList<>();
        for (Map.Entry<String, String> e : faultSamples().entrySet()) {
            for (SqlShape.Block b : SqlShape.subBlocks(e.getValue())) {
                if (b.from() == null || !"ds_quote_material".equals(b.from().table())) {
                    continue;
                }
                if (b.whereFull() == null || !b.whereFull().contains(":total_material_no")) {
                    continue;   // 不是 NARROW 桥
                }
                bridges.add(e.getKey() + "/" + b.from().alias());
                if (SqlShape.hasCustomerCodePredicate(b.whereTop(), b.from().alias())) {
                    didNotFire.add(e.getKey() + "/" + b.from().alias() + " WHERE=" + b.whereTop());
                }
            }
        }
        System.out.println("[证伪②] 改动前产物里认出的 NARROW 桥 = " + bridges);
        assertEquals(4, bridges.size(),
                "证伪②: 应认出 4 处桥（a515014e6ed3:1 + 32ab8212df6c:2 + 9291b050b6a9:1），实得=" + bridges
                        + "\n  🚨 认不出桥 ⇒ AC-16 的循环跑 0 次 ⇒ 报绿。这一条就是防它的。");
        assertEquals(List.of(), didNotFire, "证伪②🚨 **判据没红 = 白测**：" + didNotFire);
    }

    // ═══════════════ 证伪③ AC-17(b) 的判据在故障输入上必须红 ═══════════════

    @Test
    @DisplayName("证伪③: 把 AC-17(b) 的判据打在**改动前产物**上 —— 3 处根分支 NOT EXISTS 必须全部报『缺客户相关谓词』")
    void falsify3_ac17bJudgeFiresOnPreFixArtifacts() {
        List<String> found = new ArrayList<>();
        List<String> didNotFire = new ArrayList<>();
        for (Map.Entry<String, String> e : faultSamples().entrySet()) {
            List<SqlShape.Block> outer = SqlShape.outerBlocks(e.getValue());
            for (SqlShape.Block sub : SqlShape.subBlocks(e.getValue())) {
                if (sub.from() == null || !"ds_quote_material_bom".equals(sub.from().table())) {
                    continue;
                }
                if (sub.whereFull() != null && sub.whereFull().contains(":total_material_no")) {
                    continue;   // 桥归证伪②
                }
                SqlShape.Block host = outer.stream()
                        .filter(b -> b.from() != null
                                && SqlShape.mentionsAlias(sub.whereFull(), b.from().alias()))
                        .findFirst().orElse(null);
                if (host == null) {
                    continue;
                }
                found.add(e.getKey() + "/" + sub.from().alias() + "↔" + host.from().alias());
                if (SqlShape.hasCustomerCorrelation(sub.whereTop(), sub.from().alias(),
                        host.from().alias())) {
                    didNotFire.add(e.getKey() + " WHERE=" + sub.whereTop());
                }
            }
        }
        System.out.println("[证伪③] 改动前产物里认出的根分支 NOT EXISTS = " + found);
        assertEquals(3, found.size(),
                "证伪③: 应认出 3 处（QUOTE 侧 3 个 BOM 视图的根分支），实得=" + found);
        assertEquals(List.of(), didNotFire, "证伪③🚨 **判据没红 = 白测**：" + didNotFire
                + "\n  ⚠️ 特别注意：AC-17(b) 的判据是**列对列相关**而非 `= :customerCode`，"
                + "写错会恒 false ⇒ 恒『红』；本条断言的是它在故障样本上红、"
                + "而 Ac16CostBasicBridgeTest#ac17b 断言它在修好的产物上绿。两条合起来才排除恒真/恒假。");
    }

    // ═══════════════ 证伪④ 判据在合法变化时不许红（另一半） ═══════════════

    @Test
    @DisplayName("证伪④: 把改动前产物人为补上谓词 ⇒ 判据必须转绿 —— 证明它不是恒红")
    void falsify4_judgeTurnsGreenWhenPatched() {
        Map<String, String> samples = faultSamples();
        String one = samples.get("builder_a71947b68d50");
        assertTrue(one != null, "证伪④: 找不到 builder_a71947b68d50 的改动前产物");

        SqlShape.Block before = SqlShape.outerBlocks(one).get(0);
        assertFalse(SqlShape.hasCustomerCodePredicate(before.whereTop(), before.from().alias()),
                "证伪④ 前提: 改动前产物本该没有客户谓词");

        String patched = one.replace("WHERE dqm.material_no = ANY(:total_material_no)",
                "WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode");
        assertFalse(patched.equals(one), "证伪④: 变异体没生成，replace 没命中");
        SqlShape.Block after = SqlShape.outerBlocks(patched).get(0);
        assertTrue(SqlShape.hasCustomerCodePredicate(after.whereTop(), after.from().alias()),
                "证伪④🚨 判据恒红：补上谓词后仍判为『缺』，那它在改动后产物上的绿就是别的原因造成的。"
                        + " whereTop=" + after.whereTop());
        System.out.println("[证伪④] 同一视图：改动前判『缺』✔，补谓词后判『有』✔ ⇒ 判据既不恒真也不恒假");
    }

    // ═══════════════ 证伪⑤ AC-4 的两侧抹除：只抹一边会误报（后端实证的坑） ═══════════════

    @Test
    @DisplayName("证伪⑤: AC-4 的抹除必须两侧都做 —— 只抹 after 会在『ON 上本就有 :customerCode』的视图上误报"
            + "（后端同型分类器首跑误报 4 例的根因）")
    void falsify5_stripMustBeTwoSided() {
        String before = faultSamples().get("builder_a71947b68d50");
        assertTrue(before != null && before.contains("dqcp.customer_no = :customerCode"),
                "证伪⑤ 前提: 该视图的 LEFT JOIN ON 上本就有 :customerCode（客户维度形态③），"
                        + "它是这个坑的诱饵；诱饵不在则本条无意义");

        // 模拟「改动后」= 只在 WHERE 上多一条锚点谓词，其余逐字节不变
        String after = before.replace("WHERE dqm.material_no = ANY(:total_material_no)",
                "WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode");

        // ❌ 错误做法：只抹 after
        String wrong = SqlShape.stripAllowedCustomerPredicates(after);
        assertFalse(wrong.equals(before), "证伪⑤🚨 对照失效：只抹一边竟然也相等 —— "
                + "那说明 ON 上的 :customerCode 没被抹到，这个坑的复现条件不成立，本条无意义");
        System.out.println("[证伪⑤] ❌ 只抹 after ⇒ 与 before 不等（= 假红，后端首跑的那 4 例）");

        // ✅ 正确做法：两侧都抹
        assertEquals(SqlShape.stripAllowedCustomerPredicates(before), wrong,
                "证伪⑤: 两侧同样抹除后必须相等 —— 不相等说明抹除规则本身有问题");
        System.out.println("[证伪⑤] ✅ 两侧都抹 ⇒ 相等（真绿）。AC-4① 用的就是两侧抹除。");
    }
}
