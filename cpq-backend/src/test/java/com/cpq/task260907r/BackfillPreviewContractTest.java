package com.cpq.task260907r;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-05 / T-07 / T-14 / T-18 —— 核价通过预览与确认的接口契约</b>
 * （AC-5 预览展示「将写入什么」· AC-7 取消零副作用 · AC-14 零变更不写不删 · AC-18 并发确认）
 *
 * <p>断言口径只来自 {@code api.md §1/§2} 与 {@code 需求文档.md} 的 AC 原文 ——
 * 🚫 <b>一律走 HTTP + 原始 JSON</b>，不引用任何后端 DTO 类。
 * DTO 字段一改名测试<b>应该</b>红（那是契约变了）；若测试跟着实现的类结构走，它就永远不会红。
 */
@QuarkusTest
@DisplayName("AC-5/7/14/18 · 核价通过预览与确认契约")
class BackfillPreviewContractTest extends Task260907RBase {

    /**
     * 🔑 被测主表 = {@code ds_quote_element_bom}（原写 {@code ds_quote_material_bom}）。
     * 改的理由不是「换张容易的」：实查「报价模板 · ds 原生 v1.0」的 13 个组件里<b>没有物料BOM</b>，
     * ⇒ {@code ds_quote_material_bom} 走不通报价单页签这条路、造不出 {@code _record}；
     * 而「T260907-物料与元素BOM」→ {@code ds_quote_element_bom} 已实测跑通（FX-01/02/03）。
     */
    private static final String MBOM = EBOM;

    @AfterEach
    void tearDown() {
        cleanupOwnFixtures();
    }

