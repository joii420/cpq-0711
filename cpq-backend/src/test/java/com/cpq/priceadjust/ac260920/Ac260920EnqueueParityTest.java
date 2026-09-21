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
        assertEquals(0, db.count("SELECT count(*) FROM element_price_version WHERE customer_no = :c AND status = 'PENDING'",
            "c", CHINT), "前提（S1-3）：测试库正泰没有待处理版本；若已有，本用例不适用，报主线");

        String run = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UUID target = UUID.randomUUID();
        Decision oldD;
        Decision newD;
        Set<String> scope;
        String bumped;
        utx.begin();
        try {
            em.joinTransaction();
            UUID latest = (UUID) em.createNativeQuery("SELECT id FROM element_price_version WHERE customer_no = :c "
                + "ORDER BY created_at DESC LIMIT 1").setParameter("c", CHINT).getResultList().stream().findFirst().orElse(null);
            assertNotNull(latest, "前提：测试库正泰至少有一个历史版本（用来复制元素价明细）");
            em.createNativeQuery("INSERT INTO element_price_version (id, customer_no, version_no, base_date, status, trigger_type, "
                    + "created_at) VALUES (:id, :c, :vn, CURRENT_DATE, 'PENDING', 'MANUAL', now())")
                .setParameter("id", target).setParameter("c", CHINT).setParameter("vn", "T920" + run + "-ZT").executeUpdate();
            // 目标版本 = 最新一版的明细复制，只把一个元素（字母序第一个有价元素）的价 +1 ⇒ 同时存在「有变化」与「无变化」
            bumped = (String) em.createNativeQuery("SELECT min(element_code) FROM element_price_version_item WHERE version_id = :v "
                + "AND current_price IS NOT NULL").setParameter("v", latest).getSingleResult();
            em.createNativeQuery("INSERT INTO element_price_version_item (version_id, element_code, current_price, previous_price, "
                    + "change_rate, currency, price_unit, no_price, inherited_from_previous) "
                    + "SELECT :t, element_code, CASE WHEN element_code = :e THEN current_price + 1 ELSE current_price END, "
                    + "current_price, NULL, currency, price_unit, no_price, inherited_from_previous "
                    + "FROM element_price_version_item WHERE version_id = :v")
                .setParameter("t", target).setParameter("e", bumped).setParameter("v", latest).executeUpdate();
            // 范围由新批量判定给出（ALL 模式的范围解析不是 AC-2 的判定对象）；旧判定在同一范围上逐个跑
            Decision probe = T920Hooks.newDecision(budget, target, CHINT, null);
            scope = new LinkedHashSet<>(probe.pooled());
            scope.addAll(probe.advanced());
            oldD = T920Hooks.oldDecision(budget, target, CHINT, new ArrayList<>(scope));
            newD = T920Hooks.newDecision(budget, target, CHINT, new ArrayList<>(scope));
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
        // S1-3：不留任何写入
        assertEquals(reviewsBefore, db.count("SELECT count(*) FROM material_price_review WHERE customer_no = :c", "c", CHINT));
        assertEquals(refsBefore, db.count("SELECT count(*) FROM material_price_version_ref WHERE customer_no = :c", "c", CHINT));
        assertEquals(versionsBefore, db.count("SELECT count(*) FROM element_price_version WHERE customer_no = :c", "c", CHINT));
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
