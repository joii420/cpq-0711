package com.cpq.task260907r;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-10 / T-11 —— 序列 AC</b>（T-20 四层见 {@link AnchorFourTiersAcTest}）
 * （AC-10 A/B 同引一料号 · AC-11 保存→提交→驳回→改→再提交→通过 · AC-20 双锚三支路）
 *
 * <h3>🔑 本组用例共用的判据形态</h3>
 * AC-10 与 AC-20 都在原文里写了同一句话：
 * <blockquote>
 * 🚫 <b>不能只断言「新值写进去了」</b> —— 后者在原数据被整体覆盖 / 原行被删之后<b>照样成立</b>。
 * </blockquote>
 * ⇒ 本组每条用例都必须同时验一条「<b>原来的东西还在</b>」的反向断言，缺了就等于没验。
 *
 * <h3>🚨 现网造不出来，必须自造（{@code test.md} 风险点 2、3）</h3>
 * 跨版路径在现网只有 {@code S-3120014539}(v2) 与 {@code T260907-M1}(v3) 两个组曾升过版，
 * 且前者有被手工改过版本号的迹象、后者是别的会话的夹具 ⇒ <b>两个都不许用</b>。
 * 本组一律<b>自己先把组升上去</b>，再拿旧快照来通过。
 */
@QuarkusTest
@DisplayName("AC-10/11 · 序列（T-20 四层已迁至 AnchorFourTiersAcTest）")
class BackfillAnchorAndSequenceAcTest extends Task260907RBase {


    @AfterEach
    void tearDown() {
        cleanupOwnFixtures();
    }

