package com.cpq.quotation.service;

import com.cpq.quotation.entity.Quotation;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3 lazy-cardvalues(2026-06-29):钉死 {@link CardSnapshotService#ensureCardValues(UUID)} 懒算端点行为 ——
 * 清空整单卡片值后,一次 ensure 把缺失行全部补齐(返回值==缺失行数),再 ensure 返回 0(幂等)。
 *
 * <p><b>隔离</b>:沿用 {@link CardValuesBatchPersistEquivTest} 的 {@code @TestTransaction} 回滚护栏 ——
 * 整个测试(含 clear/ensure 的赋值)在同一事务内,结束<b>回滚</b>,共享远程 DB 的 rockwell 基准单<b>不被污染</b>
 * (卡片值不会被永久清空)。{@code ensureCardValues} 内部 {@code @Transactional} 默认 REQUIRED 加入外层事务,
 * {@code pg_try_advisory_xact_lock} 在同一事务内可重入,两次调用都拿得到锁(不会误判 warm-in-flight)。
 */
@QuarkusTest
class EnsureCardValuesTest {

    @Inject CardSnapshotService svc;
    @Inject EntityManager em;

    static final UUID QID = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");

    /** 清空整单两侧卡片值(同事务回滚,不落库)。clear+em.clear() 让后续读绕过持久化上下文取库内新值。 */
    private void clearCardValues() {
        em.createNativeQuery(
                "UPDATE quotation_line_item SET quote_card_values = NULL, costing_card_values = NULL " +
                "WHERE quotation_id = :q")
            .setParameter("q", QID).executeUpdate();
        em.flush();
        em.clear();
    }

    /** 与 ensureCardValues 缺失谓词同款:统计缺卡片值的行数。 */
    private int countMissing(boolean hasCosting) {
        em.flush();
        String sql = "SELECT count(*) FROM quotation_line_item WHERE quotation_id = :q " +
            "AND ( quote_card_values IS NULL" + (hasCosting ? " OR costing_card_values IS NULL" : "") + " )";
        Number n = (Number) em.createNativeQuery(sql).setParameter("q", QID).getSingleResult();
        return n.intValue();
    }

    @Test
    @TestTransaction
    void ensure_fills_all_then_idempotent() {
        Quotation q = Quotation.findById(QID);
        Assumptions.assumeTrue(q != null && q.customerTemplateId != null, "基准单缺失,跳过");
        boolean hasCosting = q.costingCardTemplateId != null;

        // 1) 清空 → 全行缺失
        clearCardValues();
        int before = countMissing(hasCosting);
        assertTrue(before > 0, "清空后应有缺失行");

        // 2) 一次 ensure → 补齐全部缺失行(返回值 == 缺失行数)
        int filled = svc.ensureCardValues(QID);
        assertEquals(before, filled, "ensureCardValues 应补算全部缺失行");

        // 3) 补齐后零缺失
        assertEquals(0, countMissing(hasCosting), "ensure 后应无缺失行");

        // 4) 再次 ensure → 幂等返回 0
        int second = svc.ensureCardValues(QID);
        assertEquals(0, second, "第二次 ensureCardValues 应为 0(幂等)");

        System.out.printf("[ensure-cardvalues-test] quotation=%s 补算 %d 行 → 0 缺失, 二次 0 ✅%n", QID, filled);
    }
}
