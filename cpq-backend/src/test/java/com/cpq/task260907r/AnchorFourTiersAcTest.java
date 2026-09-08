package com.cpq.task260907r;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-20a / T-20b / T-20c —— AC-20 双锚的三条支路</b>
 * （闸门 A0-1 裁决：{@code origin_id} 为主 + 整行指纹兜底）
 *
 * <h3>🚨 夹具一律自造，🚫 不许用现网样本取样</h3>
 * 跨版与「锚不上」都依赖「同一料号被多张单碰过」这个分布，而<b>现网恒为每个料号 1 张单 / 1 个客户</b>。
 * 📌 判据落在一个「现网恒为某值」的维度上时，<b>它不是弱证据，是零证据</b> ——
 * 因为它连「可能发现问题」的概率都没有。
 * （同型实证：并发会话验兼容视图「35 行 vs 35 行逐行相同」判通过，实为扇出恒为 1 导致的必然通过。）
 * ⇒ 本类每条用例都<b>自己把组升上去</b>，轴值每轮新 UUID，不跨轮复用。
 *
 * <h3>🔑 三条支路各自「唯一不能省的那一行断言」</h3>
 * <ul>
 *   <li><b>a 同版</b>：改第 2 行 → <b>第 1/3 行逐字未变</b>。
 *       只验「第 2 行变了」在「三行被改成同一个值」时照样成立 ⇒ 不是判据。</li>
 *   <li><b>b 跨版</b>：先证明 <b>{@code origin_id} 确已全部失效</b>（升版前后 id 集合交集为空，
 *       且 {@code _record.origin_id} 在当前主表里一个都查不到），<b>再</b>谈「指纹重锚成功」。
 *       🔑 少了这一枪，「重锚生效了」与「压根没走到重锚、恰好 {@code origin_id} 就对上了」<b>在结果上分不开</b>
 *       —— 这就是 {@code E-3} 要的那个对照，提前到这里做。</li>
 *   <li><b>c 锚不上</b>：确认后 <b>原行必须还在</b>。
 *       只验「新值写进去了」在原行被删之后照样成立 ⇒ 不是判据。</li>
 * </ul>
 */
@QuarkusTest
@io.quarkus.test.junit.TestProfile(AnchorLogProfile.class)
@DisplayName("AC-20 · 双锚四层（origin_id / 指纹 / 粒度列 / NO_ANCHOR）")
class AnchorFourTiersAcTest extends Task260907RBase {

    @AfterEach
    void tearDown() {
        cleanupOwnFixtures();
    }

    // ═══════════════════════ T-20a · 同版 ═══════════════════════

