package com.cpq.priceadjust.ac260920;

import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.ac260918.Rp0918aDb;
import com.cpq.priceadjust.ac260918.UpgradeInterceptor;
import com.cpq.priceadjust.ac260920.T920Fixture.Quote;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-7（后端部分）：审核行进池时依据单为 Q，<b>进池后、试算前</b>把 Q 改为 CANCELLED，分别经
 * <b>后台循环</b>（料号 Q1）与<b>点击即算</b>（料号 Q2，{@code POST …/compute-now} + 轮询 {@code GET …/row}）两条路径试算。
 * 断言：budget_status=FAILED、budget_error = 「判断依据单已不在活单范围内」、审核状态仍「待处理」、版本指针未推进、
 * 不停在「未计算」。另核 {@code GET /{reviewId}} 返回的 budgetError（B-19，抽屉读失败原因的来源）。
 *
 * <p>「进池后、试算前」的构造：真实生成入口 + 并发度 1（测试配置默认）；另建一张 4 行的大单 B（组内料号更多 ⇒ 按
 * B-5「料号最多的组先派」先被处理），在 B 的第一次试算里挂起（≤20 s，远小于 60 s 事务超时），挂起期间 Q 组还没轮到 ——
 * 此时改 Q 状态、对 Q2 点「计算」，再放行。若 Q 组先于 B 被处理（前置未达成），用例报「前置未达成」而非判实现失败。
 */
@QuarkusTest
@TestProfile(T920Profiles.Base.class)
class Ac260920BasisCancelledTest {

    static final String REASON = "判断依据单已不在活单范围内";

    @Inject
    EntityManager em;
    @InjectMock
    SessionHelper sessionHelper;