    /**
     * <b>T-05（AC-5 + api.md §1）</b>：预览<b>只读、不升版</b>，且响应描述的是「将写入什么」。
     *
     * <p>断言：
     * ① 预览调用后主表 {@code version_no} <b>逐字未变</b>（AC-5① 的可测投影 —— 抽屉形态归 E2E）；
     * ② 响应含 {@code dsBackfill}，其 {@code summary} 五项齐全（AC-5③ 汇总条）；
     * ③ 每个 group 必须含 {@code untouchedRows} 与 {@code columnScope.preserved}
     *    —— 🚨 <b>AP-60 的核心守卫</b>：预览只描述「哪些值变了」会漏掉「不写 = 删除」这一整类后果，
     *    {@code repair-0727} 的真实事故正是预览显示「0 变更」而执行删了 3 行。
     *
     * <p>🚨 空验证对策：先断言 {@code tables[]} / {@code groups[]} <b>非空</b>，
     * 否则「每个 group 都含 untouchedRows」在 0 个 group 时恒真恒通过。
     */
    @Test
    @DisplayName("T-05 · 预览不升版；响应含 untouchedRows 与 columnScope.preserved")
    void t05_previewIsReadOnlyAndDescribesWhatWillBeWritten() {
        requireRecordLayer();
        Fx fx = newSubmittedQuotationWithGroup("AC5");
        String mat = axisOf(fx);

        List<Object> verBefore = col("SELECT id || '|' || version_no FROM " + MBOM
                + " WHERE material_no = '" + mat + "' ORDER BY id");
        assertFixtureNonEmpty(verBefore.size(), "预览前主表该组行数");

        Response r = getPreview(fx.quotationId());
        // 🚨 共同纪律②：任何结论之前先断言状态码
        requireStatusBeforeDiff(r, 200, "AC-5 预览 GET " + String.format(PREVIEW, fx.quotationId()));

        JsonNode ds = dsBackfill(ok(r, "AC-5 预览"));

        // ── AC-5①：预览不升版
        List<Object> verAfter = col("SELECT id || '|' || version_no FROM " + MBOM
                + " WHERE material_no = '" + mat + "' ORDER BY id");
        assertEquals(verBefore, verAfter,
                "AC-5①：预览是只读的，主表 version_no 必须逐字未变。before=" + verBefore + " after=" + verAfter);

        // ── api.md §1：applicable / confirmRequired
        assertTrue(ds.path("applicable").asBoolean(false),
                "api.md §1：新链路单的 dsBackfill.applicable 应为 true，实际 " + ds.path("applicable"));
        assertTrue(ds.path("confirmRequired").asBoolean(false),
                "api.md §1：confirmRequired 恒 true（D-25 财务必须人工确认），实际 " + ds.path("confirmRequired"));

        // ── AC-5③：汇总条五项
        JsonNode sum = ds.path("summary");
        for (String k : List.of("tables", "axes", "upgradedGroups", "unchangedGroups", "unanchoredRows")) {
            assertTrue(sum.has(k), "AC-5③：dsBackfill.summary 缺字段 " + k + "，实际=" + sum);
        }

        // ── AC-5②④：逐组九列的数据侧
        JsonNode tables = ds.path("tables");
        assertTrue(tables.isArray() && tables.size() > 0,
                "🚨 空验证守卫：dsBackfill.tables 为空 ⇒ 下面「每个 group 都含 untouchedRows」会 0 次循环恒真。"
                        + "实际=" + tables);
        int groupCount = 0;
        for (JsonNode t : tables) {
            for (String k : List.of("sheetKey", "sheetName", "tableName")) {
                assertTrue(t.has(k), "api.md §1：table 段缺字段 " + k + "，实际=" + t);
            }
            for (JsonNode g : t.path("groups")) {
                groupCount++;
                for (String k : List.of("axisValue", "customerNo", "baseVersionNo", "currentVersionNo",
                        "targetVersionNo", "crossVersion", "result", "baseRowCount", "resultRowCount",
                        "patchedRows", "untouchedRows", "unanchoredRows", "columnScope")) {
                    assertTrue(g.has(k), "api.md §1：group 段缺字段 " + k + "（AC-5② 九列的数据来源），实际=" + g);
                }
                // 🚨 AP-60 列维度：preserved 必须在，且不是空壳
                JsonNode scope = g.path("columnScope");
                assertTrue(scope.has("patched") && scope.has("preserved"),
                        "AC-5④ / AP-60：columnScope 必须同时给 patched 与 preserved，实际=" + scope);
                assertTrue(scope.path("preserved").isArray(),
                        "AP-60：columnScope.preserved 应为数组，实际=" + scope.path("preserved"));
                // customerNo：上游 DDL 落库前允许 null（api.md §4 过渡期约定），但字段本身必须在
                assertTrue(g.path("customerNo").isTextual() || g.path("customerNo").isNull(),
                        "api.md §4：customerNo 类型应为 string，过渡期允许 null，实际=" + g.path("customerNo"));
            }
        }
        assertFixtureNonEmpty(groupCount,
                "预览返回的料号组数（阳性对照：0 组时上面所有逐组断言都没执行过）");

        // ── 🆕 D-33：nonParticipating 必须存在（手写视图组件不参与升版，**必须显式告知**）
        //    🚨 这与 AP-60 判据四是**同型的静默**：
        //       AP-60 漏的是「不写 = 删除」这类后果不在 diff 模型里；
        //       本条漏的是「这些组件压根没被算进来」不在预览里。
        //       实测现网 156/228 = 68% 的组件视图是手写的（无 builder_config）——
        //       不说出来，财务会以为回填覆盖了全单。
        JsonNode nonPart = ds.path("nonParticipating");
        assertTrue(!nonPart.isMissingNode() && nonPart.isArray(),
                "api.md §1 / D-33：dsBackfill 必须含 nonParticipating 数组（哪怕为空数组）。"
                        + "🚫 字段缺失 ≠ 没有不参与的组件 —— 前者让财务无从知道，属静默。实际=" + nonPart);
        for (JsonNode np : nonPart) {
            for (String k : List.of("componentId", "componentName", "reason")) {
                assertTrue(np.has(k),
                        "api.md §1：nonParticipating 元素缺字段 " + k
                                + "（缺了财务看不出是哪个组件、为什么不参与）。实际=" + np);
            }
        }
        System.out.println("[T-05] 实际返回 tables=" + tables.size() + " groups=" + groupCount
                + " nonParticipating=" + nonPart.size() + " summary=" + sum);
    }

