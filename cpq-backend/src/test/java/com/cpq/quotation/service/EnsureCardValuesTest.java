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

    /**
     * Task2 失败哨兵:确定性 build 失败的行落<b>非 NULL 哨兵</b>而非 NULL ——
     * 前端「全有或全无」gate 不被打回实时风暴 + ensureCardValues 的 {@code IS NULL} 谓词下次不再重选该行(自愈)。
     *
     * <p><b>如何制造确定性失败</b>:{@code buildCardValues(li, q.customerTemplateId, ...)} 用的是<b>报价单级</b>
     * {@code customer_template_id}(非行级 {@code template_id} —— 行级 template_id 在卡片值 build 中根本不读,
     * 故任务原建议的「改行 template_id」无效;改报价单 customer_template_id 为随机 UUID 又撞 FK 约束)。
     * 把报价单 {@code customer_template_id} 置 <b>NULL</b> → {@code buildCardValues} 命中
     * {@code templateId == null} 守卫 → {@code return null} → {@code safeCall} 返回 null → 报价侧全部落哨兵。
     * 全程在 {@code @TestTransaction} 内,结束回滚,共享 DB 的 rockwell 基准单不被污染。
     */
    @Test
    @TestTransaction
    void failed_row_writes_sentinel_not_null() {
        Quotation q = Quotation.findById(QID);
        Assumptions.assumeTrue(q != null && q.customerTemplateId != null, "基准单缺失,跳过");

        // 取一行作断言对象
        Object rawId = em.createNativeQuery(
                "SELECT id FROM quotation_line_item WHERE quotation_id = :q ORDER BY sort_order, id LIMIT 1")
            .setParameter("q", QID).getSingleResult();
        Assumptions.assumeTrue(rawId != null, "基准单无产品行,跳过");
        UUID brokenLine = (rawId instanceof UUID u) ? u : UUID.fromString(rawId.toString());

        // 1) 制造确定性失败:报价单 customer_template_id 置 NULL → 报价侧 buildCardValues 命中 null 守卫全返 null
        em.createNativeQuery(
                "UPDATE quotation SET customer_template_id = NULL WHERE id = :q")
            .setParameter("q", QID).executeUpdate();
        // 清空整单卡片值,让 ensure 重算
        em.createNativeQuery(
                "UPDATE quotation_line_item SET quote_card_values = NULL, costing_card_values = NULL " +
                "WHERE quotation_id = :q")
            .setParameter("q", QID).executeUpdate();
        em.flush();
        em.clear();   // 让 ensureCardValues 内 Quotation.findById 读到改后的坏 template_id

        // 2) ensure → 报价侧 build 失败,落哨兵(非 NULL)
        svc.ensureCardValues(QID);
        em.flush();
        em.clear();

        // 3) 断言:坏行 quote_card_values 非 NULL 且含 __cardValueFailed 哨兵标记
        Object qcv = em.createNativeQuery(
                "SELECT quote_card_values::text FROM quotation_line_item WHERE id = :id")
            .setParameter("id", brokenLine).getSingleResult();
        assertNotNull(qcv, "失败行 quote_card_values 不应为 NULL(应落哨兵)");
        assertTrue(qcv.toString().contains("__cardValueFailed"),
            "失败行 quote_card_values 应含 __cardValueFailed 哨兵标记,实际=" + qcv);

        // 4) 哨兵行不再被 IS NULL 谓词重选:坏行 quote_card_values IS NULL 计数应为 0
        Number stillNull = (Number) em.createNativeQuery(
                "SELECT count(*) FROM quotation_line_item WHERE id = :id AND quote_card_values IS NULL")
            .setParameter("id", brokenLine).getSingleResult();
        assertEquals(0, stillNull.intValue(), "哨兵已落,坏行不应再被 IS NULL 谓词命中");

        // 二次 ensure 返回 0(哨兵行 + 已算行均不再缺失 → 不重复补算)
        int second = svc.ensureCardValues(QID);
        assertEquals(0, second, "第二次 ensureCardValues 应为 0(哨兵行不被重选)");

        System.out.printf("[sentinel-test] quotation=%s line=%s 落哨兵 ✅ 二次 ensure=0%n", QID, brokenLine);
    }
}
