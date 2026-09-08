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
}
