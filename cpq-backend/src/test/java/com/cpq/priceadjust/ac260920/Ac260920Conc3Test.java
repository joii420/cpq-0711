package com.cpq.priceadjust.ac260920;

import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.ac260918.Rp0918aDb;
import com.cpq.priceadjust.ac260918.Rp0918aLogCapture;
import com.cpq.priceadjust.ac260918.UpgradeInterceptor;
import com.cpq.priceadjust.ac260918.UpgradeInterceptor.Call;
import com.cpq.priceadjust.ac260920.T920Fixture.Quote;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发度<b>显式 3</b>的两条（test.md S1-2）：AC-17（作废覆盖 QUEUED、每个工作线程都停）与 AC-24（收编改策略 / 改比对列）。
 * 「一次试算」的观测量 = {@code MaterialVersionUpgradeService#upgrade(dryRun=true)} 的调用（UpgradeInterceptor，按版本 id / 行 id 归属）。
 * 每次试算对应几次 dryRun 调用不读实现去查：AC-24 用首轮生成时实测的「每行 dryRun 次数」k 自校准。
 */
@QuarkusTest
@TestProfile(T920Profiles.Conc3.class)
class Ac260920Conc3Test {

    @Inject
    EntityManager em;
    @InjectMock
    SessionHelper sessionHelper;

    private void assertConcurrency3() {
        assertEquals(3, ConfigProvider.getConfig().getValue("cpq.price-adjust.budget.concurrency", Integer.class),
            "前提（S1-2）：本类运行时并发度必须是 3");
    }

    // ═══════════════════════════════ AC-17 ═══════════════════════════════
    @Test
    void ac17_supersedeVoidsQueued_everyWorkerStops_newVersionEnqueues() throws Exception {
        assertConcurrency3();
        Rp0918aDb db = new Rp0918aDb(em);
        T920Fixture fx = new T920Fixture(db);
        UpgradeInterceptor upgrades = UpgradeInterceptor.install();
        try (Rp0918aLogCapture logs = Rp0918aLogCapture.start()) {
            fx.createCustomerAndStrategy();
            Mockito.when(sessionHelper.getCurrentUserId(ArgumentMatchers.any())).thenReturn(fx.salesRepId);
            List<Quote> quotes = List.of(fx.createQuote("A", 5), fx.createQuote("B", 5), fx.createQuote("C", 5));
            UUID vPrev = fx.insertVersion("PREV", "SUPERSEDED", T920Fixture.P_PREV, 7_200);
            List<String> mats = new ArrayList<>();
            for (Quote q : quotes) mats.addAll(q.materials());
            for (String m : mats) fx.setPointer(m, vPrev);
            fx.addScope(mats);
            fx.setElementPriceTarget(T920Fixture.P_TARGET);

            AtomicReference<UUID> vA = new AtomicReference<>();
            AtomicBoolean bStarted = new AtomicBoolean(false);
            Set<String> aThreads = ConcurrentHashMap.newKeySet();
            CountDownLatch threeThreads = new CountDownLatch(1);
            UpgradeInterceptor.Rule slow = upgrades.addRule("AC-17 A 版本慢试算",
                c -> Boolean.TRUE.equals(c.dryRun()) && !bStarted.get() && vA.get() != null && c.touches(Set.of(vA.get())),
                c -> {
                    aThreads.add(c.thread());
                    if (aThreads.size() >= 3) threeThreads.countDown();
                    Thread.sleep(1_500);
                });

            long start = System.currentTimeMillis();
            UUID a = T920Api.anonymous().generateVersion(fx.customerNo);
            vA.set(a);
            // 生成返回前已开始的第一批调用可能漏记线程 —— 规则在 vA 置位后才生效，等三个工作线程都在算 A
            boolean three = threeThreads.await(60, TimeUnit.SECONDS);
            long queuedAtSupersede = db.count("SELECT count(*) FROM material_price_review WHERE version_id = :v "
                + "AND status = 'PENDING' AND budget_status = 'QUEUED'", "v", a);
            bStarted.set(true);
            UUID b = T920Api.anonymous().generateVersion(fx.customerNo);
            long tB = System.currentTimeMillis();

            fx.awaitVersionSettled(b, 180_000);
            fx.awaitQuiet(3_000, 60_000);
            long aPending = db.count("SELECT count(*) FROM material_price_review WHERE version_id = :v AND status = 'PENDING'", "v", a);
            String aDist = db.text("SELECT coalesce(string_agg(status || '/' || budget_status || ':' || n, ','), '无') FROM "
                + "(SELECT status, budget_status, count(*) n FROM material_price_review WHERE version_id = :v GROUP BY 1, 2) t", "v", a);
            List<Matcher> stops = logs.find(Rp0918aLogCapture.BUDGET_STOPPED, start);
            Map<String, Long> lastAStartPerThread = new HashMap<>();
            for (Call c : upgrades.callsSince(start)) {
                if (Boolean.TRUE.equals(c.dryRun()) && c.touches(Set.of(a))) lastAStartPerThread.merge(c.thread(), c.atMillis(), Math::max);
            }
            long bReviews = db.count("SELECT count(*) FROM material_price_review WHERE version_id = :v", "v", b);
            long bNotSettled = fx.notSettled(b);
            T920Evidence.log("AC-17", "A=" + a + " B=" + b + "；三个线程同时在算 A=" + three + " 线程=" + aThreads
                + "；作废时刻 A 的 QUEUED 行=" + queuedAtSupersede + "；A 分布=" + aDist + "；停止日志=" + stops.stream().map(m -> m.group(0)).toList()
                + "；各线程最后一次开始算 A 的时刻 - tB(ms)=" + lastAStartPerThread.entrySet().stream()
                .map(e -> e.getKey() + ":" + (e.getValue() - tB)).toList() + "；B 审核行=" + bReviews + " B 未收敛=" + bNotSettled);

            assertTrue(slow.fired() >= 1, "注入未生效");
            assertTrue(three, "前提（S1-2）：并发度 3 下应有 3 个工作线程同时在算 A（实际 " + aThreads + "）——否则「每个线程都停」无从验证");
            assertTrue(queuedAtSupersede > 0, "前提：生成 B 时 A 还有 QUEUED 行");
            assertEquals(0, aPending, "AC-17：A 的全部「待处理」行（含 QUEUED）变为「已作废」，分布 " + aDist);
            assertFalse(stops.isEmpty(), "AC-17：日志应出现「版本已作废，停止剩余 n 个料号」");
            for (Map.Entry<String, Long> e : lastAStartPerThread.entrySet()) {
                assertTrue(e.getValue() <= tB + 2_000, "AC-17：工作线程 " + e.getKey() + " 在 B 生成后 "
                    + (e.getValue() - tB) + "ms 仍在开始新的 A 试算 —— 该线程没有停");
            }
            assertEquals(mats.size(), bReviews, "AC-17：B 正常进池（15 个料号都应进池：指针在 vPrev、银价有变化）");
            assertEquals(0, bNotSettled, "B 的试算照常跑完");
        } finally {
            upgrades.clearRules();
            fx.awaitQuiet(3_000, 120_000);
            fx.cleanup();
        }
    }

    // ═══════════════════════════════ AC-24 ═══════════════════════════════
    @Test
    void ac24_strategyAndColumnsRecomputeFoldedIntoLoop_variantsAandB() throws Exception {
        assertConcurrency3();
        Rp0918aDb db = new Rp0918aDb(em);
        T920Fixture fx = new T920Fixture(db);
        UpgradeInterceptor upgrades = UpgradeInterceptor.install();
        T920Api api = T920Api.anonymous();
        try {
            fx.createCustomerAndStrategy();
            Mockito.when(sessionHelper.getCurrentUserId(ArgumentMatchers.any())).thenReturn(fx.salesRepId);
            List<Quote> quotes = List.of(fx.createQuote("A", 2), fx.createQuote("B", 2), fx.createQuote("C", 2));
            UUID vPrev = fx.insertVersion("PREV", "SUPERSEDED", T920Fixture.P_PREV, 7_200);
            Map<UUID, UUID> quoteOfLine = new HashMap<>();
            Map<String, UUID> lineOf = new LinkedHashMap<>();
            for (Quote q : quotes) {
                for (String m : q.materials()) {
                    fx.setPointer(m, vPrev);
                    lineOf.put(m, q.line(m));
                    quoteOfLine.put(q.line(m), q.id());
                }
                fx.addScope(q.materials());
            }
            fx.setElementPriceTarget(T920Fixture.P_TARGET);
            long g0 = System.currentTimeMillis();
            UUID v = api.generateVersion(fx.customerNo);
            assertEquals(0, fx.awaitVersionSettled(v, 180_000), "前提：首轮算完");
            fx.awaitQuiet(3_000, 60_000);
            int n = lineOf.size();
            Map<String, Long> base = dryPerLine(upgrades, g0, v, lineOf);
            long k = base.values().iterator().next();
            assertTrue(k >= 1 && base.values().stream().allMatch(x -> x == k), "自校准：首轮每行 dryRun 次数一致且 ≥1，实际 " + base);

            // ── 变体 A（循环未在跑）：改策略 ──
            InFlight tracker = new InFlight(quoteOfLine);
            UpgradeInterceptor.Rule track = upgrades.addRule("AC-24 在途计数", c -> Boolean.TRUE.equals(c.dryRun()) && c.touches(Set.of(v)),
                tracker::observe);
            long a0 = System.currentTimeMillis();
            JsonNode st = api.putStrategyThreshold(fx.customerNo, "1");
            String right = snapshotStates(db, v);
            fx.awaitVersionSettled(v, 180_000);
            fx.awaitQuiet(3_000, 60_000);
            Map<String, Long> afterStrategy = dryPerLine(upgrades, a0, v, lineOf);
            T920Evidence.log("AC-24", "变体A·改策略：响应 budgetRecomputeTriggered=" + st.path("budgetRecomputeTriggered")
                + " affectedReviewCount=" + st.path("affectedReviewCount") + "；PUT 返回瞬间状态=" + right
                + "；每行 dryRun 次数=" + afterStrategy + "（k=" + k + "）；在途峰值 总=" + tracker.maxTotal.get() + " 单张依据单="
                + tracker.maxPerQuote.get());
            assertTrue(st.path("budgetRecomputeTriggered").asBoolean(false), "前提：改阈值触发了重算（否则变体 A 空跑）: " + st);
            assertFalse(right.contains("READY"), "AC-24：PUT 返回时全部待处理行应已被标为未计算（QUEUED/COMPUTING），实际 " + right);
            for (Map.Entry<String, Long> e : afterStrategy.entrySet()) {
                assertEquals(k, e.getValue(), "AC-24：改策略后 " + e.getKey() + " 恰好被试算一次（k=" + k + " 次 dryRun）");
            }
            assertTrue(tracker.maxPerQuote.get() <= 1, "AC-24：由后台循环处理 ⇒ 同一依据单不会并发试算，实际峰值 " + tracker.maxPerQuote.get());
            assertTrue(tracker.maxTotal.get() <= 3, "AC-24：没有逐条派发的异步任务 ⇒ 在途试算 ≤ 并发度 3，实际 " + tracker.maxTotal.get());
            assertEquals(0, fx.notSettled(v));

            // ── 变体 A：改比对列 ──
            tracker.reset();
            long c0 = System.currentTimeMillis();
            Response pc = api.putColumnsThreshold(fx.customerNo, fx.templateSeriesId, "0.5");
            assertTrue(pc.statusCode() >= 200 && pc.statusCode() < 300, "PUT 比对列: " + pc.statusCode() + " " + pc.asString());
            String right2 = snapshotStates(db, v);
            fx.awaitVersionSettled(v, 180_000);
            fx.awaitQuiet(3_000, 60_000);
            Map<String, Long> afterCols = dryPerLine(upgrades, c0, v, lineOf);
            T920Evidence.log("AC-24", "变体A·改比对列：PUT 返回瞬间状态=" + right2 + "；每行 dryRun=" + afterCols + "；在途峰值 总="
                + tracker.maxTotal.get() + " 单张依据单=" + tracker.maxPerQuote.get() + "；列阈值=" + thresholds(db, v));
            assertFalse(right2.contains("READY"), "AC-24：改比对列后全部待处理行被标为未计算，实际 " + right2);
            for (Map.Entry<String, Long> e : afterCols.entrySet()) assertEquals(k, e.getValue(), "AC-24：改比对列后恰好试算一次 " + e.getKey());
            assertTrue(tracker.maxPerQuote.get() <= 1 && tracker.maxTotal.get() <= 3, "AC-24：改比对列也由后台循环按组处理");
            assertColumnsThreshold(db, v, n, "0.5", "变体A");
            track.disarm();

            // ── 变体 B（循环正在跑时改配置） ──
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<UUID> heldLine = new AtomicReference<>();
            AtomicBoolean once = new AtomicBoolean(false);
            upgrades.addRule("AC-24B 挂起一次在途试算", c -> Boolean.TRUE.equals(c.dryRun()) && c.touches(Set.of(v))
                && once.compareAndSet(false, true), c -> {
                    heldLine.set(c.lineId());
                    held.countDown();
                    release.await(20, TimeUnit.SECONDS);
                });
            long b0 = System.currentTimeMillis();
            api.putStrategyThreshold(fx.customerNo, "2");                       // 旧配置：列阈值仍是 0.5
            assertTrue(held.await(60, TimeUnit.SECONDS), "变体 B 前提：循环开始试算并被挂起");
            long tPut = System.currentTimeMillis();
            Response pc2 = api.putColumnsThreshold(fx.customerNo, fx.templateSeriesId, "0.25");   // 新配置
            assertTrue(pc2.statusCode() >= 200 && pc2.statusCode() < 300, "变体 B：PUT 比对列: " + pc2.asString());
            release.countDown();
            // 在途那一次放行后：只要看到该行 READY，其列阈值就必须已是新配置（旧配置结果不许落库）
            String heldMat = null;
            for (Map.Entry<String, UUID> e : lineOf.entrySet()) if (e.getValue().equals(heldLine.get())) heldMat = e.getKey();
            List<String> violations = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 180_000;
            while (fx.notSettled(v) > 0 && System.currentTimeMillis() < deadline) {
                String t = db.text("SELECT string_agg(c.threshold::text, ',') FROM material_price_review r JOIN "
                    + "material_price_review_column c ON c.review_id = r.id WHERE r.version_id = :v AND r.material_no = :m "
                    + "AND r.budget_status = 'READY' AND r.updated_at > to_timestamp(:t / 1000.0)", "v", v, "m", heldMat, "t", tPut);
                if (t != null && !allEqual(t, "0.25")) violations.add(t);
                T920Fixture.sleep(100);
            }
            long left = fx.notSettled(v);
            fx.awaitQuiet(3_000, 60_000);
            long heldDry = upgrades.callsSince(b0).stream().filter(c -> Boolean.TRUE.equals(c.dryRun()) && c.touches(Set.of(v))
                && c.touches(Set.of(heldLine.get()))).count();
            T920Evidence.log("AC-24", "变体B：挂起行=" + heldMat + "；该行 dryRun 次数=" + heldDry + "；采样到的旧配置落库=" + violations
                + "；未收敛=" + left + "；列阈值=" + thresholds(db, v));
            assertTrue(violations.isEmpty(), "AC-24B①：在途的旧配置试算不写入，实际采样到 READY 且阈值为旧值: " + violations);
            assertTrue(heldDry >= 2 * k, "AC-24B①：被挂起的行按新配置重算（dryRun ≥ 2k），实际 " + heldDry);
            assertEquals(0, left, "AC-24B②：重新排队的行不需要重启就被处理完（循环退出前重扫）");
            assertColumnsThreshold(db, v, n, "0.25", "AC-24B③");
        } finally {
            upgrades.clearRules();
            fx.awaitQuiet(3_000, 120_000);
            fx.cleanup();
        }
    }

    /** 每条待处理行都 READY/FAILED，且每条比对列的阈值 = 新配置。 */
    private static void assertColumnsThreshold(Rp0918aDb db, UUID v, int n, String expected, String what) {
        long rows = db.count("SELECT count(*) FROM material_price_review WHERE version_id = :v AND status = 'PENDING' "
            + "AND budget_status IN ('READY', 'FAILED')", "v", v);
        assertEquals(n, rows, what + "：每条待处理行都是 READY / FAILED");
        List<Object[]> cols = db.rows("SELECT r.material_no, c.threshold FROM material_price_review r JOIN material_price_review_column c "
            + "ON c.review_id = r.id WHERE r.version_id = :v AND r.status = 'PENDING'", "v", v);
        assertTrue(cols.size() >= n, what + "：比对列非空（每行至少一列），实际 " + cols.size());
        for (Object[] c : cols) {
            assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(c[1].toString())),
                what + "：" + c[0] + " 的比对列阈值应与新配置一致（" + expected + "），实际 " + c[1]);
        }
    }

    private static boolean allEqual(String csv, String expected) {
        for (String s : csv.split(",")) if (new BigDecimal(s).compareTo(new BigDecimal(expected)) != 0) return false;
        return true;
    }

    private static String thresholds(Rp0918aDb db, UUID v) {
        return db.text("SELECT string_agg(DISTINCT c.threshold::text, ',') FROM material_price_review r JOIN "
            + "material_price_review_column c ON c.review_id = r.id WHERE r.version_id = :v", "v", v);
    }

    private static String snapshotStates(Rp0918aDb db, UUID v) {
        return db.text("SELECT string_agg(budget_status || ':' || n, ',') FROM (SELECT budget_status, count(*) n FROM "
            + "material_price_review WHERE version_id = :v AND status = 'PENDING' GROUP BY 1) t", "v", v);
    }

    private static Map<String, Long> dryPerLine(UpgradeInterceptor up, long since, UUID v, Map<String, UUID> lineOf) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, UUID> e : lineOf.entrySet()) {
            out.put(e.getKey(), up.callsSince(since).stream().filter(c -> Boolean.TRUE.equals(c.dryRun()) && c.touches(Set.of(v))
                && c.touches(Set.of(e.getValue()))).count());
        }
        return out;
    }

    /** 在途计数：每次 dryRun 进入时占位 400 ms（在调用方线程里），重叠即视为并发。 */
    static final class InFlight {
        final Map<UUID, UUID> quoteOfLine;
        final AtomicInteger total = new AtomicInteger();
        final Map<UUID, AtomicInteger> perQuote = new ConcurrentHashMap<>();
        final AtomicInteger maxTotal = new AtomicInteger();
        final AtomicInteger maxPerQuote = new AtomicInteger();

        InFlight(Map<UUID, UUID> quoteOfLine) {
            this.quoteOfLine = quoteOfLine;
        }

        void reset() {
            maxTotal.set(0);
            maxPerQuote.set(0);
        }

        void observe(Call c) throws InterruptedException {
            UUID quote = null;
            for (UUID u : c.uuids()) if (quoteOfLine.containsKey(u)) quote = quoteOfLine.get(u);
            AtomicInteger pq = perQuote.computeIfAbsent(quote == null ? new UUID(0, 0) : quote, x -> new AtomicInteger());
            maxTotal.accumulateAndGet(total.incrementAndGet(), Math::max);
            maxPerQuote.accumulateAndGet(pq.incrementAndGet(), Math::max);
            try {
                Thread.sleep(400);
            } finally {
                total.decrementAndGet();
                pq.decrementAndGet();
            }
        }
    }

    @SuppressWarnings("unused")
    private static Set<String> set(String... s) {
        return new HashSet<>(List.of(s));
    }
}
