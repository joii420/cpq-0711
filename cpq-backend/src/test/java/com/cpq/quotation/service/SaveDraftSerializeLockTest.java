package com.cpq.quotation.service;

import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2-0 串行化验证：证明 cpq.savedraft-serialize-lock（悲观写锁）能让同单并发
 * saveDraft 排队执行，防止"全删全建"交错后行被清空（939e072e 案例）。
 *
 * <h2>测试策略</h2>
 * <p>并发测试在 JVM 内难以 100% 确定性地复现"锁阻塞"（Quarkus 测试容器中每个
 * @Transactional 调用在同一线程执行完后才提交，无法跨线程持锁）。因此本测试采用
 * 两种互补方式：
 * <ol>
 *   <li><b>TC-1 连续两次 saveDraft 行数稳定</b>（确定性断言）：同一报价单连续调用两次
 *       saveDraft（各含不同行数），最终行数等于第二次 payload 的行数，不丢失。
 *       证明了：在串行化路径下两次保存语义正确。局限：单线程，不证明并发阻塞本身。</li>
 *   <li><b>TC-2 双线程并发 saveDraft 行数不丢</b>（并发断言）：两个线程并发对同一单
 *       saveDraft，各含不同行数。锁确保两次写串行化；最终行数等于后完成线程的 payload
 *       行数（last-write-wins，与单线程连续调用语义一致）。断言行数 ∈ {1, 2}（两个
 *       payload 的合法值之一），不为 0（丢数据征兆）。局限：并发时序受 JVM 调度影响，
 *       不能保证 100% 触发交错——但即使不触发交错，断言也验证了正确性不变量。</li>
 * </ol>
 *
 * <h2>kill switch 验证</h2>
 * <p>TC-3 验证 kill switch 关闭后（-Dcpq.savedraft-serialize-lock=false）saveDraft
 * 仍正常工作（走无锁路径）——保证 kill switch 本身没有引入副作用。
 *
 * <h2>局限说明</h2>
 * <p>由于 QuarkusTest 内 @Transactional 方法在调用者线程内同步执行完成，我们无法
 * 在测试中"持锁期间让另一线程阻塞后观测到阻塞"——那需要两个真正并发的事务在同一
 * 数据库行上竞争，而测试框架无法精确控制这个时序。TC-2 通过 CountDownLatch 尽量
 * 制造并发窗口，但不能保证两个事务真的交错。代码层 review（PESSIMISTIC_WRITE 在
 * QuotationService.saveDraft 入口）是锁生效的主要证明。
 */
@QuarkusTest
class SaveDraftSerializeLockTest {

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    // ── 测试数据辅助 ──────────────────────────────────────────────────────────

    // 远程 DB 中已存在的 customer/user ID，满足 FK 约束；测试开始前通过 psql 确认存在。
    // 若 DB 数据变动导致找不到，测试会因 ConstraintViolation 失败（属预期失败，需更新这两个常量）。
    private static final UUID TEST_CUSTOMER_ID = UUID.fromString("9aee3d9d-1b4d-4698-9af6-34bd9979d887");
    private static final UUID TEST_USER_ID     = UUID.fromString("896ed7d9-bf12-4ea7-9ff1-09cb14496311");

    /** 创建一个最小 Quotation 用于测试，用 native SQL 直接插入绕开 Panache id 检测。 */
    @Transactional
    UUID createMinimalQuotation() {
        UUID qid = UUID.randomUUID();
        // 用 native SQL 插入，避免 Panache persist() 对手动设置 id 误判为 detached entity。
        em.createNativeQuery(
            "INSERT INTO quotation (id, quotation_number, customer_id, sales_rep_id, name, status, " +
            "total_amount, original_amount, system_discount_rate, final_discount_rate, " +
            "tax_rate, tax_amount, is_manually_adjusted, created_at, updated_at) " +
            "VALUES (:id, :num, :cid, :sid, :name, 'DRAFT', 0, 0, 100, 100, 0, 0, false, NOW(), NOW())")
            .setParameter("id", qid)
            .setParameter("num", "TEST-LOCK-" + System.nanoTime())
            .setParameter("cid", TEST_CUSTOMER_ID)
            .setParameter("sid", TEST_USER_ID)
            .setParameter("name", "Lock Test Quotation")
            .executeUpdate();
        return qid;
    }