    /**
     * <b>T-07（AC-7，2026-09-07 已按「活数据陷阱」重写）</b>：取消 → 一个字节不落库。
     *
     * <p>🚨 <b>比对范围必须按轴值收窄到本单自己的组</b>，🚫 不许对整表取 md5 ——
     * 整表 md5 会罩住共享库里别的会话正在写的行，那是个必然漂移的判据（需求文档「共同纪律①」，
     * 实证：master 同码重采，14 个端点 1 个仍漂移，`total` 47→49）。
     *
     * <p>「取消」在契约上没有独立端点（api.md 只有 preview + approve 两个）⇒
     * <b>取消 = 调了预览之后不调确认</b>。判据落在「预览之后、未确认之前，三处逐字未变」。
     */
    @Test
    @DisplayName("T-07 · 取消后本单轴值组在主表/_history/_record 三处逐字未变")
    void t07_cancelWritesNothing() {
        requireRecordLayer();
        Fx fx = newSubmittedQuotationWithGroup("AC7");
        List<String> axes = List.of(axisOf(fx));

        String mainBefore = scopedDigest(MBOM, "material_no", axes, "AC-7 主表基线");
        String histBefore = scopedDigest(MBOM + "_history", "material_no", axes, "AC-7 _history 基线", false);
        String recBefore = scopedDigest(MBOM + "_record", "material_no", axes, "AC-7 _record 基线");
        long approvalBefore = count("SELECT count(*) FROM quotation_approval WHERE quotation_id = '"
                + fx.quotationId() + "'");

        Response r = getPreview(fx.quotationId());
        requireStatusBeforeDiff(r, 200, "AC-7 预览");
        // 取消 = 拿到 previewToken 后不提交。此处显式不调 POST。

        // ── AC-7①：三处逐字未变（范围已按轴值收窄）
        assertEquals(mainBefore, scopedDigest(MBOM, "material_no", axes, "AC-7 主表复测"),
                "AC-7①：取消后主表该组不应有任何变化");
        assertEquals(histBefore, scopedDigest(MBOM + "_history", "material_no", axes, "AC-7 _history 复测", false),
                "AC-7①：取消后 _history 该组不应有任何变化");
        assertEquals(recBefore, scopedDigest(MBOM + "_record", "material_no", axes, "AC-7 _record 复测"),
                "AC-7①：取消后 _record 该组不应有任何变化");

        // ── AC-7②：仍为 SUBMITTED，且可再次发起
        assertEquals("SUBMITTED", String.valueOf(scalar(
                        "SELECT status FROM quotation WHERE id = '" + fx.quotationId() + "'")),
                "AC-7②：取消后报价单应仍为 SUBMITTED");
        Response again = getPreview(fx.quotationId());
        requireStatusBeforeDiff(again, 200, "AC-7② 再次发起核价通过预览");

        // ── AC-7③：未写 quotation_approval
        assertEquals(approvalBefore, count("SELECT count(*) FROM quotation_approval WHERE quotation_id = '"
                        + fx.quotationId() + "'"),
                "AC-7③：取消不应写 quotation_approval 记录");

        // ── 🆕 D-32：previewToken 的计算**必须纳入 dsBackfill**
        //    现状（改动前）token 只哈希老 QuoteBackfillPlan.groups ⇒ 预览后销售再保存一次、
        //    `_record` 变了而 token 不变，于是财务确认的对象已经不是她看过的那份，保护完全失效。
        //    ⇒ 判据：拿到 token 后**改一次 `_record`**，再带原 token 提交，必须 409。
        //    🔑 这条是「保护是否真的接上」的证伪 —— 只验「不改就能提交成功」证明不了任何事。
        String token = ds(again, "previewToken");
        String d32Before = scopedDigest(MBOM + "_record", "material_no", axes, "D-32 改动前 _record");
        mutateRecordAfterPreview(fx);
        String d32After = scopedDigest(MBOM + "_record", "material_no", axes, "D-32 改动后 _record");
        assertFalse(d32Before.equals(d32After),
                "🚨 干预未生效：本该把 _record 改掉，实测 digest 逐字未变 ⇒ 下面的 409 断言无从谈起"
                        + "（不先证明干预生效，『它返了 409』可能只是别的原因）。before=" + d32Before);

        Response stale = postApprove(fx.quotationId(), token, PREFIX + "AC7-stale");
        assertEquals(409, stale.statusCode(),
                "D-32：预览之后 _record 变了，带旧 previewToken 提交必须返 409"
                        + "（文案「报价数据在预览后发生变化，请重新预览」）。实际 " + stale.statusCode()
                        + " ⇒ token 没把 dsBackfill 纳入计算，财务确认的对象已不是她看过的那份。"
                        + " body=" + stale.asString());
    }

