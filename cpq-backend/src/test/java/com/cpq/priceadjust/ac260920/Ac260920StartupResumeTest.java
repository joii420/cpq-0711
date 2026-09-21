package com.cpq.priceadjust.ac260920;

import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.ac260918.Rp0918aDb;
import com.cpq.priceadjust.ac260918.UpgradeInterceptor;
import com.cpq.priceadjust.ac260920.T920Fixture.Quote;
import com.cpq.priceadjust.service.PriceAdjustStartupRecovery;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-16 / AC-18：经<b>真实启动入口</b> {@code PriceAdjustStartupRecovery.runOnStartup()}（🚫 直接调 resumeBudgets）。
 *
 * <p>🚦 D-12 / S1-1：{@code runOnStartup()} 作用于测试库<b>全部</b>待处理版本与在跑批次；且本类的 profile 打开了启动收尾开关，
 * Quarkus 启动那一刻就会自动调一次。⇒ 整个类以 {@code -Dt260920.allowRunOnStartup=true} 门控（未设则 JUnit 直接禁用、
 * 不启动 Quarkus），<b>必须</b>在执行阶段先按 S1-1 采样、报主线、经用户批准后才加这个开关。门控跳过 ≠ 通过。
 * 用例内另有运行期前置：在跑更新批次必须为 0（否则直接失败、不调用），并把夹具以外会被判定查询选中的版本写进证据。
 */
@QuarkusTest
@TestProfile(T920Profiles.StartupOn.class)
@EnabledIfSystemProperty(named = "t260920.allowRunOnStartup", matches = "true")
class Ac260920StartupResumeTest {

    @Inject
    EntityManager em;
    @Inject
    PriceAdjustStartupRecovery recovery;
    @InjectMock
    SessionHelper sessionHelper;

    /** 建一个客户：一张 n 行活单、全部进池并算完（真实生成入口），返回目标版本。 */
    private UUID computedVersion(T920Fixture fx, Quote[] out, int n) {
        fx.createCustomerAndStrategy();
        Mockito.when(sessionHelper.getCurrentUserId(ArgumentMatchers.any())).thenReturn(fx.salesRepId);
        Quote q = fx.createQuote("S", n);
        UUID vPrev = fx.insertVersion("PREV", "SUPERSEDED", T920Fixture.P_PREV, 7_200);
        for (String m : q.materials()) fx.setPointer(m, vPrev);
        fx.addScope(q.materials());
        fx.setElementPriceTarget(T920Fixture.P_TARGET);
        UUID v = T920Api.anonymous().generateVersion(fx.customerNo);
        assertEquals(0, fx.awaitVersionSettled(v, 180_000), "前提：首轮算完");
        fx.awaitQuiet(3_000, 60_000);
        out[0] = q;
        return v;
    }

    private void preflight(Rp0918aDb db, String ac, List<String> ownCustomers) {
        long running = db.count("SELECT count(*) FROM material_price_update_job WHERE status = 'RUNNING'");
        String others = db.text("SELECT coalesce(string_agg(v.customer_no || '/' || v.version_no || ' 未计算=' || "
            + "(SELECT count(*) FROM material_price_review r WHERE r.version_id = v.id AND r.status = 'PENDING' "
            + "AND r.budget_status IN ('QUEUED','COMPUTING')) || ' 无审核且无指针=' || "
            + "(SELECT count(*) FROM customer_price_adjust_material cm JOIN customer_price_adjust_strategy s ON s.id = cm.strategy_id "
            + " WHERE s.customer_no = v.customer_no AND NOT EXISTS (SELECT 1 FROM material_price_review r WHERE r.version_id = v.id "
            + " AND r.material_no = cm.material_no) AND NOT EXISTS (SELECT 1 FROM material_price_version_ref p WHERE "
            + " p.customer_no = v.customer_no AND p.material_no = cm.material_no AND p.version_id = v.id)), '；'), '无') "
            + "FROM element_price_version v WHERE v.status = 'PENDING' AND v.customer_no NOT IN (:own)", "own", ownCustomers);
        T920Evidence.log(ac, "S1-1 采样：在跑更新批次=" + running + "；夹具以外的待处理版本=" + others);
        assertEquals(0, running, "S1-1：测试库有在跑的更新批次 ⇒ 停，不调用 runOnStartup()，报主线");
    }