    /**
     * <b>T-10（AC-10）🔴 本段最重要一条</b>：A、B 同引一个料号，B 后通过。
     *
     * <p>前置：报价单 A、B 属<b>同一客户</b>，都引用销售料号 X，都基于 {@code v1} 拍了 {@code _record}。
     * 操作：① A 核价通过并确认（X 升到 v2）→ ② B 核价通过。
     *
     * <p>断言：
     * ① B 的确认界面显示「快照基版 v1 / 库当前 v2 / 将升到 v3」——<b>必须让财务看见库里已经变过</b>；
     * ② 财务确认后，B 的升版<b>以主表当前 v2 的整组行为基底</b>做列级 patch（🚫 不是回到 v1），
     *    ⇒ 🔑 <b>A 改过而 B 的页签没表征的列，在 v3 里仍是 A 的值</b>；
     * ③ v2 完整进 {@code _history}，可查回 A 的那一版；
     * ④ B 不被拒绝、不报 409。
     *
     * <h3>🕰️ 2026-09-07 重写：改挂 {@code ds_quote_element_bom}</h3>
     * 原版挂 {@code ds_quote_material_bom} + 三个 {@code pending()} 桩。ds 原生模板的 13 个组件里
     * <b>没有物料BOM</b> ⇒ 那条路根本拍不出 {@code _record}。本条改走已实测跑通的
     * {@code T260907-物料与元素BOM} → {@code ds_quote_element_bom}。
     *
     * <h3>与 {@link AnchorFourTiersAcTest#t20b_crossVersionReanchorsByFingerprint} 的分工</h3>
     * T-20b 验的是<b>锚定机制</b>（跨版按指纹重锚），顺带验到了 AC-10② 的同型判据。
     * 本条是<b>序列 AC</b>，多验 T-20b <b>不覆盖</b>的三件事：
     * <b>同一客户</b>前置 · 预览的<b>三个版本号</b>逐一显示（AC-10①）· v2 <b>完整</b>进 {@code _history}
     * 且能<b>查回 A 那一版的内容</b>（AC-10③）· B <b>不被 409 拒绝</b>（AC-10④）。
     */
    @Test
    @DisplayName("T-10 · B 后通过：A 改过而 B 未表征的列在 v3 里仍是 A 的值")
    void t10_laterQuotationPatchesOnCurrentVersionNotSnapshotVersion() {
        requireRecordLayer();

        String mat = axis("X10");
        // ── 前置：主表整组 v1（指纹由 VersionedGroupWriter 自己算，🚫 不手工 INSERT）
        Fx seeder = seedMainViaCreatedOrder("10Seed", mat, List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "20.0", "2.2"),
                new EbomRow(3, PREFIX + "E3", "30.0", "3.3")));
        assertEquals(1, maxVersion(mat), "AC-10 前置：夹具组应从 v1 起步");

        // ── B 先基于 v1 拍快照（提交但先不确认）。
        //    🔑 B **不表征第 1 行** —— AC-10② 要验的正是「A 改过、B 未表征的列」，
        //       B 若表征了它，B 把它写回去就是**正确**的 patch 语义，那条断言就失去意义。
        Fx b = newSubmittedOrderForCustomer("AC10-B", seeder, mat, List.of(
                new EbomRow(2, PREFIX + "E2", "88.8", "2.2"),
                new EbomRow(3, PREFIX + "E3", "30.0", "3.3")));
        // ── A 也基于 v1 拍快照，改第 1 行
        Fx a = newSubmittedOrderForCustomer("AC10-A", seeder, mat, List.of(
                new EbomRow(1, PREFIX + "E1", "99.9", "1.1"),
                new EbomRow(2, PREFIX + "E2", "20.0", "2.2"),
                new EbomRow(3, PREFIX + "E3", "30.0", "3.3")));

        // 🚨 前置阳性对照 1：A、B 确属**同一客户**（AC-10 原文的前置，不验就等于换了条 AC）
        assertEquals(a.customerId(), b.customerId(),
                "AC-10 前置：A、B 必须属于同一客户，实际 A=" + a.customerId() + " B=" + b.customerId());
        assertEquals(1L, count("SELECT count(DISTINCT customer_id) FROM quotation WHERE id IN ('"
                        + a.quotationId() + "','" + b.quotationId() + "')"),
                "AC-10 前置：库里 A、B 两单的 customer_id 应为同一个");
        // 🚨 前置阳性对照 2：两单都基于 v1
        assertEquals(1, baseVersionOf(a, mat), "AC-10 前置：A 的 _record.base_version_no 应为 1");
        assertEquals(1, baseVersionOf(b, mat), "AC-10 前置：B 的 _record.base_version_no 应为 1");
        System.out.println("[T-10] 前置就位：同客户 " + a.customerNo() + "；A=" + a.quotationNo()
                + " B=" + b.quotationNo() + "；两单快照基版均为 v1");

        // ── ① A 通过 → X 升到 v2，A 把第 1 行改成 99.9
        approveWithPreview(a, "AC10-A");
        assertEquals(2, maxVersion(mat), "AC-10 前置：A 确认后 X 应升到 v2");
        Map<Integer, String> v2Rows = groupBySeq(mat);
        assertFixtureNonEmpty(v2Rows.size(), "A 升版后 v2 的整组行");
        assertTrue(String.valueOf(v2Rows.get(1)).contains("99.9"),
                "AC-10 前置未成立：A 确认后 v2 的第 1 行应能看到 A 写的 99.9，实际=" + v2Rows);
        System.out.println("[T-10] A 已升版：v2 = " + v2Rows);

        // ── ①' AC-10①：B 的预览必须显示「快照 v1 / 库当前 v2 / 将升 v3」
        Response pv = getPreview(b.quotationId());
        requireStatusBeforeDiff(pv, 200, "AC-10① B 的核价通过预览");
        JsonNode previewData = ok(pv, "AC-10 预览");
        JsonNode g = findGroup(dsBackfill(previewData), EBOM, mat);
        assertNotNull(g, "AC-10①：B 的预览里应出现轴值 " + mat + " 的组，实际未出现。"
                + "dsBackfill=" + dsBackfill(previewData));
        System.out.println("[T-10] B 的预览组 = " + g);
        assertEquals(1, g.path("baseVersionNo").asInt(-1),
                "AC-10①：应显示快照基版 v1，实际 " + g.path("baseVersionNo") + "。group=" + g);
        assertEquals(2, g.path("currentVersionNo").asInt(-1),
                "AC-10①：应显示库当前 v2（🔑 必须让财务看见库里已经变过），实际 "
                        + g.path("currentVersionNo") + "。group=" + g);
        assertEquals(3, g.path("targetVersionNo").asInt(-1),
                "AC-10①：应显示将升到 v3，实际 " + g.path("targetVersionNo") + "。group=" + g);
        assertTrue(g.path("crossVersion").asBoolean(false),
                "AC-10①：baseVersionNo(1) != currentVersionNo(2) ⇒ crossVersion 应为 true，实际 "
                        + g.path("crossVersion"));

        // ── ② B 确认（AC-10④：不许 409）
        String token = previewData.path("previewToken").asText(null);
        assertNotNull(token, "AC-10④ 前置：预览响应缺 previewToken，无法确认。data=" + previewData);
        Response ap = postApprove(b.quotationId(), token, PREFIX + "AC10-B");
        assertFalse(ap.statusCode() == 409,
                "AC-10④：B 不应因「快照基版落后」被 409 拒绝（D-25 已推翻「拒绝后通过的单」这一设计）。"
                        + "body=" + ap.asString());
        requireStatusBeforeDiff(ap, 200, "AC-10④ B 的核价通过确认");
        assertEquals(3, maxVersion(mat), "AC-10②：B 确认后 X 应升到 v3");

        Map<Integer, String> v3Rows = groupBySeq(mat);
        System.out.println("[T-10] B 确认后：v3 = " + v3Rows);

        // ── 🔑 AC-10②：A 改过、B 未表征的第 1 行，在 v3 里仍是 A 的值
        assertEquals(v2Rows.get(1), v3Rows.get(1),
                "🔑 AC-10②：B 的页签**没表征**第 1 行，因此 v3 里它必须逐字等于 A 在 v2 写的值"
                        + "（基底应是主表当前 v2 的整组行，🚫 不是回到 v1）。"
                        + "A 在 v2 的第 1 行=" + v2Rows.get(1) + "，v3 实际=" + v3Rows.get(1)
                        + " ⇒ 不相等说明 B 的升版把 A 的数据整体覆盖回 v1 了。"
                        + "🚫 只断言「B 写的 88.8 进去了」在这种情况下照样成立，所以那不是判据。");
        assertTrue(String.valueOf(v3Rows.get(1)).contains("99.9"),
                "AC-10② 二次确认：v3 的第 1 行应仍含 A 的 99.9，实际 " + v3Rows.get(1));
        // 反向配对：B 表征并改了的列确实写进去了（防止修成「什么都不写」）
        assertTrue(String.valueOf(v3Rows.get(2)).contains("88.8"),
                "AC-10② 反向：B 表征并改了第 2 行=88.8，v3 里应能看到，实际=" + v3Rows.get(2));
        assertEquals(3, v3Rows.size(),
                "AC-10②：整组仍应 3 行（B 只表征 2 行，未表征的第 1 行原样保留，🚫 不是删掉）。实际=" + v3Rows);

        // ── AC-10③：v2 **完整**进 _history，且能查回 A 的那一版
        long histV2 = count("SELECT count(*) FROM " + EBOM + "_history WHERE material_no = '" + mat
                + "' AND version_no = 2");
        assertEquals((long) v2Rows.size(), histV2,
                "AC-10③：v2 应**完整**（" + v2Rows.size() + " 行）进 _history 以便查回 A 的那一版，实际 "
                        + histV2 + " 行");
        Map<Integer, String> histRows = historyGroupBySeq(mat, 2);
        assertEquals(v2Rows, histRows,
                "AC-10③：从 _history 查回的 v2 应逐字等于 A 当时写的内容。期望=" + v2Rows + " 实际=" + histRows);
        System.out.println("[T-10] AC-10③ 通过：_history v2 = " + histRows);
    }

    /**
     * <b>T-11（AC-11）</b>：保存 → 提交 → 驳回 → 改 → 再提交 → 通过。
     *
     * <p>断言：
     * ① {@code _record} 是<b>覆盖式</b>的，最终只有一份，内容 = 最后一次保存的值
     *    （D-7：{@code _record} 做报价单数据，{@code _history} 做版本记录）；
     * ② 回填写进主表的是<b>最后一次</b>的值，不是第一次；
     * ③ <b>中间态断言</b>：驳回后、再提交前，主表 {@code version_no} 未变（驳回不触发回填）。
     *
     * <p>🔑 断言③是「序列」类用例的价值所在 —— 只验最终态会漏掉「驳回那一刻已经写库了」这一整类缺陷。
     *
     * <p>🚨 <b>断言②的配对反向</b>：不仅要验「主表出现 200」，还要验「主表<b>不含</b> 100」。
     * 只验前者在「两次的值都写进去了、组翻倍」时照样成立。
     */
    @Test
    @DisplayName("T-11 · 序列：_record 覆盖式只有一份；回填写最后一次的值；驳回不升版")
    void t11_rejectThenResubmitSequence() {
        requireRecordLayer();

        String mat = axis("X11");
        Fx seeder = seedMainViaCreatedOrder("11Seed", mat, List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "20.0", "2.2"),
                new EbomRow(3, PREFIX + "E3", "30.0", "3.3")));
        int verAtStart = maxVersion(mat);
        assertEquals(1, verAtStart, "T-11 前置：夹具组应从 v1 起步");

        // ── 第一次保存：把第 2 行改成 100.0
        List<EbomRow> first = List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "100.0", "2.2"),
                new EbomRow(3, PREFIX + "E3", "30.0", "3.3"));
        Fx fx = newSubmittedOrderForCustomer("AC11", seeder, mat, first);

        long recCountAfterFirst = recCount(fx, mat);
        assertFixtureNonEmpty(recCountAfterFirst, "第一次保存后本单 _record 行数");
        List<Object> recFirst = recContent(fx, mat);
        assertTrue(recFirst.toString().contains("100.0"),
                "T-11 前置：第一次保存的 100.0 应在 _record 里，实际=" + recFirst);
        System.out.println("[T-11] 第一次保存后 _record " + recCountAfterFirst + " 行 = " + recFirst);

        // ── 财务驳回
        Response rj = costingReject(fx, PREFIX + "AC11-reject");
        requireStatusBeforeDiff(rj, 200, "T-11 财务驳回");
        String afterReject = String.valueOf(scalar(
                "SELECT status FROM quotation WHERE id = '" + fx.quotationId() + "'"));
        assertEquals("COSTING_REJECTED", afterReject,
                "T-11 前置：驳回后状态应为 COSTING_REJECTED，实际 " + afterReject);

        // ── 🔑 AC-11③ 中间态：驳回后、再提交前，主表 version_no 未变（驳回不触发回填）
        assertEquals(verAtStart, maxVersion(mat),
                "🔑 AC-11③：驳回不触发回填，主表 version_no 应仍为 " + verAtStart
                        + "，实际 " + maxVersion(mat)
                        + " ⇒ 变了说明驳回那一刻已经写库了（只验最终态会完全漏掉这一类缺陷）。");
        long histAfterReject = count("SELECT count(*) FROM " + EBOM + "_history WHERE material_no = '"
                + mat + "'");
        assertEquals(0L, histAfterReject,
                "AC-11③ 配对：驳回不升版 ⇒ _history 也不该有本组的行，实际 " + histAfterReject);
        System.out.println("[T-11] AC-11③ 通过：驳回后主表仍 v" + verAtStart + "，_history 0 行");

        // ── 销售改值前须先回到可编辑态。
        //    🔬 实测（2026-09-07）：COSTING_REJECTED 下直接 PUT /draft 返
        //    `400 {"message":"Only DRAFT quotations can be edited"}` ⇒ 真实用户路径是先「开始编辑」
        //    （main-api.md §4.1.21 `POST /begin-edit`）。这一步属**序列的一环**，不是绕过。
        requireStatusBeforeDiff(beginEdit(fx), 200, "T-11 驳回后开始编辑");
        assertEquals("DRAFT", String.valueOf(scalar(
                        "SELECT status FROM quotation WHERE id = '" + fx.quotationId() + "'")),
                "T-11：begin-edit 后应回到 DRAFT 才能改值");

        // ── 改同一行的值 → 再保存
        List<EbomRow> second = List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "200.0", "2.2"),
                new EbomRow(3, PREFIX + "E3", "30.0", "3.3"));
        requireStatusBeforeDiff(saveDraftModified(fx, mat, second), 200, "T-11 驳回后再次保存");

        // ── AC-11①：_record 覆盖式，最终只有一份
        long recCountAfterSecond = recCount(fx, mat);
        assertEquals(recCountAfterFirst, recCountAfterSecond,
                "AC-11①：_record 是覆盖式的（D-7：_record 做报价单数据、_history 做版本记录），"
                        + "改了再存不应累积出第二份。第一次后 " + recCountAfterFirst
                        + " 行，第二次后 " + recCountAfterSecond + " 行");
        List<Object> recSecond = recContent(fx, mat);
        assertTrue(recSecond.toString().contains("200.0"),
                "AC-11①：_record 内容应 = 最后一次保存的值 200.0，实际=" + recSecond);
        assertFalse(recSecond.toString().contains("100.0"),
                "AC-11①：_record 里仍出现第一次的 100.0 ⇒ 第一次的快照没被覆盖。实际=" + recSecond);
        System.out.println("[T-11] AC-11① 通过：_record 仍 " + recCountAfterSecond + " 行 = " + recSecond);

        // ── 再提交 → 通过并确认
        requireStatusBeforeDiff(submit(fx), 200, "T-11 再次提交");
        approveWithPreview(fx, "AC11");

        // ── AC-11②：回填写进主表的是最后一次的值
        Map<Integer, String> mainAfter = groupBySeq(mat);
        assertFixtureNonEmpty(mainAfter.size(), "回填后主表该组的行");
        assertTrue(String.valueOf(mainAfter.get(2)).contains("200.0"),
                "AC-11②：回填应写最后一次的值 200.0，实际主表第 2 行=" + mainAfter.get(2));
        assertFalse(mainAfter.toString().contains("100.0"),
                "AC-11②：主表里出现了第一次的值 100.0 ⇒ 回填用的是被驳回那一版的快照。"
                        + "🚫 只验「200 写进去了」在「两次的值都写进去、组翻倍」时照样成立，"
                        + "所以本条反向断言不能省。实际=" + mainAfter);
        assertEquals(3, mainAfter.size(),
                "AC-11② 配对：整组仍应 3 行（🚫 不许因两次保存而翻倍）。实际=" + mainAfter);
        assertEquals(verAtStart + 1, maxVersion(mat),
                "AC-11②：整个序列只有最后一次通过触发回填 ⇒ 只应升一版（v" + verAtStart + " → v"
                        + (verAtStart + 1) + "），实际 v" + maxVersion(mat));
        System.out.println("[T-11] AC-11② 通过：主表 = " + mainAfter + "，版本 v" + maxVersion(mat));
    }

    // ═══════════════════════ T-20a/b/c/d 已迁出本类 ═══════════════════════
    //
    // 🕰️ 2026-09-07：AC-20 由三支路扩为**四层**（origin_id / 指纹 / 粒度列 / NO_ANCHOR），
    //    全部重写并迁到 {@link AnchorFourTiersAcTest}，在那里 4/4 全绿。
    //
    // 🚫 **本类刻意不再保留 T-20 的任何桩**。原来这里留着三个 pending() 桩，
    //    结果同一批用例被两个类覆盖：一边报「T-20a/b/c ⛔ 待接实现」、一边报「✅ 全绿」，
    //    读报告的人无法判断哪个是真的，而 pending 那份**看起来像真实的覆盖缺口**。
    //    ⇒ 同一件事两套实现/两套判据必然漂移 —— 这正是本项目反复在治的形态
    //    （VersionedGroupWriter 类注释、PricingSheetRegistry 双写、D-31 版本号规则都是同一个病）。
    //
    // ⚠️ T-10 / T-11 **仍在本类**且仍是有效待办（序列 AC，四层不覆盖它们）。

    // ═══════════════════════ 工具（ds 原生链路，2026-09-07 接实现）═══════════════════════

    private String axis(String tag) {
        return PREFIX + tag + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
    }

    private int maxVersion(String materialNo) {
        Object v = scalar("SELECT max(version_no) FROM " + EBOM + " WHERE material_no = '" + materialNo + "'");
        assertNotNull(v, "主表上找不到轴值 " + materialNo + " ⇒ 夹具没造出来，断言会空跑");
        return ((Number) v).intValue();
    }

    private int baseVersionOf(Fx fx, String materialNo) {
        Object v = scalar("SELECT DISTINCT base_version_no FROM " + EBOM + "_record "
                + "WHERE quotation_id = '" + fx.quotationId() + "' AND material_no = '" + materialNo + "'");
        assertNotNull(v, "本单在 _record 上没有轴值 " + materialNo + " 的行 ⇒ 快照没拍成");
        return ((Number) v).intValue();
    }

    /** {@code item_seq -> 业务列元组}（业务身份认人，🚫 不用会随升版换掉的技术 id）。 */
    private Map<Integer, String> groupBySeq(String materialNo) {
        return ebomBusinessRowsBySeq(materialNo);
    }

    private Map<Integer, String> historyGroupBySeq(String materialNo, int version) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Object[] r : rows("SELECT item_seq, "
                + "coalesce(element_code,'~') || '|' || coalesce(content_pct::text,'~') || '|' "
                + "|| coalesce(net_usage::text,'~') || '|' || coalesce(loss_rate::text,'~') || '|' "
                + "|| coalesce(gross_usage::text,'~') || '|' || coalesce(recovery_qty::text,'~') "
                + "FROM " + EBOM + "_history WHERE material_no = '" + materialNo.replace("'", "''")
                + "' AND version_no = " + version + " ORDER BY item_seq, id")) {
            out.merge(((Number) r[0]).intValue(), String.valueOf(r[1]), (x, y) -> x + " ;; " + y);
        }
        return out;
    }

    private long recCount(Fx fx, String materialNo) {
        return count("SELECT count(*) FROM " + EBOM + "_record WHERE quotation_id = '"
                + fx.quotationId() + "' AND material_no = '" + materialNo + "'");
    }

    private List<Object> recContent(Fx fx, String materialNo) {
        return col("SELECT coalesce(item_seq::text,'~') || '|' || coalesce(element_code,'~') || '|' "
                + "|| coalesce(content_pct::text,'~') || '|' || coalesce(net_usage::text,'~') "
                + "FROM " + EBOM + "_record WHERE quotation_id = '" + fx.quotationId()
                + "' AND material_no = '" + materialNo + "' ORDER BY item_seq, id");
    }

    private JsonNode findGroup(JsonNode dsBackfill, String tableName, String axisValue) {
        for (JsonNode t : dsBackfill.path("tables")) {
            if (!tableName.equals(t.path("tableName").asText())) continue;
            for (JsonNode g : t.path("groups")) {
                if (axisValue.equals(g.path("axisValue").asText())) return g;
            }
        }
        return null;
    }

    // 🕰️ 2026-09-07：原来这里有一份私有的 newSubmittedOrderForCustomer。
    //    合并 master 后轴变成复合 (customer_no, material_no)，「同客户」不再只是 AC-10 的前置，
    //    而是**所有**「先造主表组、再另建单表征它」的用例的通用要求
    //    ⇒ 已提升到 Task260907RBase，本类改用继承来的那份。
    //    🚫 不保留私有副本 —— 同一件事两套实现必然漂移（本项目治过三次的病）。

    /** 财务驳回（{@code dev-docs/main-api.md} §4.1.19，请求体 {@code {comment}}）。 */
    private Response costingReject(Fx fx, String comment) {
        return io.restassured.RestAssured.given().cookies(adminCookies())
                .contentType(io.restassured.http.ContentType.JSON)
                .body(Map.of("comment", comment))
                .when().post("/api/cpq/quotations/" + fx.quotationId() + "/costing-reject").thenReturn();
    }

    /** 开始编辑（{@code main-api.md} §4.1.21）—— 驳回后回到 DRAFT 才能改值。 */
    private Response beginEdit(Fx fx) {
        return io.restassured.RestAssured.given().cookies(adminCookies())
                .contentType(io.restassured.http.ContentType.JSON)
                .when().post("/api/cpq/quotations/" + fx.quotationId() + "/begin-edit").thenReturn();
    }

    /** 二次保存：以 {@code modified} 复用既有 line item（三数组协议 + {@code baseVersion}）。 */
    private Response saveDraftModified(Fx fx, String materialNo, List<EbomRow> rows) {
        Object liId = scalar("SELECT id FROM quotation_line_item WHERE quotation_id = '"
                + fx.quotationId() + "' LIMIT 1");
        assertNotNull(liId, "二次保存前置：找不到 line item ⇒ 夹具没建成");
        long ver = count("SELECT coalesce(user_data_version,0) FROM quotation WHERE id = '"
                + fx.quotationId() + "'");
        String body = "{\"baseVersion\":" + ver + ",\"added\":[],\"modified\":[{"
                + "\"id\":\"" + liId + "\",\"templateId\":\"" + DS_TEMPLATE_ID + "\","
                + "\"sortOrder\":0,\"compositeType\":\"SIMPLE\","
                + "\"productPartNo\":\"" + materialNo + "\",\"annualVolume\":1,"
                + "\"componentData\":[{\"componentId\":\"" + COMP_ELEMENT_BOM + "\","
                + "\"tabName\":\"" + TAB_ELEMENT_BOM + "\","
                + "\"rowData\":" + jsonStr(ebomRowData(materialNo, rows)) + ",\"sortOrder\":0}]}],"
                + "\"removed\":[]}";
        return io.restassured.RestAssured.given().cookies(adminCookies())
                .contentType(io.restassured.http.ContentType.JSON).body(body)
                .when().put("/api/cpq/quotations/" + fx.quotationId() + "/draft").thenReturn();
    }

}