    /**
     * ⛔ 待接实现：预览之后改一次本单的 {@code _record}（模拟「销售在财务确认前又保存了一次」）。
     * 🚫 刻意不返回空实现 —— 空实现会让上面的 409 断言在「什么都没改」的情况下跑，
     * 那时返 409 与否都不构成证据。
     */
    private void mutateRecordAfterPreview(Fx fx) {
        throw new UnsupportedOperationException(
                "⛔ 待接实现（**不是被测功能的结论**）：在预览之后修改 " + fx.quotationNo()
                        + " 的 _record（走 saveDraft，模拟销售又保存了一次），用于验 D-32 的 409 保护。");
    }

    /**
     * <b>T-14（AC-14）</b>：零变更 → 判 {@code UNCHANGED}，不写不删，<b>但仍出现在列表里</b>。
     *
     * <p>断言：① {@code result = UNCHANGED}，{@code version_no} 与 {@code updated_at} 都不变
     * （{@code VersionedGroupWriter} 的 UNCHANGED 契约：一行不写，连 {@code updated_at} 都不许动）；
     * ② {@code _history} 无新增行；
     * ③ 🔑 该组<b>仍出现在响应的 groups 里</b>且 {@code patchedRows = 0}，🚫 不许被过滤掉
     * —— 过滤掉之后财务分不清「这张表没变」与「这张表根本没被算进去」。
     *
     * <p>断言① 是 E-7 证伪实验的靶子（让 UNCHANGED 也 touch updated_at → 本条必须变红）。
     */
    @Test
    @DisplayName("T-14 · 零变更判 UNCHANGED、updated_at 不变、仍列在抽屉里")
    void t14_unchangedWritesNothingButStaysVisible() {
        requireRecordLayer();
        Fx fx = newSubmittedQuotationWithGroup("AC14");
        String mat = axisOf(fx);
        List<String> axes = List.of(mat);

        // 保存后不改任何值 ⇒ _record 内容 == 主表内容
        List<Object> beforeRows = col("SELECT id || '|' || version_no || '|' || coalesce(updated_at::text,'<NULL>') "
                + "FROM " + MBOM + " WHERE material_no = '" + mat + "' ORDER BY id");
        assertFixtureNonEmpty(beforeRows.size(), "零变更用例的主表基线行数");
        long histBefore = count("SELECT count(*) FROM " + MBOM + "_history WHERE material_no = '" + mat + "'");

        Response pv = getPreview(fx.quotationId());
        requireStatusBeforeDiff(pv, 200, "AC-14 预览");
        JsonNode ds = dsBackfill(ok(pv, "AC-14 预览"));

        // ── AC-14③：UNCHANGED 的组仍出现在列表里
        JsonNode group = findGroup(ds, MBOM, mat);
        assertTrue(group != null,
                "AC-14③：判定为 UNCHANGED 的组被过滤掉了 —— 轴值 " + mat + " 没出现在 dsBackfill.tables 里。"
                        + "🚫 不许过滤：过滤掉之后财务分不清「这张表没变」与「这张表根本没被算进去」。"
                        + "实际 tables=" + ds.path("tables"));
        assertEquals("UNCHANGED", group.path("result").asText(),
                "AC-14①：零变更的组应判 UNCHANGED，实际 " + group.path("result").asText() + "，group=" + group);
        assertEquals(0, group.path("patchedRows").asInt(-1),
                "AC-14③：UNCHANGED 的组应带 patchedRows = 0，实际 " + group.path("patchedRows"));

        // ── 确认执行
        Response ap = postApprove(fx.quotationId(), ds(pv, "previewToken"), PREFIX + "AC14");
        requireStatusBeforeDiff(ap, 200, "AC-14 确认核价通过");

        // ── AC-14①：version_no 与 updated_at 逐字不变（🔑 E-7 靶子）
        List<Object> afterRows = col("SELECT id || '|' || version_no || '|' || coalesce(updated_at::text,'<NULL>') "
                + "FROM " + MBOM + " WHERE material_no = '" + mat + "' ORDER BY id");
        assertEquals(beforeRows, afterRows,
                "AC-14①：UNCHANGED 契约是「一行不写，连 updated_at 都不许动」。"
                        + "before=" + beforeRows + " after=" + afterRows);

        // ── AC-14②：_history 无新增
        assertEquals(histBefore, count("SELECT count(*) FROM " + MBOM + "_history WHERE material_no = '" + mat + "'"),
                "AC-14②：UNCHANGED 不应往 _history 写行");
    }

