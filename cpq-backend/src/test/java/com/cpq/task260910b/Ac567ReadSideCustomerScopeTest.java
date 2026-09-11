package com.cpq.task260910b;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260910 · 分片 S-B · <b>{@code TC-B1}（AC-5）/ {@code TC-B2}（AC-6）/ {@code TC-B3}（AC-7）</b>
 * —— 读取侧切表 + 客户维度。
 *
 * <p>断言逐条指回 {@code 需求文档.md §③ S-2}；请求形状指回 {@code api.md §2.1 / §2.2}。
 * 🚫 未读实现代码。
 */
@QuarkusTest
@DisplayName("S-B · AC-5/6/7 读取侧切表与客户维度")
public class Ac567ReadSideCustomerScopeTest extends SbBase {

    @BeforeEach
    void seed() {
        seedReadSideFixtures();
    }

    // ══════════════════════════ TC-B1 · AC-5 ══════════════════════════

    /**
     * <b>AC-5 原文（🔴 锚点已按 {@code D-16} 换）</b>：「前置：{@code CUST-0004} 的报价单，
     * 选配 → 添加配件 → 零件 → 已有。操作：搜索框输入 <b>{@code S0013}</b>（品名「导电排C」）。
     * 断言：返回 <b>≥1 条</b>且含 {@code S0013}。」
     *
     * <h3>🔑 可证伪性（AC 原文点名，本用例先自证）</h3>
     * {@code S0013} 在老表 {@code material_master} 里 {@code count(*)=0}
     * ⇒ <b>走老 SQL 必然命中 0 条</b>，切表后才命中。
     * ⇒ 不先证明这一点，本用例就退化成「搜得到自己库里的东西」，切没切表都绿。
     *
     * <h3>上一轮（作废）与本轮的差异</h3>
     * 上一轮用 {@code T260907M-AC17ANCHOR} 且以它所属的 {@code CUST-0001} 发起搜索
     * （因为该料号只挂 {@code CUST-0001}，按 AC-5 字面的 {@code CUST-0004} 会与 AC-7 互斥）。
     * {@code D-16} 换锚点后，AC-5 的前置客户与搜索客户<b>都是 {@code CUST-0004}</b>，
     * 不再需要任何「自洽读法」的让步；该老料号转为 {@link #tcB3c_ac7_netAnchorCrossCustomerMiss}
     * 的只读反向样本。
     */
    @Test
    @DisplayName("TC-B1 · AC-5：以 CUST-0004 搜 S0013 命中（老表 material_master 里 0 行 ⇒ 老 SQL 必然 0 命中）")
    void tcB1_ac5_searchPartsHitsNewTable() {
        // ── ① 前提自证：该料号在新表有（本客户下）、老表没有 ──
        long inNewThisCust = scalarLong("SELECT count(*) FROM ds_quote_material "
                + "WHERE material_no=?1 AND customer_no=?2", AC5_ANCHOR, AC5_ANCHOR_CUST);
        long inOld = tableExists("material_master")
                ? scalarLong("SELECT count(*) FROM material_master WHERE material_no=?1", AC5_ANCHOR) : -1L;
        String name = scalarStr("SELECT material_name FROM ds_quote_material "
                + "WHERE material_no=?1 AND customer_no=?2", AC5_ANCHOR, AC5_ANCHOR_CUST);
        System.out.println("[TC-B1 前提] " + AC5_ANCHOR + "@" + AC5_ANCHOR_CUST
                + " ds_quote_material=" + inNewThisCust + " 行（material_name=" + name
                + "）; material_master=" + inOld + " 行（期望 0 = 老 SQL 必然 0 命中）");
        assertEquals(1L, inNewThisCust, "环境前提已变：" + AC5_ANCHOR + " 应在 ds_quote_material 的 "
                + AC5_ANCHOR_CUST + " 下恰好 1 行（D-16 实测）。前提不成立时本用例结论无效，"
                + "🚫 不许读成产品缺陷。");
        assertEquals(0L, inOld, "🚨 可证伪性失效：" + AC5_ANCHOR + " 竟然也在老表 material_master 里（"
                + inOld + " 行）⇒ 「切表后才能搜到」不再可证伪，本用例变成恒真（D-16 的前提被推翻）。");

        // ── ②「老表全体常态」加固：该客户下料号在 material_master 的命中数应为 0（D-16 的 2672 口径）──
        if (inOld >= 0) {
            long custPartsInNew = scalarLong("SELECT count(DISTINCT material_no) FROM ds_quote_material "
                    + "WHERE customer_no=?1", AC5_ANCHOR_CUST);
            long custPartsInOld = scalarLong("SELECT count(DISTINCT m.material_no) FROM ds_quote_material m "
                    + "JOIN material_master mm ON mm.material_no = m.material_no WHERE m.customer_no=?1",
                    AC5_ANCHOR_CUST);
            System.out.println("[TC-B1 锚点稳健性] " + AC5_ANCHOR_CUST + " 在 ds_quote_material 有 "
                    + custPartsInNew + " 个料号，其中在老表 material_master 也存在的 = " + custPartsInOld
                    + "（D-16：全体 2672 个都不存在 ⇒ 期望 0）");
        }

        // ── ③ AC-5 主断言：以 CUST-0004 搜 S0013 ⇒ ≥1 条且含它 ──
        Response res = searchParts(AC5_ANCHOR_CUST, AC5_ANCHOR);
        assertReachedBusinessLayer(res, "AC-5 search-parts(" + AC5_ANCHOR_CUST + ")");
        assertEquals(200, res.statusCode(), "AC-5：search-parts 应 200，实际=" + res.statusCode()
                + " body=" + trunc(res.asString()));
        List<String> partNos = partNosOf(res);
        System.out.println("[TC-B1 实际] customerNo=" + AC5_ANCHOR_CUST + " q=" + AC5_ANCHOR
                + " → " + partNos.size() + " 条：" + partNos);
        System.out.println("[TC-B1 响应原文] " + trunc(res.asString()));
        assertNonEmpty(partNos, "AC-5：以 " + AC5_ANCHOR_CUST + " 搜 " + AC5_ANCHOR);
        assertTrue(partNos.contains(AC5_ANCHOR),
                "AC-5：返回结果里应含 " + AC5_ANCHOR + "，实际=" + partNos);

        // ── ④ 🧪 证伪实验（同一次运行内，同一端点同一 helper）：
        //    换一个<b>库里必然不存在</b>的关键词 ⇒ 必须 0 条，且 assertNonEmpty 这道守卫必须<b>硬失败</b>。
        //    不做这一步，「搜到了 S0013」可能只是端点对任何 q 都返回全量（关键词没生效）⇒ AC-5 变成恒真。
        String ghost = PREFIX + "GHOST-" + RUN_ID;
        assertEquals(0L, scalarLong("SELECT count(*) FROM ds_quote_material WHERE material_no=?1", ghost),
                "证伪实验前提：" + ghost + " 必须在库里不存在");
        Response none = searchParts(AC5_ANCHOR_CUST, ghost);
        assertEquals(200, none.statusCode(), "证伪实验：应 200 返空数组，body=" + trunc(none.asString()));
        List<String> nonePartNos = partNosOf(none);
        System.out.println("[TC-B1 证伪] customerNo=" + AC5_ANCHOR_CUST + " q=" + ghost
                + " → " + nonePartNos.size() + " 条：" + nonePartNos + "（期望 0 ⇒ 关键词确实生效）");
        assertEquals(0, nonePartNos.size(), "证伪实验：搜一个不存在的料号应返 0 条，实际=" + nonePartNos
                + "\n  ⇒ 返非空说明端点对任意关键词都返数据，AC-5 的「搜到了」不是证据。");
        AssertionError fired = assertThrows(AssertionError.class,
                () -> assertNonEmpty(nonePartNos, "证伪实验·空结果守卫"),
                "🚨 证伪失败：assertNonEmpty 对空列表没有硬失败 ⇒ 本用例的非空守卫压根没接上"
                + "（首次 PASS 证明不了它生效，testing.md §4.4）");
        System.out.println("[TC-B1 证伪·守卫已响] " + fired.getMessage().split("\n")[0]);
    }

