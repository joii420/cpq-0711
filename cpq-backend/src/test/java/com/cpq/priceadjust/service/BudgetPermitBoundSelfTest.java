package com.cpq.priceadjust.service;

import com.cpq.priceadjust.ac260918.Rp0918aDb;
import com.cpq.priceadjust.ac260918.UpgradeInterceptor;
import com.cpq.priceadjust.ac260920.T920Fixture;
import com.cpq.priceadjust.ac260920.T920Profiles;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260920 B-5 返工 · 后端自检：试算名额全进程有界（主线退回 §5.1）。
 *
 * <p>4 个私有客户各一个待处理版本（每版 3 张依据单 × 3 行 ⇒ 3 个依据单组），<b>同时</b>触发 4 个版本的预算循环
 * （并发度 3 ⇒ 若只按「每版一轮」限并发，会有 4×3=12 个工作线程同时在算）；同时另一个私有客户上并发发 6 个点击即算。
 * 每次 dryRun 注入 300ms 延时，让「同时在算」可观测。
 * <ul>
 *   <li>后台在算峰值 ≤ 3（且 ≥ 2，证明观测非空）；</li>
 *   <li>点击即算在算峰值 ≤ 2（且 ≥ 1）；</li>
 *   <li>限时内 5 个版本的待处理行全部 READY / FAILED（QUEUED / COMPUTING = 0）⇒ 无死锁 / 饥饿。</li>
 * </ul>
 * 只写 {@code T260920-<run>} 私有前缀数据，结束按 id 清理；不走定时扫描入口；库 = test profile 的 cpq_db_test。
 */
@QuarkusTest
@TestProfile(T920Profiles.Conc3.class)
class BudgetPermitBoundSelfTest {

    @Inject EntityManager em;
    @Inject PriceAdjustBudgetService budgetService;