    /**
     * <b>T-18（AC-18）</b>：两个财务会话<b>同时</b>点确认 → 只有一个成功。
     *
     * <p>断言：① 恰好一个 200，另一个拿到明确错误（状态已不是 {@code SUBMITTED}）；
     * ② 不产生双份升版 —— {@code version_no} <b>只 +1</b>、{@code _history} <b>只多一份</b>。
     *
     * <p>🔑 断言②是这条 AC 的实质。只断言「一个失败了」是不够的：
     * 失败的那个完全可能<b>已经写了一半</b>，那时「一个成功一个失败」照样成立，而数据已经错了。
     */
    @Test
    @DisplayName("T-18 · 并发确认只有一个成功，version_no 只 +1、_history 只多一份")
    void t18_concurrentApproveDoesNotDoubleUpgrade() {
        requireRecordLayer();
        Fx fx = newSubmittedQuotationWithGroup("AC18", true);
        String mat = axisOf(fx);

        Response pv = getPreview(fx.quotationId());
        requireStatusBeforeDiff(pv, 200, "AC-18 预览");
        String token = ds(pv, "previewToken");

        int verBefore = intOf("SELECT max(version_no) FROM " + MBOM + " WHERE material_no = '" + mat + "'");
        // 🔑 「_history 只多一份」的可测形态 = 该轴值在 _history 里的**归档版本数**只 +1。
        //    🚫 不用行数：一组有几行是业务数据，行数 +N 说明不了「归档了几份」。
        long histVersionsBefore = distinctHistoryVersions(mat);
        assertFixtureNonEmpty(count("SELECT count(*) FROM " + MBOM + " WHERE material_no = '" + mat + "'"),
                "AC-18 并发前主表该组行数");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Response>> tasks = List.of(
                    () -> postApprove(fx.quotationId(), token, PREFIX + "AC18-a"),
                    () -> postApprove(fx.quotationId(), token, PREFIX + "AC18-b"));
            List<Integer> codes = new ArrayList<>();
            List<String> bodies = new ArrayList<>();
            for (Future<Response> f : pool.invokeAll(tasks)) {
                Response r = f.get();
                codes.add(r.statusCode());
                bodies.add(r.asString());
            }
            long ok = codes.stream().filter(c -> c == 200).count();
            assertEquals(1L, ok,
                    "AC-18①：两个财务同时确认应恰好一个成功，实际成功 " + ok + " 个。codes=" + codes
                            + " bodies=" + bodies);
            assertTrue(codes.stream().anyMatch(c -> c != 200),
                    "AC-18①：另一个应拿到明确错误（状态已不是 SUBMITTED），codes=" + codes);
        } catch (Exception e) {
            throw new AssertionError("并发执行本身失败（用例环境问题，不是业务结论）：" + e, e);
        } finally {
            pool.shutdownNow();
        }