    // ══════════════════════════ TC-B2 · AC-6 ══════════════════════════

    /**
     * <b>AC-6 原文</b>：「分别以 {@code CUST-0001} 和 {@code CUST-0004} 打开外购件候选，不输关键词。
     * ① 每个客户各看到 <b>5 条</b>（不是 10 条，🚫 不许出现同一料号两行）；② {@code total} 显示 5；
     * ③ 🔑 阴性对照：以 {@code CUST-0004} 打开时，结果集里<b>不含</b>任何只属于 {@code CUST-0001} 的料号」。
     *
     * <h3>为什么不断言现网的「5」（{@code test.md §2}）</h3>
     * 「5 条」是<b>全局计数</b>：S-A 片造一个 {@code T260910A-OUT1} 外购件就把它打红，
     * 而且<b>红得像业务回归</b>。⇒ 按 {@code test.md §2} 把断言收窄到本片自造的
     * {@code T260910B-} 对照上：<b>「同一料号挂两个客户 ⇒ 每客户各 1 条、不出双份」</b>
     * —— 这才是 AC-6 的判据本身，且与现网条数无关。
     *
     * <h3>证伪前提（FT-2 的可执行部分）</h3>
     * {@link #assertDualCustomerPremise()} 先用<b>不带客户谓词</b>的同一数据源反证「不过滤就是 2 行」。
     * 不做这一步，若现网恰好每料号只挂 1 客户，「每客户各 1 条」<b>恒真</b>。
     */
    @Test
    @DisplayName("TC-B2 · AC-6：外购件候选按客户隔离（同料号挂两客户 ⇒ 各 1 条、不出双份 + 阴性对照）")
    void tcB2_ac6_outsourcedCustomerIsolation() {
        // ── ① 证伪前提：不过滤客户时 OUT_DUAL 出双份（FT-2） ──
        assertDualCustomerPremise();

        // ── ② 两个客户各拉一次候选 ──
        Response r1 = outsourcedParts(CUST_1, null);
        Response r4 = outsourcedParts(CUST_4, null);
        assertReachedBusinessLayer(r1, "AC-6 outsourced-parts(" + CUST_1 + ")");
        assertReachedBusinessLayer(r4, "AC-6 outsourced-parts(" + CUST_4 + ")");
        assertEquals(200, r1.statusCode(), "AC-6：" + CUST_1 + " 候选应 200，body=" + trunc(r1.asString()));
        assertEquals(200, r4.statusCode(), "AC-6：" + CUST_4 + " 候选应 200，body=" + trunc(r4.asString()));

        List<String> l1 = materialNosOf(r1);
        List<String> l4 = materialNosOf(r4);
        System.out.println("[TC-B2 实际] " + CUST_1 + " total=" + totalOf(r1) + " items=" + l1);
        System.out.println("[TC-B2 实际] " + CUST_4 + " total=" + totalOf(r4) + " items=" + l4);
        assertNonEmpty(l1, "AC-6：" + CUST_1 + " 的外购件候选");
        assertNonEmpty(l4, "AC-6：" + CUST_4 + " 的外购件候选");

        // ── ③ AC-6①：同一料号不出双份（收窄到本片自造对照）──
        assertEquals(1L, count(l1, OUT_DUAL), "AC-6①：" + CUST_1 + " 的候选里 " + OUT_DUAL
                + " 应恰好出现 1 次（🚫 不许两行）。实际列表=" + l1);
        assertEquals(1L, count(l4, OUT_DUAL), "AC-6①：" + CUST_4 + " 的候选里 " + OUT_DUAL
                + " 应恰好出现 1 次（🚫 不许两行）。实际列表=" + l4);

        // ── ④ AC-6① 加固：本片全部对照料号在两侧都不许重复 ──
        List<String> mine1 = l1.stream().filter(s -> s != null && s.startsWith(PREFIX)).toList();
        List<String> mine4 = l4.stream().filter(s -> s != null && s.startsWith(PREFIX)).toList();
        assertEquals(mine1.size(), mine1.stream().distinct().count(),
                "AC-6①：" + CUST_1 + " 侧本片料号出现重复行 ⇒ 客户维度没收窄。实际=" + mine1);
        assertEquals(mine4.size(), mine4.stream().distinct().count(),
                "AC-6①：" + CUST_4 + " 侧本片料号出现重复行 ⇒ 客户维度没收窄。实际=" + mine4);

        // ── ⑤ AC-6③ 阴性对照：CUST-0004 侧不含只属于 CUST-0001 的料号 ──
        assertFalse(l4.contains(OUT_C1ONLY), "AC-6③【客户隔离被破】" + CUST_4
                + " 的外购件候选里出现了只属于 " + CUST_1 + " 的 " + OUT_C1ONLY
                + " ⇒ D-2 未落地。实际列表=" + l4);
        assertTrue(l1.contains(OUT_C1ONLY), "AC-6③ 阳性对照：" + OUT_C1ONLY + " 必须在它自己客户 "
                + CUST_1 + " 的候选里出现 —— 不出现说明「查不到」是别的原因（例如筛选条件把它整个排除了），"
                + "那样阴性对照就是零证据。实际列表=" + l1);

        // ── ⑥ AC-6②：total 与本客户可见条数自洽（🚫 不断言现网的 5）──
        Integer t1 = totalOf(r1);
        Integer t4 = totalOf(r4);
        assertNotNull(t1, "AC-6②：响应应含 total（api.md §2.2 返回结构）");
        assertNotNull(t4, "AC-6②：响应应含 total（api.md §2.2 返回结构）");
        long db1 = scalarLong("SELECT count(*) FROM ds_quote_material "
                + "WHERE material_type='外购件' AND customer_no=?1", CUST_1);
        long db4 = scalarLong("SELECT count(*) FROM ds_quote_material "
                + "WHERE material_type='外购件' AND customer_no=?1", CUST_4);
        System.out.println("[TC-B2 total 自洽] " + CUST_1 + " api=" + t1 + " db=" + db1
                + " ; " + CUST_4 + " api=" + t4 + " db=" + db4);
        assertEquals(db1, t1.longValue(), "AC-6②：" + CUST_1 + " 的 total 应等于该客户在 ds_quote_material "
                + "的外购件行数（api.md §2.2 数据源）。api=" + t1 + " db=" + db1);
        assertEquals(db4, t4.longValue(), "AC-6②：" + CUST_4 + " 的 total 应等于该客户在 ds_quote_material "
                + "的外购件行数（api.md §2.2 数据源）。api=" + t4 + " db=" + db4);
    }

