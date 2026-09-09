package com.cpq.task260907r;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 🔴 <b>D-43 · 同一销售料号有 N 个产品行 ⇒ 核价通过把主表整组翻倍</b>（2026-09-08 主线亲验抓到的 P0）。
 *
 * <h3>缺陷形状</h3>
 * {@code _record} 的组粒度是 {@code (quotation_id, 轴值)}，{@code DsQuoteRecordService} 会把
 * 同轴值的<b>全部卡片</b>纳入重算（这是对的）。但它<b>逐张卡片直接拼接</b>投影结果，没有按主表行
 * 身份归一 ⇒ 同一条主表行被投影 N 份 ⇒ {@code anchor()} 的 {@code usedBase} 只让第一份认领到
 * {@code origin_id}，其余 N-1 份必然 {@code NULL} ⇒ 回填按新增行追加 ⇒ <b>整组 ×N</b>。
 * <p>实证（导入建单，{@code udv=0}，用户一个字没改）：合计 {@code base=43 → result=71}，
 * 两个产品行的 FG01 有 12 个组全部翻倍；只有一个产品行的 FG02 则 {@code UNCHANGED}。
 *
 * <h3>🚨 本类是「双侧还原实验」的载体，四格都必须有输出</h3>
 * <table>
 *   <tr><td></td><td>修复在</td><td>把修复改回去</td></tr>
 *   <tr><td>{@link #t01_twoCardsSameAxis_recordHasOneRowPerBaseRow} (N=2)</td>
 *       <td>绿</td><td><b>必须红</b></td></tr>
 *   <tr><td>{@link #t02_singleCard_unchangedBitForBit} (N=1)</td>
 *       <td>绿</td><td><b>必须也绿</b>（= 这条用例验不到本缺陷，是回归网的边界）</td></tr>
 * </table>
 * 右下角那格若红了，说明 N=1 的行为被改动影响了 —— 那是回归，不是修复。
 *
 * <h3>🚨 阴性对照必须同时在场</h3>
 * {@link #t03a_sameCardDuplicateGrainKey_singleCard} / {@link #t03b_sameCardDuplicateGrainKey_twoCards}：同一张卡片内两行<b>粒度键相同</b>
 * （{@code ds_quote_element_bom} 的粒度键 = {@code (material_part_no, element_code)}，实测有 14 组重复）
 * —— 这两行<b>必须都留下</b>。把它们收敛掉就是 {@code AP-60}「4 行被对齐成 1 行」的原始形态，
 * 比本缺陷严重得多（静默删数据）。⇒ 只验「不翻倍」而不验「不塌缩」，
 * 一个把所有行都收敛成 1 行的实现<b>也能全绿</b>。
 */
@QuarkusTest
@DisplayName("🔴 D-43 · 同料号多产品行的跨卡片归一（P0）")
class CrossCardDedupAcTest extends Task260907RBase {

    @AfterEach
    void tearDown() {
        cleanupOwnFixtures();
    }

    // ══════════════════════════════════════════════════════════════════
    // 阳性：N=2 —— 修复在 ⇒ 绿；把修复改回去 ⇒ 必须红
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("t01 · 同一销售料号两个产品行：_record 每条主表行恰好 1 条、origin_id 全非空、预览 result==base")
    void t01_twoCardsSameAxis_recordHasOneRowPerBaseRow() {
        requireRecordLayer();
        String mat = PREFIX + "D43A-" + UUID.randomUUID().toString().substring(0, 6);
        List<EbomRow> rows = List.of(
                new EbomRow(1, "D43-AG", "50", "2.4"),
                new EbomRow(2, "D43-CU", "30", "1.4"),
                new EbomRow(3, "D43-NI", "20", "0.9"));

        // ── 基底：用一张走 CREATED 的单把主表这一组造出来（指纹由 writer 自己算）
        Fx seeder = seedMainViaCreatedOrder("D43A-seed", mat, rows);
        long baseRows = count("SELECT count(*) FROM " + EBOM + " WHERE material_no = '" + mat + "'");
        assertEquals(3L, baseRows, "前置：主表该组应有 3 行");

        // ── 被测：同客户下一张单，**两个产品行共用同一个销售料号**
        Fx fx = newFixtureForCustomer("D43A-2card", seeder);
        Response r = putDraftWithCards(fx, mat, rows, rows);
        requireStatusBeforeDiff(r, 200, "t01 saveDraft（2 个产品行）");

        // 🚨 阳性前置：先证明「真的有 2 个产品行、且轴值相同」——
        //    不证明这一点，「没翻倍」可能只是因为压根没造出 N=2 的形状。
        long lineCount = count("SELECT count(*) FROM quotation_line_item WHERE quotation_id = '"
                + fx.quotationId() + "' AND product_part_no_snapshot = '" + mat + "'");
        assertEquals(2L, lineCount,
                "🚨 夹具前置未成立：本单同轴值的产品行应为 2 个，实际 " + lineCount
                        + " ⇒ N=2 的形状没造出来，本用例的绿不构成任何证据。");

        // 🔬 还原实验探针：先把预览数字打出来，再做断言。
        //    断言先失败的话数字就打不出来了，而「base=3 → result=6」正是本缺陷的指纹。
        previewGroup(fx, mat, "t01·探针");

        // ── 判据①（不变量）：_record 里代表同一条主表行的记录至多一条
        List<Object[]> rec = rows("SELECT material_part_no, element_code, origin_id, base_row_fingerprint "
                + "FROM " + EBOM + "_record WHERE quotation_id = '" + fx.quotationId() + "' "
                + "ORDER BY element_code, coalesce(origin_id, -1)");
        System.out.println("[t01] _record 逐行（quotation=" + fx.quotationId() + "）：");
        for (Object[] x : rec) {
            System.out.println("    material_part_no=" + x[0] + " element_code=" + x[1]
                    + " origin_id=" + x[2] + " fp=" + x[3]);
        }
        assertFixtureNonEmpty(rec.size(), "t01 的 _record 行数");
        assertEquals(3, rec.size(),
                "🔴 D-43：2 个产品行共用同一销售料号时，_record 应仍只有 3 行（每条主表行 1 条），"
                        + "实际 " + rec.size() + " 行。=6 就是「逐张卡片直接拼接」那个缺陷本身。");

        // ── 判据②：每一条都锚上了（重复投影的那些必然 origin_id=NULL，这里一条都不许有）
        int nullOrigin = 0;
        Set<Object> originIds = new LinkedHashSet<>();
        for (Object[] x : rec) {
            if (x[2] == null) nullOrigin++;
            else originIds.add(x[2]);
        }
        assertEquals(0, nullOrigin,
                "🔴 D-43：_record 有 " + nullOrigin + " 条 origin_id=NULL。"
                        + "重复投影的行必然锚不上（anchor 的 usedBase 只认领一次），"
                        + "而回填侧会把它们当新增行追加 ⇒ 整组翻倍。");
        assertEquals(3, originIds.size(),
                "🔴 3 条 _record 应各自锚到不同的主表行，实际只锚到 " + originIds.size() + " 条不同的行");

        // ── 判据③：预览侧 —— 回填后该组行数不许变大
        assertGroupNotInflated(fx, mat, 3, "t01");
    }

    // ══════════════════════════════════════════════════════════════════
    // 边界：N=1 —— 修复在 ⇒ 绿；把修复改回去 ⇒ **也必须绿**
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("t02 · 单产品行（N=1）：行为逐位不变 —— 还原实验里这一格改回去也必须绿")
    void t02_singleCard_unchangedBitForBit() {
        requireRecordLayer();
        String mat = PREFIX + "D43B-" + UUID.randomUUID().toString().substring(0, 6);
        List<EbomRow> rows = List.of(
                new EbomRow(1, "D43-AG", "50", "2.4"),
                new EbomRow(2, "D43-CU", "30", "1.4"),
                new EbomRow(3, "D43-NI", "20", "0.9"));

        Fx seeder = seedMainViaCreatedOrder("D43B-seed", mat, rows);
        Fx fx = newFixtureForCustomer("D43B-1card", seeder);
        requireStatusBeforeDiff(putDraftWithCards(fx, mat, rows), 200, "t02 saveDraft（1 个产品行）");

        long lineCount = count("SELECT count(*) FROM quotation_line_item WHERE quotation_id = '"
                + fx.quotationId() + "' AND product_part_no_snapshot = '" + mat + "'");
        assertEquals(1L, lineCount, "t02 前置：本单同轴值的产品行应为 1 个（这条用例守的是 N=1 的边界）");

        long n = count("SELECT count(*) FROM " + EBOM + "_record WHERE quotation_id = '"
                + fx.quotationId() + "'");
        assertFixtureNonEmpty(n, "t02 的 _record 行数");
        assertEquals(3L, n, "N=1 时 _record 应为 3 行（与修复前逐位相同）");

        long nullOrigin = count("SELECT count(*) FROM " + EBOM + "_record WHERE quotation_id = '"
                + fx.quotationId() + "' AND origin_id IS NULL");
        assertEquals(0L, nullOrigin, "N=1 时不该有锚不上的行");

        assertGroupNotInflated(fx, mat, 3, "t02");
    }

    // ══════════════════════════════════════════════════════════════════
    // 阴性对照：同一张卡片内部的行 🚫 绝不许被收敛（AP-60 的原始形态）
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("t03a · 同卡片内两行粒度键相同（N=1）：必须留 2 行 —— 还原实验里这一格改回去也必须绿")
    void t03a_sameCardDuplicateGrainKey_singleCard() {
        requireRecordLayer();
        // 🔑 粒度键 = (material_part_no, element_code)。夹具里 material_part_no 恒为 PREFIX+"MAT"，
        //    于是「元素相同、项次不同」的两行 = 粒度键相同的两行（AC-20④ / AC-21 的合法形状）。
        List<EbomRow> dup = List.of(
                new EbomRow(1, "D43-DUP", "50", "2.4"),
                new EbomRow(2, "D43-DUP", "30", "1.4"));

        // 单卡片：2 行必须都在（若实现按粒度键无脑去重，这里就会变成 1）
        String mat1 = PREFIX + "D43C1-" + UUID.randomUUID().toString().substring(0, 6);
        Fx fx1 = newFixture("D43C-1card");
        trackAxis(mat1);
        requireStatusBeforeDiff(putDraftWithCards(fx1, mat1, dup), 200, "t03a saveDraft（1 个产品行）");
        long n1 = count("SELECT count(*) FROM " + EBOM + "_record WHERE quotation_id = '"
                + fx1.quotationId() + "'");
        assertEquals(2L, n1,
                "🚨 AP-60 守卫：同一张卡片里两行粒度键相同是**合法形状**，必须都留下，实际 " + n1 + " 行。"
                        + "塌缩成 1 行 = 静默删数据，比 D-43 的翻倍严重得多。");

    }

    @Test
    @DisplayName("t03b · 同卡片内两行粒度键相同 × 2 张卡片：应归一为 2 行（4=没归一 / 1=塌缩，两个都不许）")
    void t03b_sameCardDuplicateGrainKey_twoCards() {
        requireRecordLayer();
        List<EbomRow> dup = List.of(
                new EbomRow(1, "D43-DUP", "50", "2.4"),
                new EbomRow(2, "D43-DUP", "30", "1.4"));

        String mat2 = PREFIX + "D43C2-" + UUID.randomUUID().toString().substring(0, 6);
        Fx fx2 = newFixture("D43C-2card");
        trackAxis(mat2);
        requireStatusBeforeDiff(putDraftWithCards(fx2, mat2, dup, dup), 200, "t03b saveDraft（2 个产品行）");
        assertEquals(2L, count("SELECT count(*) FROM quotation_line_item WHERE quotation_id = '"
                        + fx2.quotationId() + "' AND product_part_no_snapshot = '" + mat2 + "'"),
                "t03b 前置：本单同轴值的产品行应为 2 个");
        List<Object[]> rec2 = rows("SELECT item_seq, element_code FROM " + EBOM + "_record "
                + "WHERE quotation_id = '" + fx2.quotationId() + "' ORDER BY item_seq");
        System.out.println("[t03b] _record 逐行：");
        for (Object[] x : rec2) System.out.println("    item_seq=" + x[0] + " element_code=" + x[1]);
        System.out.println("[t03b·探针] _record 行数=" + rec2.size() + "（期望 2；4=没归一，1=塌缩）");
        assertEquals(2, rec2.size(),
                "🔴 2 张卡片 × 2 行同粒度键 = 4 份投影，应归一为 2 行（每条主表行 1 条），实际 "
                        + rec2.size() + " 行。4 = 没归一；1 = 把同卡片内的两行也收敛了（AP-60）。");
        Set<Object> seqs = new LinkedHashSet<>();
        for (Object[] x : rec2) seqs.add(String.valueOf(x[0]));
        assertEquals(2, seqs.size(),
                "🚨 归一后应仍保留两个不同的项次（1 和 2），实际 " + seqs
                        + " ⇒ 两行被当成同一行处理了。");
    }

    // ══════════════════════════════════════════════════════════════════
    // 契约⑦：卡片 2 独有的行 🚫 绝不许被吃掉
    // ══════════════════════════════════════════════════════════════════

    /**
     * 🔴 <b>契约⑦ · 卡片 2 独有的行不许被吃掉</b>（2026-09-08 补的那一格）。
     *
     * <h3>🚨 为什么 t01 / t03b 都拦不住它</h3>
     * 设想一种坏实现：跨卡片归一时<b>整张卡片 2 直接丢弃</b>（而不是按行归一）。
     * <ul>
     *   <li>{@link #t01_twoCardsSameAxis_recordHasOneRowPerBaseRow} 的<b>每一条</b>断言仍然成立
     *       —— 每条主表行恰好 1 条 ✅、{@code origin_id} 全非空 ✅、{@code result==base} ✅；</li>
     *   <li>{@link #t03b_sameCardDuplicateGrainKey_twoCards} 也拦不住 —— 它两张卡片<b>完全同构</b>，
     *       卡片 2 里没有任何独有行可丢。</li>
     * </ul>
     * ⇒ <b>「仲裁」解决的是冲突，不是「后来的一律丢弃」，而这句话此前没有网。</b>
     *
     * <h3>三格一次验完（缺一不可）</h3>
     * <table>
     *   <tr><td>行身份</td><td>卡片1（{@code sort_order}=0）</td><td>卡片2（=1）</td><td>期望</td><td>契约</td></tr>
     *   <tr><td>共有行 {@code D43-SH}</td><td>有（{@code content_pct}=11.1）</td><td>有（=99.9）</td>
     *       <td>留 <b>1</b> 条，取值 = <b>卡片1 的 11.1</b></td><td>①②（仲裁按 {@code line_item.sort_order}）</td></tr>
     *   <tr><td><b>卡片2 独有行 {@code D43-ONLY2}</b></td><td>无</td><td>有</td>
     *       <td><b>必须留下</b></td><td><b>⑦（本次要补的）</b></td></tr>
     *   <tr><td>卡片1 内粒度键重复的两行 {@code D43-DUP}</td><td>两行都在</td><td>无</td>
     *       <td><b>两行都留</b></td><td>③（AP-60 防线）</td></tr>
     * </table>
     *
     * <h3>🚨 三条断言都必须先过非空守卫</h3>
     * 「独有行没被吃掉」在 {@code _record} 零行时<b>恒真</b>；
     * 「共有行只留一条」在共有行压根没造出来时<b>也恒真</b>。
     * ⇒ 守卫要证明的不是「有数据」，而是<b>「我以为的那三种行，在报价单侧真的按我以为的分布存在」</b>。
     *
     * <p>📌 刻意<b>不</b>播种主表基底（与 t03a/t03b 同）：本条验的是<b>投影归一</b>那一段，
     * 不是锚定/回填。掺进基底会引入 {@code AC-21}（粒度键撞车整组 {@code BLOCKED}）的干扰 ——
     * {@code D43-DUP} 两行同粒度键，一旦基底里也有它，整组会被 C′ 拦成零写入，
     * 于是本条的判据全部失去分辨力。
     */
    @Test
    @DisplayName("t04 · 两张卡片行集不同：卡片2 独有行必须留下（契约⑦）；共有行归一取卡片1 的值；同卡重复两行都留")
    void t04_cardTwoUniqueRowSurvivesDedup() {
        requireRecordLayer();

        final String EL_SHARED = "D43-SH";
        final String EL_DUP = "D43-DUP";
        final String EL_ONLY2 = "D43-ONLY2";

        // 卡片1（sortOrder=0）：共有行（值 X=11.1）+ 同卡粒度键重复的两行
        List<EbomRow> card1 = List.of(
                new EbomRow(1, EL_SHARED, "11.1", "1.1"),
                new EbomRow(2, EL_DUP, "22.2", "2.2"),
                new EbomRow(3, EL_DUP, "33.3", "3.3"));
        // 卡片2（sortOrder=1）：共有行（值 Y=99.9≠X）+ **卡片2 独有行**
        List<EbomRow> card2 = List.of(
                new EbomRow(1, EL_SHARED, "99.9", "9.9"),
                new EbomRow(4, EL_ONLY2, "44.4", "4.4"));

        String mat = PREFIX + "D43D-" + UUID.randomUUID().toString().substring(0, 6);
        Fx fx = newFixture("D43D-asym");
        trackAxis(mat);
        requireStatusBeforeDiff(putDraftWithCards(fx, mat, card1, card2), 200,
                "t04 saveDraft（2 个产品行，行集**不同**）");

        // ══ 守卫①：两个产品行确实建出来了，且 sort_order 就是 0 / 1（仲裁维度靠它）══
        // ⚠️ 单列 native query 返回的是标量 List，🚫 不是 List<Object[]> —— 用 col() 不用 rows()。
        List<Object> lis = col("SELECT sort_order FROM quotation_line_item WHERE quotation_id = '"
                + fx.quotationId() + "' AND product_part_no_snapshot = '" + mat + "' ORDER BY sort_order");
        System.out.println("[t04·守卫①] 产品行 sort_order = " + lis);
        assertEquals(2, lis.size(),
                "🚨 夹具前置未成立：本单同轴值的产品行应为 2 个，实际 " + lis.size()
                        + " ⇒ 跨卡片的形状没造出来，本用例的绿不构成任何证据。");
        assertEquals("0", String.valueOf(lis.get(0)),
                "🚨 仲裁按 line_item.sort_order，卡片1 必须是 0，否则「取卡片1 的值」这条判据在验别的东西");
        assertEquals("1", String.valueOf(lis.get(1)),
                "🚨 卡片2 的 sort_order 必须是 1");

        // ══ 守卫②③：三种行在**报价单侧**真的按我以为的分布存在 ══
        //   🔑 尤其是「独有行确实只在卡片2 里」—— 不证明这一点，
        //      「独有行没被吃掉」就可能只是因为它压根没造进卡片2。
        long shInCard1 = cardRowDataHits(fx, mat, 0, EL_SHARED);
        long shInCard2 = cardRowDataHits(fx, mat, 1, EL_SHARED);
        long onlyInCard1 = cardRowDataHits(fx, mat, 0, EL_ONLY2);
        long onlyInCard2 = cardRowDataHits(fx, mat, 1, EL_ONLY2);
        long dupInCard1 = cardRowDataHits(fx, mat, 0, EL_DUP);
        System.out.println("[t04·守卫②③] 报价单侧分布：共有行 卡1=" + shInCard1 + " 卡2=" + shInCard2
                + "；独有行 卡1=" + onlyInCard1 + " 卡2=" + onlyInCard2 + "；重复行 卡1=" + dupInCard1);
        assertEquals(1L, shInCard1, "🚨 守卫②：共有行 " + EL_SHARED + " 应在卡片1 里恰好 1 次");
        assertEquals(1L, shInCard2, "🚨 守卫②：共有行 " + EL_SHARED + " 应在卡片2 里恰好 1 次"
                + " —— 它不在，「归一成一条」就是废话（本来就只有一条）");
        assertEquals(0L, onlyInCard1, "🚨 守卫③：" + EL_ONLY2 + " 必须**不在**卡片1 里，否则它不是「卡片2 独有行」");
        assertEquals(1L, onlyInCard2, "🚨 守卫③：" + EL_ONLY2 + " 必须真的在卡片2 里 —— "
                + "它不在，「独有行没被吃掉」在零行时**恒真**，本条什么都验不到");
        assertEquals(2L, dupInCard1, "🚨 守卫：卡片1 里 " + EL_DUP + " 应有 2 行（AP-60 那一格的靶子）");

        // ══ 🔬 无断言探针：先把 _record 全貌打出来，断言先失败的话这些数字就再也看不到了 ══
        List<Object[]> rec = rows("SELECT item_seq, element_code, content_pct, net_usage, origin_id "
                + "FROM " + EBOM + "_record WHERE quotation_id = '" + fx.quotationId() + "' "
                + "ORDER BY element_code, item_seq");
        System.out.println("[t04·探针] _record 逐行（quotation=" + fx.quotationId()
                + "，期望 4 行 = 共有1 + 重复2 + 独有1）：");
        for (Object[] x : rec) {
            System.out.println("    item_seq=" + x[0] + " element_code=" + x[1]
                    + " content_pct=" + x[2] + " net_usage=" + x[3] + " origin_id=" + x[4]);
        }
        assertFixtureNonEmpty(rec.size(), "t04 的 _record 行数");

        // ══ 判据⑦（本条的主判据，放最前）：卡片2 独有的行必须留下 ══
        List<Object[]> only2 = pick(rec, EL_ONLY2);
        assertEquals(1, only2.size(),
                "🔴 契约⑦：卡片2 独有的行 " + EL_ONLY2 + " 在 _record 里应恰好 1 条，实际 " + only2.size()
                        + " 条。\n"
                        + "  · 0 条 ⇒ **整张卡片2 被丢弃了** —— 仲裁被写成了「后来的一律丢弃」，"
                        + "而 t01 / t03b 对这种坏法**条条全绿**（t01 的三条断言都成立；t03b 两卡同构、无独有行）。\n"
                        + "  · >1 条 ⇒ 独有行被重复投影（D-43 本体）。\n"
                        + "  实际 _record = " + dump(rec));
        assertEquals(0, new java.math.BigDecimal("44.4")
                        .compareTo((java.math.BigDecimal) only2.get(0)[2]),
                "🔴 契约⑦：留下来的独有行取值应是卡片2 写的 44.4（它只有卡片2 一个来源），实际 "
                        + only2.get(0)[2] + " ⇒ 行留下了但值不是它自己的。");

        // ══ 判据③（AP-60 防线）：同卡片内粒度键重复的两行都要留 ══
        List<Object[]> dups = pick(rec, EL_DUP);
        assertEquals(2, dups.size(),
                "🚨 AP-60 守卫：卡片1 里两行粒度键相同是**合法形状**，必须都留下，实际 " + dups.size()
                        + " 条。塌缩成 1 = 静默删数据。实际 _record = " + dump(rec));
        Set<String> dupSeqs = new LinkedHashSet<>();
        for (Object[] x : dups) dupSeqs.add(String.valueOf(x[0]));
        assertEquals(Set.of("2", "3"), dupSeqs,
                "🚨 AP-60 守卫：两行应仍是项次 2 和 3，实际 " + dupSeqs);

        // ══ 判据①②：共有行归一为 1 条，且取 sort_order 小的卡片1 的值 ══
        List<Object[]> sh = pick(rec, EL_SHARED);
        assertEquals(1, sh.size(),
                "🔴 契约①②：两张卡片都有的行应归一为 1 条，实际 " + sh.size()
                        + " 条（2 = 没归一，值不同就当成两行了）。实际 _record = " + dump(rec));
        assertEquals(0, new java.math.BigDecimal("11.1")
                        .compareTo((java.math.BigDecimal) sh.get(0)[2]),
                "🔴 契约①②：仲裁按 line_item.sort_order ⇒ 应取卡片1（sort_order=0）的 11.1，"
                        + "实际 " + sh.get(0)[2] + "（99.9 = 取了卡片2 的值，仲裁方向反了）。");

        // ══ 总数（分辨力最弱，放最后）══
        assertEquals(4, rec.size(),
                "🔴 _record 应恰好 4 行 = 共有1 + 同卡重复2 + 卡片2 独有1，实际 " + rec.size()
                        + " 行：" + dump(rec));
    }

    // ══════════════════════════════════════════════════════════════════
    // 工具
    // ══════════════════════════════════════════════════════════════════

    /**
     * {@code PUT /draft}：一次建 N 个产品行，<b>全部用同一个销售料号</b>（= 同一个轴值）。
     *
     * <p>🔑 这正是本缺陷的现实来源：一个销售料号在客户料号 sheet 里有多条不同的客户产品编号，
     * 导入建单必然产出多个产品行（夹具生成脚本原注释）。
     */
    private Response putDraftWithCards(Fx fx, String materialNo, List<EbomRow>... cards) {
        List<String> added = new ArrayList<>();
        for (int i = 0; i < cards.length; i++) {
            added.add("{\"id\":null,\"tempId\":\"" + PREFIX + "d43t" + i + "\","
                    + "\"templateId\":\"" + DS_TEMPLATE_ID + "\","
                    + "\"sortOrder\":" + i + ",\"compositeType\":\"SIMPLE\","
                    + "\"productPartNo\":\"" + materialNo + "\",\"annualVolume\":1,"
                    + "\"componentData\":[{\"componentId\":\"" + COMP_ELEMENT_BOM + "\","
                    + "\"tabName\":\"" + TAB_ELEMENT_BOM + "\","
                    + "\"rowData\":" + jsonStr(ebomRowData(materialNo, cards[i])) + ",\"sortOrder\":0}]}");
        }
        String body = "{\"baseVersion\":0,\"added\":[" + String.join(",", added) + "],"
                + "\"modified\":[],\"removed\":[]}";
        return RestAssured.given().cookies(adminCookies()).contentType(ContentType.JSON).body(body)
                .when().put("/api/cpq/quotations/" + fx.quotationId() + "/draft").thenReturn();
    }

    /**
     * 预览侧判据：该轴值组回填后<b>不许变大</b>（{@code resultRowCount == baseRowCount == expected}）。
     *
     * <p>🚫 刻意不断言 {@code result} 的具体取值（{@code UNCHANGED} / {@code UPGRADED} 取决于
     * 指纹是否逐字相同，那是另一条 AC 管的事）—— 本用例只管「行数会不会膨胀」。
     */
    private void assertGroupNotInflated(Fx fx, String axisValue, int expected, String what) {
        JsonNode hit = previewGroup(fx, axisValue, what);
        assertEquals(expected, hit.path("baseRowCount").asInt(), what + " 基底行数");
        assertEquals(expected, hit.path("resultRowCount").asInt(),
                "🔴 D-43：回填后该组行数应仍为 " + expected + "，实际 "
                        + hit.path("resultRowCount").asInt() + " ⇒ 组被撑大了。");
        assertEquals(0, hit.path("unanchoredRows").size(),
                "🔴 D-43：不该有锚不上的行，实际 " + hit.path("unanchoredRows").size()
                        + " 条：" + hit.path("unanchoredRows"));
    }

    /**
     * 🔬 <b>无断言探针</b>：取该轴值组的预览并逐字打印 {@code base → result}。
     *
     * <h3>为什么必须与断言分开</h3>
     * 还原实验要的是<b>改动前后两组数字</b>。断言一旦先失败，后面的数字就<b>再也打不出来</b> ——
     * 于是「改回去会怎样」只剩一句「红了」，而红的具体形状（{@code base=3 → result=6}）
     * 恰恰是判断「修的是不是同一个洞」的唯一依据。
     */
    private JsonNode previewGroup(Fx fx, String axisValue, String what) {
        Response pv = getPreview(fx.quotationId());
        requireStatusBeforeDiff(pv, 200, what + " 预览");
        JsonNode ds = json(pv).path("data").path("dsBackfill");
        assertTrue(!ds.isMissingNode() && !ds.isNull(), what + "：预览响应缺 dsBackfill 段");
        JsonNode hit = null;
        for (JsonNode t : ds.path("tables")) {
            if (!EBOM.equals(t.path("tableName").asText(""))) continue;
            for (JsonNode g : t.path("groups")) {
                if (axisValue.equals(g.path("axisValue").asText(""))) { hit = g; break; }
            }
        }
        if (hit == null) {
            fail(what + "：预览里找不到 " + EBOM + " 轴值 " + axisValue + " 的组 ⇒ 断言会空跑。"
                    + "dsBackfill=" + ds);
        }
        assertNotNull(hit);
        System.out.println("[" + what + "] 预览组：axis=" + axisValue
                + " base=" + hit.path("baseRowCount").asInt()
                + " result=" + hit.path("resultRowCount").asInt()
                + " patched=" + hit.path("patchedRows").asInt()
                + " untouched=" + hit.path("untouchedRows").asInt()
                + " unanchored=" + hit.path("unanchoredRows").size()
                + " verdict=" + hit.path("result").asText());
        return hit;
    }

    /**
     * 卡片（按 {@code line_item.sort_order} 定位）的 {@code row_data} 里，某个元素编码出现<b>几次</b>。
     *
     * <p>🔑 用 {@code jsonb_array_elements} 逐行展开而不是 {@code row_data::text LIKE}：
     * 后者对「同一张卡片里同一个元素出现两次」只会返回 1（命中的是<b>组件数据行</b>，不是<b>业务行</b>），
     * 于是 AP-60 那一格的守卫会在「两行被塌缩成一行」时<b>照样通过</b>。
     */
    private long cardRowDataHits(Fx fx, String axisValue, int sortOrder, String elementCode) {
        return count("SELECT count(*) FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "CROSS JOIN LATERAL jsonb_array_elements(cd.row_data) r "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "' "
                + "  AND li.product_part_no_snapshot = '" + axisValue + "' "
                + "  AND li.sort_order = " + sortOrder + " "
                + "  AND cd.component_id = '" + COMP_ELEMENT_BOM + "' "
                + "  AND r->>'元素' = '" + elementCode + "'");
    }

    /** 从 {@code _record} 快照里挑出某个元素编码的全部行（列序见调用点的 SELECT）。 */
    private List<Object[]> pick(List<Object[]> rec, String elementCode) {
        List<Object[]> out = new ArrayList<>();
        for (Object[] x : rec) if (elementCode.equals(String.valueOf(x[1]))) out.add(x);
        return out;
    }

    /** 失败消息里把 {@code _record} 全貌带上 —— 只写「实际 3 条」的断言，修的人还得自己再跑一遍。 */
    private String dump(List<Object[]> rec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rec.size(); i++) {
            Object[] x = rec.get(i);
            if (i > 0) sb.append(", ");
            sb.append("{seq=").append(x[0]).append(", el=").append(x[1])
              .append(", content_pct=").append(x[2]).append(", origin_id=").append(x[4]).append('}');
        }
        return sb.append(']').toString();
    }
}