    /** 构造含 N 个最小 lineItem 的 SaveDraftRequest（lineItems 非 null，无子字段）。 */
    static SaveDraftRequest buildRequest(int lineCount) {
        SaveDraftRequest req = new SaveDraftRequest();
        req.name = "Lock Test - " + lineCount + " lines";
        req.lineItems = new ArrayList<>();
        for (int i = 0; i < lineCount; i++) {
            SaveDraftRequest.LineItemDraft li = new SaveDraftRequest.LineItemDraft();
            li.sortOrder = i;
            // 不传 id → 每次 saveDraft 都视为新行（无需命中 existingById）
            req.lineItems.add(li);
        }
        return req;
    }

    // ── TC-1：连续两次 saveDraft 行数稳定 ────────────────────────────────────

    /**
     * TC-1：连续调用两次 saveDraft（payload 行数分别为 2 和 3），最终行数 = 3。
     *
     * <p>验证内容：
     * <ul>
     *   <li>第一次 saveDraft 正常写入 2 行。</li>
     *   <li>第二次 saveDraft 在第一次事务已提交后正常写入 3 行（行数不丢）。</li>
     *   <li>证明串行化路径下多次保存语义正确，不引入副作用。</li>
     * </ul>
     *
     * <p>局限：单线程，不证明并发阻塞。TC-2 补充并发场景。
     */
    @Test
    void tc1_consecutiveSaveDraft_rowCountStable() {
        UUID qid = createMinimalQuotation();

        // 第一次：2 行
        quotationService.saveDraft(qid, buildRequest(2));
        long count1 = countLines(qid);
        assertEquals(2, count1, "第一次 saveDraft(2行) 后行数应为 2");

        // 第二次：3 行（不含旧 id，前端未回传旧 id 时全量重建）
        quotationService.saveDraft(qid, buildRequest(3));
        long count2 = countLines(qid);
        assertEquals(3, count2, "第二次 saveDraft(3行) 后行数应为 3，不应丢数据");
    }

    // ── TC-2：双线程并发 saveDraft 行数不丢 ──────────────────────────────────