    @Test
    void ac7_basisCancelledAfterEnqueue_failsWithReason_bothPaths() throws Exception {
        Rp0918aDb db = new Rp0918aDb(em);
        T920Fixture fx = new T920Fixture(db);
        UpgradeInterceptor upgrades = UpgradeInterceptor.install();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean once = new AtomicBoolean(false);
        try {
            fx.createCustomerAndStrategy();
            Mockito.when(sessionHelper.getCurrentUserId(ArgumentMatchers.any())).thenReturn(fx.salesRepId);
            Quote big = fx.createQuote("B", 4);
            Quote q = fx.createQuote("Q", 2);
            UUID vPrev = fx.insertVersion("PREV", "SUPERSEDED", T920Fixture.P_PREV, 7_200);
            for (String m : big.materials()) fx.setPointer(m, vPrev);
            for (String m : q.materials()) fx.setPointer(m, vPrev);
            fx.addScope(big.materials());
            fx.addScope(q.materials());
            fx.setElementPriceTarget(T920Fixture.P_TARGET);
            String q1 = q.material(1);
            String q2 = q.material(2);
            Set<UUID> bigLines = new HashSet<>(big.lineByMaterial().values());
            UpgradeInterceptor.Rule hold = upgrades.addRule("AC-7 挂起大单首个试算",
                c -> Boolean.TRUE.equals(c.dryRun()) && c.touches(bigLines) && once.compareAndSet(false, true),
                c -> {
                    blocked.countDown();
                    release.await(20, TimeUnit.SECONDS);
                });

            CompletableFuture<UUID> gen = CompletableFuture.supplyAsync(() -> T920Api.anonymous().generateVersion(fx.customerNo));
            assertTrue(blocked.await(90, TimeUnit.SECONDS), "后台循环 90 秒内没有开始试算（大单）");
            UUID v = gen.get(60, TimeUnit.SECONDS);
            Object[] r1 = fx.review(v, q1);
            Object[] r2 = fx.review(v, q2);
            if (r1 == null || r2 == null || !"QUEUED".equals(r1[1]) || !"QUEUED".equals(r2[1])) {
                release.countDown();
                throw new AssertionError("前置未达成：挂起大单时 Q 组的行不是「已进池 + 未计算」：Q1=" + java.util.Arrays.toString(r1)
                    + " Q2=" + java.util.Arrays.toString(r2) + "（若 Q 已被处理，说明派组次序与 B-5 不符，报主线）");
            }
            UUID id2 = (UUID) r2[4];
            UUID id1 = (UUID) r1[4];

            fx.cancelQuote(q.id());
            Response cn = T920Api.anonymous().computeNow(id2);
            assertEquals(202, cn.statusCode(), "compute-now 应一律 202（api.md §2.1）: " + cn.asString());
            JsonNode clickRow = pollRow(id2, 15_000);          // 点击即算路径与大单不同锁，挂起期间应能完成
            boolean clickDoneWhileHeld = clickRow != null && isTerminal(clickRow);
            release.countDown();
            if (!clickDoneWhileHeld) clickRow = pollRow(id2, 60_000);
            long left = fx.awaitVersionSettled(v, 120_000);
            fx.awaitQuiet(2_000, 60_000);

            Object[] f1 = fx.review(v, q1);
            Object[] f2 = fx.review(v, q2);
            JsonNode d2 = T920Api.json(T920Api.anonymous().detail(id2));
            T920Evidence.log("AC-7", "版本 " + v + "；挂起触发=" + hold.fired() + "；compute-now=" + cn.statusCode() + " "
                + cn.asString() + "；挂起期间点击即算已完成=" + clickDoneWhileHeld + "；GET row=" + clickRow
                + "\n后台循环 Q1=" + java.util.Arrays.toString(f1) + "；点击即算 Q2=" + java.util.Arrays.toString(f2)
                + "；未收敛=" + left + "；指针 Q1=" + fx.pointer(q1) + " Q2=" + fx.pointer(q2) + "（vPrev=" + vPrev + "）"
                + "；详情 budgetError=" + T920Api.unwrap(d2).path("budgetError"));

            assertTrue(hold.fired() >= 1, "注入未生效");
            for (Object[] f : List.of(f1, f2)) {
                assertNotNull(f);
                assertEquals("PENDING", f[0], "AC-7：审核状态仍为「待处理」");
                assertEquals("FAILED", f[1], "AC-7：budget_status=FAILED，且不停在「未计算」（现网潜伏缺陷回归）");
                assertEquals(REASON, f[2], "AC-7：budget_error 原文");
            }
            assertEquals(vPrev, fx.pointer(q1), "AC-7：Q1 版本指针未被推进");
            assertEquals(vPrev, fx.pointer(q2), "AC-7：Q2 版本指针未被推进");
            assertNotNull(clickRow, "点击即算：GET row 应可读");
            assertEquals("FAILED", clickRow.path("budgetStatus").asText(), "点击即算：单行查询 budgetStatus");
            assertEquals(REASON, clickRow.path("budgetError").asText(), "点击即算：单行查询 budgetError（列表项同字段）");
            assertEquals(REASON, T920Api.unwrap(d2).path("budgetError").asText(), "B-19：详情 budgetError（抽屉显示原因的来源）");
            assertEquals(id1, fx.reviewId(v, q1), "没有第二条审核行");
        } finally {
            release.countDown();
            upgrades.clearRules();
            fx.awaitQuiet(3_000, 120_000);
            fx.cleanup();
        }
    }

    static boolean isTerminal(JsonNode row) {
        String b = row.path("budgetStatus").asText();
        return "READY".equals(b) || "FAILED".equals(b) || !"PENDING".equals(row.path("reviewStatus").asText());
    }

    /** 每 300 ms 取一次 GET /{id}/row，直到终态或超时；返回最后一次读到的行。 */
    static JsonNode pollRow(UUID id, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            Response r = T920Api.anonymous().row(id);
            if (r.statusCode() == 200) {
                last = T920Api.unwrap(T920Api.json(r));
                if (isTerminal(last)) return last;
            } else if (r.statusCode() != 200) {
                throw new AssertionError("GET /{id}/row HTTP " + r.statusCode() + " " + r.asString());
            }
            T920Fixture.sleep(300);
        }
        return last;
    }
}