        // ── AC-18②：不产生双份升版
        int verAfter = intOf("SELECT max(version_no) FROM " + MBOM + " WHERE material_no = '" + mat + "'");
        assertEquals(verBefore + 1, verAfter,
                "AC-18②：version_no 应只 +1（" + verBefore + " → " + (verBefore + 1) + "），实际 " + verAfter
                        + " ⇒ 两次确认都写进去了");
        long histVersionsAfter = distinctHistoryVersions(mat);
        assertEquals(histVersionsBefore + 1, histVersionsAfter,
                "AC-18②：_history 应只多一份归档版本（" + histVersionsBefore + " → " + (histVersionsBefore + 1)
                        + "），实际 " + histVersionsAfter + " ⇒ 两次确认各归档了一份");
    }

    /** 该轴值在 {@code _history} 里的<b>归档版本数</b>（AC-18②「只多一份」的可测形态）。 */
    private long distinctHistoryVersions(String materialNo) {
        return count("SELECT count(*) FROM (SELECT DISTINCT version_no FROM " + MBOM + "_history "
                + "WHERE material_no = '" + materialNo.replace("'", "''") + "') s");
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private String ds(Response previewResponse, String field) {
        JsonNode data = json(previewResponse).path("data");
        JsonNode v = data.path(field);
        assertFalse(v.isMissingNode() || v.isNull(),
                "预览响应缺 " + field + "（api.md §1：previewToken 提交时必须原样带回）。data=" + data);
        return v.asText();
    }

    /** 在 {@code dsBackfill.tables[].groups[]} 里按表名 + 轴值找组；找不到返 null（由调用方给出 AC 语义的报错）。 */
    private JsonNode findGroup(JsonNode dsBackfill, String tableName, String axisValue) {
        for (JsonNode t : dsBackfill.path("tables")) {
            if (!tableName.equals(t.path("tableName").asText())) continue;
            for (JsonNode g : t.path("groups")) {
                if (axisValue.equals(g.path("axisValue").asText())) return g;
            }
        }
        return null;
    }

    private int intOf(String sql) {
        Object v = scalar(sql);
        assertTrue(v != null, "查询无结果，断言会空跑：" + sql);
        return ((Number) v).intValue();
    }

    private String axisOf(Fx fx) {
        Object v = scalar("SELECT material_no FROM " + MBOM + "_record WHERE quotation_id = '"
                + fx.quotationId() + "' LIMIT 1");
        assertTrue(v != null, "本单在 _record 里没有任何行 ⇒ 夹具没建成，后续断言会空跑");
        return v.toString();
    }

    /**
     * 造一张 <b>SUBMITTED</b> 的新链路报价单，其元素BOM 页签表征一个 {@code T260907R-} 轴值组。
     *
     * <p>🔑 <b>不预先在主表造这一组</b> ⇒ 轴值在主表不存在 ⇒ 回填判定为 {@code CREATED}
     * （AC-6⑤ 新增的那一态）。需要 {@code UPGRADED} / {@code UNCHANGED} 的用例
     * 自己先 {@link #seedEbomMainGroup} 造主表组。
     */
    private Fx newSubmittedQuotationWithGroup(String label) {
        return newSubmittedQuotationWithGroup(label, /*orderDiffersFromMain*/ false);
    }

    /**
     * @param orderDiffersFromMain true = 报价单的值与主表不同 ⇒ 该组判 {@code UPGRADED}（会升版）；
     *                             false = 逐字相同 ⇒ 判 {@code UNCHANGED}（一行不写）
     */
    private Fx newSubmittedQuotationWithGroup(String label, boolean orderDiffersFromMain) {
        String mat = PREFIX + label + "-" + UUID.randomUUID().toString().substring(0, 6);
        List<EbomRow> mainRows = List.of(
                new EbomRow(1, PREFIX + "EL1", "55.5", "2.4"),
                new EbomRow(2, PREFIX + "EL2", "44.5", "1.2"));
        // 🔑 先造主表整组 —— 否则轴值在主表不存在，回填判 CREATED，
        //    而本类的用例（T-05/07/14/18）验的是「已有组」的语义。
        seedEbomMainGroup(mat, mainRows, 1);
        List<EbomRow> orderRows = orderDiffersFromMain
                ? List.of(new EbomRow(1, PREFIX + "EL1", "77.7", "2.4"),
                          new EbomRow(2, PREFIX + "EL2", "44.5", "1.2"))
                : mainRows;
        return newSubmittedOrder(label, mat, orderRows);
    }
}
