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
 * <b>T-10 / T-11 / T-20a / T-20b / T-20c —— 序列与双锚三支路</b>
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
@DisplayName("AC-10/11/20 · 序列与双锚三支路")
class BackfillAnchorAndSequenceAcTest extends Task260907RBase {

    private static final String MBOM = "ds_quote_material_bom";

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
     */
    @Test
    @DisplayName("T-10 · B 后通过：A 改过而 B 未表征的列在 v3 里仍是 A 的值")
    void t10_laterQuotationPatchesOnCurrentVersionNotSnapshotVersion() {
        requireRecordLayer();

        // ── 前置：同一客户下的 A、B 两单，同引料号 X，都基于 v1 拍快照
        String mat = PREFIX + "X10-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        seedMainGroup(mat, 4);
        assertEquals(1, maxVersion(mat), "夹具组应从 v1 起步");

        Fx a = newFixture("AC10-A");
        Fx b = newFixtureSameCustomer("AC10-B", a);
        snapshotRecord(a, mat, colsOf("component_qty"));      // A 表征 component_qty
        snapshotRecord(b, mat, colsOf("unit_weight"));        // B 表征 unit_weight（🔑 与 A 不重叠）
        assertEquals(1, baseVersionOf(a, mat), "AC-10 前置：A 的 _record.base_version_no 应为 1");
        assertEquals(1, baseVersionOf(b, mat), "AC-10 前置：B 的 _record.base_version_no 应为 1");

        // ── ① A 通过 → X 升到 v2，A 改了 component_qty
        changeRecord(a, mat, Map.of("component_qty", "111.0"));
        approve(a);
        assertEquals(2, maxVersion(mat), "AC-10 前置：A 确认后 X 应升到 v2");
        Map<String, String> aValues = currentGroupColumnValues(mat, "component_qty");
        assertFixtureNonEmpty(aValues.size(), "A 升版后 v2 的 component_qty 取值集");
        assertTrue(aValues.values().stream().anyMatch(v -> v != null && v.startsWith("111")),
                "AC-10 前置未成立：A 确认后 v2 里应能看到 A 写的 component_qty=111.0，实际=" + aValues);

        // ── ①' AC-10①：B 的预览必须显示「快照 v1 / 库当前 v2 / 将升 v3」
        Response pv = getPreview(b.quotationId());
        requireStatusBeforeDiff(pv, 200, "AC-10④ B 的核价通过预览");
        JsonNode g = findGroup(dsBackfill(ok(pv, "AC-10 预览")), MBOM, mat);
        assertNotNull(g, "AC-10①：B 的预览里应出现轴值 " + mat + " 的组，实际未出现");
        assertEquals(1, g.path("baseVersionNo").asInt(-1),
                "AC-10①：应显示快照基版 v1，实际 " + g.path("baseVersionNo"));
        assertEquals(2, g.path("currentVersionNo").asInt(-1),
                "AC-10①：应显示库当前 v2（必须让财务看见库里已经变过），实际 " + g.path("currentVersionNo"));
        assertEquals(3, g.path("targetVersionNo").asInt(-1),
                "AC-10①：应显示将升到 v3，实际 " + g.path("targetVersionNo"));
        assertTrue(g.path("crossVersion").asBoolean(false),
                "AC-10①：baseVersionNo(1) != currentVersionNo(2) ⇒ crossVersion 应为 true，实际 " + g.path("crossVersion"));

        // ── ② B 确认
        changeRecord(b, mat, Map.of("unit_weight", "7.77"));
        Response ap = postApprove(b.quotationId(), g.path("previewToken").asText(
                dsBackfill(ok(pv, "AC-10 预览")).path("previewToken").asText(
                        ok(pv, "AC-10 预览").path("previewToken").asText())), PREFIX + "AC10-B");
        // ── AC-10④：B 不被拒绝、不报 409
        assertFalse(ap.statusCode() == 409,
                "AC-10④：B 不应因「快照基版落后」被 409 拒绝（D-25 已推翻「拒绝后通过的单」这一设计）。"
                        + "body=" + ap.asString());
        requireStatusBeforeDiff(ap, 200, "AC-10④ B 的核价通过确认");

        assertEquals(3, maxVersion(mat), "AC-10②：B 确认后 X 应升到 v3");

        // ── 🔑 AC-10②：A 改过、B 未表征的列，在 v3 里仍是 A 的值
        Map<String, String> v3Qty = currentGroupColumnValues(mat, "component_qty");
        assertEquals(aValues, v3Qty,
                "🔑 AC-10②：B 的页签没表征 component_qty，因此 v3 里它必须仍是 A 在 v2 写的值"
                        + "（基底应是主表当前 v2 的整组行，🚫 不是回到 v1）。"
                        + "A 在 v2 的值=" + aValues + "，v3 实际=" + v3Qty
                        + " ⇒ 不相等说明 B 的升版把 A 的数据整体覆盖回 v1 了。"
                        + "🚫 注意：只断言「B 写的 unit_weight 进去了」在这种情况下照样成立，所以那不是判据。");
        // 反向配对：B 表征的列确实写进去了（防止修成「什么都不写」）
        Map<String, String> v3Weight = currentGroupColumnValues(mat, "unit_weight");
        assertTrue(v3Weight.values().stream().anyMatch(v -> v != null && v.startsWith("7.77")),
                "AC-10②反向：B 表征并改了 unit_weight=7.77，v3 里应能看到，实际=" + v3Weight);

        // ── AC-10③：v2 完整进 _history，可查回 A 的那一版
        long v2Rows = count("SELECT count(*) FROM " + MBOM + "_history WHERE material_no = '" + mat
                + "' AND version_no = 2");
        assertEquals(4L, v2Rows,
                "AC-10③：v2 应<b>完整</b>（4 行）进 _history 以便查回 A 的那一版，实际 " + v2Rows + " 行");
        Map<String, String> histQty = historyGroupColumnValues(mat, 2, "component_qty");
        assertEquals(aValues, histQty,
                "AC-10③：从 _history 查回的 v2 应逐字等于 A 当时写的内容。期望=" + aValues + " 实际=" + histQty);
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
     */
    @Test
    @DisplayName("T-11 · 序列：_record 覆盖式只有一份；回填写最后一次的值；驳回不升版")
    void t11_rejectThenResubmitSequence() {
        requireRecordLayer();

        String mat = PREFIX + "X11-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        seedMainGroup(mat, 3);
        Fx fx = newFixture("AC11");
        snapshotRecord(fx, mat, colsOf("component_qty"));

        int verAtStart = maxVersion(mat);

        // ── 第一次保存的值
        changeRecord(fx, mat, Map.of("component_qty", "100.0"));
        long recCountAfterFirst = count("SELECT count(*) FROM " + MBOM + "_record WHERE quotation_id = '"
                + fx.quotationId() + "' AND material_no = '" + mat + "'");
        assertFixtureNonEmpty(recCountAfterFirst, "第一次保存后本单 _record 行数");

        requireStatusBeforeDiff(submit(fx), 200, "AC-11 首次提交");
        reject(fx);

        // ── 🔑 AC-11③ 中间态：驳回后、再提交前，主表 version_no 未变
        assertEquals(verAtStart, maxVersion(mat),
                "AC-11③：驳回不触发回填，主表 version_no 应仍为 " + verAtStart + "，实际 " + maxVersion(mat));

        // ── 改同一行的值 → 再保存
        changeRecord(fx, mat, Map.of("component_qty", "200.0"));

        // ── AC-11①：_record 覆盖式，最终只有一份
        long recCountAfterSecond = count("SELECT count(*) FROM " + MBOM + "_record WHERE quotation_id = '"
                + fx.quotationId() + "' AND material_no = '" + mat + "'");
        assertEquals(recCountAfterFirst, recCountAfterSecond,
                "AC-11①：_record 是覆盖式的，改了再存不应累积出第二份。"
                        + "第一次后 " + recCountAfterFirst + " 行，第二次后 " + recCountAfterSecond + " 行");
        List<Object> recValues = col("SELECT DISTINCT component_qty::text FROM " + MBOM + "_record "
                + "WHERE quotation_id = '" + fx.quotationId() + "' AND material_no = '" + mat + "'");
        assertTrue(recValues.stream().allMatch(v -> v != null && v.toString().startsWith("200")),
                "AC-11①：_record 内容应 = 最后一次保存的值 200.0，实际取值集=" + recValues
                        + " ⇒ 出现 100.0 说明第一次的快照没被覆盖");

        requireStatusBeforeDiff(submit(fx), 200, "AC-11 再次提交");
        approve(fx);

        // ── AC-11②：回填写进主表的是最后一次的值
        Map<String, String> mainQty = currentGroupColumnValues(mat, "component_qty");
        assertFixtureNonEmpty(mainQty.size(), "回填后主表该组的 component_qty 取值集");
        assertTrue(mainQty.values().stream().anyMatch(v -> v != null && v.startsWith("200")),
                "AC-11②：回填应写最后一次的值 200.0，实际主表=" + mainQty);
        assertFalse(mainQty.values().stream().anyMatch(v -> v != null && v.startsWith("100")),
                "AC-11②：主表里出现了第一次的值 100.0 ⇒ 回填用的是被驳回那一版的快照。实际=" + mainQty);
    }

    /**
     * <b>T-20a（AC-20 同版支路）</b>：{@code base_version_no == 主表当前 version_no}
     * → 按 {@code origin_id} 对位，patch 落在<b>正确的行</b>上。
     *
     * <p>判据形态照 AC 原文：<b>改第 2 行的值，第 1/3 行逐字未变</b>。
     * 🔑 「落在正确的行上」不能只验「第 2 行变了」—— 那在「三行全被改成同一个值」时照样成立。
     */
    @Test
    @DisplayName("T-20a · 同版：按 origin_id 对位，改第 2 行，第 1/3 行逐字未变")
    void t20a_sameVersionAnchorsByOriginId() {
        requireRecordLayer();

        String mat = PREFIX + "X20a-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        seedMainGroup(mat, 3);
        Fx fx = newFixture("AC20a");
        snapshotRecord(fx, mat, colsOf("component_qty"));

        assertEquals(maxVersion(mat), baseVersionOf(fx, mat),
                "AC-20 同版支路前置：_record.base_version_no 应 == 主表当前 version_no");

        Map<Long, String> before = groupSnapshotByOrigin(mat);
        assertEquals(3, before.size(), "夹具应为 3 行");
        Long row2 = List.copyOf(before.keySet()).get(1);

        changeRecordRow(fx, mat, row2, Map.of("component_qty", "555.0"));
        approve(fx);

        Map<Long, String> after = groupSnapshotByOrigin(mat);
        assertEquals(before.keySet(), after.keySet(),
                "AC-20 同版支路：行集合不应变化（origin 身份保持），before=" + before.keySet()
                        + " after=" + after.keySet());
        for (Long id : before.keySet()) {
            if (id.equals(row2)) {
                assertFalse(before.get(id).equals(after.get(id)),
                        "AC-20 同版支路：被改的第 2 行（origin#" + id + "）应发生变化，实测逐字未变");
            } else {
                assertEquals(before.get(id), after.get(id),
                        "AC-20 同版支路：第 1/3 行（origin#" + id + "）必须逐字未变 —— "
                                + "🔑 只验「第 2 行变了」在三行全被改成同值时照样成立，所以本条才是判据。"
                                + " before=" + before.get(id) + " after=" + after.get(id));
            }
        }
    }

    /**
     * <b>T-20b（AC-20 跨版支路）</b>：别的单先把该组升过版（{@code id} 已换）
     * → 改用 {@code base_row_fingerprint} 重锚成功，patch 仍落在<b>内容对应的那一行</b>上。
     *
     * <p>🚨 <b>前置必须先实证 {@code id} 确已改变</b>（{@code test.md} 风险点 3）：
     * 对比升版前后主表 {@code id} 集合，<b>交集为空</b>。不先证明这一点，
     * 「指纹兜底生效了」就无从谈起 —— 因为 {@code origin_id} 可能压根还是有效的，
     * 那时用例走的其实是同版支路，却被当成跨版支路报绿。
     *
     * <p>本用例是 <b>E-3</b> 证伪实验的靶子（关掉指纹重锚只留 {@code origin_id} → 必须变红）。
     */
    @Test
    @DisplayName("T-20b · 跨版：先实证 id 交集为空，再验指纹重锚落在内容对应的行上")
    void t20b_crossVersionReanchorsByFingerprint() {
        requireRecordLayer();

        String mat = PREFIX + "X20b-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        seedMainGroup(mat, 3);

        Fx b = newFixture("AC20b-B");
        snapshotRecord(b, mat, colsOf("component_qty"));    // B 基于 v1 拍快照
        assertEquals(1, baseVersionOf(b, mat), "跨版支路前置：B 的快照基版应为 v1");

        // ── 🚨 风险点 3：自己先把组升上去，并实证 id 集合交集为空
        Set<Long> idsBefore = idSet(MBOM, "material_no", mat);
        Fx a = newFixtureSameCustomer("AC20b-A", b);
        snapshotRecord(a, mat, colsOf("unit_weight"));
        changeRecord(a, mat, Map.of("unit_weight", "3.33"));
        approve(a);
        Set<Long> idsAfter = idSet(MBOM, "material_no", mat);
        assertIdSetsDisjoint(idsBefore, idsAfter, "跨版支路的料号组 " + mat);
        assertEquals(2, maxVersion(mat), "跨版支路前置：A 确认后应升到 v2");

        // ── B 的快照此时 origin_id 全部失效 ⇒ 必须靠指纹重锚
        // 记录 B 要改的那一行「内容上」是哪一行（按 item_seq 认人，item_seq 是业务身份不是技术 id）
        int targetSeq = 2;
        String beforeOtherSeq1 = valueAt(mat, 1, "component_qty");
        String beforeOtherSeq3 = valueAt(mat, 3, "component_qty");
        assertNotNull(beforeOtherSeq1, "夹具 item_seq=1 的行不存在 ⇒ 断言会空跑");
        assertNotNull(beforeOtherSeq3, "夹具 item_seq=3 的行不存在 ⇒ 断言会空跑");

        changeRecordRowBySeq(b, mat, targetSeq, Map.of("component_qty", "888.0"));

        Response pv = getPreview(b.quotationId());
        requireStatusBeforeDiff(pv, 200, "AC-20b B 的预览");
        JsonNode g = findGroup(dsBackfill(ok(pv, "AC-20b 预览")), MBOM, mat);
        assertNotNull(g, "AC-20b：预览里应出现轴值 " + mat + " 的组");
        assertTrue(g.path("crossVersion").asBoolean(false),
                "AC-20b：base(v1) != current(v2) ⇒ crossVersion 应为 true，实际 " + g);
        assertEquals(0, g.path("unanchoredRows").size(),
                "AC-20b：内容未变的行应能靠指纹重锚成功，不该进「对不上」区。实际 unanchoredRows="
                        + g.path("unanchoredRows"));

        approve(b);

        // ── 断言：patch 落在内容对应的那一行（item_seq=2），其余行逐字未变
        assertTrue(String.valueOf(valueAt(mat, targetSeq, "component_qty")).startsWith("888"),
                "AC-20b：指纹重锚后 patch 应落在内容对应的行（item_seq=" + targetSeq + "）上，实际值="
                        + valueAt(mat, targetSeq, "component_qty")
                        + " ⇒ 落错行说明重锚失败或锚到了别的行（E-3 靶子）");
        assertEquals(beforeOtherSeq1, valueAt(mat, 1, "component_qty"),
                "AC-20b：item_seq=1 的行未被表征，应逐字未变");
        assertEquals(beforeOtherSeq3, valueAt(mat, 3, "component_qty"),
                "AC-20b：item_seq=3 的行未被表征，应逐字未变");
    }

    /**
     * <b>T-20c（AC-20 锚不上支路）</b>：跨版且该行<b>同时被别的单改过值</b>（指纹已变）。
     *
     * <p>断言：
     * ① 确认界面出现「对不上的行」明细区并列出该行（界面形态归 E2E，本条验数据侧：
     *    {@code unanchoredRows} 非空且含该行，{@code reason} 有值）；
     * ② 财务不确认时<b>一个字节不落库</b>；
     * ③ 财务确认后，该行按新增写入且 🔑 <b>主表原行未被静默删除</b>。
     *
     * <p>🔑 断言③必须验「原行还在」，🚫 不能只验「新值写进去了」——
     * 后者在原行被删之后照样成立（AC 原文的 🔑 注记）。
     */
    @Test
    @DisplayName("T-20c · 锚不上：进「对不上」区；不确认零落库；确认后原行未被静默删除")
    void t20c_unanchoredRowVisibleAndOriginalRowSurvives() {
        requireRecordLayer();

        String mat = PREFIX + "X20c-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        seedMainGroup(mat, 3);

        Fx b = newFixture("AC20c-B");
        snapshotRecord(b, mat, colsOf("component_qty"));
        assertEquals(1, baseVersionOf(b, mat), "锚不上支路前置：B 的快照基版应为 v1");

        // ── A 升版，并且**改掉 B 要改的那一行的值** ⇒ 指纹已变，B 锚不上
        Set<Long> idsBefore = idSet(MBOM, "material_no", mat);
        Fx a = newFixtureSameCustomer("AC20c-A", b);
        snapshotRecord(a, mat, colsOf("component_qty"));
        changeRecordRowBySeq(a, mat, 2, Map.of("component_qty", "666.0"));
        approve(a);
        assertIdSetsDisjoint(idsBefore, idSet(MBOM, "material_no", mat), "锚不上支路的料号组 " + mat);

        changeRecordRowBySeq(b, mat, 2, Map.of("component_qty", "999.0"));

        // ── AC-20c①：预览里出现「对不上的行」
        Response pv = getPreview(b.quotationId());
        requireStatusBeforeDiff(pv, 200, "AC-20c B 的预览");
        JsonNode ds = dsBackfill(ok(pv, "AC-20c 预览"));
        JsonNode g = findGroup(ds, MBOM, mat);
        assertNotNull(g, "AC-20c：预览里应出现轴值 " + mat + " 的组");
        JsonNode unanchored = g.path("unanchoredRows");
        assertTrue(unanchored.isArray() && unanchored.size() > 0,
                "AC-20c①：该行在 v2 里已被 A 改过值（指纹已变）⇒ 应进「对不上的行」明细区，"
                        + "实际 unanchoredRows=" + unanchored + "，group=" + g);
        for (JsonNode u : unanchored) {
            for (String k : List.of("recordId", "originId", "baseRowFingerprint", "displayValues", "reason")) {
                assertTrue(u.has(k), "api.md §1：unanchoredRows 元素缺字段 " + k + "，实际=" + u);
            }
            assertFalse(u.path("displayValues").isEmpty(),
                    "AC-20c①：displayValues 为空，财务在界面上看不出这是哪一行。实际=" + u);
        }
        assertTrue(ds.path("summary").path("unanchoredRows").asInt(0) > 0,
                "AC-20c①：汇总条的 unanchoredRows 应 > 0（>0 时前端必须显著提示），实际="
                        + ds.path("summary"));

        // ── AC-20c②：不确认 → 一个字节不落库（范围按轴值收窄，🚫 不整表 md5）
        List<String> axes = List.of(mat);
        String mainDigest = scopedDigest(MBOM, "material_no", axes, "AC-20c② 主表基线");
        String histDigest = scopedDigest(MBOM + "_history", "material_no", axes, "AC-20c② _history 基线");
        getPreview(b.quotationId());   // 再看一次，仍不确认
        assertEquals(mainDigest, scopedDigest(MBOM, "material_no", axes, "AC-20c② 主表复测"),
                "AC-20c②：财务不确认时主表该组应一个字节不变");
        assertEquals(histDigest, scopedDigest(MBOM + "_history", "material_no", axes, "AC-20c② _history 复测"),
                "AC-20c②：财务不确认时 _history 该组应一个字节不变");

        // ── AC-20c③：确认后，新行写入 + 🔑 原行未被静默删除
        Map<Integer, String> beforeBySeq = groupBySeq(mat);
        assertFixtureNonEmpty(beforeBySeq.size(), "确认前主表该组按 item_seq 的行");
        long rowsBefore = count("SELECT count(*) FROM " + MBOM + " WHERE material_no = '" + mat + "'");

        approve(b);

        long rowsAfter = count("SELECT count(*) FROM " + MBOM + " WHERE material_no = '" + mat + "'");
        assertEquals(rowsBefore + 1, rowsAfter,
                "AC-20c③：锚不上的行应按<b>新增</b>写入 ⇒ 行数 " + rowsBefore + " → " + (rowsBefore + 1)
                        + "，实际 " + rowsAfter
                        + "。若 = " + rowsBefore + " 说明它覆盖了原行（那就是静默删除）");

        // 🔑 原行还在：A 在 v2 写的 666.0 必须仍能在新版里找到
        Map<Integer, String> afterBySeq = groupBySeq(mat);
        assertTrue(afterBySeq.values().stream().anyMatch(v -> v != null && v.contains("666")),
                "🔑 AC-20c③：主表原行（A 在 v2 写的 component_qty=666.0）被静默删除了。"
                        + "🚫 只验「B 的 999.0 写进去了」在原行被删之后照样成立，所以那不是判据。"
                        + "确认前=" + beforeBySeq + " 确认后=" + afterBySeq);
        assertTrue(afterBySeq.values().stream().anyMatch(v -> v != null && v.contains("999")),
                "AC-20c③ 反向：B 的新行应写入，实际=" + afterBySeq);
    }

    // ═══════════════════════ 工具 ═══════════════════════

    private void seedMainGroup(String materialNo, int rows) {
        inTx(() -> {
            for (int i = 1; i <= rows; i++) {
                insertMaterialBomRow(materialNo, i, PREFIX + "IN-" + i, String.valueOf(i * 10), 1);
            }
        });
        assertFixtureNonEmpty(count("SELECT count(*) FROM " + MBOM + " WHERE material_no = '" + materialNo + "'"),
                "夹具主表组 " + materialNo + " 行数");
    }

    private int maxVersion(String materialNo) {
        Object v = scalar("SELECT max(version_no) FROM " + MBOM + " WHERE material_no = '" + materialNo + "'");
        assertNotNull(v, "主表上找不到轴值 " + materialNo + " ⇒ 夹具没造出来，断言会空跑");
        return ((Number) v).intValue();
    }

    private int baseVersionOf(Fx fx, String materialNo) {
        Object v = scalar("SELECT DISTINCT base_version_no FROM " + MBOM + "_record "
                + "WHERE quotation_id = '" + fx.quotationId() + "' AND material_no = '" + materialNo + "'");
        assertNotNull(v, "本单在 _record 上没有轴值 " + materialNo + " 的行 ⇒ 快照没拍成");
        return ((Number) v).intValue();
    }

    /** {@code item_seq -> component_qty}（业务身份认人，🚫 不用会随升版换掉的技术 id）。 */
    private Map<Integer, String> groupBySeq(String materialNo) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Object[] r : rows("SELECT item_seq, coalesce(component_qty::text,'<NULL>') FROM " + MBOM
                + " WHERE material_no = '" + materialNo + "' ORDER BY item_seq, id")) {
            out.merge(((Number) r[0]).intValue(), String.valueOf(r[1]), (x, y) -> x + "," + y);
        }
        return out;
    }

    private String valueAt(String materialNo, int itemSeq, String column) {
        Object v = scalar("SELECT " + sqlSafe(column) + "::text FROM " + MBOM
                + " WHERE material_no = '" + materialNo + "' AND item_seq = " + itemSeq + " ORDER BY id LIMIT 1");
        return v == null ? null : v.toString();
    }

    private Map<String, String> currentGroupColumnValues(String materialNo, String column) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Object[] r : rows("SELECT item_seq::text, coalesce(" + sqlSafe(column) + "::text,'<NULL>') FROM "
                + MBOM + " WHERE material_no = '" + materialNo + "' ORDER BY item_seq, id")) {
            out.merge(String.valueOf(r[0]), String.valueOf(r[1]), (x, y) -> x + "," + y);
        }
        return out;
    }

    private Map<String, String> historyGroupColumnValues(String materialNo, int version, String column) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Object[] r : rows("SELECT item_seq::text, coalesce(" + sqlSafe(column) + "::text,'<NULL>') FROM "
                + MBOM + "_history WHERE material_no = '" + materialNo + "' AND version_no = " + version
                + " ORDER BY item_seq, id")) {
            out.merge(String.valueOf(r[0]), String.valueOf(r[1]), (x, y) -> x + "," + y);
        }
        return out;
    }

    private Map<Long, String> groupSnapshotByOrigin(String materialNo) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (Object[] r : rows("SELECT item_seq, coalesce(component_qty::text,'<NULL>') || '|' "
                + "|| coalesce(unit_weight::text,'<NULL>') FROM " + MBOM
                + " WHERE material_no = '" + materialNo + "' ORDER BY item_seq, id")) {
            out.put(((Number) r[0]).longValue(), String.valueOf(r[1]));
        }
        return out;
    }

    private static Set<String> colsOf(String... cols) {
        return new java.util.LinkedHashSet<>(List.of(cols));
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

    // ⛔ 待接实现的挂载点 —— 🚫 刻意不返回空实现

    private Fx newFixtureSameCustomer(String label, Fx sibling) {
        throw pending("建一张与 " + sibling.quotationNo() + " <b>同客户</b>的报价单 " + PREFIX + label
                + "（AC-10 前置：A、B 必须属于同一客户）");
    }

    private void snapshotRecord(Fx fx, String materialNo, Set<String> representedColumns) {
        throw pending("为 " + fx.quotationNo() + " 拍 " + materialNo + " 的 _record 快照，"
                + "只表征列 " + representedColumns);
    }

    private void changeRecord(Fx fx, String materialNo, Map<String, String> values) {
        throw pending("把 " + fx.quotationNo() + " 的 " + materialNo + " 整组 _record 改成 " + values);
    }

    private void changeRecordRow(Fx fx, String materialNo, long originId, Map<String, String> values) {
        throw pending("把 " + fx.quotationNo() + " 的 " + materialNo + " 中 origin#" + originId
                + " 那一行 _record 改成 " + values);
    }

    private void changeRecordRowBySeq(Fx fx, String materialNo, int itemSeq, Map<String, String> values) {
        throw pending("把 " + fx.quotationNo() + " 的 " + materialNo + " 中 item_seq=" + itemSeq
                + " 那一行 _record 改成 " + values);
    }

    private void reject(Fx fx) {
        throw pending("财务驳回 " + fx.quotationNo() + "（AC-11 序列的中间态）");
    }

    private void approve(Fx fx) {
        throw pending("对 " + fx.quotationNo() + " 预览拿 previewToken → 带 token 确认核价通过");
    }

    private static UnsupportedOperationException pending(String what) {
        return new UnsupportedOperationException(
                "⛔ 待接实现（**不是被测功能的结论**）：" + what
                        + "。缺的契约信息已在测试回报的「缺什么」清单里列给主线。");
    }
}
