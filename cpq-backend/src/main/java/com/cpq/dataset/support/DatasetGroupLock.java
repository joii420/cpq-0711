package com.cpq.dataset.support;

import jakarta.persistence.EntityManager;

/**
 * 数据集写入的表级临界区（PG 事务级 advisory lock）。
 *
 * <p><b>为什么需要它</b>：AC-41 的乐观锁是「读当前版本 → 比对 baseVersion → 写」三步。
 * 没有锁时两个并发请求可以同时读到 v3、同时判定「baseVersion 匹配」、同时升版到 v4 ——
 * 乐观锁静默失效，<b>不报错</b>，只有事后对不上账。取锁后再读，过期写入被串行化，
 * 第二个请求能读到已提交的 v4 从而正确 409。
 *
 * <p>🚨 <b>同源纪律</b>：key 与语句必须与 {@code VersionedGroupWriter.writeGroups} 内部取的锁
 * <b>逐字一致</b>（{@code "ds:" + 表名}，{@code hashtext} 后 cast 成 bigint）。
 * key 不一致 = 锁的不是同一把 = 竞态窗口仍在，而且测不出来。
 * PG 事务级 advisory lock 同事务内可重入，写入器稍后再取同一 key 不阻塞、不死锁。
 *
 * <p>粒度是<b>表级</b>而非「表 + 轴值」级 —— 与写入器保持一致：轴值级在批量导入场景要发 N 条
 * lock 语句（N+1 形态），且两种粒度混用会彻底失去互斥性。
 */
public final class DatasetGroupLock {

    private DatasetGroupLock() {}

    /** 锁 key，与 {@code VersionedGroupWriter} 逐字相同。 */
    public static String key(String tableName) {
        return "ds:" + SqlIdent.of(tableName);
    }

    /** 取该表的事务级 advisory lock。必须在 {@code @Transactional} 内调用。 */
    public static void acquire(EntityManager em, String tableName) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(cast(hashtext(:t) as bigint))")
          .setParameter("t", key(tableName))
          .getSingleResult();
    }
}
