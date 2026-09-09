package com.cpq.repair260908;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260908 · <b>S-3 / AC-3</b>：B 族页签的行数差 <b>恰等于</b>被缺陷① 滤掉的跨客户行数，不多不少。
 *
 * <h3>AC-3 原文（{@code 问题说明.md §⑥ 阳性类}，逐字）</h3>
 * <blockquote>
 * 前置：同 AC-1 ｜ 操作：B 族 15 个页签（BOM / 材质元素 / 加工费 …），逐张卡片记录改动前后行数 ｜
 * 断言：差值<b>恰等于</b>「被缺陷① 滤掉的跨客户行数」，<b>不多不少</b>。
 * 特别地：来料类页签的 {@code input_material_no} 明细<b>一行不少</b>。
 * </blockquote>
 *
 * <h3>🚨 主线交办的核心要求：把它做成<b>可复跑的用例</b>，而不是一次性数字</h3>
 * 主线在 {@code QT-20260908-0624} 上量到 BOM {@code 84→42} / 材质元素 {@code 52→26} /
 * 加工费 {@code 20→10}（恰好减半）。
 * 🚫 <b>本类不写死这些数字</b> —— 共享库上它们是移动靶（并发线随时造数），
 * 写死会红成「业务回归」的样子；而且「减半」这个巧合只在「恰好两个客户、行数对称」时成立，
 * 换一张单就不成立，判据会静默失效。
 *
 * <h3>本类的判据形态：<b>按客户分解，做集合恒等式</b>（不是比两个数）</h3>
 * 对每个 B 族视图、在目标单的整单闭包 {@code T} 上：
 * <pre>
 *   A       = 执行「修好的」视图，绑 :customerCode = CUST-0004
 *   B       = 执行「缺陷① 形态」的同一视图（把 <b>WHERE 里</b>的客户谓词摘掉）
 *   A_c     = 执行「修好的」视图，绑 :customerCode = c，c 取遍库里全部客户
 *
 *   ① 多重集恒等：  multiset(B) == multiset(⋃_c A_c)      ← 「不多不少」的结构版
 *   ② 本家不丢行：  A == A_(CUST-0004)
 *   ③ 差值恒等：    |B| - |A| == Σ_(c≠CUST-0004) |A_c|     ← AC-3 的字面断言
 *   ④ 逐卡成立：    按 hf_part_no 分组后 ①②③ 仍逐组成立    ← 「逐张卡片」
 * </pre>
 * 🔑 <b>①式是关键</b>：它同时排除了两个反方向的错误 —— 「谓词多滤了本该留下的行」（会让 ① 左多右少）
 * 与「有行在任何客户下都取不到」（{@code customer_no} 为 NULL 的行会从所有 {@code A_c} 里消失，
 * 也让 ① 不成立）。只比两个数字的判据对这两种错都<b>恒为真</b>。
 *
 * <h3>🚨 量具自证（{@code test.md §3} + §2.5 的反面样本）</h3>
 * 「缺陷① 形态」是靠一个文本改写器造出来的，它<b>本身可能是坏的</b>：
 * 改写器什么都没匹到时，{@code B} 就等于 {@code A}，差值恒为 0，
 * 而 ①②③ <b>全部成立</b> —— 一个漂亮的全绿零证据。
 * ⇒ {@link #t01_neutralizer_selfProof} 先把改写器按在已知答案上验四件事：
 * <ol>
 *   <li>对含 {@code WHERE ... customer_no = :customerCode} 的 SQL <b>必须命中</b>；</li>
 *   <li>对 {@code LEFT JOIN ... ON ... customer_no = :customerCode}（客户维度<b>形态②</b>，
 *       本来就该在）<b>必须不动</b>，{@code ON} 子句逐字保留；</li>
 *   <li>对不含该谓词的 SQL <b>必须原样返回</b>（不许「改了个寂寞」也报成功）；</li>
 *   <li>对每个被测视图，{@code 改写后 != 改写前} —— 否则该视图这一轮判据作废。</li>
 * </ol>
 *
 * <h3>🚨 证伪实验（{@code test.md §4}）</h3>
 * 把 {@code B-1} 的 {@code applyFullScope} 客户谓词注释掉并重编译视图，
 * 则「修好的」与「缺陷① 形态」变成同一条 SQL ⇒ {@code |B| - |A| == 0}，
 * 而右边 {@code Σ_(c≠CUST-0004) |A_c|} 仍然 {@code > 0}（那是从物理表按客户分解出来的，不依赖谓词）
 * ⇒ ③式变红。
 * <p>本类另外内置了一道「分辨力」断言 {@link #t02_bFamilyDelta_equalsCrossCustomerRows} 里的
 * {@code assertTrue(bRows.size() > aRows.size(), ...)}：跨客户行数为 0 时直接硬失败，
 * 🚫 不让「没有可滤的行」冒充「滤得刚好」。
 *
 * <h3>写入面</h3>
 * <b>本类零写入</b>：只 {@code SELECT}（含把编译产物当子查询跑一遍）。
 * 🚫 不 INSERT/UPDATE/DELETE，🚫 不动共享 {@code component_sql_view} / {@code template}。
 * 🚫 无全局计数断言 —— 所有数字都在「本单闭包 + 点名的视图」这个范围里算出来，
 * 并发线往同一张表造数不影响本判据（他们的料号不在这个闭包里）。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ac3CrossCustomerRowDeltaTest extends S3FixtureBase {

    /**
     * 被测的 B 族页签（{@code 取值测试模板2} 的 3 个 {@code axis_scope=CLOSURE} 视图）。
     * <p>🚫 不断言「B 族共 15 个」—— 那是全局计数，且 15 是立项时的口径。
     * 本类只对<b>目标单实际用到的这三个</b>下结论，覆盖面限定写进报告。
     */
    private static final Map<String, String> B_FAMILY = new LinkedHashMap<>() {{
        put("BOM", VIEW_BOM);
        put("材质元素", VIEW_ELEMENT);
        put("加工费", VIEW_FEE);
    }};

    /** {@code <别名>.customer_no = :customerCode} 的所有出现位置。 */
    private static final Pattern CUST_PRED = Pattern.compile(
            "(?<![A-Za-z0-9_.])([A-Za-z_][A-Za-z0-9_]*)\\.customer_no\\s*=\\s*:customerCode");

    /** 匹配点之前最近的一个子句关键字，用来区分「{@code WHERE} 里的」和「{@code ON} 里的」。 */
    private static final Pattern CLAUSE_KW = Pattern.compile("(?i)(?<![A-Za-z0-9_])(WHERE|ON)(?![A-Za-z0-9_])");

    // ═══════════════════ 量具：缺陷① 形态改写器 ═══════════════════

    /** 改写结果 + 它自己的可核查痕迹（🚫 不许只返回一个字符串就当它工作了）。 */
    record Neutralized(String sql, int replacedInWhere, int keptInJoinOn, List<String> sites) {
    }

    /**
     * 把 <b>{@code WHERE} 子句里</b>的 {@code <别名>.customer_no = :customerCode} 换成 {@code TRUE}，
     * 重建「缺陷① 形态」；{@code JOIN ... ON} 里的同形谓词<b>原样保留</b>。
     *
     * <p>🔑 为什么必须区分：{@code D-13} 已实证 {@code LEFT JOIN ds_quote_customer_part ...
     * AND dqcp.customer_no = :customerCode} 是客户维度<b>形态②</b>，改动前就存在、本来就该在。
     * 把它一起摘掉，造出来的就不是「改动前」，而是一个从未存在过的第三种形态 ——
     * 且 {@code LEFT JOIN} 少了限制会<b>放大行数</b>，让差值偏大，
     * 判据会以「差值对不上」的样子红，看起来像产品缺陷。
     */
    static Neutralized neutralizeWherePredicate(String sql) {
        StringBuilder out = new StringBuilder();
        Matcher m = CUST_PRED.matcher(sql);
        int last = 0;
        int replaced = 0;
        int kept = 0;
        List<String> sites = new ArrayList<>();
        while (m.find()) {
            String before = sql.substring(0, m.start());
            String kw = lastClauseKeyword(before);
            String snippet = sql.substring(Math.max(0, m.start() - 40), Math.min(sql.length(), m.end() + 10))
                    .replace('\n', ' ');
            out.append(sql, last, m.start());
            if ("WHERE".equalsIgnoreCase(kw)) {
                out.append("TRUE");
                replaced++;
                sites.add("[WHERE→TRUE] …" + snippet + "…");
            } else {
                out.append(m.group());
                kept++;
                sites.add("[" + kw + " 保留] …" + snippet + "…");
            }
            last = m.end();
        }
        out.append(sql.substring(last));
        return new Neutralized(out.toString(), replaced, kept, sites);
    }

    private static String lastClauseKeyword(String before) {
        Matcher k = CLAUSE_KW.matcher(before);
        String kw = "<无>";
        while (k.find()) {
            kw = k.group(1).toUpperCase();
        }
        return kw;
    }

    // ═══════════════════ t01 量具自证 ═══════════════════

    @Test
    @Order(1)
    void t01_neutralizer_selfProof() {
        // ① 已知答案：产品视图同时含「WHERE 里的」和「LEFT JOIN ON 里的」两处，必须区分开
        String product = storedSql(VIEW_PRODUCT);
        assertNotNull(product, "取不到 " + VIEW_PRODUCT + " 的 sql_template —— 环境未就绪");
        assertTrue(product.contains("LEFT JOIN ds_quote_customer_part"),
                "量具自证前提不成立：" + VIEW_PRODUCT + " 里应有 LEFT JOIN ds_quote_customer_part（形态②）。"
                + "\n  若这条 JOIN 没了，本自证用例失去参照物，请报主线换参照。SQL=\n" + product);
        Neutralized n = neutralizeWherePredicate(product);
        n.sites().forEach(s -> System.out.println("[AC-3·量具自证] " + s));
        assertEquals(1, n.replacedInWhere(), "产品视图的 WHERE 里应恰有 1 处客户谓词被摘掉");
        assertEquals(1, n.keptInJoinOn(), "产品视图 LEFT JOIN ON 上的形态② 谓词必须原样保留");
        assertTrue(n.sql().contains("dqcp.customer_no = :customerCode"),
                "量具自证失败：ON 子句里的形态② 谓词被误伤了 —— 那会造出一个从未存在过的第三种形态");
        assertTrue(!n.sql().equals(product), "量具自证失败：改写器对含谓词的 SQL 没有产生任何变化");

        // ② 反向：不含该谓词的 SQL 必须原样返回（🚫 不许「什么都没改」也报成功）
        String clean = "SELECT 1 AS a FROM ds_quote_material dqm WHERE dqm.material_no = 'X'";
        Neutralized n2 = neutralizeWherePredicate(clean);
        assertEquals(clean, n2.sql(), "量具自证失败：改写器动了不该动的 SQL");
        assertEquals(0, n2.replacedInWhere());

        // ③ 每个被测视图都必须真的被改动过 —— 否则该视图的差值判据是空跑
        B_FAMILY.forEach((tab, view) -> {
            String sql = storedSql(view);
            assertNotNull(sql, "取不到 " + view + " 的 sql_template");
            Neutralized nv = neutralizeWherePredicate(sql);
            System.out.println("[AC-3·量具自证] " + tab + "/" + view + " WHERE 摘掉 " + nv.replacedInWhere()
                    + " 处，JOIN 保留 " + nv.keptInJoinOn() + " 处");
            nv.sites().forEach(s -> System.out.println("      " + s));
            assertTrue(nv.replacedInWhere() > 0,
                    "量具自证失败：" + tab + "/" + view + " 的 WHERE 里一处客户谓词都没匹到"
                    + "\n  ⇒ 造不出『缺陷① 形态』，B 会恒等于 A、差值恒 0，而恒等式会全部成立 —— 那是零证据。"
                    + "\n  两种可能：(a) B-1 没给这个视图加谓词（真缺陷，报主线）；(b) 谓词写法变了（改判据）。"
                    + "\n  SQL=\n" + sql);
            assertTrue(!nv.sql().equals(sql), tab + "：改写前后逐字节相同 ⇒ 判据作废");
        });
    }

    // ═══════════════════ t02 AC-3 主判据 ═══════════════════

    @Test
    @Order(2)
    void t02_bFamilyDelta_equalsCrossCustomerRows() {
        List<String> closure = quotationClosure();
        assertTrue(closure.size() >= 2,
                "前置未就绪：目标单闭包只有 " + closure.size() + " 个料号，跨客户对比失去意义。闭包=" + closure);
        List<String> customers = allDsCustomers();
        assertTrue(customers.contains(CUSTOMER), "候选客户集里应含目标客户 " + CUSTOMER + "，实际 " + customers);
        System.out.println("[AC-3] 目标单闭包 " + closure.size() + " 个料号 = " + closure);
        System.out.println("[AC-3] 候选客户集 = " + customers);

        StringBuilder report = new StringBuilder();
        B_FAMILY.forEach((tab, view) -> {
            String fixedSql = storedSql(view);

            List<String> aRows = runView(tab + "/A", fixedSql, CUSTOMER, closure);
            List<String> bRows = runView(tab + "/B(缺陷①形态)", fixedSql, null, closure);

            Map<String, List<String>> perCustomer = new LinkedHashMap<>();
            for (String c : customers) {
                perCustomer.put(c, runView(tab + "/A_" + c, fixedSql, c, closure));
            }

            // 量具分辨力：跨客户行必须真的存在，否则「差值 = 0」冒充「滤得刚好」
            assertTrue(bRows.size() > aRows.size(),
                    "[AC-3/" + tab + "] 量具无分辨力：缺陷① 形态返回 " + bRows.size()
                    + " 行，不多于修好后的 " + aRows.size() + " 行"
                    + "\n  ⇒ 这个闭包里根本没有跨客户重复行，差值恒 0，恒等式会全部成立但什么都没证明。"
                    + "\n  处置：报主线换一个含跨客户数据的单/闭包，🚫 不许当作通过。");

            // ② 本家不丢行
            assertEquals(perCustomer.get(CUSTOMER), aRows,
                    "[AC-3/" + tab + "] 绑 " + CUSTOMER + " 的结果与 A 不一致 —— 说明 A 的取数口径不是纯客户维度");

            // ① 多重集恒等：B == ⋃_c A_c
            List<String> union = new ArrayList<>();
            perCustomer.values().forEach(union::addAll);
            Collections.sort(union);
            List<String> bSorted = new ArrayList<>(bRows);
            Collections.sort(bSorted);
            assertEquals(bSorted.size(), union.size(),
                    "[AC-3/" + tab + "] 「不多不少」不成立：缺陷① 形态 " + bSorted.size()
                    + " 行，按客户分解求和 " + union.size() + " 行。"
                    + "\n  左多于右 ⇒ 有行在任何客户下都取不到（customer_no 为 NULL / 谓词误伤）；"
                    + "\n  右多于左 ⇒ 同一行被多个客户重复取到（客户维度没有把行分干净）。"
                    + "\n  差集示例（最多 3 条）=" + firstDiff(bSorted, union));
            assertEquals(union, bSorted,
                    "[AC-3/" + tab + "] 行数对上但内容对不上 —— 按客户分解的并集 ≠ 缺陷① 形态的结果集");

            // ③ AC-3 字面断言：差值 == 跨客户行数，不多不少
            long cross = 0;
            for (Map.Entry<String, List<String>> e : perCustomer.entrySet()) {
                if (!CUSTOMER.equals(e.getKey())) {
                    cross += e.getValue().size();
                }
            }
            assertEquals(cross, bRows.size() - aRows.size(),
                    "[AC-3/" + tab + "] 差值 ≠ 跨客户行数：改动前 " + bRows.size() + " 行 → 改动后 "
                    + aRows.size() + " 行（差 " + (bRows.size() - aRows.size()) + "），"
                    + "而按客户分解出的非 " + CUSTOMER + " 行共 " + cross + " 行。"
                    + "\n  差得多 ⇒ 谓词多滤了本家的行（AC-3 要防的「多」）；"
                    + "\n  差得少 ⇒ 还有别家客户的行没被滤掉（缺陷① 没修干净）。");

            // ④ 逐张卡片（按 hf_part_no 分组）同一恒等式仍成立
            Map<String, Long> aByCard = countByCard(fixedSql, CUSTOMER, closure);
            Map<String, Long> bByCard = countByCard(fixedSql, null, closure);
            Map<String, Long> crossByCard = new LinkedHashMap<>();
            for (String c : customers) {
                if (CUSTOMER.equals(c)) {
                    continue;
                }
                countByCard(fixedSql, c, closure).forEach((k, v) -> crossByCard.merge(k, v, Long::sum));
            }
            for (String card : bByCard.keySet()) {
                long a = aByCard.getOrDefault(card, 0L);
                long b = bByCard.getOrDefault(card, 0L);
                long x = crossByCard.getOrDefault(card, 0L);
                assertEquals(x, b - a, "[AC-3/" + tab + "] 卡片 " + card + " 的差值 ≠ 该卡片的跨客户行数："
                        + b + " → " + a + "（差 " + (b - a) + "），跨客户 " + x);
            }

            report.append(String.format("%-6s %-22s 改动前 %3d → 改动后 %3d ｜ 差 %3d ｜ 跨客户 %3d ｜ 卡片数 %d%n",
                    tab, view, bRows.size(), aRows.size(), bRows.size() - aRows.size(), cross, bByCard.size()));
        });
        System.out.println("── [AC-3] B 族页签行数分解（本轮实测，非写死）──\n" + report);
    }

    // ═══════════════════ t03 来料明细一行不少 ═══════════════════

    /**
     * AC-3 的后半句：「来料类页签的 {@code input_material_no} 明细<b>一行不少</b>」。
     *
     * <p>判据用<b>物理表独立计数</b>（不经视图）：本家客户在闭包内的来料边有多少条，
     * BOM 页签的<b>非根行</b>就该有多少条。
     * 🔑 用物理表当参照，才不会「用视图证明视图」。
     */
    @Test
    @Order(3)
    void t03_inboundDetailNotDropped() {
        List<String> closure = quotationClosure();
        String bomSql = storedSql(VIEW_BOM);
        List<String> aRows = runView("BOM/A", bomSql, CUSTOMER, closure);
        assertTrue(!aRows.isEmpty(), "BOM 页签修好后返回 0 行 ⇒ 后面的『一行不少』会空跑");

        long childRowsFromView = runViewColumn(bomSql, CUSTOMER, closure,
                "parent_no").stream().filter(v -> v != null && !v.isBlank()).count();
        long childRowsFromTable = scalarLong(
                "SELECT count(*) FROM ds_quote_material_bom "
                + "WHERE customer_no = ?1 AND input_material_no = ANY(CAST(?2 AS text[]))",
                CUSTOMER, pgArray(closure));

        System.out.println("[AC-3·来料明细] 视图非根行 = " + childRowsFromView
                + " ｜ 物理表 ds_quote_material_bom 本家闭包内边数 = " + childRowsFromTable);
        assertTrue(childRowsFromTable > 0,
                "前置未就绪：本家客户在闭包内一条来料边都没有 ⇒ 『一行不少』无从谈起");
        assertEquals(childRowsFromTable, childRowsFromView,
                "AC-3：来料明细少了行 —— 物理表有 " + childRowsFromTable + " 条本家来料边，"
                + "BOM 页签只渲出 " + childRowsFromView + " 条非根行。"
                + "\n  这正是 B-1c(b) 的失败方向：父边判定加了同客户约束后，"
                + "若某成品在别客户下有父边，会被误判成非树根而从根分支消失。");
    }

    // ═══════════════════ t04 证伪实验（内建，不改实现文件） ═══════════════════

    /**
     * 🚨 <b>证伪实验</b>（{@code test.md §4}：判据必须在故障时红）—— <b>不改任何实现文件</b>。
     *
     * <p>做法：在本用例里<b>重建「{@code B-1} 没做」的那个世界</b> ——
     * 那个世界里「实际发出的 SQL」就是缺陷① 形态，于是 {@code A} 与 {@code B} 是<b>同一条 SQL</b>、
     * 同一个结果集。把 {@link #t02_bFamilyDelta_equalsCrossCustomerRows} 用的两条判据原样套上去，
     * <b>必须抛 {@link AssertionError}</b>。
     *
     * <p>🔑 这样做的理由：改实现文件做还原实验越过了「测试员不写业务代码」那条线
     * （{@code test.md §4} 明写「改实现做证伪 ⇒ 先报主线」）。
     * 而本判据的故障态<b>可以在测试侧完整重建</b> —— 缺陷① 的可观测后果就是
     * 「查询里没有那条客户谓词」，这一点用同一个改写器就能造出来，不需要动 {@code SemanticCompiler}。
     *
     * <p>🚫 若哪天这个用例<b>不再抛异常</b>，说明 {@code t02} 的判据已经退化成恒真 —— 判据作废重写。
     */
    @Test
    @Order(4)
    void t04_falsification_criteriaMustTurnRedWhenPredicateAbsent() {
        List<String> closure = quotationClosure();
        List<String> customers = allDsCustomers();
        String bomSql = storedSql(VIEW_BOM);

        // 「B-1 没做」的世界：实际发出的 SQL 就是缺陷① 形态 ⇒ A 与 B 同源
        List<String> defectWorld = runView("证伪/缺陷①未修", bomSql, null, closure);
        long crossAcc = 0;
        for (String c : customers) {
            if (!CUSTOMER.equals(c)) {
                crossAcc += runView("证伪/A_" + c, bomSql, c, closure).size();
            }
        }
        final long cross = crossAcc;
        assertTrue(cross > 0, "证伪实验前提不成立：这个闭包里没有跨客户行，构造不出故障态");

        // 判据 ③（差值 == 跨客户行数）在故障态下必须红
        AssertionError e1 = org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
                () -> assertEquals(cross, defectWorld.size() - defectWorld.size(),
                        "差值判据"),
                "🚫 证伪失败：缺陷① 未修时『差值 == 跨客户行数』竟然仍成立 ⇒ 该判据是恒真的，作废重写");

        // 判据「量具分辨力」（B 必须严格多于 A）在故障态下必须红
        AssertionError e2 = org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
                () -> assertTrue(defectWorld.size() > defectWorld.size(), "分辨力判据"),
                "🚫 证伪失败：缺陷① 未修时『B 严格多于 A』竟然仍成立 ⇒ 该判据是恒真的，作废重写");

        System.out.println("[AC-3·证伪] 缺陷① 未修时 A=B=" + defectWorld.size() + " 行，跨客户 " + cross
                + " 行 ⇒ 两条判据均按预期变红：\n    ③ " + e1.getMessage() + "\n    分辨力 " + e2.getMessage());
    }

    // ═══════════════════ 助手 ═══════════════════

    private String storedSql(String viewName) {
        return scalarStr("SELECT sql_template FROM component_sql_view WHERE sql_view_name = ?1", viewName);
    }

    /** 目标单的整单闭包 —— 与 {@code 问题说明.md §②} 的复现 SQL 同一口径。 */
    private List<String> quotationClosure() {
        return strList("""
                WITH roots AS (SELECT DISTINCT product_part_no_snapshot pn FROM quotation_line_item
                                WHERE quotation_id = CAST(?1 AS uuid)),
                     closure AS (SELECT pn FROM roots
                                 UNION SELECT b.input_material_no FROM ds_quote_material_bom b
                                         JOIN roots r ON r.pn = b.material_no)
                SELECT pn FROM closure WHERE pn IS NOT NULL ORDER BY 1
                """, SRC_QUOTATION);
    }

    /** 库里 {@code ds_quote_*} 四张锚点表出现过的全部客户号（判据用它做「按客户分解」）。 */
    private List<String> allDsCustomers() {
        return strList("""
                SELECT DISTINCT customer_no FROM (
                  SELECT customer_no FROM ds_quote_material
                  UNION SELECT customer_no FROM ds_quote_material_bom
                  UNION SELECT customer_no FROM ds_quote_element_bom
                  UNION SELECT customer_no FROM ds_quote_self_process_fee) s
                WHERE customer_no IS NOT NULL ORDER BY 1
                """);
    }

    /**
     * 绑字面量后把编译产物当子查询执行，整行转 JSON 文本返回（可直接做多重集比较）。
     *
     * @param scopeCustomer <b>行范围维度</b>：{@code WHERE} 里那条客户谓词绑谁；
     *                      传 {@code null} = 摘掉该谓词（重建「缺陷① 形态」）
     */
    private List<String> runView(String tag, String sql, String scopeCustomer, List<String> closure) {
        String wrapped = "SELECT to_jsonb(x)::text FROM (\n" + bind(sql, scopeCustomer, closure)
                + "\n) x ORDER BY 1";
        List<String> rows = strList(wrapped);
        System.out.println("[AC-3] " + tag + " → " + rows.size() + " 行");
        return rows;
    }

    private List<String> runViewColumn(String sql, String scopeCustomer, List<String> closure, String col) {
        return strList("SELECT x." + col + " FROM (\n" + bind(sql, scopeCustomer, closure) + "\n) x");
    }

    private Map<String, Long> countByCard(String sql, String scopeCustomer, List<String> closure) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] r : rowList("SELECT x.hf_part_no, count(*) FROM (\n" + bind(sql, scopeCustomer, closure)
                + "\n) x GROUP BY 1 ORDER BY 1")) {
            out.put(r[0] == null ? "<NULL>" : String.valueOf(r[0]), ((Number) r[1]).longValue());
        }
        return out;
    }

    /**
     * 字面量绑定 —— <b>把 {@code :customerCode} 的两种角色分开绑</b>。
     *
     * <h4>🚨 为什么必须分开（2026-09-09 实测，第一版判据就栽在这里）</h4>
     * {@code :customerCode} 在同一条 SQL 里承担<b>两个互不相干的角色</b>：
     * <ul>
     *   <li><b>行范围维度</b> —— {@code WHERE <锚点别名>.customer_no = :customerCode}：
     *       决定<b>取哪些行</b>。缺陷① 缺的就是它，本类研究的也只有它；</li>
     *   <li><b>取值维度</b> —— {@code LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate)}
     *       与 {@code LEFT JOIN ds_quote_customer_part ... AND dqcp.customer_no = :customerCode}
     *       （客户维度<b>形态②</b>）：决定<b>同一行上取谁的价/谁的客户料号</b>。</li>
     * </ul>
     * 第一版把两个角色一起换成 {@code c}，于是 {@code A_CUST-0001} 的「元素单价」是罗克韦尔的价、
     * {@code B} 的是正泰的价 ⇒ <b>行数对得上、内容对不上</b>，判据以「产品缺陷」的样子红。
     * 实测差异样本：同一行 {@code 元素单价} 在两次运行里分别是 {@code 3000.0000} 与 {@code null}。
     * <p>⇒ 本方法只让<b>行范围维度</b>动，取值维度一律钉死在 {@link S3FixtureBase#CUSTOMER}。
     * 这样「按客户分解求并集」才真的只在分解<b>行的归属</b>。
     *
     * <p>🚨 绑完<b>硬检查不许再有未绑定的 {@code :xxx}</b> —— 否则查询会因别的原因报错或返 0 行，
     * 而我们会把它读成「谓词生效了」。
     */
    private String bind(String sql, String scopeCustomer, List<String> closure) {
        // ① 行范围维度：只动 WHERE 里的那些
        StringBuilder sb = new StringBuilder();
        Matcher m = CUST_PRED.matcher(sql);
        int last = 0;
        int touched = 0;
        while (m.find()) {
            String kw = lastClauseKeyword(sql.substring(0, m.start()));
            sb.append(sql, last, m.start());
            if ("WHERE".equalsIgnoreCase(kw)) {
                sb.append(scopeCustomer == null
                        ? "TRUE"
                        : m.group(1) + ".customer_no = '" + scopeCustomer.replace("'", "''") + "'");
                touched++;
            } else {
                sb.append(m.group());
            }
            last = m.end();
        }
        sb.append(sql.substring(last));
        assertTrue(touched > 0, "绑定失败：WHERE 里一处客户谓词都没匹到 ⇒ scopeCustomer 参数无效，"
                + "所有运行会返回同一结果集，判据空跑。SQL=\n" + sql);

        // ② 取值维度：其余 :customerCode 一律钉死在目标客户
        String s = sb.toString()
                .replace(":customerCode", "'" + CUSTOMER.replace("'", "''") + "'")
                .replace(":total_material_no", pgArrayLiteral(closure))
                .replace(":priceBaseDate", "CURRENT_DATE");
        String leftover = firstUnbound(s);
        assertTrue(leftover == null, "编译产物里还有未绑定的占位符 `" + leftover
                + "`，本片只绑 :customerCode / :total_material_no / :priceBaseDate。"
                + "\n  🚫 不许带着它硬跑 —— 报错或 0 行会被误读成『谓词生效』。SQL=\n" + s);
        String t = s.strip();
        return t.endsWith(";") ? t.substring(0, t.length() - 1) : t;
    }

    private static String firstUnbound(String sql) {
        Matcher m = Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)").matcher(sql);
        return m.find() ? ":" + m.group(1) : null;
    }

    private static String pgArrayLiteral(List<String> xs) {
        if (xs == null || xs.isEmpty()) {
            return "ARRAY[]::text[]";
        }
        StringBuilder sb = new StringBuilder("ARRAY[");
        for (int i = 0; i < xs.size(); i++) {
            sb.append(i == 0 ? "" : ",").append("'").append(xs.get(i).replace("'", "''")).append("'");
        }
        return sb.append("]::text[]").toString();
    }

    /** 给命名参数用的 PG 数组字面量（{@code {a,b}} 形式）。 */
    private static String pgArray(List<String> xs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < xs.size(); i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(xs.get(i).replace("\"", "\\\"")).append('"');
        }
        return sb.append("}").toString();
    }

    private static String firstDiff(List<String> a, List<String> b) {
        List<String> onlyA = new ArrayList<>(a);
        b.forEach(onlyA::remove);
        List<String> onlyB = new ArrayList<>(b);
        a.forEach(onlyB::remove);
        return "\n    仅在缺陷①形态: " + onlyA.subList(0, Math.min(3, onlyA.size()))
             + "\n    仅在按客户并集: " + onlyB.subList(0, Math.min(3, onlyB.size()));
    }
}
