package com.cpq.priceadjust.ac260918;

import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.ac260918.Rp0918aFixture.Edge;
import com.cpq.priceadjust.ac260918.Rp0918aFixture.Quote;
import com.cpq.priceadjust.service.PriceAdjustBudgetService;
import com.cpq.priceadjust.service.PriceAdjustStartupRecovery;
import com.cpq.priceadjust.service.PriceAdjustVersionGenerationService;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.RollbackException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;

import static com.cpq.priceadjust.ac260918.Rp0918aFixture.P_PREV;
import static com.cpq.priceadjust.ac260918.Rp0918aFixture.P_TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260918 · S-BE · 连带问题：预算。
 * <ul>
 *   <li><b>T-BE-17 / AC-17</b>：注入某料号预算试算超时 → 待办池出现该料号、预算失败、原因「预算试算超时（超过 60 秒）」；
 *       撤注入后「重算」→ 就绪。</li>
 *   <li><b>T-BE-18 / AC-18</b>：V1 预算进行中（注入慢试算）生成 V2 作废 V1 ⇒ V1 名下 PENDING 审核 = 0、
 *       日志「版本已作废，停止剩余 N 个料号」N &gt; 0、V2 预算照常。</li>
 *   <li><b>T-BE-23 / AC-23</b>：前 k 个料号已处理 → {@code resumeBudgets(自建版本)} ⇒ 前 k 个不重复试算、
 *       续跑后「既无审核行、指针也未指向本版本」的料号数 = 0。</li>
 * </ul>
 * 预算由「生成版本」触发（AC-17 / AC-18 走真实生成入口）；价差由本客户自己的元素取价策略提供，不碰全局日价。
 */
@QuarkusTest
@TestProfile(Rp0918aProfile.class)
class Ac260918BudgetTest {

    private static final String BUDGET_TIMEOUT_MSG = "预算试算超时（超过 60 秒）";

    @Inject
    EntityManager em;
    @Inject
    PriceAdjustBudgetService budget;
    @Inject
    PriceAdjustVersionGenerationService generation;
    @Inject
    PriceAdjustStartupRecovery recovery;
    @InjectMock
    SessionHelper sessionHelper;

    private Rp0918aDb db;
    private Rp0918aFixture fx;
    private UpgradeInterceptor upgrades;
    private Rp0918aLogCapture logs;

    @BeforeEach
    void setUp() {
        db = new Rp0918aDb(em);
        fx = new Rp0918aFixture(db);
        fx.createCustomer().createAdjustStrategy();
        fx.createTemplate();
        Mockito.when(sessionHelper.getCurrentUserId(ArgumentMatchers.any())).thenReturn(fx.salesRepId);
        upgrades = UpgradeInterceptor.install();
        logs = Rp0918aLogCapture.start();
    }

