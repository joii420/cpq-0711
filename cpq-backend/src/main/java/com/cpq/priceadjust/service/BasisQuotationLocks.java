package com.cpq.priceadjust.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * task-260920 B-15① · per-quotation in-process <b>fair</b> lock (single-instance premise, repair-260918 D-7).
 *
 * <p>Every path that runs a price-version upgrade (real or dry run) against a quotation's line items takes the lock of
 * that quotation first: budget loop workers, compute-now, recompute-budget, the review drawer's element-impact dry runs
 * and the formal update job (D-9). Two upgrades of the same quotation therefore never overlap — the 2026-06-22 race was
 * exactly two dry runs of one quotation interleaving.
 *
 * <p>🔒 Ordering contract (backtask header): <b>lock → preempt → compute → conditional complete → unlock</b>. The lock
 * must be taken while the thread holds <b>no open transaction and no row lock</b>; otherwise "row lock held, waiting for
 * the process lock" vs "process lock held, waiting for the row" deadlocks (review round 2 【4】).
 *
 * <p>🔒 Fair ({@code new ReentrantLock(true)}): a background worker that releases and immediately re-acquires would
 * otherwise starve a waiting compute-now (review round 2 【14】). Note {@link ReentrantLock#tryLock()} (no timeout)
 * barges even on a fair lock, so this class only ever uses the timed variant.
 *
 * <p>Locks are kept for the lifetime of the process (one small object per quotation that was ever upgraded); a
 * {@code null} quotation id (materials without a basis quotation) needs no lock and gets a no-op handle.
 */
@ApplicationScoped
public class BasisQuotationLocks {

    private static final Logger LOG = Logger.getLogger(BasisQuotationLocks.class);

    /** Handle returned by {@link #acquire}; {@link #close()} releases. Idempotent. */
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    private static final Handle NOOP = () -> { };

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock(true);
        volatile String holder;
    }

    private final ConcurrentHashMap<UUID, Entry> locks = new ConcurrentHashMap<>();

    /**
     * Blocks until the lock of {@code quotationId} is held. When it has to wait, logs
     * {@code [price-adjust-lock] quotation=… <who> 等待 …} before and the waited milliseconds after (AC-26 ③ evidence).
     */
    public Handle acquire(UUID quotationId, String who) {
        if (quotationId == null) return NOOP;
        Entry e = locks.computeIfAbsent(quotationId, k -> new Entry());
        boolean immediate;
        try {
            immediate = e.lock.tryLock(0, TimeUnit.MILLISECONDS); // honours fairness, unlike tryLock()
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            immediate = false;
        }
        if (!immediate) {
            long t0 = System.nanoTime();
            LOG.infof("[price-adjust-lock] quotation=%s %s 等待依据单锁（当前持有者：%s）", quotationId, who, e.holder);
            e.lock.lock();
            LOG.infof("[price-adjust-lock] quotation=%s %s 取得依据单锁，等待 %d ms", quotationId, who,
                (System.nanoTime() - t0) / 1_000_000);
        }
        e.holder = who;
        return handleFor(e);
    }

    /**
     * Like {@link #acquire} but gives up after {@code timeoutMillis}; returns {@code null} when the lock could not be
     * taken in time (the caller then falls back to an asynchronous path).
     */
    public Handle tryAcquire(UUID quotationId, String who, long timeoutMillis) {
        if (quotationId == null) return NOOP;
        Entry e = locks.computeIfAbsent(quotationId, k -> new Entry());
        long t0 = System.nanoTime();
        boolean ok;
        try {
            ok = e.lock.tryLock(Math.max(0, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ok = false;
        }
        long waited = (System.nanoTime() - t0) / 1_000_000;
        if (!ok) {
            LOG.infof("[price-adjust-lock] quotation=%s %s 等待依据单锁超过 %d ms 未取得（当前持有者：%s），改走异步",
                quotationId, who, timeoutMillis, e.holder);
            return null;
        }
        if (waited > 0) {
            LOG.infof("[price-adjust-lock] quotation=%s %s 取得依据单锁，等待 %d ms", quotationId, who, waited);
        }
        e.holder = who;
        return handleFor(e);
    }

    private static Handle handleFor(Entry e) {
        return new Handle() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) return;
                closed = true;
                e.holder = null;
                e.lock.unlock();
            }
        };
    }

    /** Test / diagnostics: whether some thread currently holds the lock of {@code quotationId}. */
    public boolean isLocked(UUID quotationId) {
        Entry e = quotationId != null ? locks.get(quotationId) : null;
        return e != null && e.lock.isLocked();
    }
}