    @Test
    void permitsBoundBackgroundAndInteractiveAcrossVersions() throws Exception {
        Rp0918aDb db = new Rp0918aDb(em);
        UpgradeInterceptor upgrades = UpgradeInterceptor.install();
        upgrades.addRule("slow dryRun", c -> Boolean.TRUE.equals(c.dryRun()), UpgradeInterceptor.sleeping(300));
        List<T920Fixture> fixtures = new ArrayList<>();
        ExecutorService callers = Executors.newFixedThreadPool(12);
        try {
            // ── 4 个后台版本 ──
            Map<UUID, T920Fixture> bgVersions = new LinkedHashMap<>();
            for (int k = 0; k < 4; k++) {
                T920Fixture fx = new T920Fixture(db).createCustomerAndStrategy();
                fixtures.add(fx);
                List<String> mats = new ArrayList<>();
                for (String label : List.of("A", "B", "C")) mats.addAll(fx.createQuote(label, 3).materials());
                fx.addScope(mats);
                bgVersions.put(fx.insertVersion("TGT", "PENDING", T920Fixture.P_TARGET, 0), fx);
            }
            // ── 点击即算：第 5 个客户，只进池不跑循环，行停在 QUEUED ──
            T920Fixture ifx = new T920Fixture(db).createCustomerAndStrategy();
            fixtures.add(ifx);
            List<String> imats = new ArrayList<>();
            for (String label : List.of("A", "B", "C")) imats.addAll(ifx.createQuote(label, 2).materials());
            ifx.addScope(imats);
            UUID iv = ifx.insertVersion("TGT", "PENDING", T920Fixture.P_TARGET, 0);
            PriceAdjustBudgetService.EnqueueResult er = budgetService.enqueueMaterials(iv, ifx.customerNo, imats);
            assertEquals(imats.size(), er.pooled, "前提：点击即算客户的料号全部进池（QUEUED）");
            List<UUID> interactiveRows = new ArrayList<>();
            for (String m : imats) interactiveRows.add(ifx.reviewId(iv, m));

            BudgetConcurrencyMeter meter = budgetService.concurrencyMeter();
            meter.resetPeaks();
            Map<UUID, Long> settledMs = new ConcurrentHashMap<>();
            CountDownLatch go = new CountDownLatch(1);
            long t0 = System.currentTimeMillis();
            List<CompletableFuture<?>> fs = new ArrayList<>();
            for (UUID v : bgVersions.keySet()) {
                fs.add(CompletableFuture.runAsync(() -> {
                    await(go);
                    budgetService.onVersionGenerated(v);
                    settledMs.put(v, System.currentTimeMillis() - t0);
                }, callers));
            }
            List<String> accepted = new ArrayList<>();
            for (UUID rid : interactiveRows) {
                fs.add(CompletableFuture.runAsync(() -> {
                    await(go);
                    String st = budgetService.requestCompute(rid, PriceAdjustBudgetService.ComputeMode.COMPUTE_NOW);
                    synchronized (accepted) { accepted.add(st); }
                }, callers));
            }
            go.countDown();

            // 限时收敛（无死锁判据）
            List<UUID> all = new ArrayList<>(bgVersions.keySet());
            all.add(iv);
            long deadline = System.currentTimeMillis() + 240_000;
            long left = Long.MAX_VALUE;
            while (System.currentTimeMillis() < deadline) {
                left = 0;
                for (UUID v : all) left += bgVersions.containsKey(v) ? bgVersions.get(v).notSettled(v) : ifx.notSettled(v);
                if (left == 0 && fs.stream().allMatch(CompletableFuture::isDone)) break;
                T920Fixture.sleep(300);
            }
            long total = System.currentTimeMillis() - t0;

            Map<String, String> dist = new LinkedHashMap<>();
            for (UUID v : all) {
                dist.put(v.toString().substring(0, 8), db.text("SELECT coalesce(string_agg(budget_status || ':' || n, ','), '无') "
                    + "FROM (SELECT budget_status, count(*) n FROM material_price_review WHERE version_id = :v AND status = 'PENDING' "
                    + "GROUP BY budget_status ORDER BY 1) t", "v", v));
            }
            System.out.println("[BUDGET-PERMIT-SELFTEST] backgroundPeak=" + meter.backgroundPeak()
                + " interactivePeak=" + meter.interactivePeak()
                + " backgroundInFlightNow=" + meter.backgroundInFlight() + " interactiveInFlightNow=" + meter.interactiveInFlight()
                + " totalMs=" + total + " notSettled=" + left);
            System.out.println("[BUDGET-PERMIT-SELFTEST] 各版本循环返回耗时(ms)=" + settledMs + " 点击即算受理状态=" + accepted
                + " dryRun 次数=" + upgrades.calls().stream().filter(c -> Boolean.TRUE.equals(c.dryRun())).count());
            System.out.println("[BUDGET-PERMIT-SELFTEST] 各版本待处理行分布=" + dist);

            assertEquals(0, left, "无死锁：限时内全部待处理行应为 READY / FAILED，分布 " + dist);
            assertTrue(meter.backgroundPeak() <= 3, "后台在算峰值应 ≤ 3，实际 " + meter.backgroundPeak());
            assertTrue(meter.backgroundPeak() >= 2, "观测非空：后台峰值应 ≥ 2，实际 " + meter.backgroundPeak());
            assertTrue(meter.interactivePeak() <= 2, "点击即算在算峰值应 ≤ 2，实际 " + meter.interactivePeak());
            assertTrue(meter.interactivePeak() >= 1, "观测非空：点击即算峰值应 ≥ 1，实际 " + meter.interactivePeak());
            assertEquals(0, meter.backgroundInFlight() + meter.interactiveInFlight(), "结束后名额应全部归还");
        } finally {
            upgrades.clearRules();
            callers.shutdownNow();
            callers.awaitTermination(30, TimeUnit.SECONDS);
            for (T920Fixture fx : fixtures) {
                fx.awaitQuiet(3_000, 60_000);
                fx.cleanup();
            }
        }
    }

    private static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
