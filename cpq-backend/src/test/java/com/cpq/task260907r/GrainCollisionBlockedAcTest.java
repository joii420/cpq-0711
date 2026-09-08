package com.cpq.task260907r;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>D-37 · C′ 歧义膨胀拦截 —— {@code BLOCKED} 第四态。</b>
 *
 * <h3>它拦的是什么</h3>
 * {@code AC-20④} 下，粒度键有歧义的行退回 {@code NO_ANCHOR} 并按新增追加
 * ⇒ 同一个料号<b>每被一张新单碰一次，组就 +N 行，单调膨胀且没有收敛机制</b>
 * （原判据实测 {@code base 4 → result 6}）。C′ 给这类组加一道拦截：<b>整组跳过回填，一个字节不写</b>。
 *
 * <h3>契约（主线 2026-09-07 定稿）</h3>
 * <ul>
 *   <li>{@code result} 四值：{@code CREATED} / {@code UPGRADED} / {@code UNCHANGED} / <b>{@code BLOCKED}</b>；</li>
 *   <li>{@code summary.blockedGroups} <b>恒发</b>（不是「有才发」—— 恒发才能断言 0）；</li>
 *   <li>{@code BLOCKED} 组的 <b>{@code resultRowCount == baseRowCount}</b>
 *       —— 语义是「一个字节不写」⇒ 结果行数<b>事实上等于基底</b>，不是未知；</li>
 *   <li>{@code blockedReason} 枚举，目前只 {@code GRAIN_KEY_COLLISION}；</li>
 *   <li>{@code collidingRows[]} 含 {@code grainKey} / {@code baseRowCount} / {@code recordRowCount}，
 *       其中 {@code grainKey} 的<b>键是物理列名</b>，与 {@code columnScope} 同一套命名；</li>
 *   <li><b>不阻断核价通过本身</b>：整单仍返 200、仍转 {@code APPROVED}，只是这一组不写。</li>
 * </ul>
 * 判据是<b>不变量、无阈值</b>：{@code ∃ r ∈ unanchoredRows, grainKey(r) ∈ grainKeys(baseRows)}。
 *
 * <h3>🚨 为什么必须成对写（阳性 + 阴性对照）</h3>
 * 只写阳性，「判成 BLOCKED」在一种坏法下照样成立 —— <b>把判据放宽成「凡 unanchored 就拦」</b>。
 * 那种坏法会<b>吞掉用户真正新增的行</b>，正是 {@code D-36} 刚刚收回过的形态。
 * ⇒ {@link #t37b_genuinelyNewRowIsNotBlocked} 是本类<b>不可省</b>的一半：
 * 真新增行（{@code grainKey} 不在基底集合里）<b>必须不被拦</b>，且必须写进去。
 * <p>🔑 <b>两条必须一红一绿地成对出现</b>；两条同时绿而判据没执行是可能的，
 * 所以每条都自带「先证明前提成立」的阳性对照（歧义真的造出来了 / 新行的 grainKey 真的不在基底里）。
 */
@QuarkusTest
@DisplayName("D-37 · C′ 歧义膨胀拦截（BLOCKED 第四态）")
class GrainCollisionBlockedAcTest extends Task260907RBase {

    @AfterEach
    void tearDown() {
        cleanupOwnFixtures();
    }

    /**
     * <b>阳性</b>：粒度键与基底相撞且锚不上 ⇒ 整组 {@code BLOCKED}、一个字节不写，
     * 但核价通过本身照常完成。
     */
    @Test
    @DisplayName("C′-1 · 撞键 → BLOCKED + 整组零写入 + 核价通过仍返 200 转 APPROVED")
    void t37a_grainCollisionBlocksTheGroup() {
        requireRecordLayer();
        String mat = axis("C1");
        String dup = PREFIX + "DUP";   // 同 material_part_no + 同 element_code ⇒ grain 相同

        // 基底：两行 grain 完全相同（歧义）
        seedMainViaCreatedOrder("c1Seed", mat, List.of(
                new EbomRow(1, dup, "10.0", "1.1"),
                new EbomRow(2, dup, "20.0", "2.2")));

        // 🚨 前提阳性对照 1：歧义真的存在，否则本条验的根本不是撞键路径
        long dupGroups = count("SELECT count(*) FROM (SELECT material_part_no, element_code "
                + "FROM " + EBOM + " WHERE material_no = '" + mat + "' "
                + "GROUP BY material_part_no, element_code HAVING count(*) > 1) s");
        assertFixtureNonEmpty(dupGroups,
                "🚨 前提未成立：夹具没造出 grain 重复的行（grain = material_part_no + element_code）"
                        + " ⇒ C′ 根本不会被触发，本用例会验成别的支路还以为过了");

        // B 基于 v1 拍快照
        Fx b = newSubmittedOrder("c1B", mat, List.of(
                new EbomRow(1, dup, "88.8", "1.1"),
                new EbomRow(2, dup, "99.9", "2.2")));
        Set<Long> idsBefore = idSet(EBOM, "material_no", mat);

        // A 升版并改值 ⇒ origin_id 失效 + 指纹变 ⇒ 只剩粒度列，而粒度列有歧义
        Fx a = newSubmittedOrder("c1A", mat, List.of(
                new EbomRow(1, dup, "55.5", "1.1"),
                new EbomRow(2, dup, "44.4", "2.2")));
        approveWithPreview(a, "c1A");
        // 🚨 前提阳性对照 2：origin_id 真的失效了（否则走不到粒度列这一层）
        assertIdSetsDisjoint(idsBefore, idSet(EBOM, "material_no", mat), "C′ 阳性组 " + mat);

        JsonNode g = group(b, mat);
        System.out.println("[C′-1] B 的预览组 = " + g);

        // ── 契约断言
        assertEquals("BLOCKED", g.path("result").asText(),
                "🔑 C′：撞键组应判 BLOCKED（整组跳过回填）。实际 " + g.path("result") + "。group=" + g);
        assertEquals("GRAIN_KEY_COLLISION", g.path("blockedReason").asText(),
                "C′：blockedReason 目前只有 GRAIN_KEY_COLLISION 一个取值。实际 " + g.path("blockedReason"));
        assertEquals(g.path("baseRowCount").asInt(-1), g.path("resultRowCount").asInt(-2),
                "🔑 C′：BLOCKED 语义是「一个字节不写」⇒ resultRowCount 必须等于 baseRowCount"
                        + "（不是未知、不是 0）。base=" + g.path("baseRowCount")
                        + " result=" + g.path("resultRowCount"));

        JsonNode colliding = g.path("collidingRows");
        assertTrue(colliding.isArray() && colliding.size() > 0,
                "C′：BLOCKED 组必须给出 collidingRows 明细，否则财务看不出是哪几行撞了。group=" + g);
        Set<String> physicalCols = new LinkedHashSet<>();
        for (JsonNode c : g.path("columnScope").path("patched")) physicalCols.add(c.asText());
        for (JsonNode c : g.path("columnScope").path("preserved")) physicalCols.add(c.asText());
        assertFixtureNonEmpty(physicalCols.size(), "columnScope 的物理列名集合");
        for (JsonNode c : colliding) {
            for (String k : List.of("grainKey", "baseRowCount", "recordRowCount")) {
                assertTrue(c.has(k), "C′：collidingRows 元素缺字段 " + k + "，实际=" + c);
            }
            JsonNode gk = c.path("grainKey");
            assertTrue(gk.isObject() && gk.size() > 0, "C′：grainKey 应为非空对象，实际=" + c);
            gk.fieldNames().forEachRemaining(f -> assertTrue(physicalCols.contains(f),
                    "🔑 C′ 契约：collidingRows[].grainKey 的键必须是**物理列名**、与 columnScope 同一套命名。"
                            + "「" + f + "」不在 columnScope 里（" + physicalCols + "）⇒ 两处命名已漂移，"
                            + "前端按 columnScope 去查 grainKey 会查不到。实际=" + c));
        }
        assertTrue(dsSummary(b).path("blockedGroups").asInt(-1) >= 1,
                "C′：summary.blockedGroups 恒发且此处应 ≥ 1。summary=" + dsSummary(b));

        // ── 🔑 零写入：整组逐字未变、版本不变、_history 无新增
        String digestBefore = scopedDigest(EBOM, "material_no", List.of(mat), "C′ 确认前主表组");
        int verBefore = maxVer(mat);
        long histBefore = count("SELECT count(*) FROM " + EBOM + "_history WHERE material_no = '" + mat + "'");

        approveWithPreview(b, "c1B");   // 内部已断言核价通过返 200

        assertEquals(digestBefore, scopedDigest(EBOM, "material_no", List.of(mat), "C′ 确认后主表组"),
                "🔑 C′：BLOCKED 组必须一个字节不写，实测主表该组内容变了。");
        assertEquals(verBefore, maxVer(mat), "C′：BLOCKED 组不该升版");
        assertEquals(histBefore, count("SELECT count(*) FROM " + EBOM + "_history WHERE material_no = '"
                        + mat + "'"), "C′：BLOCKED 组不该往 _history 写");

        // ── 不阻断：整单仍转 APPROVED
        assertEquals("APPROVED", String.valueOf(scalar(
                        "SELECT status FROM quotation WHERE id = '" + b.quotationId() + "'")),
                "🔑 C′：BLOCKED 只跳过这一组，**不阻断核价通过本身**，单据仍应转 APPROVED");
        System.out.println("[C′-1] ✅ BLOCKED + 零写入 + 单据仍 APPROVED");
    }

    /**
     * 🚨 <b>阴性对照（本类不可省的一半）</b>：真新增行（{@code grainKey} 不在基底集合里）
     * <b>不</b>该被判 {@code BLOCKED}，且必须按新增写进去。
     *
     * <p>防的是「把判据放宽成『凡 unanchored 就拦』」那种坏法 —— 它会<b>吞掉用户新增的行</b>，
     * 正是 {@code D-36} 刚收回过的形态，而且在只有阳性用例时<b>全绿</b>。
     */
    @Test
    @DisplayName("C′-2 · 阴性对照：真新增行 grainKey 不在基底 → 不判 BLOCKED，按新增写入")
    void t37b_genuinelyNewRowIsNotBlocked() {
        requireRecordLayer();
        String mat = axis("C2");
        String newEl = PREFIX + "E3NEW";

        // 基底：两行，grain 各不相同（🚫 不许有歧义，否则验的是阳性路径）
        seedMainViaCreatedOrder("c2Seed", mat, List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "20.0", "2.2")));
        assertEquals(0L, count("SELECT count(*) FROM (SELECT material_part_no, element_code "
                        + "FROM " + EBOM + " WHERE material_no = '" + mat + "' "
                        + "GROUP BY material_part_no, element_code HAVING count(*) > 1) s"),
                "阴性对照前提：基底 grain 必须唯一，否则拦截会因歧义触发，验的就不是本条了");

        // 🚨 前提阳性对照：新行的 grainKey 确实**不在**基底集合里
        assertEquals(0L, count("SELECT count(*) FROM " + EBOM + " WHERE material_no = '" + mat
                        + "' AND element_code = '" + newEl + "'"),
                "阴性对照前提：新增行的 element_code 必须是基底里没有的，否则它就撞键了");

        // B 表征基底两行（原样）+ 一行真新增
        Fx b = newSubmittedOrder("c2B", mat, List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "20.0", "2.2"),
                new EbomRow(3, newEl, "33.3", "3.3")));

        JsonNode g = group(b, mat);
        System.out.println("[C′-2] B 的预览组 = " + g);

        assertNotEquals("BLOCKED", g.path("result").asText(),
                "🔑 C′ 阴性对照：真新增行（grainKey 不在基底）**不该**被拦。"
                        + "实际判 " + g.path("result") + " ⇒ 判据被放宽成「凡 unanchored 就拦」，"
                        + "那会吞掉用户新增的行（D-36 刚收回过这个形态）。group=" + g);
        assertTrue(g.path("blockedReason").isNull() || g.path("blockedReason").isMissingNode(),
                "C′ 阴性对照：未被拦的组不该带 blockedReason，实际 " + g.path("blockedReason"));
        assertEquals(0, g.path("collidingRows").size(),
                "C′ 阴性对照：未撞键 ⇒ collidingRows 应为空，实际 " + g.path("collidingRows"));
        assertEquals(0, dsSummary(b).path("blockedGroups").asInt(-1),
                "C′ 阴性对照：本单无撞键组 ⇒ summary.blockedGroups 应为 0"
                        + "（该字段恒发，所以这里断言 0 是有意义的）。summary=" + dsSummary(b));

        long rowsBefore = count("SELECT count(*) FROM " + EBOM + " WHERE material_no = '" + mat + "'");
        approveWithPreview(b, "c2B");
        long rowsAfter = count("SELECT count(*) FROM " + EBOM + " WHERE material_no = '" + mat + "'");

        assertEquals(rowsBefore + 1, rowsAfter,
                "🔑 C′ 阴性对照：新增行必须真的写进去（" + rowsBefore + " → 期望 " + (rowsBefore + 1)
                        + "，实际 " + rowsAfter + "）。🚫 只断言「没判 BLOCKED」证明不了它没被吞 —— "
                        + "行数才是。");
        assertFixtureNonEmpty(count("SELECT count(*) FROM " + EBOM + " WHERE material_no = '" + mat
                        + "' AND element_code = '" + newEl + "'"),
                "新增行 " + newEl + " 在主表中的落地行数");
        System.out.println("[C′-2] ✅ 未拦 + 新增行落库（" + rowsBefore + " → " + rowsAfter + " 行）");
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private String axis(String tag) {
        return PREFIX + tag + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private int maxVer(String mat) {
        Object v = scalar("SELECT max(version_no) FROM " + EBOM + " WHERE material_no = '" + mat + "'");
        assertFalse(v == null, "主表上找不到轴值 " + mat);
        return ((Number) v).intValue();
    }

    private JsonNode dsSummary(Fx fx) {
        return dsBackfill(ok(getPreview(fx.quotationId()), "预览")).path("summary");
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