    /**
     * {@code api.md §3}：两个候选端点缺 {@code customerNo} ⇒ <b>400</b>（🚫 不许静默跨客户查，D-2）。
     * <p>这是 AC-6 / AC-7 的守卫：不拦住「不传客户」，前端漏传时会静默退回跨客户，
     * 而症状是「别客户的料号出现在候选里」—— 与数据本来如此不可区分。
     */
    @Test
    @DisplayName("TC-B2b · api.md §3：外购件候选缺 customerNo ⇒ 400，🚫 不许静默跨客户返回")
    void tcB2b_outsourcedWithoutCustomerNoMustBeRejected() {
        Response res = outsourcedParts(null, null);
        assertReachedBusinessLayer(res, "缺 customerNo 的 outsourced-parts");
        System.out.println("[TC-B2b 实际] 不带 customerNo → HTTP " + res.statusCode()
                + " body=" + trunc(res.asString()));
        if (res.statusCode() == 200) {
            List<String> all = materialNosOf(res);
            long dual = count(all, OUT_DUAL);
            throw new AssertionError("api.md §3：缺 customerNo 应返 400 `CUSTOMER_NO_REQUIRED`，"
                    + "实际 200 且返回 " + all.size() + " 条（其中 " + OUT_DUAL + " 出现 " + dual
                    + " 次）⇒ 静默跨客户查询仍然可达，D-2 未闭合。实际列表=" + all);
        }
        assertEquals(400, res.statusCode(), "api.md §3：缺 customerNo 应 400，实际=" + res.statusCode()
                + " body=" + trunc(res.asString()));
    }

