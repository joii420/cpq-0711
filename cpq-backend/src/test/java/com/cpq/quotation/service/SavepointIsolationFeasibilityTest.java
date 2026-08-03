package com.cpq.quotation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Savepoint;

import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * repair-0803 B2 可行性验证 —— Savepoint 能否在 Quarkus/Hibernate/JTA 下隔离 SQL 错误。
 *
 * <p><b>要验证什么</b>：BL-0097 的根因是「某个 `$view` 查询报错 → PG 把整个事务置 aborted
 * → 同事务后续每条 SQL 都失败」。拟用 Savepoint 隔离每个组件的 expand 查询，
 * 但这依赖一个前提：<b>SQL 报错后回滚到 Savepoint，同一事务能否继续正常执行</b>。
 *
 * <p><b>为什么必须先验证</b>：Hibernate 在捕获 SQLException 时可能把 JTA 事务标记为
 * rollback-only；若如此，即便回滚到 Savepoint，事务也已无法提交，Savepoint 方案就不成立，
 * 须退回「仅让错误可见」（B1，已交付）并重新评估独立事务方案。
 *
 * <p>本测试是<b>方案选型的判据</b>，不是功能测试。
 */
@QuarkusTest
@DisplayName("repair-0803 B2：Savepoint 隔离可行性")
class SavepointIsolationFeasibilityTest {

    @Inject
    EntityManager em;

    /** 对照组：不加 Savepoint 时，SQL 错误确实会毒化整个事务（复现 BL-0097 的机制）。 */
    @Test
    @Transactional
    @DisplayName("对照：无 Savepoint 时 SQL 错误毒化事务，后续查询连锁失败")
    void withoutSavepoint_transactionPoisoned() {
        // 先确认事务本来是好的
        Object ok = em.createNativeQuery("SELECT 1").getSingleResult();
        assertEquals(1, ((Number) ok).intValue());

        // 制造一条 SQL 错误（查不存在的表）
        assertThrows(Exception.class, () ->
            em.createNativeQuery("SELECT 1 FROM __table_that_does_not_exist_repair0803__").getSingleResult());

        // 关键：此后同事务内的正常查询也会失败 —— 这就是 BL-0097 的机制
        Exception poisoned = assertThrows(Exception.class, () ->
            em.createNativeQuery("SELECT 1").getSingleResult());
        String msg = String.valueOf(poisoned.getMessage()) + String.valueOf(poisoned.getCause());
        System.out.println("[B2-验证] 无 Savepoint，后续查询异常 = " + msg);
    }

    /**
     * 实验组 —— <b>结论：Savepoint 方案在本项目技术栈下不成立，已被证否</b>。
     *
     * <p>Quarkus 默认连接池 <b>Agroal</b> 在连接被 enlist 到 JTA 事务后，
     * 直接拒绝 {@code Connection.rollback(Savepoint)}：
     * <pre>
     * java.sql.SQLException: Attempting to rollback while enlisted in a transaction
     *     at io.agroal.pool.wrapper.ConnectionWrapper.rollback(ConnectionWrapper.java:220)
     * </pre>
     *
     * <p>本测试固化这一结论，<b>防止后人再花一轮去尝试同一条死路</b>：
     * 想在 JTA 事务内用 Savepoint 隔离单条 SQL 错误，在 Quarkus + Agroal 下走不通；
     * 要真正隔离必须走独立事务（{@code REQUIRES_NEW}），而那有连接池与性能代价，
     * 须单独做性能评估 —— 见 [[BL-0097]] 的方案二。
     *
     * <p>注：{@code setSavepoint} 本身是允许的，被拒的是 {@code rollback(sp)}，
     * 所以"设了却回滚不了"，比完全不支持更具迷惑性。
     */
    @Test
    @Transactional
    @DisplayName("实验：Agroal 在 JTA 内禁止 rollback(Savepoint) —— Savepoint 方案被证否")
    void withSavepoint_agroalRejectsRollback() {
        Session session = em.unwrap(Session.class);
        final Savepoint[] sp = new Savepoint[1];

        // setSavepoint 本身不报错（迷惑点：看起来能用）
        session.doWork(conn -> sp[0] = conn.setSavepoint("expand_probe"));

        try {
            em.createNativeQuery("SELECT 1 FROM __table_that_does_not_exist_repair0803__").getSingleResult();
        } catch (Exception expected) {
            System.out.println("[B2-验证] 捕获预期内 SQL 错误: " + expected.getMessage());
        }

        // 真正的判据：回滚到 Savepoint 会被 Agroal 拒绝
        Exception rejected = assertThrows(Exception.class,
            () -> session.doWork(conn -> conn.rollback(sp[0])),
            "若此处不再抛异常，说明技术栈行为已变（Agroal 放开了限制），"
            + "应重新评估 BL-0097 的 Savepoint 方案是否可行");

        String chain = String.valueOf(rejected.getMessage()) + " | " + String.valueOf(rejected.getCause());
        System.out.println("[B2-验证] ❌ Savepoint 方案被证否: " + chain);
        org.junit.jupiter.api.Assertions.assertTrue(
            chain.contains("enlisted in a transaction") || chain.contains("rollback"),
            "期望是 Agroal 的 enlist 限制，实际: " + chain);
    }
}
