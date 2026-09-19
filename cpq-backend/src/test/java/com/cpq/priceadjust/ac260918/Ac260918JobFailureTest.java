package com.cpq.priceadjust.ac260918;

import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.ac260918.Rp0918aFixture.Edge;
import com.cpq.priceadjust.ac260918.Rp0918aFixture.Quote;
import com.cpq.priceadjust.ac260918.Rp0918aSnapshots.Revision;
import com.cpq.priceadjust.service.PriceAdjustBudgetService;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.RollbackException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.cpq.priceadjust.ac260918.Rp0918aFixture.P_PREV;
import static com.cpq.priceadjust.ac260918.Rp0918aFixture.P_TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260918 · S-BE · 更新任务的失败面（小单即可，失败形态与行数无关）：
 * <ul>
 *   <li><b>T-BE-14 / AC-14</b> 分组写本期快照失败 → REVISION_WRITE_FAILED；单条重试恢复；</li>
 *   <li><b>T-BE-15 / AC-15</b> 超时两种形态 → EXECUTION_TIMEOUT；其它异常 → UNEXPECTED_ERROR（根因类名开头、不含外壳）；
 *       单条重试路径同样落库并重算批次计数；</li>
 *   <li><b>T-BE-22 / AC-22</b> 执行抛 {@code Error} → 批次不许停在执行中；</li>
 *   <li><b>T-BE-24 / AC-24</b> 批量重试 = FAILED + CONFLICT，STALE 不动。</li>
 * </ul>
 * CONFLICT / STALE 两种状态难以自然造出，用 SQL 改<b>本片自建明细</b>的状态模拟（已在回报中登记）。
 */
@QuarkusTest
@TestProfile(Rp0918aProfile.class)
class Ac260918JobFailureTest {

    private static final BigDecimal EXPECTED = Rp0918aFixture.expectedSubtotal(P_TARGET);
    private static final String TIMEOUT_MSG = "执行超时（超过 60 秒）被中止，本行未更新，可重试";
    private static final String REVISION_MSG = "价格已更新，但版本记录写入失败，请重试";
    private static final String WRAPPER = "Error invoking subclass method";

    @Inject
    EntityManager em;
    @Inject
    PriceAdjustBudgetService budget;
    @InjectMock
    SessionHelper sessionHelper;

    private Rp0918aDb db;
    private Rp0918aSnapshots snaps;
    private Rp0918aFixture fx;
    private Quote q;
    private UUID vPrev, vTarget;
    private UpgradeInterceptor upgrades;
    private RevisionWriterInterceptor writer;
    private Rp0918aLogCapture logs;

    @BeforeEach
    void setUp() {
        db = new Rp0918aDb(em);
        snaps = new Rp0918aSnapshots(db);
        fx = new Rp0918aFixture(db);
        fx.createCustomer().createAdjustStrategy();
        fx.createTemplate();
        q = fx.createQuote("J", 12, null, Edge.NONE, 8);
        fx.addScopeFromQuotes(List.of(q.id()));
        vPrev = fx.insertVersion("PREV", "SUPERSEDED", P_PREV, null, null, 7_200);
        vTarget = fx.insertVersion("TGT", "PENDING", P_TARGET, P_PREV,
            P_TARGET.subtract(P_PREV).divide(P_PREV, 12, RoundingMode.HALF_UP), 3_600);
        fx.setPointersFromQuotes(vPrev, List.of(q.id()));
        Mockito.when(sessionHelper.getCurrentUserId(ArgumentMatchers.any())).thenReturn(fx.salesRepId);
        upgrades = UpgradeInterceptor.install();
        writer = RevisionWriterInterceptor.install();
        logs = Rp0918aLogCapture.start();
    }

