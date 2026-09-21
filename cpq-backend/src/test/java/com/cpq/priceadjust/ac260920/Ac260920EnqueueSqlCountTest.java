package com.cpq.priceadjust.ac260920;

import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.ac260918.Rp0918aDb;
import com.cpq.priceadjust.ac260918.Rp0918aLogCapture;
import com.cpq.priceadjust.ac260920.T920Fixture.Branches;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-3：两个客户，料号数一小一大、<b>分支构成比例相同</b>（六个判定分支都有），分别走真实生成入口，
 * 读 {@code [perf] budget-enqueue customer=%s N=%d pooled=%d advanced=%d sql=%d ms=%d}（backtask B-2 ⑤ 约定的埋点）。
 * 断言：两次 {@code sql=} 相等；并核对 N / pooled / advanced 与夹具期望一致（证明两次测的确实是各自那批料号，不是空跑）。
 *
 * <p>⚠️ AC 原文写「N=5 与 N=50」，但同一条 AC 要求「每种判定分支都有」，而 AC-2 列出的分支是 6 个 ⇒ N=5 无法覆盖全部分支。
 * 本用例默认取每分支 k=1 / k=10（N=6 / N=60，比例 1:10 与原文一致），可用 {@code -Dt260920.ac3.kSmall / kLarge} 改写；
 * 已在回报里请主线澄清。
 */
@QuarkusTest
@TestProfile(T920Profiles.Base.class)
class Ac260920EnqueueSqlCountTest {

    static final Pattern ENQUEUE = Pattern.compile(
        "\\[perf] budget-enqueue customer=(\\S+) N=(\\d+) pooled=(\\d+) advanced=(\\d+) sql=(\\d+) ms=(\\d+)");

    @Inject
    EntityManager em;
    @InjectMock
    SessionHelper sessionHelper;

    record Measured(String customer, int n, int pooled, int advanced, long sql, long ms, String raw) {
    }

    @Test
    void ac3_enqueueSqlCountIndependentOfN() {
        int kSmall = Integer.getInteger("t260920.ac3.kSmall", 1);
        int kLarge = Integer.getInteger("t260920.ac3.kLarge", 10);
        Rp0918aDb db = new Rp0918aDb(em);
        List<T920Fixture> fixtures = new ArrayList<>();
        try (Rp0918aLogCapture logs = Rp0918aLogCapture.start()) {
            Measured small = measure(db, fixtures, logs, kSmall);
            Measured large = measure(db, fixtures, logs, kLarge);
            T920Evidence.log("AC-3", "小=" + small + "\n大=" + large);
            assertEquals(small.sql(), large.sql(),
                "AC-3：进池 SQL 条数应与料号数无关（N=" + small.n() + " sql=" + small.sql() + " vs N=" + large.n() + " sql="
                    + large.sql() + "）。口径：SqlStatementCounter 只计 Hibernate 预编译语句，进池路径不得走裸 JDBC");
            assertTrue(small.sql() > 0, "sql 计数 > 0（计数器确实接上了进池路径，不是恒 0）");
        } finally {
            for (T920Fixture fx : fixtures) {
                fx.awaitQuiet(3_000, 180_000);
                fx.cleanup();
            }
        }
    }

    private Measured measure(Rp0918aDb db, List<T920Fixture> fixtures, Rp0918aLogCapture logs, int k) {
        T920Fixture fx = new T920Fixture(db);
        fixtures.add(fx);
        fx.createCustomerAndStrategy();
        Mockito.when(sessionHelper.getCurrentUserId(ArgumentMatchers.any())).thenReturn(fx.salesRepId);
        Branches br = fx.buildSixBranches(k);
        fx.setElementPriceTarget(T920Fixture.P_TARGET);
        long t0 = System.currentTimeMillis();
        UUID v = T920Api.anonymous().generateVersion(fx.customerNo);
        Measured m = null;
        long deadline = System.currentTimeMillis() + 60_000;
        while (m == null && System.currentTimeMillis() < deadline) {
            for (Matcher x : logs.find(ENQUEUE, t0)) {
                if (fx.customerNo.equals(x.group(1))) {
                    m = new Measured(x.group(1), Integer.parseInt(x.group(2)), Integer.parseInt(x.group(3)),
                        Integer.parseInt(x.group(4)), Long.parseLong(x.group(5)), Long.parseLong(x.group(6)), x.group(0));
                }
            }
            if (m == null) T920Fixture.sleep(200);
        }
        if (m == null) throw new AssertionError("AC-3：60 秒内没有出现本客户 " + fx.customerNo + " 的 [perf] budget-enqueue 日志"
            + "（埋点缺失或格式与 backtask B-2 ⑤ 不一致）");
        assertEquals(br.total(), m.n(), "埋点 N 应等于范围料号数");
        assertEquals(br.expectedPooled().size(), m.pooled(), "埋点 pooled 应等于按 AC 分支树应进池的料号数");
        assertEquals(br.expectedAdvanced().size(), m.advanced(), "埋点 advanced 应等于应推进的料号数");
        // 可观测结果与埋点一致：应推进者指针已到本版本，应进池者有审核行
        for (String x : br.expectedAdvanced()) assertEquals(v, fx.pointer(x), "应推进的 " + x + " 指针应指向本版本");
        for (String x : br.expectedPooled()) {
            assertTrue(fx.review(v, x) != null, "应进池的 " + x + " 应有审核行");
        }
        return m;
    }
}