    @Test
    void ac16_runOnStartupResumesVersionWhoseRowsAllExist() {
        Rp0918aDb db = new Rp0918aDb(em);
        T920Fixture fx = new T920Fixture(db);
        T920Fixture ctl = new T920Fixture(db);
        UpgradeInterceptor upgrades = UpgradeInterceptor.install();
        try {
            Quote[] q = new Quote[1];
            Quote[] cq = new Quote[1];
            UUID v = computedVersion(fx, q, 5);
            UUID vc = computedVersion(ctl, cq, 3);                 // 反向夹具：全部 READY
            String m1 = q[0].material(1), m2 = q[0].material(2);
            db.exec("UPDATE material_price_review SET budget_status = 'QUEUED', updated_at = now() - interval '1 hour' "
                + "WHERE version_id = :v AND material_no IN (:m)", "v", v, "m", List.of(m1, m2));
            assertEquals(0, db.count("SELECT count(*) FROM customer_price_adjust_material cm WHERE cm.strategy_id = :s AND NOT EXISTS "
                + "(SELECT 1 FROM material_price_review r WHERE r.version_id = :v AND r.material_no = cm.material_no)",
                "s", fx.strategyId, "v", v), "前提：范围内每个料号都已有审核行（旧判据下不会被选中）");
            String ctlBefore = stamp(db, vc);
            preflight(db, "AC-16", List.of(fx.customerNo, ctl.customerNo));

            long t0 = System.currentTimeMillis();
            recovery.runOnStartup();
            long left = fx.awaitVersionSettled(v, 120_000);
            ctl.awaitQuiet(3_000, 30_000);
            long dry = upgrades.callsSince(t0).stream().filter(c -> Boolean.TRUE.equals(c.dryRun()) && c.touches(java.util.Set.of(v))).count();
            long ctlDry = upgrades.callsSince(t0).stream().filter(c -> Boolean.TRUE.equals(c.dryRun()) && c.touches(java.util.Set.of(vc))).count();
            T920Evidence.log("AC-16", "v=" + v + " QUEUED 行 " + m1 + "/" + m2 + " → " + fx.review(v, m1)[1] + "/" + fx.review(v, m2)[1]
                + "；未收敛=" + left + "；dryRun(v)=" + dry + "；反向夹具 vc dryRun=" + ctlDry + " 指纹前=" + ctlBefore + " 后=" + stamp(db, vc));
            assertEquals(0, left, "AC-16：该版本被判定为需续跑，QUEUED 行最终全部变为 READY / FAILED");
            assertTrue(dry >= 2, "AC-16：续跑确实试算了这两行（dryRun ≥ 2），实际 " + dry);
            assertEquals(0, ctlDry, "AC-16 反向：全部 READY 的版本不被选中（无试算）");
            assertEquals(ctlBefore, stamp(db, vc), "AC-16 反向：全部 READY 的版本审核行纹丝不动");
        } finally {
            upgrades.clearRules();
            fx.awaitQuiet(3_000, 120_000);
            fx.cleanup();
            ctl.cleanup();
        }
    }

    @Test
    void ac18_staleComputingResetAndResumedWithin2Minutes() {
        Rp0918aDb db = new Rp0918aDb(em);
        T920Fixture fx = new T920Fixture(db);
        UpgradeInterceptor upgrades = UpgradeInterceptor.install();
        try {
            Quote[] q = new Quote[1];
            UUID v = computedVersion(fx, q, 4);
            List<String> stuck = List.of(q[0].material(1), q[0].material(3));
            db.exec("UPDATE material_price_review SET budget_status = 'COMPUTING', updated_at = now() - interval '1 day' "
                + "WHERE version_id = :v AND material_no IN (:m)", "v", v, "m", stuck);
            preflight(db, "AC-18", List.of(fx.customerNo));

            long t0 = System.currentTimeMillis();
            recovery.runOnStartup();
            long left = fx.awaitVersionSettled(v, 120_000);          // AC-18：2 分钟内满足 AC-12
            long elapsed = System.currentTimeMillis() - t0;
            long pending = db.count("SELECT count(*) FROM material_price_review WHERE version_id = :v AND status = 'PENDING'", "v", v);
            long dry = upgrades.callsSince(t0).stream().filter(c -> Boolean.TRUE.equals(c.dryRun()) && c.touches(java.util.Set.of(v))).count();
            T920Evidence.log("AC-18", "v=" + v + " 卡住行=" + stuck + " → " + fx.review(v, stuck.get(0))[1] + "/" + fx.review(v, stuck.get(1))[1]
                + "；未收敛=" + left + " 用时=" + elapsed + "ms；待处理行=" + pending + "；dryRun(v)=" + dry);
            assertTrue(pending > 0, "AC-12 前置：待处理行 > 0");
            assertEquals(0, left, "AC-18：2 分钟内 QUEUED/COMPUTING = 0（🚫 停在 QUEUED 不算通过）");
            assertTrue(dry >= stuck.size(), "AC-18：残留的 COMPUTING 行确实被重置并续跑（dryRun ≥ 2），实际 " + dry);
        } finally {
            upgrades.clearRules();
            fx.awaitQuiet(3_000, 120_000);
            fx.cleanup();
        }
    }

    private static String stamp(Rp0918aDb db, UUID v) {
        return db.text("SELECT md5(string_agg(id || budget_status || status || updated_at, ',' ORDER BY id)) "
            + "FROM material_price_review WHERE version_id = :v", "v", v);
    }
}