    @Test
    @DisplayName("T-20a · 同版：按 origin_id 对位，改第 2 行，第 1/3 行逐字未变")
    void t20a_sameVersionAnchorsByOriginId() {
        requireRecordLayer();
        String mat = axis("A20a");
        seedMainViaCreatedOrder("20aSeed", mat, List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "20.0", "2.2"),
                new EbomRow(3, PREFIX + "E3", "30.0", "3.3")));

        Map<Integer, String> before = ebomBusinessRowsBySeq(mat);
        assertEquals(3, before.size(), "前置：主表该组应 3 行，实际 " + before);
        int verBefore = maxVer(mat);

        // 报价单表征同样 3 行，只把第 2 行改值
        Fx fx = newSubmittedOrder("20a", mat, List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "77.7", "2.2"),
                new EbomRow(3, PREFIX + "E3", "30.0", "3.3")));

        JsonNode g = group(fx, mat);
        assertFalse(g.path("crossVersion").asBoolean(true),
                "T-20a 前置：本条是**同版**支路，crossVersion 应为 false。group=" + g);
        assertEquals(0, g.path("unanchoredRows").size(),
                "T-20a：同版下三行都应按 origin_id 对上，不该有锚不上的行。group=" + g);
        System.out.println("[T-20a] 预览组 = " + g);

        approveWithPreview(fx, "20a");

        Map<Integer, String> after = ebomBusinessRowsBySeq(mat);
        assertEquals(before.keySet(), after.keySet(),
                "T-20a：行集合不应变化。before=" + before.keySet() + " after=" + after.keySet());
        assertEquals(3, after.size(), "T-20a：整组仍应 3 行，实际 " + after.size() + " → " + after);

        // 🔑 唯一不能省的那条：第 1/3 行逐字未变
        assertEquals(before.get(1), after.get(1),
                "T-20a：第 1 行未被改动，必须逐字未变 —— 只验「第 2 行变了」在三行被改成同值时照样成立，"
                        + "所以本条才是判据。before=" + before.get(1) + " after=" + after.get(1));
        assertEquals(before.get(3), after.get(3),
                "T-20a：第 3 行未被改动，必须逐字未变。before=" + before.get(3) + " after=" + after.get(3));
        assertTrue(String.valueOf(after.get(2)).contains("77.7"),
                "T-20a 反向：第 2 行应写入 77.7（防修成什么都不写），实际 " + after.get(2));
        assertEquals(verBefore + 1, maxVer(mat), "T-20a：应升一版");
    }

    // ═══════════════════════ T-20b · 跨版重锚 ═══════════════════════

    @Test
    @DisplayName("T-20b · 跨版：先证 origin_id 全失效，再验指纹重锚落在内容对应的行上")
    void t20b_crossVersionReanchorsByFingerprint() {
        requireRecordLayer();
        String mat = axis("A20b");
        seedMainViaCreatedOrder("20bSeed", mat, List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "20.0", "2.2"),
                new EbomRow(3, PREFIX + "E3", "30.0", "3.3")));

        // ── B 先基于 v1 拍快照（提交但先不确认）
        // 🕰️ 修用例设计：B **不表征第 1 行**。
        //    首版让 B 的 rowData 也带第 1 行（值 10.0），于是 B 表征了它 ——
        //    那么 B 把它覆盖回 10.0 **是正确的 patch 语义**，不是缺陷。
        //    AC-10② 要验的是「A 改过、**B 没表征**的那一部分，在新版里仍是 A 的值」，
        //    ⇒ B 必须**不含**第 1 行，它才是「未表征」。
        Fx b = newSubmittedOrder("20bB", mat, List.of(
                new EbomRow(2, PREFIX + "E2", "88.8", "2.2"),      // B 只表征第 2、3 行
                new EbomRow(3, PREFIX + "E3", "30.0", "3.3")));
        assertEquals(1, baseVer(b, mat), "T-20b 前置：B 的快照基版应为 v1");
        Set<Long> idsBefore = idSet(EBOM, "material_no", mat);
        Set<Long> bOriginIds = recordOriginIds(b, mat);
        assertFixtureNonEmpty(bOriginIds.size(), "B 的 _record.origin_id 集合");

        // ── A 先升版：只改第 1 行（🚫 不碰 B 要改的第 2 行，否则就变成 20c 的场景了）
        Fx a = newSubmittedOrder("20bA", mat, List.of(
                new EbomRow(1, PREFIX + "E1", "99.9", "1.1"),
                new EbomRow(2, PREFIX + "E2", "20.0", "2.2"),
                new EbomRow(3, PREFIX + "E3", "30.0", "3.3")));
        approveWithPreview(a, "20bA");
        assertEquals(2, maxVer(mat), "T-20b 前置：A 确认后应升到 v2");

        // ══ 🔑 E-3 的对照，提前到这里：先证明 origin_id 真的全失效 ══
        Set<Long> idsAfter = idSet(EBOM, "material_no", mat);
        assertIdSetsDisjoint(idsBefore, idsAfter, "跨版支路的料号组 " + mat);
        Set<Long> stillAlive = new LinkedHashSet<>(bOriginIds);
        stillAlive.retainAll(idsAfter);
        assertTrue(stillAlive.isEmpty(),
                "🔑 T-20b 前置未成立：B 的 _record.origin_id " + stillAlive + " 在升版后的主表里仍然存在 "
                        + "⇒ 它照 origin_id 就能对上，根本走不到指纹兜底。"
                        + "那样的话「重锚生效了」与「压根没走到重锚」在结果上分不开，本用例证明不了任何事。"
                        + " origin_ids=" + bOriginIds + " 当前主表 ids=" + idsAfter);
        System.out.println("[T-20b] origin_id 全失效已证：_record.origin_id=" + bOriginIds
                + " ∩ 当前主表 ids=" + idsAfter + " = ∅");

        // ── B 现在确认：只能靠指纹重锚
        JsonNode g = group(b, mat);
        assertTrue(g.path("crossVersion").asBoolean(false),
                "T-20b：base(v1) != current(v2) ⇒ crossVersion 应为 true。group=" + g);
        assertEquals(0, g.path("unanchoredRows").size(),
                "T-20b：内容未被 A 改过的行应能靠指纹重锚成功，不该进「对不上」区。group=" + g);
        System.out.println("[T-20b] 预览组 = " + g);

        Map<Integer, String> before = ebomBusinessRowsBySeq(mat);
        approveWithPreview(b, "20bB");
        Map<Integer, String> after = ebomBusinessRowsBySeq(mat);

        assertEquals(3, after.size(),
                "T-20b：整组仍应 3 行（重锚成功=patch 到原行；失败=被当新增追加）。实际 " + after.size() + " → " + after);
        assertTrue(String.valueOf(after.get(2)).contains("88.8"),
                "T-20b：指纹重锚后 patch 应落在**内容对应**的第 2 行上，实际 " + after.get(2));
        // 🔑 A 改过、B 未表征改动的第 1 行，必须仍是 A 的值（同 AC-10 的判据形态）
        // 🔑 AC-10② 同型判据：A 改过、**B 未表征**的第 1 行，在新版里必须仍是 A 的值
        assertTrue(String.valueOf(after.get(1)).contains("99.9"),
                "🔑 T-20b / AC-10②：第 1 行 A 在 v2 改成 99.9 且 B **没有表征**它，"
                        + "新版里必须仍是 A 的值。实际 " + after.get(1)
                        + " ⇒ 不保留说明 B 的升版是拿 v1 整组当基底覆盖，而不是以主表当前 v2 为基底做列级 patch。"
                        + "🚫 只断言「B 写进去了」在 A 的数据被整体覆盖之后照样成立，所以本条才是判据。");
        assertEquals(before.get(3), after.get(3), "T-20b：第 3 行未被表征改动，应逐字未变");
    }

    // ═══════════════════════ T-20c · 第三层：粒度列兜底 ═══════════════════════

    /**
     * <b>T-20c（AC-20③）</b>：跨版 <b>且</b> 指纹已变，但<b>粒度列未变</b> → 粒度列兜底命中、<b>组不翻倍</b>。
     *
     * <h3>🕰️ 本条是重写的：原判据「指纹已变 ⇒ 锚不上」在四层模型下结构上不成立</h3>
     * 我上一轮按三层模型写它，实测锚上了。<b>经日志双侧证实</b>是第三层接住的，不是推断：
     * <pre>
     *   [ds-record][anchor]   粒度列兜底命中 baseRowId=… grainKey=…（整行内容不同 = 用户改过值）
     *   [ds-backfill][anchor] 粒度列兜底命中 baseRowId=…（origin_id/指纹都对不上）
     * </pre>
     * ⇒ 行为是对的：粒度列（{@code material_part_no + element_code}）没变 ⇒ 业务上就是同一行 ⇒
     * 把值 patch 到它身上，正是「以主表当前版为底做列级 patch」。
     * <b>若这里退回「按新增追加」，就是「用户改一个数就翻倍」那个 P0 换条路径复发。</b>
     */
    @Test
    @DisplayName("T-20c · 第三层：跨版+指纹变但粒度列未变 → 兜底命中、组不翻倍")
    void t20c_grainColumnFallbackHits() {
        requireRecordLayer();
        String mat = axis("A20c");
        seedMainViaCreatedOrder("20cSeed", mat, List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "20.0", "2.2")));

        // B 基于 v1 拍快照，要把第 2 行改成 88.8
        Fx b = newSubmittedOrder("20cB", mat, List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "88.8", "2.2")));
        assertEquals(1, baseVer(b, mat), "T-20c 前置：B 的快照基版应为 v1");
        Set<Long> idsBefore = idSet(EBOM, "material_no", mat);

        // A 升版并**恰好也改了第 2 行** ⇒ 指纹已变；但粒度列(material_part_no+element_code)没变
        Fx a = newSubmittedOrder("20cA", mat, List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "66.6", "2.2")));
        approveWithPreview(a, "20cA");
        assertIdSetsDisjoint(idsBefore, idSet(EBOM, "material_no", mat), "第三层的料号组 " + mat);
        assertEquals(2, maxVer(mat), "T-20c 前置：A 确认后应升到 v2");

        long rowsBefore = count("SELECT count(*) FROM " + EBOM + " WHERE material_no = '" + mat + "'");
        JsonNode g = group(b, mat);
        assertTrue(g.path("crossVersion").asBoolean(false), "T-20c：应为跨版。group=" + g);
        System.out.println("[T-20c] 预览组 = " + g);

        // ══ AC-20③ 硬要求 1：必须**看到日志**，🚫 不许靠推断 ══
        LogTap tap = LogTap.attach();
        try {
            approveWithPreview(b, "20cB");
        } finally {
            tap.detach();
        }
        assertTrue(tap.contains("粒度列兜底命中"),
                "🚨 AC-20③ 要求「断言必须看到『粒度列兜底命中』日志」以证实机制。"
                        + "本轮没捕到该行 ⇒ 要么走的不是第三层（可能是 ② 指纹重锚，见下条时序断言），"
                        + "要么日志级别没生效。捕获到的 dsrecord 日志共 " + tap.size() + " 行：" + tap.tail(12));
        System.out.println("[T-20c] 机制已证实（日志原文）：" + tap.firstMatching("粒度列兜底命中"));

        // ══ AC-20③ 硬要求 2：时序必须确实走 ③ 而不是 ② ══
        //    anchorValues 取的是拍快照那一刻的 driver 原值；若 A 的改动发生在 B 拍快照之**前**，
        //    B 存的就已经是新值、指纹本来就对得上 ②，压根轮不到 ③。
        //    ⇒ 判据：日志里那行明写「origin_id/指纹都对不上」，即 ①② 都 miss 过。
        assertTrue(tap.contains("origin_id/指纹都对不上") || tap.contains("整行内容不同"),
                "🚨 AC-20③ 要求实测确认走的是 ③ 不是 ②。日志里没有「①② 都 miss」的证据 ⇒ "
                        + "夹具时序可能让指纹本来就对得上。捕获日志：" + tap.tail(12));

        // ══ 结果：组不翻倍，patch 落在原行 ══
        long rowsAfter = count("SELECT count(*) FROM " + EBOM + " WHERE material_no = '" + mat + "'");
        Map<Integer, String> after = ebomBusinessRowsBySeq(mat);
        assertEquals(rowsBefore, rowsAfter,
                "🚨 AC-20③：粒度列兜底命中 ⇒ 应 patch 到原行，组行数不变（" + rowsBefore + "）。"
                        + "实际 " + rowsAfter + " ⇒ 变多 = 退回「按新增追加」，"
                        + "那就是「用户改一个数就翻倍」那个 P0 换条路径复发。after=" + after);
        assertTrue(String.valueOf(after.get(2)).contains("88.8"),
                "T-20c：B 的值应 patch 到粒度列对应的那一行，实际 " + after.get(2));
        assertEquals(0, g.path("unanchoredRows").size(),
                "T-20c：粒度列能兜住 ⇒ 不该进「对不上」区。group=" + g);
    }

    // ═══════════════════════ T-20d · 第四层：粒度列歧义 → NO_ANCHOR ═══════════════════════

    /**
     * <b>T-20d（AC-20④）</b>：粒度列本身<b>有歧义</b>（同组两行 grain 相同）→ 整组不认 → {@code NO_ANCHOR} 显式上报。
     *
     * <h3>为什么造「歧义」而不是「缺失」</h3>
     * 缺失型（B 的行主表根本没有）更像<b>普通新增</b>，不是 AC 说的「同一行认不出」。
     * 歧义型直接验到 {@code 需求文档 §⑥} 那句「实证这些键<b>不保证唯一</b>」。
     * 🚫 <b>不取样现网</b>（提议五）：现网虽真有重复（报价侧 {@code MATERIAL_BOM} grain 只有一列
     * {@code input_material_no}，实测 5 组重复），但判据落在现网分布上就是零证据。⇒ 自造。
     *
     * <p>🔑 {@code ELEMENT_BOM} 的 grain 实测 = {@code {material_part_no, element_code}}，
     * <b>不含 {@code item_seq}</b> ⇒ 同组内两行只差项次即撞 grain，歧义好造。
     */
    @Test
    @DisplayName("T-20d · 第四层：粒度列歧义 → NO_ANCHOR 显式上报，且原行未被静默删除")
    void t20d_grainAmbiguityFallsBackToNoAnchor() {
        requireRecordLayer();
        String mat = axis("A20d");
        String dupEl = PREFIX + "DUP";   // 两行同 element_code + 同 material_part_no ⇒ grain 相同

        seedMainViaCreatedOrder("20dSeed", mat, List.of(
                new EbomRow(1, dupEl, "10.0", "1.1"),
                new EbomRow(2, dupEl, "20.0", "2.2")));

        // 🚨 阳性对照：先证明歧义真的存在（两行 grain 相同），否则本条验的根本不是第四层
        long dupGroups = count("SELECT count(*) FROM (SELECT material_part_no, element_code "
                + "FROM " + EBOM + " WHERE material_no = '" + mat + "' "
                + "GROUP BY material_part_no, element_code HAVING count(*) > 1) s");
        assertFixtureNonEmpty(dupGroups,
                "🚨 前置未成立：夹具没造出 grain 重复的行（grain = material_part_no + element_code）⇒ "
                        + "第四层根本不会被触发，本用例会验成第三层还以为过了");
        System.out.println("[T-20d] 歧义已造出：grain 重复组数 = " + dupGroups);

        Fx b = newSubmittedOrder("20dB", mat, List.of(
                new EbomRow(1, dupEl, "88.8", "1.1"),
                new EbomRow(2, dupEl, "99.9", "2.2")));
        Set<Long> idsBefore = idSet(EBOM, "material_no", mat);

        // A 升版并改值 ⇒ origin_id 失效 + 指纹变 ⇒ 只剩粒度列，而粒度列有歧义
        Fx a = newSubmittedOrder("20dA", mat, List.of(
                new EbomRow(1, dupEl, "55.5", "1.1"),
                new EbomRow(2, dupEl, "44.4", "2.2")));
        approveWithPreview(a, "20dA");
        assertIdSetsDisjoint(idsBefore, idSet(EBOM, "material_no", mat), "第四层的料号组 " + mat);

        // ── ④ 必须进 NO_ANCHOR 并显式上报
        JsonNode g = group(b, mat);
        JsonNode un = g.path("unanchoredRows");
        System.out.println("[T-20d] 预览组 = " + g);
        assertTrue(un.isArray() && un.size() > 0,
                "🚨 AC-20④：粒度列有歧义（两侧不是「恰好一条」）⇒ 应整组不认、退回 NO_ANCHOR 并显式上报，"
                        + "🚫 不许静默处理。实际 unanchoredRows=" + un + "，group=" + g);
        for (JsonNode u : un) {
            for (String k : List.of("recordId", "originId", "baseRowFingerprint", "displayValues", "reason")) {
                assertTrue(u.has(k), "api.md §1：unanchoredRows 元素缺字段 " + k + "，实际=" + u);
            }
            assertFalse(u.path("displayValues").isEmpty(),
                    "AC-20④：displayValues 为空，财务看不出这是哪一行。实际=" + u);
        }

        // ── 🔑 原行必须还在（唯一不能省的那条）
        Map<Integer, String> before = ebomBusinessRowsBySeq(mat);
        long rowsBefore = count("SELECT count(*) FROM " + EBOM + " WHERE material_no = '" + mat + "'");
        approveWithPreview(b, "20dB");
        Map<Integer, String> after = ebomBusinessRowsBySeq(mat);
        long rowsAfter = count("SELECT count(*) FROM " + EBOM + " WHERE material_no = '" + mat + "'");
        System.out.println("[T-20d] 确认前 " + rowsBefore + " 行 " + before
                + "；确认后 " + rowsAfter + " 行 " + after);

        assertTrue(after.values().stream().anyMatch(v -> v != null && v.contains("55.5"))
                        && after.values().stream().anyMatch(v -> v != null && v.contains("44.4")),
                "🔑 AC-20④：A 在 v2 写的两行（55.5 / 44.4）必须都还在。"
                        + "🚫 只验「B 的新行写进去了」在原行被删之后照样成立，所以那不是判据。"
                        + "确认前=" + before + " 确认后=" + after);
        assertTrue(rowsAfter >= rowsBefore,
                "AC-20④：锚不上的行按新增写入，行数不应减少（" + rowsBefore + " → " + rowsAfter + "）");
    }

    // ═══════════════════════ 日志捕获（AC-20③ 的机制证实手段）═══════════════════════

    /**
     * 进程内抓 {@code dsrecord} 包的日志。
     *
     * <p>🔑 <b>为什么不靠命令行 flag</b>：日志级别若留给调用方，别人不带 flag 跑，
     * 「没捕到日志」会以「机制没生效」的面目**假红**。级别已钉进 {@link AnchorLogProfile}。
     */
    static final class LogTap extends java.util.logging.Handler {
        private final List<String> lines = new ArrayList<>();
        private final java.util.logging.Logger target =
                java.util.logging.Logger.getLogger("com.cpq.quotation.service.dsrecord");

        static LogTap attach() {
            LogTap t = new LogTap();
            t.setLevel(java.util.logging.Level.ALL);
            t.target.addHandler(t);
            return t;
        }

        void detach() { target.removeHandler(this); }

        @Override public synchronized void publish(java.util.logging.LogRecord r) {
            if (r != null && r.getMessage() != null) lines.add(String.valueOf(r.getMessage()));
        }
        @Override public void flush() {}
        @Override public void close() {}

        synchronized boolean contains(String needle) {
            return lines.stream().anyMatch(l -> l.contains(needle));
        }
        synchronized String firstMatching(String needle) {
            return lines.stream().filter(l -> l.contains(needle)).findFirst().orElse("<未捕获>");
        }
        synchronized int size() { return lines.size(); }
        synchronized List<String> tail(int n) {
            return lines.subList(Math.max(0, lines.size() - n), lines.size());
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private String axis(String tag) {
        return PREFIX + tag + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private int maxVer(String mat) {
        Object v = scalar("SELECT max(version_no) FROM " + EBOM + " WHERE material_no = '" + mat + "'");
        assertNotNull(v, "主表上找不到轴值 " + mat);
        return ((Number) v).intValue();
    }

    private int baseVer(Fx fx, String mat) {
        Object v = scalar("SELECT DISTINCT base_version_no FROM " + EBOM + "_record "
                + "WHERE quotation_id = '" + fx.quotationId() + "' AND material_no = '" + mat + "'");
        assertNotNull(v, "本单在 _record 上没有轴值 " + mat + " 的行 ⇒ 快照没拍成");
        return ((Number) v).intValue();
    }

    private Set<Long> recordOriginIds(Fx fx, String mat) {
        Set<Long> out = new LinkedHashSet<>();
        for (Object o : col("SELECT origin_id FROM " + EBOM + "_record WHERE quotation_id = '"
                + fx.quotationId() + "' AND material_no = '" + mat + "' AND origin_id IS NOT NULL")) {
            out.add(((Number) o).longValue());
        }
        return out;
    }

    private JsonNode group(Fx fx, String mat) {
        JsonNode ds = dsBackfill(ok(getPreview(fx.quotationId()), "预览"));
        for (JsonNode t : ds.path("tables")) {
            if (!EBOM.equals(t.path("tableName").asText())) continue;
            for (JsonNode g : t.path("groups")) {
                if (mat.equals(g.path("axisValue").asText())) return g;
            }
        }
        throw new AssertionError("预览里没有轴值 " + mat + " 的组。dsBackfill=" + ds);
    }
}
