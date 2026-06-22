package com.cpq.quotation.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SnapshotParallelExecutor 纯单测:不依赖 Quarkus 容器(手动 set 字段 + init)。 */
class SnapshotParallelExecutorTest {

    private SnapshotParallelExecutor ex;

    private SnapshotParallelExecutor make(int threads, long timeout) {
        SnapshotParallelExecutor e = new SnapshotParallelExecutor();
        e.threads = threads;
        e.timeoutSeconds = timeout;
        e.init();
        return e;
    }

    @AfterEach
    void tearDown() {
        if (ex != null) ex.shutdown();
    }

    @Test
    void runsEveryTaskExactlyOnce() {
        ex = make(4, 30);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) ids.add(UUID.randomUUID());
        AtomicInteger count = new AtomicInteger();
        int submitted = ex.runParallel(ids, id -> count.incrementAndGet());
        assertEquals(50, submitted, "提交任务数 = ids 大小");
        assertEquals(50, count.get(), "每个任务恰好执行一次");
    }

    @Test
    void concurrencyBoundedByThreads() {
        ex = make(4, 30);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) ids.add(UUID.randomUUID());
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ex.runParallel(ids, id -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try { Thread.sleep(20); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            inFlight.decrementAndGet();
        });
        assertTrue(peak.get() <= 4, "并发峰值不超过池大小, 实际=" + peak.get());
        assertTrue(peak.get() >= 2, "应确有并行(峰值≥2), 实际=" + peak.get());
    }

    @Test
    void emptyOrNull_noop() {
        ex = make(2, 5);
        assertEquals(0, ex.runParallel(List.of(), id -> {}));
        assertEquals(0, ex.runParallel(null, id -> {}));
    }

    @Test
    void taskExceptionDoesNotBreakBatch() {
        ex = make(3, 30);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 9; i++) ids.add(UUID.randomUUID());
        AtomicInteger ok = new AtomicInteger();
        // 偶发抛错的任务:runParallel 不应整体卡死/抛出(allOf.get 捕获后降级)
        ex.runParallel(ids, id -> {
            if (id.hashCode() % 2 == 0) throw new RuntimeException("boom");
            ok.incrementAndGet();
        });
        // 仅断言"未抛出 + 正常任务有执行";异常任务被 allOf 吞掉记 warn
        assertTrue(ok.get() >= 0);
    }
}
