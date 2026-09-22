package com.cpq.priceadjust.ac260920;

import com.cpq.priceadjust.ac260918.Rp0918aDb;
import com.cpq.priceadjust.ac260920.T920Fixture.Branch;
import com.cpq.priceadjust.ac260920.T920Fixture.Branches;
import com.cpq.priceadjust.ac260920.T920Hooks.Decision;
import com.cpq.priceadjust.service.PriceAdjustBudgetService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Status;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-2：同一事务（结束回滚）、同一快照上，旧逐料号判定（原样调用四个现有函数）与新批量判定的
 * 「进池集合」「推进指针集合」逐个相同，且进池集合非空。
 * <ul>
 *   <li>{@link #ac2_sixBranchFixture}：自建客户、六分支各 2 个料号；另断言旧判定与 AC 分支树的期望一致
 *       （证明夹具真的覆盖了每个分支，而不是两边都在同一个退化输入上相等）。</li>
 *   <li>{@link #ac2_chintFullData}：测试库正泰 CUST-0004 全量（ALL 模式）。测试库正泰没有待处理版本 ⇒
 *       在<b>同一个回滚事务</b>里先建目标版本（版本 + 元素价明细），比完随事务回滚（test.md S1-3），不留任何写入。</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(T920Profiles.Base.class)
class Ac260920EnqueueParityTest {

    static final String CHINT = "CUST-0004";

    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;
    @Inject
    PriceAdjustBudgetService budget;

    @Test
    void ac2_sixBranchFixture() throws Exception {
        Rp0918aDb db = new Rp0918aDb(em);
        T920Fixture fx = new T920Fixture(db);
        try {
            fx.createCustomerAndStrategy();
            Branches br = fx.buildSixBranches(2);
            UUID target = fx.insertVersion("TGT", "PENDING", T920Fixture.P_TARGET, 60);
            List<String> scope = fx.scope();
            assertEquals(br.total(), scope.size(), "前提：范围 = 六分支全部料号");

            Decision oldD;
            Decision newD;
            utx.begin();
            try {
                em.joinTransaction();
                oldD = T920Hooks.oldDecision(budget, target, fx.customerNo, scope);
                newD = T920Hooks.newDecision(budget, target, fx.customerNo, scope);
            } finally {
                rollback();
            }
            T920Evidence.log("AC-2", "六分支夹具 客户=" + fx.customerNo + " 分支=" + br.byBranch()
                + "\n旧 进池=" + new TreeSet<>(oldD.pooled()) + " 推进=" + new TreeSet<>(oldD.advanced())
                + "\n新 进池=" + new TreeSet<>(newD.pooled()) + " 推进=" + new TreeSet<>(newD.advanced()));

            // 夹具鉴别力：旧判定必须落在 AC 分支树的期望上（每个分支都真的走到了）
            for (Branch b : Branch.values()) {
                for (String m : br.byBranch().get(b)) {
                    assertEquals(b.pooled, oldD.pooled().contains(m),
                        "夹具前提：旧判定对分支 " + b + " 的料号 " + m + " 应" + (b.pooled ? "进池" : "推进")
                            + " —— 不成立说明夹具没走到该分支（夹具问题，非实现问题），报主线");
                }
            }
            assertFalse(oldD.pooled().isEmpty(), "进池集合非空");
            assertEquals(new TreeSet<>(oldD.pooled()), new TreeSet<>(newD.pooled()), "AC-2：进池料号集合逐个相同");
            assertEquals(new TreeSet<>(oldD.advanced()), new TreeSet<>(newD.advanced()), "AC-2：推进指针料号集合逐个相同");
            assertNoWrites(db, fx.customerNo, target);
        } finally {
            fx.cleanup();
        }
    }

    @Test
    void ac2_chintFullData() throws Exception {
        Rp0918aDb db = new Rp0918aDb(em);
        String clone = db.text("SELECT coalesce((SELECT max(created_at)::text FROM element_price_version), '?')");
        long reviewsBefore = db.count("SELECT count(*) FROM material_price_review WHERE customer_no = :c", "c", CHINT);
        long refsBefore = db.count("SELECT count(*) FROM material_price_version_ref WHERE customer_no = :c", "c", CHINT);
        long versionsBefore = db.count("SELECT count(*) FROM element_price_version WHERE customer_no = :c", "c", CHINT);
        String elemBefore = db.text("SELECT count(*) || ':' || coalesce(string_agg(e.element_code, ',' ORDER BY e.element_code), '') "
            + "FROM customer_price_adjust_element e JOIN customer_price_adjust_strategy s ON s.id = e.strategy_id WHERE s.customer_no = :c",
            "c", CHINT);
        assertEquals(0, db.count("SELECT count(*) FROM element_price_version WHERE customer_no = :c AND status = 'PENDING'",
            "c", CHINT), "前提（S1-3）：测试库正泰没有待处理版本；若已有，本用例不适用，报主线");

        String run = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UUID target = UUID.randomUUID();
        Decision oldD;
        Decision newD;
        Decision oldP;
        Decision newP;
        int pointerK;
        Set<String> scope;
        String bumped;
        utx.begin();
        try {
            em.joinTransaction();
            // 测试库正泰：0 个版本 / 0 条指针 / 0 条审核行 / 策略参与元素 0 个（主线 01:52 后端只读实查）
            // ⇒ 全部在本回滚事务内合成：参与元素 Ag + 目标版本（仅 Ag 一条明细）。事务外零写入。
            UUID strategy = (UUID) em.createNativeQuery("SELECT id FROM customer_price_adjust_strategy WHERE customer_no = :c")
                .setParameter("c", CHINT).getResultList().stream().findFirst().orElse(null);
            assertNotNull(strategy, "前提：测试库正泰有调价策略（ALL 模式）");
            em.createNativeQuery("INSERT INTO customer_price_adjust_element (strategy_id, element_code) VALUES (:s, 'Ag') "
                + "ON CONFLICT DO NOTHING").setParameter("s", strategy).executeUpdate();
            Object ag = em.createNativeQuery("SELECT raw_price FROM element_daily_price WHERE element_name = 'Ag' "
                + "AND raw_price IS NOT NULL AND price_date <= CURRENT_DATE ORDER BY price_date DESC, source_id LIMIT 1")
                .getResultList().stream().findFirst().orElse(null);
            assertNotNull(ag, "前提：测试库有银日价（合成目标版本价格用）");
            java.math.BigDecimal agPrice = new java.math.BigDecimal(ag.toString());
            bumped = "Ag";
            em.createNativeQuery("INSERT INTO element_price_version (id, customer_no, version_no, base_date, status, trigger_type, "
                    + "created_at) VALUES (:id, :c, :vn, CURRENT_DATE, 'PENDING', 'MANUAL', now())")
                .setParameter("id", target).setParameter("c", CHINT).setParameter("vn", "T920" + run + "-ZT").executeUpdate();
            em.createNativeQuery("INSERT INTO element_price_version_item (version_id, element_code, current_price, currency, "
                    + "price_unit, no_price, inherited_from_previous) VALUES (:t, 'Ag', CAST(:p AS numeric), 'CNY', 'kg', false, false)")
                .setParameter("t", target).setParameter("p", agPrice.toPlainString()).executeUpdate();
            // 范围由新批量判定给出（ALL 模式的范围解析不是 AC-2 的判定对象）；旧判定在同一范围上逐个跑
            Decision probe = T920Hooks.newDecision(budget, target, CHINT, null);
            scope = new LinkedHashSet<>(probe.pooled());
            scope.addAll(probe.advanced());
            oldD = T920Hooks.oldDecision(budget, target, CHINT, new ArrayList<>(scope));
            newD = T920Hooks.newDecision(budget, target, CHINT, new ArrayList<>(scope));
            // 变体 P（同一回滚事务）：测试库正泰没有指针 ⇒ 上面只覆盖「无指针」类分支。再合成两个旧版本
            // （银价相同 / 不同），把前 2×K 个进池料号的指针分别指过去，覆盖「有指针无变化 / 有变化 / 扫不出元素」分支后再比一次。
            UUID vSame = UUID.randomUUID();
            UUID vDiff = UUID.randomUUID();
            for (Object[] v : new Object[][]{{vSame, "-ZS", agPrice}, {vDiff, "-ZD", agPrice.subtract(java.math.BigDecimal.ONE)}}) {
                em.createNativeQuery("INSERT INTO element_price_version (id, customer_no, version_no, base_date, status, trigger_type, "
                        + "created_at) VALUES (:id, :c, :vn, CURRENT_DATE - 1, 'SUPERSEDED', 'MANUAL', now() - interval '1 day')")
                    .setParameter("id", v[0]).setParameter("c", CHINT).setParameter("vn", "T920" + run + v[1]).executeUpdate();
                em.createNativeQuery("INSERT INTO element_price_version_item (version_id, element_code, current_price, currency, "
                        + "price_unit, no_price, inherited_from_previous) VALUES (:t, 'Ag', CAST(:p AS numeric), 'CNY', 'kg', false, false)")
                    .setParameter("t", v[0]).setParameter("p", ((java.math.BigDecimal) v[2]).toPlainString()).executeUpdate();
            }
            List<String> pooledSorted = new ArrayList<>(new TreeSet<>(oldD.pooled()));
            int k = Math.min(Integer.getInteger("t260920.ac2.pointerK", 100), pooledSorted.size() / 2);
            for (int i = 0; i < 2 * k; i++) {
                em.createNativeQuery("INSERT INTO material_price_version_ref (customer_no, material_no, version_id, updated_at) "
                        + "VALUES (:c, :m, :v, now())")
                    .setParameter("c", CHINT).setParameter("m", pooledSorted.get(i)).setParameter("v", i < k ? vSame : vDiff).executeUpdate();
            }
            em.flush();
            oldP = T920Hooks.oldDecision(budget, target, CHINT, new ArrayList<>(scope));
            newP = T920Hooks.newDecision(budget, target, CHINT, new ArrayList<>(scope));
            pointerK = k;
        } finally {
            rollback();
        }
        Set<String> overlap = new TreeSet<>(newD.pooled());
        overlap.retainAll(newD.advanced());
        T920Evidence.log("AC-2", "正泰全量（测试库）最新版本创建时刻≈克隆时点 " + clone + "；范围料号数=" + scope.size()
            + "（AC 实查 3698）；目标版本加价元素=" + bumped
            + "；旧 进池=" + oldD.pooled().size() + " 推进=" + oldD.advanced().size()
            + "；新 进池=" + newD.pooled().size() + " 推进=" + newD.advanced().size() + "；新两集合交集=" + overlap.size());
        Set<String> onlyOld = new TreeSet<>(oldD.pooled());
        onlyOld.removeAll(newD.pooled());
        Set<String> onlyNew = new TreeSet<>(newD.pooled());
        onlyNew.removeAll(oldD.pooled());
        if (!onlyOld.isEmpty() || !onlyNew.isEmpty()) {
            T920Evidence.log("AC-2", "差异 只在旧进池=" + head(onlyOld) + " 只在新进池=" + head(onlyNew));
        }
        assertTrue(scope.size() > 0, "范围非空");
        assertTrue(overlap.isEmpty(), "进池与推进不应重叠: " + head(overlap));
        assertFalse(oldD.pooled().isEmpty(), "进池集合非空");
        assertEquals(new TreeSet<>(oldD.pooled()), new TreeSet<>(newD.pooled()), "AC-2（正泰全量）：进池集合逐个相同");
        assertEquals(new TreeSet<>(oldD.advanced()), new TreeSet<>(newD.advanced()), "AC-2（正泰全量）：推进集合逐个相同");
        T920Evidence.log("AC-2", "正泰变体P（合成指针 " + pointerK + " 同价 + " + pointerK + " 变价）：旧 进池=" + oldP.pooled().size()
            + " 推进=" + oldP.advanced().size() + "；新 进池=" + newP.pooled().size() + " 推进=" + newP.advanced().size());
        assertTrue(pointerK > 0, "变体P前提：至少合成 1 组指针");
        assertTrue(oldP.advanced().size() > oldD.advanced().size(),
            "变体P鉴别力：合成的「同价指针」料号应从进池转为推进（否则指针分支没被走到）");
        assertEquals(new TreeSet<>(oldP.pooled()), new TreeSet<>(newP.pooled()), "AC-2（正泰+合成指针）：进池集合逐个相同");
        assertEquals(new TreeSet<>(oldP.advanced()), new TreeSet<>(newP.advanced()), "AC-2（正泰+合成指针）：推进集合逐个相同");
        // S1-3：不留任何写入
        assertEquals(reviewsBefore, db.count("SELECT count(*) FROM material_price_review WHERE customer_no = :c", "c", CHINT));
        assertEquals(refsBefore, db.count("SELECT count(*) FROM material_price_version_ref WHERE customer_no = :c", "c", CHINT));
        assertEquals(versionsBefore, db.count("SELECT count(*) FROM element_price_version WHERE customer_no = :c", "c", CHINT));
        assertEquals(elemBefore, db.text("SELECT count(*) || ':' || coalesce(string_agg(e.element_code, ',' ORDER BY e.element_code), '') "
            + "FROM customer_price_adjust_element e JOIN customer_price_adjust_strategy s ON s.id = e.strategy_id WHERE s.customer_no = :c",
            "c", CHINT), "S1-3：策略参与元素随事务回滚");
        assertEquals(0, db.count("SELECT count(*) FROM element_price_version WHERE id = :id", "id", target), "目标版本已随事务回滚");
    }

    private void rollback() throws Exception {
        int st = utx.getStatus();
        if (st == Status.STATUS_ACTIVE || st == Status.STATUS_MARKED_ROLLBACK) utx.rollback();
    }

    /** 新判定入口承诺只读：回滚后自建客户名下不得多出审核行、指针不得指向目标版本。 */
    private static void assertNoWrites(Rp0918aDb db, String customerNo, UUID target) {
        assertEquals(0, db.count("SELECT count(*) FROM material_price_review WHERE version_id = :v", "v", target));
        assertEquals(0, db.count("SELECT count(*) FROM material_price_version_ref WHERE customer_no = :c AND version_id = :v",
            "c", customerNo, "v", target));
    }

    private static String head(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        return l.size() <= 20 ? l.toString() : l.subList(0, 20) + "…共" + l.size();
    }
}