    // ══════════════════════════ TC-B3 · AC-7 ══════════════════════════

    /**
     * <b>AC-7 原文</b>：「造一个只在 {@code CUST-0001} 下存在的料号 ⇒ 以 {@code CUST-0004}
     * 的报价单搜索它 ⇒ 返回 <b>0 条</b>（D-2 的语义变更 —— 现状会返回 1 条）」。
     *
     * <p>🔑 「返回 0 条」是<b>阴性</b>断言，天生有假绿风险（端点坏了 / 关键词没生效
     * ⇒ 恒返 0 ⇒ 照样绿）。⇒ 本用例配<b>两枪阳性对照</b>：
     * ① 同一料号以 {@code CUST-0001} 搜<b>必须命中</b>；
     * ② 同一客户 {@code CUST-0004} 搜自己的料号 {@link #MULTI} <b>必须命中</b>。
     * 两枪都响，才能证明「0 条」是客户过滤的结果，不是搜索坏了。
     */
    @Test
    @DisplayName("TC-B3 · AC-7：已有零件搜索也按客户过滤（跨客户 ⇒ 0 条 + 两枪阳性对照）")
    void tcB3_ac7_searchPartsFiltersByCustomer() {
        // ── 阳性对照①：料号所属客户能搜到它 ──
        Response own = searchParts(CUST_1, C1ONLY);
        assertReachedBusinessLayer(own, "AC-7 阳性对照① search-parts(" + CUST_1 + ")");
        assertEquals(200, own.statusCode(), "阳性对照①应 200，body=" + trunc(own.asString()));
        List<String> ownList = partNosOf(own);
        System.out.println("[TC-B3 阳性①] customerNo=" + CUST_1 + " q=" + C1ONLY + " → " + ownList);
        assertNonEmpty(ownList, "AC-7 阳性对照①：以 " + CUST_1 + " 搜自己的 " + C1ONLY);
        assertTrue(ownList.contains(C1ONLY), "AC-7 阳性对照①：以 " + CUST_1 + " 搜应命中 " + C1ONLY
                + "，实际=" + ownList + "\n  ⇒ 命中不了说明搜索本身有问题，此时下面的「0 条」是零证据。");

        // ── 阳性对照②：被测客户能搜到属于自己的料号（证明该客户视角的搜索是活的）──
        Response other = searchParts(CUST_4, MULTI);
        assertReachedBusinessLayer(other, "AC-7 阳性对照② search-parts(" + CUST_4 + ")");
        assertEquals(200, other.statusCode(), "阳性对照②应 200，body=" + trunc(other.asString()));
        List<String> otherList = partNosOf(other);
        System.out.println("[TC-B3 阳性②] customerNo=" + CUST_4 + " q=" + MULTI + " → " + otherList);
        assertNonEmpty(otherList, "AC-7 阳性对照②：以 " + CUST_4 + " 搜自己的 " + MULTI);
        assertTrue(otherList.contains(MULTI), "AC-7 阳性对照②：以 " + CUST_4 + " 搜应命中 " + MULTI
                + "，实际=" + otherList);

        // ── AC-7 主断言：跨客户 ⇒ 0 条 ──
        Response cross = searchParts(CUST_4, C1ONLY);
        assertReachedBusinessLayer(cross, "AC-7 主断言 search-parts(" + CUST_4 + ", " + C1ONLY + ")");
        assertEquals(200, cross.statusCode(), "AC-7：应 200 返空数组（不是错误码），body="
                + trunc(cross.asString()));
        List<String> crossList = partNosOf(cross);
        System.out.println("[TC-B3 主断言] customerNo=" + CUST_4 + " q=" + C1ONLY
                + " → " + crossList.size() + " 条：" + crossList);
        assertEquals(0, crossList.size(), "AC-7【客户隔离被破】以 " + CUST_4 + " 搜只属于 " + CUST_1
                + " 的 " + C1ONLY + " 应返 0 条，实际返 " + crossList.size() + " 条=" + crossList
                + "\n  ⇒ D-2 的语义变更未落地（现状即返 1 条）。");
    }