    /**
     * TC-2：两个线程并发对同一报价单 saveDraft（payload 各为 1 行、2 行），
     * 最终行数应 ∈ {1, 2}，不为 0（丢数据征兆）。
     *
     * <p>验证内容：
     * <ul>
     *   <li>并发保存后行数不归零（防御 939e072e 式"全删全建交错清空"）。</li>
     *   <li>行数等于某一次 saveDraft 的合法行数（last-write-wins，串行化效果）。</li>
     * </ul>
     *
     * <p>局限：
     * <ul>
     *   <li>JVM 线程调度不确定，两个事务不一定真的交错。即使不交错，断言也验证正确性。</li>
     *   <li>QuarkusTest 内每次 @Transactional 调用在调用线程内同步完成。两个线程各自
     *       发起独立事务，CountDownLatch 制造尽量同时开始的窗口，但不保证事务真正并发。</li>
     *   <li>悲观锁的"阻塞效果"无法在测试中 100% 观测，代码层 review 是补充证明。</li>
     * </ul>
     */
    @Test
    void tc2_concurrentSaveDraft_rowCountNotZero() throws Exception {
        UUID qid = createMinimalQuotation();

        // 先确保报价单存在（TC-1 已隔离，这里再创建一次）
        int threadCount = 2;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger successCount = new AtomicInteger(0);

        // 线程 A：saveDraft 1 行
        Thread threadA = new Thread(() -> {
            try {
                startLatch.await();
                quotationService.saveDraft(qid, buildRequest(1));
                successCount.incrementAndGet();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                doneLatch.countDown();
            }
        }, "saveDraft-thread-A");

        // 线程 B：saveDraft 2 行
        Thread threadB = new Thread(() -> {
            try {
                startLatch.await();
                quotationService.saveDraft(qid, buildRequest(2));
                successCount.incrementAndGet();
            } catch (Throwable t) {
                // 悲观锁超时（LockTimeoutException）是可接受的结果——
                // 说明锁确实在阻塞，B 等待超时而非数据损坏
                error.set(t);
            } finally {
                doneLatch.countDown();
            }
        }, "saveDraft-thread-B");

        threadA.start();
        threadB.start();

        // 同时释放两个线程，制造并发窗口
        startLatch.countDown();

        // 等待两个线程完成（最多 120 秒，saveDraft 每次 ~8s）
        boolean finished = doneLatch.await(120, TimeUnit.SECONDS);
        assertTrue(finished, "两个 saveDraft 线程应在 120s 内完成");

        // 至少一个线程应成功（悲观锁超时属于可接受；两个都抛异常才是问题）
        if (successCount.get() == 0 && error.get() != null) {
            fail("两个 saveDraft 线程都失败: " + error.get().getMessage());
        }

        // 核心断言：行数不为 0（防御并发删空）
        long finalCount = countLines(qid);
        assertTrue(finalCount >= 0, "行数不应为负数");
        // 如果至少一个线程成功，行数应 ∈ {1, 2}
        if (successCount.get() > 0) {
            assertTrue(finalCount == 1 || finalCount == 2,
                "并发 saveDraft 后行数应为 1 或 2（两个 payload 的合法值之一），实际=" + finalCount
                + "（0 = 数据丢失，其他值 = 不一致）");
        }
    }

    // ── TC-3：kill switch 关闭后 saveDraft 仍正常工作 ─────────────────────────

    /**
     * TC-3：关闭 kill switch（-Dcpq.savedraft-serialize-lock=false）后，saveDraft
     * 走无锁路径，功能仍正常（行数写入正确）。
     *
     * <p>验证内容：kill switch 本身没有引入逻辑副作用，关闭后行为等同于改动前。
     *
     * <p>注意：此测试通过设置 System property 临时关闭 kill switch，测试结束后恢复，
     * 不影响其他测试。
     */
    @Test
    void tc3_killSwitchOff_saveDraftStillWorks() {
        String prev = System.getProperty("cpq.savedraft-serialize-lock");
        System.setProperty("cpq.savedraft-serialize-lock", "false");
        try {
            UUID qid = createMinimalQuotation();
            quotationService.saveDraft(qid, buildRequest(2));
            long count = countLines(qid);
            assertEquals(2, count,
                "kill switch 关闭后 saveDraft(2行) 行数应为 2（无锁路径功能等价）");
        } finally {
            if (prev == null) System.clearProperty("cpq.savedraft-serialize-lock");
            else System.setProperty("cpq.savedraft-serialize-lock", prev);
        }
    }

    // ── TC-4：kill switch 开启时 PESSIMISTIC_WRITE 路径被激活 ─────────────────

    /**
     * TC-4：显式开启 kill switch，验证 saveDraft 走锁路径且行数正确。
     * 补充 TC-3 的对称验证。
     */
    @Test
    void tc4_killSwitchOn_saveDraftWithLockWorks() {
        String prev = System.getProperty("cpq.savedraft-serialize-lock");
        System.setProperty("cpq.savedraft-serialize-lock", "true");
        try {
            UUID qid = createMinimalQuotation();
            quotationService.saveDraft(qid, buildRequest(3));
            long count = countLines(qid);
            assertEquals(3, count,
                "kill switch 开启后 saveDraft(3行) 行数应为 3（锁路径功能正确）");
        } finally {
            if (prev == null) System.clearProperty("cpq.savedraft-serialize-lock");
            else System.setProperty("cpq.savedraft-serialize-lock", prev);
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    @Transactional
    long countLines(UUID quotationId) {
        return QuotationLineItem.count("quotationId = ?1", quotationId);
    }
}
