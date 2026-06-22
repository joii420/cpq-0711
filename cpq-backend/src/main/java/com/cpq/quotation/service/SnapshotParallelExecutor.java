package com.cpq.quotation.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 报价单快照内部并行化专用有界线程池(导入首存提速)。
 *
 * <p><b>隔离纪律(RECORD 头号教训)</b>:这是与 RESTEasy worker 池 / ManagedExecutor 物理隔离的
 * 专用 daemon 池——无论它多忙都不会占满请求 worker → 503/502。仅在首存(需 expand 的新行)路径触发。
 *
 * <p>请求线程提交所有 per-line 任务后 {@link #runParallel} <b>同步阻塞</b>等待全部完成(allOf + 总超时),
 * 不引入轮询/异步状态机。总超时是"卡死兜底"(应远不触发),超时后已完成行已落库、未完成行由下次
 * saveDraft 的增量 skip 自动补齐(自愈)。
 */
@ApplicationScoped
public class SnapshotParallelExecutor {

    private static final Logger LOG = Logger.getLogger(SnapshotParallelExecutor.class);
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    @ConfigProperty(name = "cpq.snapshot.parallel.threads", defaultValue = "8")
    int threads;

    @ConfigProperty(name = "cpq.snapshot.parallel.timeout-seconds", defaultValue = "120")
    long timeoutSeconds;

    private ExecutorService pool;

    @PostConstruct
    void init() {
        int n = Math.max(1, threads);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "snapshot-par-" + THREAD_SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        pool = Executors.newFixedThreadPool(n, tf);
        LOG.infof("[snapshot-par] pool initialized threads=%d timeoutSeconds=%d", n, timeoutSeconds);
    }

    @PreDestroy
    void shutdown() {
        if (pool != null) pool.shutdownNow();
    }

    public int threads() { return threads; }

    /**
     * 把 {@code ids} 的每个元素作为一个任务并行跑 {@code task},<b>同步等待全部完成</b>(总超时兜底)。
     * 任务自身必须不抛(内部 try/catch 降级);超时只记 warn,不抛出影响主流程。
     *
     * @return 实际提交的任务数(= ids 大小;空集返回 0,不触碰池)
     */
    public int runParallel(Collection<java.util.UUID> ids, Consumer<java.util.UUID> task) {
        if (ids == null || ids.isEmpty()) return 0;
        List<CompletableFuture<Void>> futures = ids.stream()
                .map(idv -> CompletableFuture.runAsync(() -> task.accept(idv), pool))
                .collect(Collectors.toList());
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 超时/中断:已完成行已落库,未完成行下次 saveDraft 增量补齐(自愈)。不抛。
            LOG.warnf("[snapshot-par] batch incomplete (size=%d): %s", ids.size(), e.getMessage());
        }
        return ids.size();
    }
}
