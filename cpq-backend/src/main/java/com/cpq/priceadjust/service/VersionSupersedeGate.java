package com.cpq.priceadjust.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * task-260920 问题-1（AC-17）· 按价格版本的进程内<b>公平</b>读写锁，替 PostgreSQL 行锁补上「写者优先排队」。
 *
 * <p><b>为什么需要</b>：试算事务以 {@code SELECT status FROM element_price_version … FOR SHARE} 复核版本并持有到提交
 * （AC-20 的语义依赖它）；生成新版本时对旧版本行做 {@code UPDATE}。PostgreSQL 里元组只被共享锁锁住时，后到的
 * {@code FOR SHARE} 直接加入共享者、不排到已在等待的 UPDATE 后面（实测：写者排队后到的读者 1.3 ms 即拿到共享锁，
 * 写者一直等到最后一个交叠的读者结束）。多个工作线程的试算事务首尾交叠 ⇒ 生成请求被饿死到整轮算完。
 *
 * <p><b>用法</b>：
 * <ul>
 *   <li>读者 —— 每一个会执行上述 {@code FOR SHARE} 的事务（试算 / 失败落库 / 进池），在事务<b>开始前</b>取读锁、
 *       <b>提交后</b>放；</li>
 *   <li>写者 —— 生成新版本（{@link PriceAdjustVersionGenerationService#generateVersionAndEnqueueBudget}，手动与定时
 *       两个入口都走它）在生成事务开始前对当前 PENDING 版本取写锁，事务提交（或失败）后放。</li>
 * </ul>
 * 公平模式下写者一旦排队，新读者一律排在它后面 ⇒ 生成只等<b>在途的</b>那几次试算（≤ 名额数）。写者提交后，排队的读者进入
 * 事务读到 SUPERSEDED ⇒ 停止（「版本已作废，停止剩余 n 个料号」）；生成失败 ⇒ 放锁后读者照常继续，版本仍 PENDING。
 *
 * <p><b>次序</b>：试算 = 名额 → 依据单锁 → 抢占 → <b>版本读锁</b> → 试算事务 → 条件完成 → 提交 → <b>放读锁</b> → 放依据单锁
 * → 还名额；生成 = <b>版本写锁</b> → 生成事务 → 提交 → 放写锁（写者持锁期间不取名额、不取依据单锁；读者持读锁期间只等 DB，
 * 而与之冲突的 DB 写者此刻不可能在跑）⇒ 无环。
 *
 * <p>🔒 <b>单实例部署前提</b>（与 repair-260918 D-7 相同）：多实例时本锁挡不住别的实例的读者，退回 PG 行锁行为（只是不加速，
 * 不会错写）。锁对象按版本 id 常驻进程（每个版本一个小对象）。
 */
@ApplicationScoped
public class VersionSupersedeGate {

    private static final Logger LOG = Logger.getLogger(VersionSupersedeGate.class);

    /** {@link #read} / {@link #write} 返回的句柄；{@link #close()} 放锁，幂等。 */
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    private static final Handle NOOP = () -> { };

    private final ConcurrentHashMap<UUID, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    private ReentrantReadWriteLock lockOf(UUID versionId) {
        return locks.computeIfAbsent(versionId, k -> new ReentrantReadWriteLock(true));
    }

    /** 读者：阻塞直到取得该版本的读锁（有写者在等 / 持有时排在其后）。{@code versionId==null} ⇒ 空操作。 */
    public Handle read(UUID versionId, String who) {
        if (versionId == null) return NOOP;
        return acquire(lockOf(versionId).readLock(), versionId, who, "读");
    }

    /** 写者：阻塞直到该版本的在途读者全部放锁；持有期间新读者一律排队。{@code versionId==null} ⇒ 空操作。 */
    public Handle write(UUID versionId, String who) {
        if (versionId == null) return NOOP;
        return acquire(lockOf(versionId).writeLock(), versionId, who, "写");
    }

    private static Handle acquire(Lock lock, UUID versionId, String who, String kind) {
        boolean immediate;
        try {
            immediate = lock.tryLock(0, TimeUnit.MILLISECONDS); // 计时版遵守公平（无参 tryLock 会插队）
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            immediate = false;
        }
        if (!immediate) {
            long t0 = System.nanoTime();
            LOG.infof("[price-adjust-version-gate] version=%s %s 等待版本%s锁", versionId, who, kind);
            lock.lock();
            LOG.infof("[price-adjust-version-gate] version=%s %s 取得版本%s锁，等待 %d ms", versionId, who, kind,
                (System.nanoTime() - t0) / 1_000_000);
        }
        return new Handle() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) return;
                closed = true;
                lock.unlock();
            }
        };
    }
}