    @AfterEach
    void tearDown() {
        if (upgrades != null) {
            Rp0918aEvidence.log("upgrade-paths", getClass().getSimpleName() + " 拦到的 upgrade 调用：" + upgrades.signatureSummary());
            upgrades.clearRules();
        }
        if (logs != null) logs.close();
        if (fx != null) {
            fx.awaitQuiet(3_000, 120_000);
            fx.cleanup();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // T-BE-17 · AC-17
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void tBe17_ac17_budgetTimeoutVisibleInPool_thenRecomputeReady() {
        Quote q = fx.createQuote("B", 6, null, Edge.NONE, 8);
        String b1 = q.material(1);
        String b2 = q.material(2);
        fx.addScope(List.of(b1, b2));
        UUID vPrev = fx.insertVersion("PREV", "SUPERSEDED", P_PREV, null, null, 3_600);
        fx.setPointer(b1, vPrev);
        fx.setPointer(b2, vPrev);
        fx.setElementPriceTarget(P_TARGET);

        UpgradeInterceptor.Rule timeout = upgrades.addRule("AC-17 预算试算超时",
            UpgradeInterceptor.onLines(Set.of(q.line(b1)), true),
            UpgradeInterceptor.throwing(() -> new RuntimeException("Error invoking subclass method",
                new RollbackException("ARJUNA016102: The transaction is not active! Uid is 0:ffff:rp0918a:17"))));

        // 走用户入口（POST /versions/generate）：预算由该入口触发，直接调服务方法不会启动预算（首轮实测）
        UUID v = Rp0918aApi.generateVersion(fx.customerNo, true);
        awaitBudgetSettled(v, List.of(b1, b2), 120_000);
        timeout.disarm();
        assertTrue(timeout.fired() >= 1, "注入未生效：预算没有对 " + b1 + " 做试算");

        Object[] r1 = review(v, b1);
        Object[] r2 = review(v, b2);
        JsonNode row1 = poolRow(b1);
        Rp0918aEvidence.log("AC-17", "版本 " + v + "；B1 审核行(status/budget/error)=" + fmt(r1) + "；B2=" + fmt(r2)
            + "；待办池接口 B1 行=" + row1 + "（接口是否带 budgetError 字段：" + (row1 != null && row1.has("budgetError")) + "）");
        assertNotNull(r1, "AC-17：待办池应出现该料号（审核行存在），实际没有 —— 料号静默漏出待办池");
        assertEquals("PENDING", r1[0], "AC-17：应在待办池（待处理）");
        assertEquals("FAILED", r1[1], "AC-17：预算状态应为「预算失败」");
        assertEquals(BUDGET_TIMEOUT_MSG, r1[2], "AC-17：原因文案");
        assertNotNull(row1, "AC-17：待办池接口 GET /reviews 里应能找到 " + b1);
        assertEquals("FAILED", row1.path("budgetStatus").asText(), "AC-17：接口 budgetStatus");
        // D-8（用户裁决）：审核列表接口一定返回 budgetError
        assertTrue(row1.has("budgetError"), "AC-17/D-8：审核列表项应带 budgetError 字段: " + row1);
        assertEquals(BUDGET_TIMEOUT_MSG, row1.path("budgetError").asText(), "AC-17/D-8：列表 budgetError 原文");
        assertNotNull(r2, "对照：B2 应照常进池（单料号失败不连累同版其他料号）");
        assertEquals("READY", r2[1], "对照：B2 预算应就绪");
        JsonNode row2 = poolRow(b2);
        Rp0918aEvidence.log("AC-17", "对照 B2 列表行=" + row2);
        assertNotNull(row2, "对照：列表里应能找到 " + b2);
        assertTrue(row2.has("budgetError") && row2.get("budgetError").isNull(),
            "AC-17/D-8：预算正常的对照项 budgetError 应为 null，实际 " + row2.get("budgetError"));

        // 撤掉注入后「重算」
        UUID reviewId = (UUID) db.scalar("SELECT id FROM material_price_review WHERE version_id = :v AND material_no = :m",
            "v", v, "m", b1);
        Response rr = Rp0918aApi.recomputeBudget(reviewId);
        assertEquals(202, rr.statusCode(), "重算应 202: " + rr.asString());
        long deadline = System.currentTimeMillis() + 60_000;
        Object[] now = review(v, b1);
        while (System.currentTimeMillis() < deadline && !"READY".equals(now[1])) {
            Rp0918aFixture.sleep(200);
            now = review(v, b1);
        }
        JsonNode rowAfter = poolRow(b1);
        Rp0918aEvidence.log("AC-17", "重算后 B1=" + fmt(now) + "；接口行=" + rowAfter);
        assertEquals("READY", now[1], "AC-17：重算后预算状态应变「就绪」");
        assertEquals("READY", rowAfter.path("budgetStatus").asText(), "AC-17：接口 budgetStatus 就绪");
    }

    // ═════════════════════════════════════════════════════════════════════
    // T-BE-18 · AC-18
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void tBe18_ac18_supersededVersionBudgetStops_noPendingLeft_newVersionProceeds() throws Exception {
        Quote q = fx.createQuote("C", 8, null, Edge.NONE, 8);
        List<String> mats = new ArrayList<>(q.materialByOrdinal().values());
        fx.addScope(mats);
        UUID vPrev = fx.insertVersion("PREV", "SUPERSEDED", P_PREV, null, null, 3_600);
        for (String m : mats) fx.setPointer(m, vPrev);
        fx.setElementPriceTarget(P_TARGET);

        Set<UUID> lines = new HashSet<>(q.lineByMaterial().values());
        AtomicReference<UUID> v2 = new AtomicReference<>();
        CountDownLatch firstSlow = new CountDownLatch(1);
        UpgradeInterceptor.Rule slow = upgrades.addRule("AC-18 V1 慢试算",
            c -> Boolean.TRUE.equals(c.dryRun()) && c.touches(lines) && (v2.get() == null || !c.touches(Set.of(v2.get()))),
            c -> {
                firstSlow.countDown();
                Thread.sleep(1_500);
            });

        long start = System.currentTimeMillis();
        CompletableFuture<UUID> f1 = CompletableFuture.supplyAsync(
            () -> Rp0918aApi.generateVersion(fx.customerNo, true));
        assertTrue(firstSlow.await(90, TimeUnit.SECONDS), "V1 预算未开始（90 秒内没有任何试算调用）");
        UUID v1 = (UUID) db.scalar("SELECT id FROM element_price_version WHERE customer_no = :c AND status = 'PENDING'",
            "c", fx.customerNo);
        assertNotNull(v1, "找不到 V1");
        Rp0918aFixture.sleep(300);

        v2.set(Rp0918aApi.generateVersion(fx.customerNo, true));
        assertEquals("SUPERSEDED", db.text("SELECT status FROM element_price_version WHERE id = :id", "id", v1),
            "前提：生成 V2 应作废 V1");

        // ② 停止日志
        List<Matcher> stop = List.of();
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            stop = logs.find(Rp0918aLogCapture.BUDGET_STOPPED, start);
            if (!stop.isEmpty()) break;
            Rp0918aFixture.sleep(200);
        }
        f1.get(180, TimeUnit.SECONDS);

        // ① V1 循环退出后（名下审核行不再变化）PENDING = 0
        awaitReviewsQuiet(v1, 3_000, 60_000);
        long v1Pending = db.count("SELECT count(*) FROM material_price_review WHERE version_id = :v AND status = 'PENDING'",
            "v", v1);
        String v1Dist = db.text("SELECT coalesce(string_agg(status || ':' || n, ','), '无') FROM (SELECT status, count(*) n "
            + "FROM material_price_review WHERE version_id = :v GROUP BY status) t", "v", v1);

        // ③ V2 预算照常：范围内每个料号都已处理（有 V2 审核行或指针指向 V2）
        long v2Unprocessed = awaitProcessed(v2.get(), mats, 180_000);
        Rp0918aEvidence.log("AC-18", "V1=" + v1 + " V2=" + v2.get() + "；V1 慢试算触发 " + slow.fired() + " 次；停止日志="
            + (stop.isEmpty() ? "无" : stop.get(0).group(0)) + "；V1 审核分布=" + v1Dist + "；V2 未处理料号数=" + v2Unprocessed);
        assertTrue(slow.fired() >= 1, "注入未生效");
        assertEquals(0, v1Pending, "AC-18①：V1 预算循环退出后，V1 名下 PENDING 审核应为 0，实际分布 " + v1Dist);
        assertFalse(stop.isEmpty(), "AC-18②：日志应出现「版本已作废，停止剩余 N 个料号」");
        assertTrue(Integer.parseInt(stop.get(0).group(1)) > 0, "AC-18②：N 应 > 0，实际 " + stop.get(0).group(0));
        assertEquals(0, v2Unprocessed, "AC-18③：V2 的预算应照常进行完毕");
    }

    // ═════════════════════════════════════════════════════════════════════
    // T-BE-23 · AC-23
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void tBe23_ac23_resumeBudgetSkipsProcessed_andFinishesRest() {
        Quote q = fx.createQuote("D", 6, null, Edge.NONE, 8);
        List<String> mats = new ArrayList<>(q.materialByOrdinal().values());
        fx.addScope(mats);
        UUID vPrev = fx.insertVersion("PREV", "SUPERSEDED", P_PREV, null, null, 7_200);
        UUID v = fx.insertVersion("TGT", "PENDING", P_TARGET, P_PREV,
            P_TARGET.subtract(P_PREV).divide(P_PREV, 12, RoundingMode.HALF_UP), 3_600);
        for (String m : mats) fx.setPointer(m, vPrev);

        // 「中断」现场：前 k=3 个已处理 —— D1、D2 已进池，D3 指针已推进到本版本（零变动 / 无活单的已处理形态）
        Map<String, UUID> done = fx.createReviewsViaBudget(budget, v, List.of(mats.get(0), mats.get(1)));
        fx.setPointer(mats.get(2), v);
        Map<String, String> stampBefore = new LinkedHashMap<>();
        for (String m : done.keySet()) stampBefore.put(m, reviewStamp(v, m));
        long unprocessedBefore = unprocessed(v);
        assertEquals(3, unprocessedBefore, "前提：还有 3 个料号既无审核行、指针也未指向本版本");

        long start = System.currentTimeMillis();
        recovery.resumeBudgets(List.of(v));
        long left = awaitProcessed(v, mats, 180_000);
        fx.awaitQuiet(3_000, 60_000);

        Set<UUID> doneLines = Set.of(q.line(mats.get(0)), q.line(mats.get(1)), q.line(mats.get(2)));
        long reDry = upgrades.callsSince(start).stream().filter(c -> Boolean.TRUE.equals(c.dryRun()) && c.touches(doneLines)).count();
        Map<String, Long> restDry = new LinkedHashMap<>();
        for (String m : mats.subList(3, 6)) {
            UUID l = q.line(m);
            restDry.put(m, upgrades.callsSince(start).stream()
                .filter(c -> Boolean.TRUE.equals(c.dryRun()) && c.touches(Set.of(l))).count());
        }
        Map<String, String> stampAfter = new LinkedHashMap<>();
        for (String m : done.keySet()) stampAfter.put(m, reviewStamp(v, m));
        long dupReviews = db.count("SELECT count(*) FROM (SELECT material_no FROM material_price_review WHERE version_id = :v "
            + "GROUP BY material_no HAVING count(*) > 1) t", "v", v);
        Rp0918aEvidence.log("AC-23", "续跑前未处理=" + unprocessedBefore + " 续跑后=" + left + "；前 2 个审核行 updated_at 前="
            + stampBefore + " 后=" + stampAfter + "；续跑期间对前 3 个料号的试算次数=" + reDry + "；对后 3 个=" + restDry);
        assertEquals(stampBefore, stampAfter, "AC-23①：前 k 个料号的审核行 updated_at 不变（未重复试算）");
        assertEquals(0, reDry, "AC-23①：续跑不应再对已处理料号试算");
        assertEquals(0, dupReviews, "同一版本同一料号不应出现两条审核行");
        for (Map.Entry<String, Long> e : restDry.entrySet()) {
            assertTrue(e.getValue() >= 1, "阳性对照：未处理料号 " + e.getKey() + " 续跑时应被试算（观测手段有效）");
        }
        assertEquals(0, left, "AC-23②：续跑结束后「既无审核行、指针也未指向本版本」的料号数应为 0");
    }

    // ─────────────────────────────────────────────────────────────────────

    private Object[] review(UUID v, String m) {
        List<Object[]> r = db.rows("SELECT status, budget_status, budget_error FROM material_price_review "
            + "WHERE version_id = :v AND material_no = :m", "v", v, "m", m);
        return r.isEmpty() ? null : r.get(0);
    }

    private static String fmt(Object[] r) {
        return r == null ? "无" : r[0] + "/" + r[1] + "/" + r[2];
    }

    private JsonNode poolRow(String material) {
        for (JsonNode row : Rp0918aApi.reviewList(fx.customerNo).path("content")) {
            if (material.equals(row.path("materialNo").asText())) return row;
        }
        return null;
    }

    private String reviewStamp(UUID v, String m) {
        return db.text("SELECT id::text || '|' || updated_at::text || '|' || budget_status FROM material_price_review "
            + "WHERE version_id = :v AND material_no = :m", "v", v, "m", m);
    }

    /** 本客户调价范围内「既无本版本审核行、指针也未指向本版本」的料号数。 */
    private long unprocessed(UUID v) {
        return db.count("SELECT count(*) FROM customer_price_adjust_material cm WHERE cm.strategy_id = :s "
                + "AND NOT EXISTS (SELECT 1 FROM material_price_review r WHERE r.version_id = :v AND r.material_no = cm.material_no) "
                + "AND NOT EXISTS (SELECT 1 FROM material_price_version_ref p WHERE p.customer_no = :c "
                + "AND p.material_no = cm.material_no AND p.version_id = :v)",
            "s", fx.strategyId, "v", v, "c", fx.customerNo);
    }

    private long awaitProcessed(UUID v, List<String> mats, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        long left = unprocessed(v);
        while (left > 0 && System.currentTimeMillis() < deadline) {
            Rp0918aFixture.sleep(500);
            left = unprocessed(v);
        }
        return left;
    }

    /** 等这些料号的审核行都出现且预算不再处于排队/计算中。 */
    private void awaitBudgetSettled(UUID v, List<String> mats, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            long settled = db.count("SELECT count(*) FROM material_price_review WHERE version_id = :v AND material_no IN (:m) "
                + "AND budget_status IN ('READY', 'FAILED')", "v", v, "m", mats);
            if (settled == mats.size()) return;
            Rp0918aFixture.sleep(300);
        }
    }

    private void awaitReviewsQuiet(UUID v, long stableMillis, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        String last = null;
        long since = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            String now = db.text("SELECT count(*) || ':' || coalesce(max(updated_at)::text, '') || ':' || "
                + "coalesce(string_agg(status, ',' ORDER BY id), '') FROM material_price_review WHERE version_id = :v", "v", v);
            if (!now.equals(last)) {
                last = now;
                since = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - since >= stableMillis) {
                return;
            }
            Rp0918aFixture.sleep(500);
        }
    }
}