    @AfterEach
    void tearDown() {
        if (upgrades != null) {
            Rp0918aEvidence.log("upgrade-paths", getClass().getSimpleName() + " 拦到的 upgrade 调用：" + upgrades.signatureSummary());
            upgrades.clearRules();
        }
        if (writer != null) writer.disarm();
        if (logs != null) logs.close();
        if (fx != null) {
            fx.awaitQuiet(2_000, 60_000);
            fx.cleanup();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // T-BE-14 · AC-14
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void tBe14_ac14_groupRevisionWriteFails_itemsMarkedRevisionWriteFailed_retryRecovers() {
        String m1 = q.material(3);
        String m2 = q.material(8);
        Map<String, UUID> rv = fx.createReviewsViaBudget(budget, vTarget, List.of(m1, m2));
        writer.armFailure(q.id());

        UUID job = Rp0918aApi.approveOk(rv.values());
        String st = Rp0918aApi.awaitJobTerminal(db, job, 120_000);
        writer.disarm();
        assertTrue(writer.injectedFailures() >= 1, "注入未生效：没有拦到对该单写本期快照的调用");
        Map<String, List<JsonNode>> items = Rp0918aApi.itemsByMaterial(job);
        Rp0918aEvidence.log("AC-14", "批次 " + job + " 状态 " + st + "；写快照调用=" + writer.calls() + "；明细=" + items);

        for (String m : List.of(m1, m2)) {
            JsonNode it = items.get(m).get(0);
            assertEquals("FAILED", it.path("status").asText(), "AC-14：" + m + " 应标失败: " + it);
            assertEquals("REVISION_WRITE_FAILED", it.path("errorCode").asText(), "AC-14：错误码: " + it);
            assertEquals(REVISION_MSG, it.path("errorMessage").asText(), "AC-14：错误信息: " + it);
            UUID line = q.line(m);
            assertEquals(0, P_TARGET.compareTo(fx.lineElementPrice(line)), "AC-14：价格已更新（行已提交）" + m);
            assertEquals(0, EXPECTED.compareTo(fx.lineSubtotal(line)), "AC-14：小计已按新价 " + m);
        }
        Revision curBefore = snaps.current(q.id(), vTarget);
        assertTrue(curBefore == null || (!curBefore.upgradedMaterialNos().contains(m1)
                && !curBefore.upgradedMaterialNos().contains(m2)),
            "前提：写快照失败 ⇒ 本期记录里不应有这两个料号，实际 " + (curBefore == null ? null : curBefore.upgradedMaterialNos()));
        assertCountsMatchItems(job, "AC-14 失败后");

        // 重试其中一条
        UUID line1 = q.line(m1);
        BigDecimal subBefore = fx.lineSubtotal(line1);
        BigDecimal priceBefore = fx.lineElementPrice(line1);
        JsonNode it1 = items.get(m1).get(0);
        UUID itemId = UUID.fromString(it1.path("itemId").asText());
        int retryBefore = it1.path("retryCount").asInt();
        Response rr = Rp0918aApi.retryItem(itemId);
        assertEquals(202, rr.statusCode(), rr.asString());
        Object[] fin = Rp0918aApi.awaitItem(db, itemId, row -> "SUCCESS".equals(row[0])
            || (Rp0918aApi.isTerminal((String) row[0]) && ((Number) row[3]).intValue() > retryBefore), 120_000);
        Revision cur = snaps.current(q.id(), vTarget);
        Rp0918aEvidence.log("AC-14", "重试 " + m1 + " → " + fin[0] + "/" + fin[1] + "/" + fin[2] + "；小计 前=" + subBefore
            + " 后=" + fx.lineSubtotal(line1) + "；单价 前=" + priceBefore + " 后=" + fx.lineElementPrice(line1)
            + "；本期记录=" + (cur == null ? null : cur.revisionNo() + " " + cur.upgradedMaterialNos()));
        assertEquals("SUCCESS", fin[0], "AC-14：重试应成功: " + fin[1] + " " + fin[2]);
        assertEquals(0, subBefore.compareTo(fx.lineSubtotal(line1)), "AC-14：重试后小计与重试前相同");
        assertEquals(0, priceBefore.compareTo(fx.lineElementPrice(line1)), "AC-14：重试后价格与重试前相同");
        assertNotNull(cur, "AC-14：重试后本期版本记录应已写入");
        assertTrue(cur.upgradedMaterialNos().contains(m1), "AC-14：本期记录应含 " + m1);
        assertCountsMatchItems(job, "AC-14 重试后");
    }

    // ═════════════════════════════════════════════════════════════════════
    // T-BE-15 · AC-15
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void tBe15_ac15_failureReasonsReadable_bothPaths() {
        String a = q.material(2);
        String b = q.material(5);
        String c = q.material(9);
        Map<String, UUID> rv = fx.createReviewsViaBudget(budget, vTarget, List.of(a, b, c));
        UpgradeInterceptor.Rule ra = upgrades.addRule("AC-15 a 超时(Rollback)", UpgradeInterceptor.onLines(Set.of(q.line(a)), false),
            UpgradeInterceptor.throwing(() -> new RuntimeException(WRAPPER,
                new RollbackException("ARJUNA016102: The transaction is not active! Uid is 0:ffff:rp0918a:15"))));
        UpgradeInterceptor.Rule rb = upgrades.addRule("AC-15 b 超时(57014)", UpgradeInterceptor.onLines(Set.of(q.line(b)), false),
            UpgradeInterceptor.throwing(() -> new RuntimeException(WRAPPER, new PersistenceException("could not execute statement",
                new SQLException("ERROR: canceling statement due to statement timeout", "57014")))));
        UpgradeInterceptor.Rule rc = upgrades.addRule("AC-15 c 其它异常", UpgradeInterceptor.onLines(Set.of(q.line(c)), false),
            UpgradeInterceptor.throwing(() -> new RuntimeException(WRAPPER, new IllegalStateException("boom"))));

        UUID job = Rp0918aApi.approveOk(rv.values());
        String st = Rp0918aApi.awaitJobTerminal(db, job, 120_000);
        assertTrue(ra.fired() >= 1 && rb.fired() >= 1 && rc.fired() >= 1,
            "注入未全部生效: a=" + ra.fired() + " b=" + rb.fired() + " c=" + rc.fired());
        Map<String, List<JsonNode>> items = Rp0918aApi.itemsByMaterial(job);
        JsonNode ia = items.get(a).get(0);
        JsonNode ib = items.get(b).get(0);
        JsonNode ic = items.get(c).get(0);
        Rp0918aEvidence.log("AC-15", "批次 " + job + " " + st + "\n  a(Rollback)=" + ia + "\n  b(57014)=" + ib + "\n  c(ISE)=" + ic);

        for (JsonNode it : List.of(ia, ib)) {
            assertEquals("FAILED", it.path("status").asText(), "AC-15：超时明细应失败: " + it);
            assertEquals("EXECUTION_TIMEOUT", it.path("errorCode").asText(), "AC-15：超时错误码: " + it);
            assertEquals(TIMEOUT_MSG, it.path("errorMessage").asText(), "AC-15：超时文案: " + it);
        }
        assertUnexpected(ic, "boom", "AC-15 其它异常");
        for (String m : List.of(a, b, c)) {
            assertEquals(0, P_PREV.compareTo(fx.lineElementPrice(q.line(m))), "本行未更新: " + m);
        }
        assertCountsMatchItems(job, "AC-15 批量执行后");

        // 单条重试路径 ①：FAILED 再失败 —— 信息应被重新落库（换一条可辨识的根因信息）
        rc.disarm();
        UpgradeInterceptor.Rule rc2 = upgrades.addRule("AC-15 c 重试仍失败", UpgradeInterceptor.onLines(Set.of(q.line(c)), false),
            UpgradeInterceptor.throwing(() -> new RuntimeException(WRAPPER, new IllegalStateException("boom-retry"))));
        UUID itemC = UUID.fromString(ic.path("itemId").asText());
        int retryC = ic.path("retryCount").asInt();
        assertEquals(202, Rp0918aApi.retryItem(itemC).statusCode());
        // 等待条件 = AC-15 的可观测量（信息已按本次异常重新落库）；retry_count 是否递增不属 AC-15，单独记录
        Object[] finC = Rp0918aApi.awaitItem(db, itemC, row -> Rp0918aApi.isTerminal((String) row[0])
            && row[2] != null && row[2].toString().contains("boom-retry"), 60_000);
        Rp0918aEvidence.log("AC-15", "观察：单条重试① 前 retry_count=" + retryC + " 后=" + finC[3]);
        JsonNode ic2 = Rp0918aApi.itemsById(job).get(itemC.toString());
        Rp0918aEvidence.log("AC-15", "单条重试① c → " + ic2 + "（注入触发 " + rc2.fired() + " 次）");
        assertTrue(rc2.fired() >= 1, "重试注入未生效");
        assertEquals("FAILED", finC[0]);
        assertUnexpected(ic2, "boom-retry", "AC-15 单条重试路径");
        assertCountsMatchItems(job, "AC-15 单条重试①后");

        // 单条重试路径 ②：CONFLICT → 重试抛异常 → 必须改落 FAILED 且批次计数重算（旧行为：停在旧状态、批次不重算）
        UUID itemA = UUID.fromString(ia.path("itemId").asText());
        db.exec("UPDATE material_price_update_job_item SET status = 'CONFLICT', error_code = 'ROW_VERSION_CONFLICT', "
            + "error_message = 'RP0918A 模拟行版本冲突' WHERE id = :id", "id", itemA);
        db.exec("UPDATE material_price_update_job SET failed_count = 2, conflict_count = 1 WHERE id = :id", "id", job);
        JsonNode jobMid = Rp0918aApi.job(job);
        assertEquals(1, jobMid.path("conflict").asInt(), "前提：批次冲突数 1");
        ra.disarm();
        UpgradeInterceptor.Rule ra2 = upgrades.addRule("AC-15 a 冲突重试失败", UpgradeInterceptor.onLines(Set.of(q.line(a)), false),
            UpgradeInterceptor.throwing(() -> new RuntimeException(WRAPPER, new IllegalStateException("boom-conflict"))));
        int retryA = ((Number) db.scalar("SELECT retry_count FROM material_price_update_job_item WHERE id = :id", "id", itemA)).intValue();
        assertEquals(202, Rp0918aApi.retryItem(itemA).statusCode());
        Object[] finA = Rp0918aApi.awaitItem(db, itemA, row -> !"CONFLICT".equals(row[0]) && Rp0918aApi.isTerminal((String) row[0])
            && row[2] != null && row[2].toString().contains("boom-conflict"), 60_000);
        Rp0918aEvidence.log("AC-15", "观察：单条重试② 前 retry_count=" + retryA + " 后=" + finA[3]);
        JsonNode ia2 = Rp0918aApi.itemsById(job).get(itemA.toString());
        JsonNode jobAfter = Rp0918aApi.job(job);
        Rp0918aEvidence.log("AC-15", "单条重试② a(CONFLICT) → " + ia2 + "；批次 前=" + jobMid + " 后=" + jobAfter);
        assertTrue(ra2.fired() >= 1, "重试注入未生效");
        assertEquals("FAILED", finA[0], "AC-15：单条重试异常时明细应落库为失败");
        assertUnexpected(ia2, "boom-conflict", "AC-15 单条重试路径（冲突项）");
        assertEquals(0, jobAfter.path("conflict").asInt(), "AC-15：批次计数应随之更新（冲突 1→0）");
        assertEquals(3, jobAfter.path("failed").asInt(), "AC-15：批次计数应随之更新（失败 2→3）");
        assertCountsMatchItems(job, "AC-15 单条重试②后");
    }

    // ═════════════════════════════════════════════════════════════════════
    // T-BE-22 · AC-22
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void tBe22_ac22_errorThrownDuringExecution_jobDoesNotStayRunning() {
        List<String> ms = List.of(q.material(2), q.material(4), q.material(6));
        Map<String, UUID> rv = fx.createReviewsViaBudget(budget, vTarget, ms);
        Set<UUID> lines = Set.of(q.line(ms.get(0)), q.line(ms.get(1)), q.line(ms.get(2)));
        UpgradeInterceptor.Rule err = upgrades.addRule("AC-22 抛 Error", UpgradeInterceptor.onLines(lines, false),
            UpgradeInterceptor.throwing(() -> new AssertionError("RP0918A 注入 Error（不是 Exception）")));

        long start = System.currentTimeMillis();
        UUID job = Rp0918aApi.approveOk(rv.values());
        String st = Rp0918aApi.awaitJobTerminal(db, job, 90_000); // 停在 RUNNING 会在这里硬失败
        Rp0918aFixture.sleep(500);
        JsonNode items = Rp0918aApi.items(job);
        List<Rp0918aLogCapture.Line> errors = new ArrayList<>();
        for (Rp0918aLogCapture.Line l : logs.since(start)) {
            if (l.isError() && (l.message().contains(job.toString()) || l.message().contains("AssertionError")
                || l.throwableChain().contains("AssertionError"))) {
                errors.add(l);
            }
        }
        StringBuilder el = new StringBuilder();
        for (Rp0918aLogCapture.Line l : errors) el.append("[").append(l.level()).append(" ").append(l.logger()).append("] ")
            .append(l.message()).append(" | ").append(l.throwableChain()).append("\n");
        Rp0918aEvidence.log("AC-22", "注入触发 " + err.fired() + " 次；批次 " + job + " 状态 " + st + "；明细=" + items
            + "\nERROR 日志:\n" + el);
        assertTrue(err.fired() >= 1, "注入未生效");
        assertEquals("FAILED", st, "AC-22：批次状态应为「失败」");
        assertEquals(3, items.size());
        for (JsonNode it : items) {
            assertEquals("FAILED", it.path("status").asText(), "AC-22：未完成明细应为「失败」: " + it);
        }
        assertFalse(errors.isEmpty(), "AC-22：日志应有对应的 ERROR 行");
    }

    // ═════════════════════════════════════════════════════════════════════
    // T-BE-24 · AC-24
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void tBe24_ac24_batchRetryRerunsFailedAndConflict_notStale() {
        String m1 = q.material(3);
        String m2 = q.material(6);
        String m3 = q.material(9);
        Map<String, UUID> rv = fx.createReviewsViaBudget(budget, vTarget, List.of(m1, m2, m3));
        Set<UUID> lines = Set.of(q.line(m1), q.line(m2), q.line(m3));
        UpgradeInterceptor.Rule fail = upgrades.addRule("AC-24 造失败", UpgradeInterceptor.onLines(lines, false),
            UpgradeInterceptor.throwing(() -> new RuntimeException(WRAPPER, new IllegalStateException("boom-24"))));
        UUID job = Rp0918aApi.approveOk(rv.values());
        Rp0918aApi.awaitJobTerminal(db, job, 120_000);
        fail.disarm();
        assertTrue(fail.fired() >= 3, "注入未全部生效: " + fail.fired());
        Map<String, List<JsonNode>> items = Rp0918aApi.itemsByMaterial(job);
        UUID i1 = UUID.fromString(items.get(m1).get(0).path("itemId").asText());
        UUID i2 = UUID.fromString(items.get(m2).get(0).path("itemId").asText());
        UUID i3 = UUID.fromString(items.get(m3).get(0).path("itemId").asText());
        db.exec("UPDATE material_price_update_job_item SET status = 'CONFLICT', error_code = 'ROW_VERSION_CONFLICT', "
            + "error_message = 'RP0918A 模拟行版本冲突' WHERE id = :id", "id", i2);
        db.exec("UPDATE material_price_update_job_item SET status = 'STALE', error_code = NULL, "
            + "error_message = 'RP0918A 模拟所属版本已被取代' WHERE id = :id", "id", i3);
        Map<String, JsonNode> before = Rp0918aApi.itemsById(job);
        assertEquals("FAILED", before.get(i1.toString()).path("status").asText(), "前提 FAILED");
        assertEquals("CONFLICT", before.get(i2.toString()).path("status").asText(), "前提 CONFLICT");
        assertEquals("STALE", before.get(i3.toString()).path("status").asText(), "前提 STALE");
        int staleRetry = before.get(i3.toString()).path("retryCount").asInt();

        List<String> probes = Collections.synchronizedList(new ArrayList<>());
        Set<UUID> rerunLines = Set.of(q.line(m1), q.line(m2));
        upgrades.addRule("AC-24 执行中探针", UpgradeInterceptor.onLines(rerunLines, false), c -> {
            JsonNode j = Rp0918aApi.job(job);
            String statusOfProbed = null;
            for (JsonNode it : Rp0918aApi.items(job)) {
                if (c.touches(Set.of(UUID.fromString(it.path("lineItemId").asText())))) statusOfProbed = it.path("status").asText();
            }
            probes.add("running=" + j.path("running").asInt(-1) + " probedItem=" + statusOfProbed + " job=" + j);
        });
        UpgradeInterceptor.Rule staleGuard = upgrades.addRule("AC-24 STALE 不应被执行",
            UpgradeInterceptor.onLines(Set.of(q.line(m3)), false), c -> { });

        Response rr = Rp0918aApi.retryJob(job);
        assertEquals(202, rr.statusCode(), rr.asString());
        Object[] f1 = Rp0918aApi.awaitItem(db, i1, row -> "SUCCESS".equals(row[0]), 60_000);
        Object[] f2 = Rp0918aApi.awaitItem(db, i2, row -> "SUCCESS".equals(row[0]), 60_000);
        Rp0918aFixture.sleep(2_000);
        Map<String, JsonNode> after = Rp0918aApi.itemsById(job);
        Rp0918aEvidence.log("AC-24", "批量重试前=" + before.values() + "\n后=" + after.values() + "\n执行中探针=" + probes
            + "\nSTALE 行被执行次数=" + staleGuard.fired());
        assertEquals("SUCCESS", f1[0], "AC-24：FAILED 明细应被重跑并成功");
        assertEquals("SUCCESS", f2[0], "AC-24：CONFLICT 明细应被重跑并成功");
        assertEquals("STALE", after.get(i3.toString()).path("status").asText(), "AC-24：STALE 状态不变");
        assertEquals(staleRetry, after.get(i3.toString()).path("retryCount").asInt(), "AC-24：STALE 重试次数不变");
        assertEquals(0, staleGuard.fired(), "AC-24：STALE 明细不应被执行");
        assertEquals(2, probes.size(), "AC-24：两条被重跑的明细各应被探到一次执行中");
        for (String p : probes) {
            assertTrue(p.startsWith("running=1 probedItem=RUNNING"), "AC-24：执行中应可见「执行中」: " + p);
        }
        assertEquals(0, P_TARGET.compareTo(fx.lineElementPrice(q.line(m1))), "m1 行已按本期价更新");
        assertEquals(0, P_TARGET.compareTo(fx.lineElementPrice(q.line(m2))), "m2 行已按本期价更新");
        assertEquals(0, P_PREV.compareTo(fx.lineElementPrice(q.line(m3))), "STALE 的 m3 行不应被更新");
        assertCountsMatchItems(job, "AC-24 批量重试后");
    }

    // ─────────────────────────────────────────────────────────────────────

    private static void assertUnexpected(JsonNode it, String rootMessage, String what) {
        String msg = it.path("errorMessage").asText();
        assertEquals("FAILED", it.path("status").asText(), what + "：应失败: " + it);
        assertEquals("UNEXPECTED_ERROR", it.path("errorCode").asText(), what + "：错误码: " + it);
        assertTrue(msg.startsWith("IllegalStateException"), what + "：信息应以根因类名开头，实际「" + msg + "」");
        assertTrue(msg.contains(rootMessage), what + "：信息应含根因信息「" + rootMessage + "」，实际「" + msg + "」");
        assertFalse(msg.contains(WRAPPER), what + "：信息不得含「" + WRAPPER + "」，实际「" + msg + "」");
    }

    /** 批次表头计数与明细逐项一致（「批次计数随之更新」「表头计数与明细一致」）。 */
    private void assertCountsMatchItems(UUID job, String when) {
        JsonNode j = Rp0918aApi.job(job);
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        int total = 0;
        for (JsonNode it : Rp0918aApi.items(job)) {
            byStatus.merge(it.path("status").asText(), 1, Integer::sum);
            total++;
        }
        Rp0918aEvidence.log("counts", when + "：批次=" + j + " 明细分布=" + byStatus);
        assertEquals(total, j.path("total").asInt(), when + "：total");
        assertEquals(byStatus.getOrDefault("SUCCESS", 0).intValue(), j.path("success").asInt(), when + "：success");
        assertEquals(byStatus.getOrDefault("FAILED", 0).intValue(), j.path("failed").asInt(), when + "：failed");
        assertEquals(byStatus.getOrDefault("CONFLICT", 0).intValue(), j.path("conflict").asInt(), when + "：conflict");
        assertEquals(byStatus.getOrDefault("STALE", 0).intValue(), j.path("stale").asInt(), when + "：stale");
        assertEquals(byStatus.getOrDefault("RUNNING", 0).intValue(), j.path("running").asInt(-1), when + "：running");
        assertNotEquals("RUNNING", j.path("status").asText(), when + "：批次不应仍在执行中");
    }
}