    /** {@code api.md §3}：{@code search-parts} 缺 {@code customerNo} ⇒ 400（同 TC-B2b 的理由）。 */
    @Test
    @DisplayName("TC-B3b · api.md §3：search-parts 缺 customerNo ⇒ 400，🚫 不许静默跨客户返回")
    void tcB3b_searchPartsWithoutCustomerNoMustBeRejected() {
        Response res = searchPartsLegacy(C1ONLY);
        assertReachedBusinessLayer(res, "缺 customerNo 的 search-parts");
        System.out.println("[TC-B3b 实际] 不带 customerNo q=" + C1ONLY + " → HTTP " + res.statusCode()
                + " body=" + trunc(res.asString()));
        if (res.statusCode() == 200) {
            List<String> all = partNosOf(res);
            throw new AssertionError("api.md §3：缺 customerNo 应返 400 `CUSTOMER_NO_REQUIRED`，"
                    + "实际 200 且返回 " + all.size() + " 条=" + all
                    + " ⇒ 静默跨客户搜索仍然可达，D-2 未闭合。");
        }
        assertEquals(400, res.statusCode(), "api.md §3：缺 customerNo 应 400，实际=" + res.statusCode()
                + " body=" + trunc(res.asString()));
    }

    /**
     * <b>AC-7 加固 · 现网只读样本</b>（{@code D-16} 明确「{@code T260907M-AC17ANCHOR} 逐字不动，
     * 实测仍只挂 {@code CUST-0001}」）。
     *
     * <p>本用例把该料号当成<b>现网版的 {@code C1ONLY}</b>：以 {@code CUST-0004} 搜它必须 0 条。
     * 价值在于「不依赖本片造数」—— 若造数链路某天静默失效，{@link #tcB3_ac7_searchPartsFiltersByCustomer}
     * 会因为料号不存在而变成零证据，而本用例用的是一条长期存在的现网数据。
     * 🚫 只读：不造、不改、不删这条数据。
     */
    @Test
    @DisplayName("TC-B3c · AC-7 加固：现网只挂 CUST-0001 的料号，以 CUST-0004 搜 ⇒ 0 条（配阳性对照）")
    void tcB3c_ac7_netAnchorCrossCustomerMiss() {
        List<String> owners = strList("SELECT customer_no FROM ds_quote_material WHERE material_no=?1 "
                + "ORDER BY customer_no", AC7_NET_C1ONLY);
        System.out.println("[TC-B3c 前提] " + AC7_NET_C1ONLY + " 在 ds_quote_material 的客户 = " + owners);
        if (!List.of(CUST_1).equals(owners)) {
            System.out.println("[TC-B3c 跳过] 环境前提已变：该料号现在挂在 " + owners
                    + "（D-16 实测应只挂 " + CUST_1 + "）⇒ 本加固用例无样本，"
                    + "AC-7 的结论仍由 tcB3（自造 C1ONLY）承担");
            return;
        }

        // 阳性对照：它自己的客户能搜到（不响就说明「0 条」是搜索坏了，不是客户过滤）
        Response own = searchParts(CUST_1, AC7_NET_C1ONLY);
        assertReachedBusinessLayer(own, "AC-7 加固阳性对照 search-parts(" + CUST_1 + ")");
        assertEquals(200, own.statusCode(), "阳性对照应 200，body=" + trunc(own.asString()));
        List<String> ownList = partNosOf(own);
        System.out.println("[TC-B3c 阳性] customerNo=" + CUST_1 + " q=" + AC7_NET_C1ONLY + " → " + ownList);
        assertNonEmpty(ownList, "AC-7 加固阳性对照：以 " + CUST_1 + " 搜 " + AC7_NET_C1ONLY);
        assertTrue(ownList.contains(AC7_NET_C1ONLY), "AC-7 加固阳性对照：以 " + CUST_1
                + " 搜应命中 " + AC7_NET_C1ONLY + "，实际=" + ownList);

        // 主断言：跨客户 0 条
        Response cross = searchParts(CUST_4, AC7_NET_C1ONLY);
        assertReachedBusinessLayer(cross, "AC-7 加固主断言 search-parts(" + CUST_4 + ")");
        assertEquals(200, cross.statusCode(), "应 200 返空数组，body=" + trunc(cross.asString()));
        List<String> crossList = partNosOf(cross);
        System.out.println("[TC-B3c 主断言] customerNo=" + CUST_4 + " q=" + AC7_NET_C1ONLY
                + " → " + crossList.size() + " 条：" + crossList);
        assertEquals(0, crossList.size(), "AC-7【客户隔离被破】以 " + CUST_4 + " 搜只挂 " + CUST_1
                + " 的现网料号 " + AC7_NET_C1ONLY + " 应返 0 条，实际=" + crossList);
    }

    // ══════════════════════════ 响应解析（形状取自 api.md §2）══════════════════════════

    /** {@code api.md §2.1}：裸数组，元素含 {@code hfPartNo}。 */
    protected List<String> partNosOf(Response res) {
        JsonPath jp = res.jsonPath();
        List<String> direct = jp.getList("hfPartNo", String.class);
        if (direct != null && !direct.isEmpty()) return direct;
        // 兼容「被 {code,data} 信封包住」的形状 —— 只为读数，不改断言口径
        List<String> wrapped = jp.getList("data.hfPartNo", String.class);
        return wrapped == null ? List.of() : wrapped;
    }

    /** {@code api.md §2.2}：{@code {total, items:[{materialNo,...}]}}。 */
    protected List<String> materialNosOf(Response res) {
        JsonPath jp = res.jsonPath();
        List<String> direct = jp.getList("items.materialNo", String.class);
        if (direct != null && !direct.isEmpty()) return direct;
        List<String> wrapped = jp.getList("data.items.materialNo", String.class);
        return wrapped == null ? List.of() : wrapped;
    }

    protected Integer totalOf(Response res) {
        JsonPath jp = res.jsonPath();
        Integer t = jp.get("total");
        return t != null ? t : jp.get("data.total");
    }

    protected long count(List<String> l, String v) {
        return l.stream().filter(v::equals).count();
    }
}
