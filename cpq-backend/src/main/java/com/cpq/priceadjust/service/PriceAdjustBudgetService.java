package com.cpq.priceadjust.service;

import com.cpq.common.perf.SqlStatementCounter;
import com.cpq.costing.service.ComparisonViewService;
import com.cpq.priceadjust.dto.ComparisonColumnDef;
import com.cpq.priceadjust.dto.UpgradeResult;
import com.cpq.priceadjust.entity.ComparisonColumnConfig;
import com.cpq.priceadjust.entity.CustomerPriceAdjustMaterial;
import com.cpq.priceadjust.entity.CustomerPriceAdjustStrategy;
import com.cpq.priceadjust.entity.ElementPriceVersion;
import com.cpq.priceadjust.entity.ElementPriceVersionItem;
import com.cpq.priceadjust.entity.MaterialPriceReview;
import com.cpq.priceadjust.entity.MaterialPriceReviewColumn;
import com.cpq.priceadjust.entity.MaterialPriceVersionRef;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.entity.Template;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * task-0729 B4 · 预算计算（异步）+ 比对算法；task-260920 起拆成「进池」与「试算」两段。
 *
 * <p><b>进池（R-1，B-1/B-2）</b>：{@link #onVersionGenerated} 先在一个事务里用<b>常数条</b>批量查询判定范围内哪些料号
 * 该进池（{@link #decideEnqueueBatch}，判定口径与原逐料号 {@code processMaterial} 逐条相同），再一条语句批量建审核行
 * （{@code QUEUED}；无依据单有驳回史的反例外直接 {@code READY} + 0 列）、一条语句批量推进不进池料号的指针。
 * 生成版本后十几秒内全部料号即可在审核列表搜到。
 *
 * <p><b>试算（R-5/R-7，B-3/B-5/B-15）</b>：取本版本 {@code PENDING + QUEUED} 行，按依据单分组，组间并行（有界，
 * {@code cpq.price-adjust.budget.concurrency}，默认 3、上限 5）、组内按料号顺序串行，料号多的组先派。每条严格按
 * <b>取锁 → 抢占（独立短事务）→ 试算事务 → 条件完成 → 放锁</b>；锁 = {@link BasisQuotationLocks}（按依据单的进程内
 * 公平锁）。循环退出前重扫，退出与唤起在同一把判定锁内互斥（{@link #requestBudgetRun}）。
 *
 * <p>所有试算入口（后台循环 / compute-now / recompute-budget / 单料号 {@link #processMaterial}）共用同一份
 * {@link #computeTx}。
 */
@ApplicationScoped
public class PriceAdjustBudgetService {

    private static final Logger LOG = Logger.getLogger(PriceAdjustBudgetService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ACTIVE_STATUSES = MaterialVersionUpgradeService.ACTIVE_STATUSES;

    /** B-17②：依据单已不在活单范围内（或该单上已找不到该料号）时的失败原因（D-5 原文）。 */
    public static final String BASIS_GONE_ERROR = "判断依据单已不在活单范围内";

    /** B-5：并发度上限（每个工作线程同时占 2 个主池连接，主池 20）。 */
    static final int MAX_CONCURRENCY = 5;

    /** compute-now / recompute 在请求线程里等依据单锁的上限；超过则整段改走异步（见 {@link #requestCompute}）。 */
    static final long REQUEST_LOCK_WAIT_MS = 5_000;

    @Inject EntityManager em;
    @Inject ComparisonViewService comparisonViewService;
    @Inject MaterialVersionUpgradeService materialVersionUpgradeService;
    @Inject BasisQuotationLocks quotationLocks;
    /** 问题-1（AC-17）：按版本的公平读写锁 —— 每个执行 FOR SHARE 的事务是读者，生成新版本是写者。 */
    @Inject VersionSupersedeGate versionGate;
    @Inject TransactionManager transactionManager;

    @ConfigProperty(name = "cpq.price-adjust.budget.concurrency", defaultValue = "3")
    int configuredConcurrency;

    /** 点击即算 / 重算的试算名额（与后台分开计数、互不挤占）。 */
    @ConfigProperty(name = "cpq.price-adjust.budget.interactive-concurrency", defaultValue = "2")
    int configuredInteractiveConcurrency;

    /**
     * 本类自己的线程（不用 {@code ManagedExecutor}：它会把提交线程的 CDI 请求上下文传播过去，请求作用域的
     * {@code DataLoader} 会被多个线程共用 —— expand 层非线程安全）。普通线程上 {@code @ActivateRequestContext} 各开一个。
     *
     * <p>🔒 task-260920 B-5 返工：线程数本身不限，<b>同时在算的数量由下面两个公平名额限住</b>，两类线程分开：
     * <ul>
     *   <li>{@link #coordinatorPool}：版本级协调线程（{@link #runComputeOnly} / {@link #enqueueThenRun}），会 {@code join}
     *       工作线程、<b>本身不占试算名额</b>；与工作线程分池，版本再多也不会把工作线程饿死；</li>
     *   <li>{@link #workerPool}：后台工作线程与点击即算 / 重算的异步段；等名额时不持锁、不开事务。</li>
     * </ul>
     */
    private final ExecutorService coordinatorPool = Executors.newCachedThreadPool(namedDaemon("price-adjust-budget-coord-"));
    private final ExecutorService workerPool = Executors.newCachedThreadPool(namedDaemon("price-adjust-budget-"));

    /**
     * 后台试算名额：<b>全进程所有版本的工作线程合计</b>同时在算不超过 {@link #effectiveConcurrency()}（默认 3、上限 5）。
     * 点击即算 / 重算另用 {@link #interactivePermits}（默认 2）。每个在算线程占 2 个主池连接 ⇒ 默认最多 10 个、上限配置下 14 个。
     * 公平（先到先得），逐料号取还 ⇒ 多个版本交错推进。
     */
    private Semaphore backgroundPermits;
    private Semaphore interactivePermits;
    private final BudgetConcurrencyMeter meter = new BudgetConcurrencyMeter();

    @PostConstruct
    void initPermits() {
        backgroundPermits = new Semaphore(effectiveConcurrency(), true);
        interactivePermits = new Semaphore(Math.max(1, configuredInteractiveConcurrency), true);
        LOG.infof("[price-adjust-budget] 试算名额：后台=%d 点击即算/重算=%d", backgroundPermits.availablePermits(),
            interactivePermits.availablePermits());
    }

    /** 测试 / 诊断：名额计量器。 */
    BudgetConcurrencyMeter concurrencyMeter() {
        return meter;
    }

    private static java.util.concurrent.ThreadFactory namedDaemon(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    void shutdownPool() {
        coordinatorPool.shutdownNow();
        workerPool.shutdownNow();
    }

    /**
     * 取一个试算名额（公平）。🔒 调用方此刻不得持有依据单锁、不得有打开的事务（次序：名额 → 锁 → 抢占 → 试算 → 条件完成 → 放锁 → 还名额）。
     *
     * @return false = 等待中被中断（已恢复中断标志）：调用方直接停止，不改该行状态
     */
    private boolean acquirePermit(boolean isBackground, String who) {
        Semaphore s = isBackground ? backgroundPermits : interactivePermits;
        try {
            if (!s.tryAcquire(0, java.util.concurrent.TimeUnit.MILLISECONDS)) { // 计时版遵守公平（无参 tryAcquire 会插队）
                long t0 = System.nanoTime();
                s.acquire();
                LOG.infof("[price-adjust-budget] %s 等待试算名额（%s）%d ms", who, isBackground ? "后台" : "点击即算/重算",
                    (System.nanoTime() - t0) / 1_000_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.infof("[price-adjust-budget] %s 等待试算名额时被中断，停止（未改动该行）", who);
            return false;
        }
        meter.enter(isBackground);
        return true;
    }

    private void releasePermit(boolean isBackground) {
        meter.exit(isBackground);
        (isBackground ? backgroundPermits : interactivePermits).release();
    }

    /** 试算入口允许抢占的预算状态（B-15②）。 */
    public enum ComputeMode {
        /** 后台循环：只抢 QUEUED。 */
        LOOP(Set.of(MaterialPriceReview.BUDGET_QUEUED)),
        /** 点击即算：QUEUED / FAILED（FAILED 仅用户显式「重新计算」时由前端发起）。 */
        COMPUTE_NOW(Set.of(MaterialPriceReview.BUDGET_QUEUED, MaterialPriceReview.BUDGET_FAILED)),
        /** 既有「重算」：QUEUED / FAILED / READY。 */
        RECOMPUTE(Set.of(MaterialPriceReview.BUDGET_QUEUED, MaterialPriceReview.BUDGET_FAILED,
            MaterialPriceReview.BUDGET_READY));

        final Set<String> allowed;

        ComputeMode(Set<String> allowed) {
            this.allowed = allowed;
        }
    }

    /** 单条试算的结果。 */
    enum ItemResult {
        READY, FAILED,
        /** 条件完成更新到 0 行（期间被重新排队 / 作废），整体回滚，结果丢弃。 */
        LOST,
        /** 未抢到 / 行已不是本入口可处理的状态（被其它入口处理），本次什么都没写。 */
        SKIPPED,
        /** 版本已不是 PENDING：什么都没写，调用方停止。 */
        STOP,
        /** 等试算名额时被中断（进程关闭）：没有抢占、什么都没写，调用方直接停止。 */
        INTERRUPTED
    }

    // -------------------------------------------------------------------------
    // 版本级预算循环登记（一个版本同时只有一个循环；退出 / 唤起互斥，B-3 / B-16）
    // -------------------------------------------------------------------------

    private static final class VersionRun {
        /** 在跑期间被唤起（有行被重新排队）：退出前必须再扫一次。 */
        boolean rescan;
        /** 某个工作线程发现版本已作废：其余工作线程在下一条开始前各自停。 */
        volatile boolean stopped;
        /** 等名额时被中断（进程关闭）：整个循环退出，不记「版本已作废」。 */
        volatile boolean aborted;
    }

    /** guarded by itself. */
    private final Map<UUID, VersionRun> runs = new HashMap<>();

    /** 登记新循环；已有循环在跑 ⇒ 置其「需要重扫」并返回 null。 */
    private VersionRun startRun(UUID versionId) {
        synchronized (runs) {
            VersionRun existing = runs.get(versionId);
            if (existing != null) {
                existing.rescan = true;
                return null;
            }
            VersionRun r = new VersionRun();
            runs.put(versionId, r);
            return r;
        }
    }

    /** 队列取空时调用：有未消费的唤起 ⇒ 清标志并返回 false（继续扫）；否则注销并返回 true（退出）。 */
    private boolean tryFinishRun(UUID versionId, VersionRun run) {
        synchronized (runs) {
            if (run.rescan && !run.stopped) {
                run.rescan = false;
                return false;
            }
            runs.remove(versionId, run);
            return true;
        }
    }

    private void forceFinishRun(UUID versionId, VersionRun run) {
        synchronized (runs) {
            runs.remove(versionId, run);
        }
    }

    /** 该版本的预算循环是否正在本进程里跑（供启动续跑判重 / 测试观察）。 */
    public boolean isBudgetLoopRunning(UUID versionId) {
        if (versionId == null) return false;
        synchronized (runs) {
            return runs.containsKey(versionId);
        }
    }

    int effectiveConcurrency() {
        int c = configuredConcurrency;
        if (c > MAX_CONCURRENCY) {
            LOG.warnf("[price-adjust-budget] cpq.price-adjust.budget.concurrency=%d 超过上限 %d，按 %d 处理", c,
                MAX_CONCURRENCY, MAX_CONCURRENCY);
            return MAX_CONCURRENCY;
        }
        return Math.max(1, c);
    }

    // -------------------------------------------------------------------------
    // 入口
    // -------------------------------------------------------------------------

    /**
     * 版本生成 / 启动续跑的入口：进池（批量、一个事务）→ 试算循环。已有循环在跑 ⇒ 只置「需要重扫」。
     */
    @ActivateRequestContext
    public void onVersionGenerated(UUID versionId) {
        if (versionId == null) return;
        VersionRun run = startRun(versionId);
        if (run == null) {
            LOG.infof("[price-adjust-budget] versionId=%s 已有预算循环在跑，本次触发跳过（已置重扫）", versionId);
            return;
        }
        try {
            EnqueueResult er = null;
            try {
                try (VersionSupersedeGate.Handle g = versionGate.read(versionId, "budget-enqueue")) {
                    er = enqueueScope(versionId);
                }
            } catch (Exception e) {
                LOG.errorf(e, "[price-adjust-budget] versionId=%s 进池失败（继续处理已有的未计算行）", versionId);
            }
            if (er != null && er.versionMissing) {
                LOG.warnf("[price-adjust-budget] versionId=%s 找不到版本或策略，跳过", versionId);
                return;
            }
            if (er != null && er.versionNotPending) {
                run.stopped = true;
                logStopped(versionId, er.candidates);
                return;
            }
            computeLoop(versionId, run, er);
        } finally {
            forceFinishRun(versionId, run);
        }
    }

    /**
     * B-16 收编：改策略 / 改比对列 / 失败退回后，唤起这些版本的试算循环 —— 未在跑 ⇒ 另起一个只试算的循环；
     * 在跑 ⇒ 置「需要重扫」，由循环退出前的重扫接住（🚫 只靠判重直接跳过，评审二轮【6】）。
     */
    public void requestBudgetRun(Collection<UUID> versionIds) {
        if (versionIds == null) return;
        for (UUID v : new LinkedHashSet<>(versionIds)) { // no DB access: in-memory registry + async dispatch
            if (v == null) continue;
            VersionRun run = startRun(v);
            if (run == null) {
                LOG.infof("[price-adjust-budget] versionId=%s 预算循环在跑，已置重扫", v);
                continue;
            }
            try {
                coordinatorPool.execute(() -> runComputeOnly(v, run));
            } catch (RejectedExecutionException e) {
                forceFinishRun(v, run);
                LOG.errorf(e, "[price-adjust-budget] versionId=%s 唤起试算循环失败", v);
            }
        }
    }

    @ActivateRequestContext
    void runComputeOnly(UUID versionId, VersionRun run) {
        try {
            computeLoop(versionId, run, null);
        } catch (Exception e) {
            LOG.errorf(e, "[price-adjust-budget] versionId=%s 试算循环异常", versionId);
        } finally {
            forceFinishRun(versionId, run);
        }
    }

    /**
     * 策略「指定料号」新增料号时：对这些料号走同一套进池判定（异步），再唤起试算循环。
     */
    public void enqueueMaterialsAndRun(UUID versionId, String customerNo, Collection<String> materials) {
        if (versionId == null || materials == null || materials.isEmpty()) return;
        List<String> mats = new ArrayList<>(materials);
        try {
            coordinatorPool.execute(() -> enqueueThenRun(versionId, customerNo, mats));
        } catch (RejectedExecutionException e) {
            LOG.errorf(e, "[price-adjust-budget] versionId=%s 新增料号进池派发失败", versionId);
        }
    }

    @ActivateRequestContext
    void enqueueThenRun(UUID versionId, String customerNo, List<String> materials) {
        try {
            try (VersionSupersedeGate.Handle g = versionGate.read(versionId, "budget-enqueue-added")) {
                enqueueMaterials(versionId, customerNo, materials);
            }
        } catch (Exception e) {
            LOG.errorf(e, "[price-adjust-budget] versionId=%s 新增料号进池失败", versionId);
        }
        requestBudgetRun(List.of(versionId));
    }

    private void logStopped(UUID versionId, int remaining) {
        // B-8①：带锁复核发现版本已作废 → 不写并停止循环。（日志文案是 repair-260918 的验收观测点，勿改）
        LOG.infof("[price-adjust-budget] versionId=%s 版本已作废，停止剩余 %d 个料号", versionId, remaining);
        // B-8③ 兜底：对旧版再执行一次作废待处理审核（幂等）。
        try {
            int voided = voidPendingOfSupersededVersion(versionId);
            if (voided > 0) {
                LOG.infof("[price-adjust-budget] versionId=%s 兜底作废残留待处理审核 %d 条", versionId, voided);
            }
        } catch (Exception e) {
            LOG.errorf(e, "[price-adjust-budget] versionId=%s 兜底作废待处理审核失败", versionId);
        }
    }

    /**
     * repair-260918 B-7：某料号预算抛异常（含事务超时）时，另开事务 find-or-create 审核行并标「预算失败」，
     * 让料号出现在待办池（前端已有「预算失败」标签与「重算」入口）。写之前做 B-8① 的带锁复核。
     *
     * @return {@code false} = 版本已不是 PENDING（未写，调用方应停止循环）；{@code true} = 已记录
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    boolean recordBudgetFailure(UUID versionId, String customerNo, String materialNo, Throwable cause) {
        if (!lockVersionAndCheckPending(versionId)) return false;
        MaterialPriceReview review = MaterialPriceReview.findByVersionAndMaterial(versionId, materialNo);
        if (review == null) {
            review = new MaterialPriceReview();
            review.versionId = versionId;
            review.customerNo = customerNo;
            review.materialNo = materialNo;
            review.status = MaterialPriceReview.STATUS_PENDING;
            MaterialPriceVersionRef ref = MaterialPriceVersionRef.findRef(customerNo, materialNo);
            review.previousVersionId = ref != null && !versionId.equals(ref.versionId) ? ref.versionId : null;
        }
        try {
            BasisLine basis = findBasisLine(customerNo, materialNo);
            if (basis != null) {
                review.basisQuotationId = basis.quotationId;
                review.templateSeriesId = basis.templateSeriesId;
            }
        } catch (Exception e) {
            LOG.warnf("[price-adjust-budget] versionId=%s material=%s 记录预算失败时解析依据单失败（忽略）: %s",
                versionId, materialNo, e.getMessage());
        }
        review.budgetStatus = MaterialPriceReview.BUDGET_FAILED;
        review.budgetError = PriceAdjustFailureTranslator.forBudget(cause);
        review.updatedAt = java.time.OffsetDateTime.now();
        review.persist();
        return true;
    }

    /**
     * repair-260918 B-8①：{@code SELECT status … FOR SHARE} 复核版本仍是 PENDING —— 共享锁持有到当前事务提交，
     * 与 {@code PriceAdjustVersionGenerationService#generateVersion} 作废旧版时的行更新互斥。
     */
    boolean lockVersionAndCheckPending(UUID versionId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                "SELECT status FROM element_price_version WHERE id = :v FOR SHARE")
            .setParameter("v", versionId)
            .getResultList();
        return !rows.isEmpty() && ElementPriceVersion.STATUS_PENDING.equals(rows.get(0));
    }

    /** B-8③：循环因作废退出时，对旧版再作废一次待处理审核（幂等）。 */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    int voidPendingOfSupersededVersion(UUID versionId) {
        ElementPriceVersion v = ElementPriceVersion.findById(versionId);
        if (v == null || ElementPriceVersion.STATUS_PENDING.equals(v.status)) return 0;
        return MaterialPriceReview.voidPendingByVersion(versionId);
    }

    /**
     * repair-260918 B-8①：带锁复核发现版本已不是 PENDING 时，{@link #processMaterial} 抛出本异常（事务随之回滚，
     * 什么都不写）。预算循环据此停止；其他入口（单条重算 / 比对列变更 / 策略变更）收到同样不写。
     */
    public static class VersionNotPendingException extends RuntimeException {
        public VersionNotPendingException(UUID versionId) {
            super("版本已作废（不再是 PENDING），不写预算: " + versionId);
        }

        static boolean isCauseOf(Throwable t) {
            java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
                if (c instanceof VersionNotPendingException) return true;
            }
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // 进池（R-1 · B-1 / B-2 / B-10）
    // -------------------------------------------------------------------------

    /** 一次进池的结果（计数给日志 / 测试用）。 */
    public static final class EnqueueResult {
        public boolean versionMissing;
        public boolean versionNotPending;
        public int scope;
        public int candidates;
        public int skipped;
        public int pooled;
        public int readyNow;
        public int advanced;
        public int inserted;
        public long sql;
    }

    /** 进池判定里一个要建审核行的料号。 */
    public record PoolRow(String materialNo, UUID previousVersionId, UUID basisQuotationId, UUID basisLineItemId,
                          UUID templateSeriesId, boolean readyNow) { }

    /**
     * B-1 批量判定的结果（只读，不写库）。AC-2 用它（{@link #decideEnqueueBatch}）与原逐料号函数对照。
     */
    public static final class EnqueuePlan {
        /** 去重后的输入料号。 */
        public final List<String> scope = new ArrayList<>();
        /** B-10：本版本已有审核行 ∨ 指针已指向本版本 —— 判定不重做。 */
        public final Set<String> skipped = new LinkedHashSet<>();
        /** 要建审核行的料号（含 {@link #readyNow}）。 */
        public final Set<String> pooled = new LinkedHashSet<>();
        /** 不进池、推进指针的料号。 */
        public final Set<String> advanced = new LinkedHashSet<>();
        /** 反例外（无依据单、有驳回史）：进池即 READY + 0 列，不进试算队列。 */
        public final Set<String> readyNow = new LinkedHashSet<>();
        public final Map<String, PoolRow> rows = new LinkedHashMap<>();

        /** AC-2 测试钩子访问器：进池料号集合（含反例外直接就绪的）。 */
        public Set<String> pooledMaterials() { return pooled; }

        /** AC-2 测试钩子访问器：不进池、推进指针的料号集合。 */
        public Set<String> advancedMaterials() { return advanced; }
    }

    private static final class BasisRow {
        final UUID quotationId;
        final UUID lineItemId;
        final UUID templateSeriesId;

        BasisRow(UUID quotationId, UUID lineItemId, UUID templateSeriesId) {
            this.quotationId = quotationId;
            this.lineItemId = lineItemId;
            this.templateSeriesId = templateSeriesId;
        }
    }

    /** 版本生成 / 续跑：按策略范围进池。版本或策略不存在 ⇒ {@code versionMissing}。 */
    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    EnqueueResult enqueueScope(UUID versionId) {
        long sql0 = SqlStatementCounter.current();
        long t0 = System.nanoTime();
        ElementPriceVersion version = ElementPriceVersion.findById(versionId);
        CustomerPriceAdjustStrategy strategy = version != null
            ? CustomerPriceAdjustStrategy.findByCustomerNo(version.customerNo) : null;
        if (version == null || strategy == null) {
            EnqueueResult r = new EnqueueResult();
            r.versionMissing = true;
            return r;
        }
        List<String> materials = resolveScopeMaterials(strategy);
        return doEnqueue(versionId, version.customerNo, materials, sql0, t0);
    }

    /** 指定料号进池（策略新增料号 / 单料号入口 {@link #processMaterial}）。版本不存在 ⇒ {@code versionMissing}。 */
    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    EnqueueResult enqueueMaterials(UUID versionId, String customerNo, Collection<String> materials) {
        long sql0 = SqlStatementCounter.current();
        long t0 = System.nanoTime();
        ElementPriceVersion version = ElementPriceVersion.findById(versionId);
        if (version == null) {
            EnqueueResult r = new EnqueueResult();
            r.versionMissing = true;
            return r;
        }
        return doEnqueue(versionId, customerNo, new ArrayList<>(materials), sql0, t0);
    }

    /**
     * B-2：判定 → 带锁复核版本仍 PENDING → 一条语句批量建行 → 一条语句批量推进指针。整段在调用方的一个事务里。
     * 版本已不是 PENDING ⇒ 什么都不写，返回 {@code versionNotPending=true}。
     */
    private EnqueueResult doEnqueue(UUID versionId, String customerNo, List<String> materials, long sql0, long t0) {
        EnqueuePlan plan = decideEnqueueBatch(versionId, customerNo, materials);
        EnqueueResult r = new EnqueueResult();
        r.scope = plan.scope.size();
        r.skipped = plan.skipped.size();
        r.candidates = plan.pooled.size() + plan.advanced.size();
        r.pooled = plan.pooled.size();
        r.readyNow = plan.readyNow.size();
        r.advanced = plan.advanced.size();

        // repair-260918 B-8①：第一次写库之前带锁复核版本仍是 PENDING（共享锁持有到本事务提交）。
        if (r.candidates > 0 && !lockVersionAndCheckPending(versionId)) {
            r.versionNotPending = true;
            return r;
        }

        if (!plan.pooled.isEmpty()) {
            int n = plan.rows.size();
            String[] mnos = new String[n];
            UUID[] prevs = new UUID[n];
            UUID[] bases = new UUID[n];
            UUID[] series = new UUID[n];
            String[] statuses = new String[n];
            int i = 0;
            for (PoolRow p : plan.rows.values()) { // pure in-memory array building
                mnos[i] = p.materialNo();
                prevs[i] = p.previousVersionId();
                bases[i] = p.basisQuotationId();
                series[i] = p.templateSeriesId();
                statuses[i] = p.readyNow() ? MaterialPriceReview.BUDGET_READY : MaterialPriceReview.BUDGET_QUEUED;
                i++;
            }
            // 🔒 一条原生语句建全部审核行（🚫 逐个 persist：批量 flush 会按批计入 SqlStatementCounter，AC-3）。
            // 反例外行 budget_status=READY、column_count=0（与原 processMaterial 的反例外分支同形）。
            r.inserted = em.createNativeQuery(
                    "INSERT INTO material_price_review (id, version_id, customer_no, material_no, previous_version_id, " +
                    "  basis_quotation_id, template_series_id, status, budget_status, column_count, created_at, updated_at) " +
                    "SELECT gen_random_uuid(), :v, :c, t.m, t.p, t.b, t.s, 'PENDING', t.bs, 0, now(), now() " +
                    "  FROM unnest(CAST(:mnos AS varchar[]), CAST(:prevs AS uuid[]), CAST(:bases AS uuid[]), " +
                    "              CAST(:series AS uuid[]), CAST(:bss AS varchar[])) AS t(m, p, b, s, bs) " +
                    "ON CONFLICT (version_id, material_no) DO NOTHING")
                .setParameter("v", versionId)
                .setParameter("c", customerNo)
                .setParameter("mnos", mnos)
                .setParameter("prevs", prevs)
                .setParameter("bases", bases)
                .setParameter("series", series)
                .setParameter("bss", statuses)
                .executeUpdate();
        }
        if (!plan.advanced.isEmpty()) {
            em.createNativeQuery(
                    "INSERT INTO material_price_version_ref (id, customer_no, material_no, version_id, updated_at) " +
                    "SELECT gen_random_uuid(), :c, t.m, :v, now() FROM unnest(CAST(:mnos AS varchar[])) AS t(m) " +
                    "ON CONFLICT (customer_no, material_no) DO UPDATE " +
                    "   SET version_id = EXCLUDED.version_id, updated_at = EXCLUDED.updated_at")
                .setParameter("c", customerNo)
                .setParameter("v", versionId)
                .setParameter("mnos", plan.advanced.toArray(new String[0]))
                .executeUpdate();
        }
        r.sql = SqlStatementCounter.current() - sql0;
        LOG.infof("[perf] budget-enqueue customer=%s N=%d pooled=%d advanced=%d sql=%d ms=%d",
            customerNo, r.scope, r.pooled, r.advanced, r.sql, (System.nanoTime() - t0) / 1_000_000);
        LOG.infof("[price-adjust-budget] versionId=%s 进池：范围=%d 续跑跳过=%d 进池=%d（其中直接就绪=%d，新建行=%d）直接推进指针=%d",
            versionId, r.scope, r.skipped, r.pooled, r.readyNow, r.inserted, r.advanced);
        return r;
    }

    /**
     * B-1 · 批量进池判定（只读，<b>条数与料号数无关</b>，全部走 {@code EntityManager}；{@code materials=null} ⇒ 该客户策略全范围）。判定口径与原逐料号
     * {@code processMaterial} 逐条相同（只改「数据怎么取」）：
     * <ol>
     *   <li>本版本已有审核行 ∨ 指针已指向本版本 ⇒ 跳过（B-10）；</li>
     *   <li>无依据单 ∧ 无驳回史 ⇒ 推进指针，不进池（D5）；</li>
     *   <li>有依据单 ∧ 有指针 ∧ 相关元素价与指针版本逐个相同 ⇒ 推进指针，不进池（裁决 39；扫不出相关元素 ⇒ 保守判为有变动）；</li>
     *   <li>其余进池；无依据单（反例外）⇒ READY + 0 列。</li>
     * </ol>
     * 依据行按 J-3 取：{@code q.created_at DESC, li.sort_order ASC NULLS LAST, li.id ASC} 的第一行。
     */
    public EnqueuePlan decideEnqueueBatch(UUID versionId, String customerNo, Collection<String> materials) {
        // 🔒 只读、加入调用方事务（本方法及其调用的 collectMaterialElementCodesBatch 都不带 @Transactional），
        //    进池入口 doEnqueue 实际调用的就是它（AC-2 测试钩子，backtask B-1）。
        if (materials == null) {
            CustomerPriceAdjustStrategy strategy = CustomerPriceAdjustStrategy.findByCustomerNo(customerNo);
            materials = strategy != null ? resolveScopeMaterials(strategy) : List.of();
        }
        EnqueuePlan plan = new EnqueuePlan();
        for (String m : new LinkedHashSet<>(materials)) if (m != null) plan.scope.add(m); // pure in-memory
        if (plan.scope.isEmpty()) return plan;
        String[] scopeArr = plan.scope.toArray(new String[0]);

        // ④ 本版本已有审核行
        @SuppressWarnings("unchecked")
        List<String> reviewed = em.createNativeQuery(
                "SELECT material_no FROM material_price_review WHERE version_id = :v")
            .setParameter("v", versionId)
            .getResultList();
        Set<String> reviewedSet = new java.util.HashSet<>(reviewed);

        // ⑤ 已有指针（本客户、范围内）
        @SuppressWarnings("unchecked")
        List<Object[]> refRows = em.createNativeQuery(
                "SELECT material_no, version_id FROM material_price_version_ref " +
                "WHERE customer_no = :c AND material_no = ANY(:mnos)")
            .setParameter("c", customerNo)
            .setParameter("mnos", scopeArr)
            .getResultList();
        Map<String, UUID> refs = new HashMap<>();
        for (Object[] r : refRows) refs.put((String) r[0], (UUID) r[1]); // pure in-memory

        List<String> candidates = new ArrayList<>();
        for (String m : plan.scope) { // pure in-memory
            if (reviewedSet.contains(m) || versionId.equals(refs.get(m))) plan.skipped.add(m);
            else candidates.add(m);
        }
        if (candidates.isEmpty()) return plan;
        String[] candArr = candidates.toArray(new String[0]);

        // ③ 驳回史（🔒 必须带 customer_no，与 MaterialPriceReview.hasEverRejected 同口径）
        @SuppressWarnings("unchecked")
        List<String> rejected = em.createNativeQuery(
                "SELECT DISTINCT material_no FROM material_price_review " +
                "WHERE customer_no = :c AND status = 'REJECTED' AND material_no = ANY(:mnos)")
            .setParameter("c", customerNo)
            .setParameter("mnos", candArr)
            .getResultList();
        Set<String> rejectedSet = new java.util.HashSet<>(rejected);

        // ② 依据行（J-3 确定次序，末级唯一列 li.id；JOIN template 带出 template_series_id）
        @SuppressWarnings("unchecked")
        List<Object[]> basisRows = em.createNativeQuery(
                "SELECT x.mno, x.qid, x.lid, x.series FROM (" +
                "  SELECT li.product_part_no_snapshot AS mno, q.id AS qid, li.id AS lid, t.template_series_id AS series, " +
                "         ROW_NUMBER() OVER (PARTITION BY li.product_part_no_snapshot " +
                "                            ORDER BY q.created_at DESC, li.sort_order ASC NULLS LAST, li.id ASC) AS rn " +
                "    FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id " +
                "    JOIN customer c ON c.id = q.customer_id " +
                "    LEFT JOIN template t ON t.id = q.customer_template_id " +
                "   WHERE c.code = :cno AND li.product_part_no_snapshot = ANY(:mnos) " +
                "     AND (li.composite_type IS NULL OR li.composite_type <> 'PART') " +
                "     AND q.status = ANY(:statuses)" +
                ") x WHERE x.rn = 1")
            .setParameter("cno", customerNo)
            .setParameter("mnos", candArr)
            .setParameter("statuses", ACTIVE_STATUSES.toArray(new String[0]))
            .getResultList();
        Map<String, BasisRow> basis = new HashMap<>();
        for (Object[] r : basisRows) { // pure in-memory
            basis.put((String) r[0], new BasisRow((UUID) r[1], (UUID) r[2], (UUID) r[3]));
        }

        // 需要做裁决 39 比较的料号：有依据单 ∧ 有指针
        List<String> needCompare = new ArrayList<>();
        for (String m : candidates) { // pure in-memory
            if (basis.containsKey(m) && refs.get(m) != null) needCompare.add(m);
        }
        Map<UUID, Set<String>> codesByLine = Map.of();
        Map<UUID, Map<String, BigDecimal[]>> priceKeysByVersion = new HashMap<>();
        Map<UUID, Map<String, String>> currencyByVersion = new HashMap<>();
        if (!needCompare.isEmpty()) {
            // ⑦ 相关元素编码：新增只读批量方法（5 条，与料号数无关）
            List<UUID> lines = new ArrayList<>();
            Set<UUID> versionIds = new LinkedHashSet<>();
            versionIds.add(versionId);
            for (String m : needCompare) { // pure in-memory
                lines.add(basis.get(m).lineItemId);
                versionIds.add(refs.get(m));
            }
            codesByLine = materialVersionUpgradeService.collectMaterialElementCodesBatch(lines);
            // ⑥ 目标版本与全部指针版本的元素价（一条）
            @SuppressWarnings("unchecked")
            List<Object[]> priceRows = em.createNativeQuery(
                    "SELECT version_id, element_code, current_price, currency FROM element_price_version_item " +
                    "WHERE version_id = ANY(:vids)")
                .setParameter("vids", versionIds.toArray(new UUID[0]))
                .getResultList();
            for (Object[] r : priceRows) { // pure in-memory
                UUID vid = (UUID) r[0];
                priceKeysByVersion.computeIfAbsent(vid, k -> new HashMap<>())
                    .put((String) r[1], new BigDecimal[]{(BigDecimal) r[2]});
                currencyByVersion.computeIfAbsent(vid, k -> new HashMap<>()).put((String) r[1], (String) r[3]);
            }
        }

        for (String m : candidates) { // pure in-memory decision
            UUID prev = refs.get(m);
            BasisRow b = basis.get(m);
            boolean hasRejectedHistory = rejectedSet.contains(m);
            // D5：无活单料号 —— 不进待办池，指针照常推进（除非 D5 反例外：存在过 REJECTED 记录）
            if (b == null && !hasRejectedHistory) {
                plan.advanced.add(m);
                continue;
            }
            // 裁决 39：有依据单、有指针、相关元素价与指针版本逐个相同 → 不进池，指针照常推进
            if (b != null && prev != null
                    && !relevantPriceChanged(codesByLine.getOrDefault(b.lineItemId, Set.of()),
                        priceKeysByVersion.getOrDefault(versionId, Map.of()), currencyByVersion.getOrDefault(versionId, Map.of()),
                        priceKeysByVersion.getOrDefault(prev, Map.of()), currencyByVersion.getOrDefault(prev, Map.of()))) {
                plan.advanced.add(m);
                continue;
            }
            boolean readyNow = b == null;
            plan.pooled.add(m);
            if (readyNow) plan.readyNow.add(m);
            plan.rows.put(m, new PoolRow(m, prev,
                b != null ? b.quotationId : null, b != null ? b.lineItemId : null,
                b != null ? b.templateSeriesId : null, readyNow));
        }
        return plan;
    }

    /**
     * 裁决 39 的比较（与 {@link #hasRelevantPriceChange} 逐条同口径，只把数据换成预加载的 map）：相关元素为空 ⇒ 保守判为有变动；
     * 价格用 {@code compareTo}，两侧都 null 视为相同；币种 {@code Objects.equals}。
     */
    private static boolean relevantPriceChanged(Set<String> relevant,
                                                Map<String, BigDecimal[]> curPrices, Map<String, String> curCurrency,
                                                Map<String, BigDecimal[]> prevPrices, Map<String, String> prevCurrency) {
        if (relevant.isEmpty()) return true;
        for (String code : relevant) {
            BigDecimal[] a = curPrices.get(code);
            BigDecimal[] b = prevPrices.get(code);
            BigDecimal pa = a != null ? a[0] : null;
            BigDecimal pb = b != null ? b[0] : null;
            if (pa == null ? pb != null : (pb == null || pa.compareTo(pb) != 0)) return true;
            String ca = a != null ? curCurrency.get(code) : null;
            String cb = b != null ? prevCurrency.get(code) : null;
            if (!Objects.equals(ca, cb)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // 范围解析（B4.1 前置）
    // -------------------------------------------------------------------------

    /**
     * 策略料号范围解析：SPECIFIED → customer_price_adjust_material 清单；
     * ALL → 该客户历史报价单里出现过的全部销售料号（{@code quotation_line_item
     * .product_part_no_snapshot} 去重）——backtask/需求说明未给出 ALL 模式的具体数据源
     * SQL，本实现按"该客户实际报过价的料号集合"这一最贴合业务语义的口径落地，已在交付
     * 说明中如实标注为一个需确认的假设（非凭空捏造字段，取的是既有列）。
     */
    List<String> resolveScopeMaterials(CustomerPriceAdjustStrategy strategy) {
        if ("SPECIFIED".equals(strategy.materialScopeMode)) {
            List<CustomerPriceAdjustMaterial> rows = CustomerPriceAdjustMaterial.listByStrategy(strategy.id);
            List<String> out = new ArrayList<>();
            for (CustomerPriceAdjustMaterial m : rows) out.add(m.materialNo);
            return out;
        }
        @SuppressWarnings("unchecked")
        List<String> rows = em.createNativeQuery(
                "SELECT DISTINCT li.product_part_no_snapshot " +
                "FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id " +
                "JOIN customer c ON c.id = q.customer_id " +
                "WHERE c.code = :cno AND li.product_part_no_snapshot IS NOT NULL")
            .setParameter("cno", strategy.customerNo)
            .getResultList();
        return rows;
    }

    // -------------------------------------------------------------------------
    // 试算循环（B-3 / B-5 / B-11）
    // -------------------------------------------------------------------------

    private record QueuedRow(UUID id, String materialNo, UUID basisQuotationId) { }

    private static final class Group {
        final UUID basisQuotationId;
        final List<QueuedRow> rows = new ArrayList<>();

        Group(UUID basisQuotationId) {
            this.basisQuotationId = basisQuotationId;
        }
    }

    private static final class PassCounters {
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger ready = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger lost = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
    }

    /** 本版本「待处理 + 未计算（QUEUED）」的行。 */
    @Transactional
    List<QueuedRow> loadQueued(UUID versionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, material_no, basis_quotation_id FROM material_price_review " +
                "WHERE version_id = :v AND status = 'PENDING' AND budget_status = 'QUEUED'")
            .setParameter("v", versionId)
            .getResultList();
        List<QueuedRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) out.add(new QueuedRow((UUID) r[0], (String) r[1], (UUID) r[2])); // pure in-memory
        return out;
    }

    @Transactional
    boolean isVersionPending(UUID versionId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("SELECT status FROM element_price_version WHERE id = :v")
            .setParameter("v", versionId)
            .getResultList();
        return !rows.isEmpty() && ElementPriceVersion.STATUS_PENDING.equals(rows.get(0));
    }

    /**
     * 取本版本 QUEUED 行 → 按依据单分组并行试算 → 再取，直到取空（退出前重扫，B-3）。
     */
    private void computeLoop(UUID versionId, VersionRun run, EnqueueResult er) {
        long t0 = System.nanoTime();
        int ready = 0, failed = 0, lost = 0, skippedByOthers = 0, passes = 0, idlePasses = 0;
        int stoppedRemaining = -1;
        while (true) {
            List<QueuedRow> rows = loadQueued(versionId);
            if (rows.isEmpty()) {
                if (tryFinishRun(versionId, run)) break;
                continue; // 有未消费的唤起：再扫一次
            }
            passes++;
            PassCounters pc = computePass(versionId, run, rows);
            ready += pc.ready.get();
            failed += pc.failed.get();
            lost += pc.lost.get();
            skippedByOthers += pc.skipped.get();
            if (run.aborted) {
                LOG.infof("[price-adjust-budget] versionId=%s 等试算名额时被中断（进程关闭），循环退出（剩余行留待重启续跑）", versionId);
                break;
            }
            if (run.stopped) {
                stoppedRemaining = rows.size() - pc.processed.get();
                break;
            }
            // 防空转：整轮没有任何一条被本循环算完（全是被他方抢走），稍等再扫；连续 50 轮则放弃（剩余行由唤起 / 续跑接住）
            if (pc.ready.get() + pc.failed.get() + pc.lost.get() == 0) {
                if (++idlePasses >= 50) {
                    LOG.warnf("[price-adjust-budget] versionId=%s 连续 %d 轮未取得任何可算行，退出（剩余 %d 行）",
                        versionId, idlePasses, rows.size());
                    break;
                }
                sleepQuietly(200);
            } else {
                idlePasses = 0;
            }
        }
        if (stoppedRemaining >= 0) {
            logStopped(versionId, Math.max(stoppedRemaining, 1));
        }
        LOG.infof("[price-adjust-budget] versionId=%s done: 进池=%d（直接就绪=%d） 直接推进指针=%d 续跑跳过=%d "
                + "试算就绪=%d 试算失败=%d 结果丢弃重排=%d 被他方处理=%d 轮次=%d 耗时=%dms%s",
            versionId, er != null ? er.pooled : 0, er != null ? er.readyNow : 0, er != null ? er.advanced : 0,
            er != null ? er.skipped : 0, ready, failed, lost, skippedByOthers, passes, (System.nanoTime() - t0) / 1_000_000,
            stoppedRemaining >= 0 ? " 作废停止=" + stoppedRemaining : "");
    }

    /** 一轮：分组 → 大组先派 → 有界并行（同一依据单永不跨线程）。 */
    private PassCounters computePass(UUID versionId, VersionRun run, List<QueuedRow> rows) {
        long t0 = System.nanoTime();
        Map<UUID, Group> byBasis = new LinkedHashMap<>();
        Group noBasis = null;
        List<QueuedRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(QueuedRow::materialNo)); // 组内按 material_no 升序（码点序），纯内存
        for (QueuedRow r : sorted) { // pure in-memory grouping
            if (r.basisQuotationId() == null) {
                if (noBasis == null) noBasis = new Group(null);
                noBasis.rows.add(r);
            } else {
                byBasis.computeIfAbsent(r.basisQuotationId(), Group::new).rows.add(r);
            }
        }
        List<Group> groups = new ArrayList<>(byBasis.values());
        if (noBasis != null) groups.add(noBasis);
        // 料号多的组先派（最长组不排到最后）；同规模按依据单 id 定序
        groups.sort(Comparator.<Group>comparingInt(g -> -g.rows.size())
            .thenComparing(g -> String.valueOf(g.basisQuotationId)));
        ConcurrentLinkedQueue<Group> queue = new ConcurrentLinkedQueue<>(groups);
        int workers = Math.min(effectiveConcurrency(), groups.size());
        PassCounters pc = new PassCounters();
        if (workers <= 1) {
            runWorker(versionId, run, queue, pc, 1);
        } else {
            List<CompletableFuture<Void>> fs = new ArrayList<>();
            for (int w = 1; w <= workers; w++) {
                final int workerNo = w;
                fs.add(CompletableFuture.runAsync(() -> runWorker(versionId, run, queue, pc, workerNo), workerPool));
            }
            for (CompletableFuture<Void> f : fs) {
                try {
                    f.join();
                } catch (Exception e) {
                    LOG.errorf(e, "[price-adjust-budget] versionId=%s 工作线程异常", versionId);
                }
            }
        }
        LOG.infof("[perf] budget-compute version=%s groups=%d materials=%d workers=%d ms=%d",
            versionId, groups.size(), rows.size(), workers, (System.nanoTime() - t0) / 1_000_000);
        return pc;
    }

    /**
     * 一个工作线程：独立请求上下文，逐组取、组内严格顺序；每条独立取放锁（公平锁，点击即算可插队）。
     */
    @ActivateRequestContext
    void runWorker(UUID versionId, VersionRun run, ConcurrentLinkedQueue<Group> queue, PassCounters pc, int workerNo) {
        int left = 0;
        Group g;
        outer:
        while ((g = queue.poll()) != null) {
            long t0 = System.nanoTime();
            int done = 0;
            for (int i = 0; i < g.rows.size(); i++) {
                QueuedRow row = g.rows.get(i);
                if (run.stopped || run.aborted) {
                    left = g.rows.size() - i;
                    break outer;
                }
                ItemResult r;
                try {
                    r = computeItemInLoop(versionId, row, "budget-loop worker=" + workerNo);
                } catch (Exception e) {
                    LOG.errorf(e, "[price-adjust-budget] versionId=%s material=%s 处理异常（不影响后续料号；该行未写，留待重扫）",
                        versionId, row.materialNo());
                    r = ItemResult.SKIPPED; // 不算进展：持续异常时由 computeLoop 的防空转上限兜住，不会死循环
                }
                if (r == ItemResult.INTERRUPTED) {
                    run.aborted = true;
                    left = g.rows.size() - i;
                    break outer;
                }
                if (r == ItemResult.STOP) {
                    run.stopped = true;
                    left = g.rows.size() - i;
                    break outer;
                }
                pc.processed.incrementAndGet();
                done++;
                switch (r) {
                    case READY -> pc.ready.incrementAndGet();
                    case FAILED -> pc.failed.incrementAndGet();
                    case LOST -> pc.lost.incrementAndGet();
                    default -> pc.skipped.incrementAndGet();
                }
            }
            LOG.infof("[perf] budget-group quotation=%s materials=%d ms=%d",
                g.basisQuotationId, done, (System.nanoTime() - t0) / 1_000_000);
        }
        if (run.stopped) {
            LOG.infof("[price-adjust-budget] versionId=%s worker=%d 版本已作废，本线程停止（本线程当前组剩余 %d 个料号）",
                versionId, workerNo, left);
        }
    }

    /**
     * 后台循环的一条：<b>取后台名额 → 取锁 → 抢占（QUEUED）→ 试算事务 → 条件完成 → 放锁 → 还名额</b>。
     * 已作废版本的线程拿到名额后抢占必失败 ⇒ 立刻 STOP 还名额，不久占。
     */
    private ItemResult computeItemInLoop(UUID versionId, QueuedRow row, String who) {
        String whoItem = who + " material=" + row.materialNo();
        if (!acquirePermit(true, whoItem)) return ItemResult.INTERRUPTED;
        try (BasisQuotationLocks.Handle h = quotationLocks.acquire(row.basisQuotationId(), whoItem)) {
            if (!preempt(row.id(), ComputeMode.LOOP.allowed)) {
                return isVersionPending(versionId) ? ItemResult.SKIPPED : ItemResult.STOP;
            }
            return computeTxSafe(row.id(), versionId, null, whoItem);
        } finally {
            releasePermit(true);
        }
    }

    // -------------------------------------------------------------------------
    // 抢占 / 条件完成（B-15 ② ③）
    // -------------------------------------------------------------------------

    /**
     * B-15②：条件抢占，独立短事务立即提交。只有「待处理 ∧ 预算状态在本入口允许集合里 ∧ 版本仍 PENDING」才置 COMPUTING。
     * 🔒 调用方必须已持有该行依据单的锁（取锁在任何写库之前）。
     *
     * @return 抢到（更新到 1 行）
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    boolean preempt(UUID reviewId, Set<String> allowed) {
        int n = em.createNativeQuery(
                "UPDATE material_price_review r SET budget_status = 'COMPUTING', updated_at = now() " +
                " WHERE r.id = :id AND r.status = 'PENDING' AND r.budget_status = ANY(:allowed) " +
                "   AND EXISTS (SELECT 1 FROM element_price_version v WHERE v.id = r.version_id AND v.status = 'PENDING')")
            .setParameter("id", reviewId)
            .setParameter("allowed", allowed.toArray(new String[0]))
            .executeUpdate();
        return n == 1;
    }

    /**
     * {@link #computeTx} 的兜底包装：事务本身抛异常（含事务超时）⇒ 另开事务条件地记 FAILED。
     * 问题-1（AC-17）：两个事务各自在<b>开始前取版本读锁、提交后放</b>（{@link VersionSupersedeGate}），让排队的生成请求
     * 只等在途的这一次，而不是被首尾交叠的 FOR SHARE 饿死。调用方此刻已持名额与依据单锁、已完成抢占，且没有打开的事务。
     */
    private ItemResult computeTxSafe(UUID reviewId, UUID versionId, BigDecimal thresholdOverride, String who) {
        try {
            try (VersionSupersedeGate.Handle g = versionGate.read(versionId, who)) {
                return computeTx(reviewId, thresholdOverride);
            }
        } catch (Exception e) {
            if (VersionNotPendingException.isCauseOf(e)) return ItemResult.STOP;
            String msg = PriceAdjustFailureTranslator.forBudget(e);
            LOG.errorf(e, "[price-adjust-budget] review=%s 试算事务异常（%s）", reviewId, msg);
            try (VersionSupersedeGate.Handle g = versionGate.read(versionId, who + " mark-failed")) {
                return markFailedIfComputing(reviewId, msg) ? ItemResult.FAILED : ItemResult.STOP;
            } catch (Exception x) {
                LOG.errorf(x, "[price-adjust-budget] review=%s 记录预算失败时再次异常", reviewId);
                return ItemResult.FAILED;
            }
        }
    }

    /**
     * 试算事务外抛异常后的失败落库：带锁复核版本仍 PENDING，再<b>条件</b>置 FAILED（仍在 COMPUTING 才写）。
     *
     * @return {@code false} = 版本已不是 PENDING（什么都没写）
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    boolean markFailedIfComputing(UUID reviewId, String error) {
        MaterialPriceReview r = MaterialPriceReview.findById(reviewId);
        if (r == null) return true;
        if (!lockVersionAndCheckPending(r.versionId)) return false;
        em.createNativeQuery(
                "UPDATE material_price_review SET budget_status = 'FAILED', budget_error = :e, updated_at = now() " +
                " WHERE id = :id AND status = 'PENDING' AND budget_status = 'COMPUTING'")
            .setParameter("e", error)
            .setParameter("id", reviewId)
            .executeUpdate();
        return true;
    }

    /**
     * <b>唯一的一份试算实现</b>（后台循环 / compute-now / recompute / 单料号入口共用，AC-6）。
     * <ol>
     *   <li>带锁复核版本仍 PENDING（{@code FOR SHARE} 持有到本事务提交 ⇒ 生成新版本的请求等本次提交后才能作废旧版）；</li>
     *   <li>行必须仍是「待处理 + COMPUTING」（由调用方抢占），否则什么都不做；</li>
     *   <li>按审核行上记下的依据单 + 料号重找依据行（B-17，J-3 次序）；找不到 ⇒ FAILED「判断依据单已不在活单范围内」，
     *       不推进指针、不作废；依据单为空（反例外）⇒ READY + 0 列；</li>
     *   <li>条件完成：{@code UPDATE … WHERE status='PENDING' AND budget_status='COMPUTING'}，更新到 0 行 ⇒ 整体回滚，
     *       不写比对列（期间被改配置重新排队 / 被作废）；否则同一事务里删插比对列。</li>
     * </ol>
     *
     * @param thresholdOverride 非 null 时代替策略阈值（仅单料号入口保留旧签名语义用）；null ⇒ 每次现读策略阈值
     *                          （改阈值后重排队的行必须按新阈值算，AC-24）
     */
    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    ItemResult computeTx(UUID reviewId, BigDecimal thresholdOverride) {
        MaterialPriceReview r = MaterialPriceReview.findById(reviewId);
        if (r == null) return ItemResult.SKIPPED;
        UUID versionId = r.versionId;
        if (!lockVersionAndCheckPending(versionId)) {
            throw new VersionNotPendingException(versionId);
        }
        if (!MaterialPriceReview.STATUS_PENDING.equals(r.status)
                || !MaterialPriceReview.BUDGET_COMPUTING.equals(r.budgetStatus)) {
            return ItemResult.SKIPPED;
        }
        String customerNo = r.customerNo;
        String materialNo = r.materialNo;
        LOG.infof("[price-adjust-budget] review=%s material=%s 开始试算", reviewId, materialNo);

        BudgetOutcome o;
        if (r.basisQuotationId == null) {
            // B-17③ 反例外：无依据单可预算 —— READY + 0 列，交人工裁决
            o = BudgetOutcome.readyEmpty();
        } else {
            BasisLine basis = findBasisLineInQuotation(customerNo, r.basisQuotationId, materialNo);
            if (basis == null) {
                o = BudgetOutcome.failed(BASIS_GONE_ERROR);
            } else {
                BigDecimal threshold = thresholdOverride != null ? thresholdOverride : loadThreshold(customerNo);
                try {
                    o = computeBudget(reviewId, customerNo, materialNo, basis, versionId, threshold);
                } catch (Exception e) {
                    // repair-260918 B-6/B-7：可读口径（超时 ⇒「预算试算超时（超过 60 秒）」）
                    o = BudgetOutcome.failed(PriceAdjustFailureTranslator.forBudget(e));
                    LOG.errorf(e, "[price-adjust-budget] review=%s material=%s 预算计算失败", reviewId, materialNo);
                }
            }
        }

        int n = writeOutcome(reviewId, o);
        if (n == 0) {
            try {
                transactionManager.setRollbackOnly();
            } catch (Exception e) {
                LOG.warnf("[price-adjust-budget] review=%s setRollbackOnly 失败: %s", reviewId, e.getMessage());
            }
            LOG.infof("[price-adjust-budget] review=%s material=%s 条件完成未命中（期间被重新排队或作废），本次结果丢弃",
                reviewId, materialNo);
            return ItemResult.LOST;
        }
        if (o.replaceColumns) {
            MaterialPriceReviewColumn.delete("reviewId", reviewId);
            for (MaterialPriceReviewColumn c : o.columns) c.persist();
        }
        if (o.ready && o.columns != null && !o.columns.isEmpty()) {
            LOG.infof("[price-adjust-budget] review=%s material=%s READY columns=%d breached=%d amber=%d missing=%d stale=%d",
                reviewId, materialNo, o.columns.size(), o.breached, o.amber, o.missing, o.stale);
        }
        return o.ready ? ItemResult.READY : ItemResult.FAILED;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int writeOutcome(UUID reviewId, BudgetOutcome o) {
        StringBuilder sql = new StringBuilder("UPDATE material_price_review SET ");
        if (o.ready) {
            sql.append("budget_status = 'READY', budget_error = NULL, breached_count = :b, amber_count = :a, ")
               .append("missing_count = :m, stale_count = :s, column_count = :cc, ");
        } else {
            sql.append("budget_status = 'FAILED', budget_error = :e, ");
        }
        if (o.touchWarn) {
            sql.append("warn_code = CAST(:wc AS varchar), warn_message = CAST(:wm AS text), warn_diff = CAST(:wd AS numeric), ");
        }
        if (o.templateSeriesKnown) sql.append("template_series_id = CAST(:ts AS uuid), ");
        sql.append("updated_at = now() WHERE id = :id AND status = 'PENDING' AND budget_status = 'COMPUTING'");
        org.hibernate.query.NativeQuery q = em.createNativeQuery(sql.toString())
            .unwrap(org.hibernate.query.NativeQuery.class);
        q.setParameter("id", reviewId);
        if (o.ready) {
            q.setParameter("b", o.breached);
            q.setParameter("a", o.amber);
            q.setParameter("m", o.missing);
            q.setParameter("s", o.stale);
            q.setParameter("cc", o.columns == null ? 0 : o.columns.size());
        } else {
            q.setParameter("e", o.error);
        }
        if (o.touchWarn) {
            q.setParameter("wc", o.warnCode, String.class);
            q.setParameter("wm", o.warnMessage, String.class);
            q.setParameter("wd", o.warnDiff, BigDecimal.class);
        }
        if (o.templateSeriesKnown) q.setParameter("ts", o.templateSeriesId, UUID.class);
        return q.executeUpdate();
    }

    @Transactional
    BigDecimal loadThreshold(String customerNo) {
        CustomerPriceAdjustStrategy s = CustomerPriceAdjustStrategy.findByCustomerNo(customerNo);
        return s != null ? s.costDiffThreshold : BigDecimal.ZERO;
    }

    // -------------------------------------------------------------------------
    // 点击即算 / 重算（B-7 / B-15）
    // -------------------------------------------------------------------------

    /** compute-now 在请求线程拿不到锁、改走整段异步时，登记在这里（单行查询据此显示「计算中」）。 */
    private final Set<UUID> acceptedCompute = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 该行是否已被受理、异步等待试算中（仅整段异步的降级路径会登记）。 */
    public boolean isComputeAccepted(UUID reviewId) {
        return reviewId != null && acceptedCompute.contains(reviewId);
    }

    /** 审核行的最小快照（请求入口判状态用）。 */
    public record ReviewBrief(UUID id, UUID versionId, String materialNo, UUID basisQuotationId,
                              String status, String budgetStatus) { }

    @Transactional
    public ReviewBrief loadBrief(UUID reviewId) {
        MaterialPriceReview r = MaterialPriceReview.findById(reviewId);
        return r == null ? null
            : new ReviewBrief(r.id, r.versionId, r.materialNo, r.basisQuotationId, r.status, r.budgetStatus);
    }

    /**
     * compute-now / recompute-budget 的请求段（异步受理，结果经轮询取）。
     * <ul>
     *   <li>请求线程按统一次序 <b>取锁（最多等 {@value #REQUEST_LOCK_WAIT_MS} ms）→ 抢占 → 放锁</b>，抢到后把试算交给工作线程，
     *       工作线程再 <b>取锁 → 试算 → 条件完成 → 放锁</b>。这样 202 返回时该行已是 COMPUTING，轮询不会读到抢占前的旧状态
     *       （FAILED 行点「重新计算」后立刻轮询、AC-13 R3 等回到 READY 再发下一条都依赖这一点）。两次持锁之间行处于
     *       COMPUTING，其它入口抢不到，不会重复试算；</li>
     *   <li>请求线程等锁超时 ⇒ 整段（取锁 → 抢占 → 试算 → 放锁）交给工作线程，并登记 {@link #isComputeAccepted}。</li>
     * </ul>
     *
     * @return 该行此刻的预算状态（受理 ⇒ COMPUTING；无需计算 ⇒ 当前状态）；行不存在 ⇒ null
     */
    public String requestCompute(UUID reviewId, ComputeMode mode) {
        ReviewBrief b = loadBrief(reviewId);
        if (b == null) return null;
        if (!MaterialPriceReview.STATUS_PENDING.equals(b.status())) return b.budgetStatus();
        if (MaterialPriceReview.BUDGET_COMPUTING.equals(b.budgetStatus()) || acceptedCompute.contains(reviewId)) {
            return MaterialPriceReview.BUDGET_COMPUTING;
        }
        if (!mode.allowed.contains(b.budgetStatus())) return b.budgetStatus();
        String who = mode.name().toLowerCase().replace('_', '-') + " review=" + reviewId + " material=" + b.materialNo();

        BasisQuotationLocks.Handle h = quotationLocks.tryAcquire(b.basisQuotationId(), who, REQUEST_LOCK_WAIT_MS);
        if (h != null) {
            boolean claimed;
            try {
                claimed = preempt(reviewId, mode.allowed);
            } finally {
                h.close();
            }
            if (!claimed) {
                ReviewBrief now = loadBrief(reviewId);
                return now != null ? now.budgetStatus() : null;
            }
            try {
                workerPool.execute(() -> computeClaimed(reviewId, b.versionId(), b.basisQuotationId(), who));
            } catch (RejectedExecutionException e) {
                LOG.errorf(e, "[price-adjust-budget] review=%s 派发试算失败，退回未计算", reviewId);
                releaseClaim(reviewId);
                requestBudgetRun(List.of(b.versionId()));
                return MaterialPriceReview.BUDGET_QUEUED;
            }
            return MaterialPriceReview.BUDGET_COMPUTING;
        }
        if (!acceptedCompute.add(reviewId)) return MaterialPriceReview.BUDGET_COMPUTING;
        try {
            workerPool.execute(() -> {
                try {
                    computeWithLock(reviewId, b.versionId(), b.basisQuotationId(), mode.allowed, null, who);
                } finally {
                    acceptedCompute.remove(reviewId);
                }
            });
        } catch (RejectedExecutionException e) {
            acceptedCompute.remove(reviewId);
            LOG.errorf(e, "[price-adjust-budget] review=%s 派发试算失败", reviewId);
            return b.budgetStatus();
        }
        return MaterialPriceReview.BUDGET_COMPUTING;
    }

    /** 已由请求线程抢占（COMPUTING）的行：取锁 → 试算事务 → 条件完成 → 放锁。 */
    @ActivateRequestContext
    void computeClaimed(UUID reviewId, UUID versionId, UUID basisQuotationId, String who) {
        // 名额 → 锁 → 试算事务 → 条件完成 → 放锁 → 还名额。等名额被中断（进程关闭）⇒ 行保持 COMPUTING，由下次启动的
        // B-12 收尾重置为 QUEUED（本线程不再写库）。
        if (!acquirePermit(false, who)) return;
        try (BasisQuotationLocks.Handle h = quotationLocks.acquire(basisQuotationId, who)) {
            computeTxSafe(reviewId, versionId, null, who);
        } catch (Exception e) {
            LOG.errorf(e, "[price-adjust-budget] review=%s 试算异常", reviewId);
        } finally {
            releasePermit(false);
        }
    }

    /** 完整一条：取锁 → 抢占 → 试算事务 → 条件完成 → 放锁。 */
    @ActivateRequestContext
    ItemResult computeWithLock(UUID reviewId, UUID versionId, UUID basisQuotationId, Set<String> allowed,
                               BigDecimal thresholdOverride, String who) {
        // 名额（点击即算 / 重算额度）→ 锁 → 抢占 → 试算事务 → 条件完成 → 放锁 → 还名额
        if (!acquirePermit(false, who)) return ItemResult.INTERRUPTED;
        try {
            return computeWithLockHeld(reviewId, versionId, basisQuotationId, allowed, thresholdOverride, who);
        } finally {
            releasePermit(false);
        }
    }

    private ItemResult computeWithLockHeld(UUID reviewId, UUID versionId, UUID basisQuotationId, Set<String> allowed,
                                           BigDecimal thresholdOverride, String who) {
        try (BasisQuotationLocks.Handle h = quotationLocks.acquire(basisQuotationId, who)) {
            if (!preempt(reviewId, allowed)) return ItemResult.SKIPPED;
            return computeTxSafe(reviewId, versionId, thresholdOverride, who);
        }
    }

    /** 派发失败时把自己抢占的行退回 QUEUED（仍在 COMPUTING 才退）。 */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void releaseClaim(UUID reviewId) {
        em.createNativeQuery(
                "UPDATE material_price_review SET budget_status = 'QUEUED', updated_at = now() " +
                " WHERE id = :id AND status = 'PENDING' AND budget_status = 'COMPUTING'")
            .setParameter("id", reviewId)
            .executeUpdate();
    }

    /**
     * 单料号入口（保留原签名；生产代码已不再调用，既有测试夹具经它为一个料号「进池 + 算完」）：
     * 同一套进池判定（{@link #enqueueMaterials}）→ 有审核行 ⇒ 按统一次序取锁 → 抢占（QUEUED/FAILED/READY）→ 试算。
     *
     * @return true=进入待办池（有审核行）；false=未进池（指针推进 / 版本不存在）
     * @throws VersionNotPendingException 版本已不是 PENDING（什么都没写）
     */
    @ActivateRequestContext
    boolean processMaterial(UUID versionId, String customerNo, BigDecimal costDiffThreshold, String materialNo) {
        EnqueueResult er;
        try (VersionSupersedeGate.Handle g = versionGate.read(versionId, "processMaterial-enqueue")) {
            er = enqueueMaterials(versionId, customerNo, List.of(materialNo));
        }
        if (er.versionMissing) return false;
        if (er.versionNotPending) throw new VersionNotPendingException(versionId);
        ReviewBrief b = loadBriefByMaterial(versionId, materialNo);
        if (b == null) return false;
        ItemResult r = computeWithLock(b.id(), b.versionId(), b.basisQuotationId(), ComputeMode.RECOMPUTE.allowed,
            costDiffThreshold != null ? costDiffThreshold : BigDecimal.ZERO, "processMaterial material=" + materialNo);
        if (r == ItemResult.STOP) throw new VersionNotPendingException(versionId);
        return true;
    }

    @Transactional
    ReviewBrief loadBriefByMaterial(UUID versionId, String materialNo) {
        MaterialPriceReview r = MaterialPriceReview.findByVersionAndMaterial(versionId, materialNo);
        return r == null ? null
            : new ReviewBrief(r.id, r.versionId, r.materialNo, r.basisQuotationId, r.status, r.budgetStatus);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 裁决 39（§11.5.5 补丁 1）：该料号的「相关元素」在 {@code versionId} 与
     * {@code previousVersionId}（= 该料号版本指针**当前指向**的那一版，<b>不是</b>"上一个 V 版本"）
     * 两版里是否存在差异。
     *
     * <p><b>「相关元素」的来源</b>：{@link MaterialVersionUpgradeService#collectMaterialElementCodes}
     * —— 即 S3a/S3b 真正会去改写价格的那批行上扫出来的元素编码全集，与升版执行同一口径。
     * 🔒 不另写一套：两套口径一旦分岔，"预算算的元素"与"准入判定的元素"就不是同一批，
     * 比现在"全都进池"更难查。
     *
     * <p><b>比什么</b>：{@code current_price} + {@code currency} —— 正是 S1
     * （{@code loadVersionPrices}）读出、S3a/S3b 写进行里的两个值。{@code price_unit} 全链路
     * 不参与写回故不比；{@code change_rate}/{@code previous_price} 是派生展示列，更不比。
     * 两版都为 NULL（彻底无价）判为相同 —— 升版对这种元素本就一行都不动。
     * 金额用 {@code compareTo} 判等，不用 {@code equals}（5450 与 5450.000000 必须视为同价）。
     *
     * <p><b>保守方向（重要）</b>：任何"证明不了没变"的情形一律判为**有变动 → 照常进池**，包括
     * 相关元素集合为空（扫不出元素）、某元素只在其中一版有 item。宁可多进池让财务点一下，
     * 也不能静默跳过 + 推进指针 —— 后者等于未经审核就接受了本期价。
     *
     * @return {@code true} = 有差异（应进池）；{@code false} = 逐个相同（无事可审）
     */
    boolean hasRelevantPriceChange(UUID versionId, UUID previousVersionId, UUID basisLineItemId) {
        Set<String> relevant = materialVersionUpgradeService.collectMaterialElementCodes(basisLineItemId);
        if (relevant.isEmpty()) {
            // 扫不出相关元素 ≠ 该料号与元素价无关（可能是页签从未物化 / 冻结结构缺失）——
            // 证明不了"没变"，保守判为有变动，维持本次改动前的行为（照常进池）。
            LOG.debugf("[price-adjust-budget] lineItem=%s 未扫出任何相关元素，保守判为有变动（照常进池）",
                basisLineItemId);
            return true;
        }
        Map<String, EffectivePrice> cur = loadVersionEffectivePrices(versionId);
        Map<String, EffectivePrice> prev = loadVersionEffectivePrices(previousVersionId);

        for (String code : relevant) {
            EffectivePrice a = cur.get(code);
            EffectivePrice b = prev.get(code);
            BigDecimal pa = a != null ? a.price : null;
            BigDecimal pb = b != null ? b.price : null;
            if (pa == null ? pb != null : (pb == null || pa.compareTo(pb) != 0)) {
                LOG.debugf("[price-adjust-budget] 元素 %s 价格有变动：%s → %s（version %s → %s）",
                    code, pb, pa, previousVersionId, versionId);
                return true;
            }
            String ca = a != null ? a.currency : null;
            String cb = b != null ? b.currency : null;
            if (!java.util.Objects.equals(ca, cb)) {
                LOG.debugf("[price-adjust-budget] 元素 %s 币种有变动：%s → %s（version %s → %s）",
                    code, cb, ca, previousVersionId, versionId);
                return true;
            }
        }
        return false;
    }

    /** 版本明细里一个元素的「生效价 + 币种」（判等用；{@code price} 可为 null = 彻底无价）。 */
    private record EffectivePrice(BigDecimal price, String currency) { }

    /**
     * 读一版全部元素的生效价 + 币种。🔒 与 {@code MaterialVersionUpgradeService#loadVersionPrices}
     * 不同，本方法**保留 {@code current_price IS NULL}（彻底无价）的元素**：判等时"两版都无价"
     * 要能判为相同，而"一版有价、另一版无价"必须判为不同——若沿用那个过滤版方法，后者会因
     * 两边都 miss 被误判为相同，故不能复用。
     */
    private Map<String, EffectivePrice> loadVersionEffectivePrices(UUID versionId) {
        Map<String, EffectivePrice> out = new java.util.HashMap<>();
        for (ElementPriceVersionItem it : ElementPriceVersionItem.listByVersion(versionId)) {
            out.put(it.elementCode, new EffectivePrice(it.currentPrice, it.currency));
        }
        return out;
    }

    private static final class BasisLine {
        UUID quotationId;
        UUID lineItemId;
        UUID templateSeriesId;
    }

    /** 判断依据单：该料号建单日期倒序首张活单（ACTIVE_STATUSES）（E11-6）。 */
    private BasisLine findBasisLine(String customerNo, String materialNo) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT q.id, li.id, q.customer_template_id " +
                "FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id " +
                "JOIN customer c ON c.id = q.customer_id " +
                "WHERE c.code = :cno AND li.product_part_no_snapshot = :mno " +
                "AND (li.composite_type IS NULL OR li.composite_type <> 'PART') " +
                "AND q.status = ANY(:statuses) " +
                "ORDER BY q.created_at DESC LIMIT 1")
            .setParameter("cno", customerNo)
            .setParameter("mno", materialNo)
            .setParameter("statuses", ACTIVE_STATUSES.toArray(new String[0]))
            .getResultList();
        if (rows.isEmpty()) return null;
        Object[] row = rows.get(0);
        BasisLine bl = new BasisLine();
        bl.quotationId = (UUID) row[0];
        bl.lineItemId = (UUID) row[1];
        UUID templateId = (UUID) row[2];
        if (templateId != null) {
            Template t = Template.findById(templateId);
            bl.templateSeriesId = t != null ? t.templateSeriesId : null;
        }
        return bl;
    }

    /**
     * B-17① 试算时的依据行：按审核行上记下的依据单 + 料号重找（该单须仍在活单范围内），同单多行按 J-3 取第一行
     * （{@code li.sort_order ASC NULLS LAST, li.id ASC}）—— 与进池（{@link #decideEnqueueBatch}）、抽屉 detail 同一规则。
     *
     * @return null = 该单已不在活单范围内 / 该单上已没有这个料号
     */
    private BasisLine findBasisLineInQuotation(String customerNo, UUID quotationId, String materialNo) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT q.id, li.id, t.template_series_id " +
                "FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id " +
                "JOIN customer c ON c.id = q.customer_id " +
                "LEFT JOIN template t ON t.id = q.customer_template_id " +
                "WHERE q.id = :qid AND c.code = :cno AND li.product_part_no_snapshot = :mno " +
                "AND (li.composite_type IS NULL OR li.composite_type <> 'PART') " +
                "AND q.status = ANY(:statuses) " +
                "ORDER BY li.sort_order ASC NULLS LAST, li.id ASC LIMIT 1")
            .setParameter("qid", quotationId)
            .setParameter("cno", customerNo)
            .setParameter("mno", materialNo)
            .setParameter("statuses", ACTIVE_STATUSES.toArray(new String[0]))
            .getResultList();
        if (rows.isEmpty()) return null;
        BasisLine bl = new BasisLine();
        bl.quotationId = (UUID) rows.get(0)[0];
        bl.lineItemId = (UUID) rows.get(0)[1];
        bl.templateSeriesId = (UUID) rows.get(0)[2];
        return bl;
    }

    // -------------------------------------------------------------------------
    // B4.2 dryRun 预算 + B4.3 比对差异/着色
    // -------------------------------------------------------------------------

    /** 一次试算的结果（纯内存，由 {@link #computeTx} 以条件完成写回）。 */
    static final class BudgetOutcome {
        boolean ready;
        String error;
        /** READY 时是否整体替换比对列（删后插）。 */
        boolean replaceColumns;
        List<MaterialPriceReviewColumn> columns;
        int breached, amber, missing, stale;
        boolean touchWarn;
        String warnCode;
        String warnMessage;
        BigDecimal warnDiff;
        boolean templateSeriesKnown;
        UUID templateSeriesId;

        static BudgetOutcome readyEmpty() {
            BudgetOutcome o = new BudgetOutcome();
            o.ready = true;
            o.replaceColumns = true;
            o.columns = List.of();
            return o;
        }

        static BudgetOutcome failed(String error) {
            BudgetOutcome o = new BudgetOutcome();
            o.ready = false;
            o.error = error;
            return o;
        }
    }

    /**
     * 原 {@code computeBudget}（task-0729 B4.2/B4.3）改为返回结果、不直接写审核行 —— 取值与判定逐行未变，
     * 写回由 {@link #computeTx} 的条件完成统一做。
     */
    private BudgetOutcome computeBudget(UUID reviewId, String customerNo, String materialNo, BasisLine basis,
                                        UUID versionId, BigDecimal costDiffThreshold) {
        List<ComparisonColumnDef> columns = resolveComparisonColumns(customerNo, basis.templateSeriesId, costDiffThreshold);

        // 升版前「现值」：直接读当前已落库的卡片值，不重算（B4.3 取值口径与 task-0717 一致）。
        QuotationLineItem li = QuotationLineItem.findById(basis.lineItemId);
        ComparisonViewService.SideValues currentQuote = li != null ? comparisonViewService.extractSide(li.quoteCardValues) : null;
        ComparisonViewService.SideValues currentCosting = li != null ? comparisonViewService.extractSide(li.costingCardValues) : null;

        // 升版后「调整值」：隔离子事务跑 dryRun（🔒 禁止在预算阶段触发整单 ensureCardValues，硬约束4）。
        DryRunSnapshot snap = runDryRunSnapshot(basis.lineItemId, versionId);

        BudgetOutcome o = new BudgetOutcome();
        o.templateSeriesKnown = true;
        o.templateSeriesId = basis.templateSeriesId;
        // ---- 方向3 T2：L3 口径守卫告警落库（dryRun 路径 = 高频侦测路径）----
        // snap.upgradeResult 是普通 POJO，不随 dryRun 子事务回滚消失；告警随本次条件完成一起落库。
        o.touchWarn = true;
        if (snap.upgradeResult != null && snap.upgradeResult.warnCode != null) {
            o.warnCode = snap.upgradeResult.warnCode;
            o.warnMessage = snap.upgradeResult.warnMessage;
            o.warnDiff = snap.upgradeResult.diffValue;
            LOG.warnf("[price-adjust-budget] review=%s material=%s L3 守卫告警 %s diff=%s（不阻断预算）",
                reviewId, materialNo, snap.upgradeResult.warnCode, snap.upgradeResult.diffValue);
        }
        // else：幂等 —— 重算时若分叉已消失，必须清掉上一轮的告警（三个字段写 null）。

        if (snap.upgradeResult == null || snap.upgradeResult.status == UpgradeResult.Status.FAILED
                || snap.upgradeResult.status == UpgradeResult.Status.CONFLICT) {
            o.ready = false;
            o.error = snap.upgradeResult != null ? snap.upgradeResult.message : "dryRun 升版预算未知失败";
            return o;
        }

        o.ready = true;
        o.replaceColumns = true;
        o.columns = new ArrayList<>();
        int sortOrder = 0;
        for (ComparisonColumnDef col : columns) { // pure in-memory evaluation
            ComparisonColumnEvaluator.ColumnEval curEval =
                ComparisonColumnEvaluator.evaluate(currentQuote, currentCosting, col);
            ComparisonColumnEvaluator.ColumnEval adjEval =
                ComparisonColumnEvaluator.evaluate(snap.quoteSide, snap.costingSide, col);

            MaterialPriceReviewColumn row = new MaterialPriceReviewColumn();
            row.reviewId = reviewId;
            row.columnId = col.id;
            row.columnLabel = col.isProductTotal() ? "产品总价" : col.quoteLabel;
            row.threshold = col.threshold;
            row.sortOrder = sortOrder++;
            row.quoteCurrent = curEval.quoteVal;
            row.costingCurrent = curEval.costingVal;
            row.quoteAdjusted = adjEval.quoteVal;
            row.costingAdjusted = adjEval.costingVal;
            row.diffCurrent = curEval.diff;
            row.diffAdjusted = adjEval.diff;
            row.status = adjEval.status; // 汇总标记按"调整后"评估（屏3/4 关注升版后的结果）
            row.missingSide = adjEval.missingSide;
            o.columns.add(row);

            switch (adjEval.status) {
                case ComparisonColumnEvaluator.RED -> o.breached++;
                case ComparisonColumnEvaluator.MISSING -> { o.breached++; o.missing++; } // E4：MISSING 计入 breached
                case ComparisonColumnEvaluator.AMBER -> o.amber++;
                case ComparisonColumnEvaluator.STALE -> o.stale++; // 不计 breached/amber
                default -> { /* NORMAL 不计数 */ }
            }
        }
        return o;
    }

    private static final class DryRunSnapshot {
        UpgradeResult upgradeResult;
        ComparisonViewService.SideValues quoteSide;
        ComparisonViewService.SideValues costingSide;
    }

    /**
     * 隔离子事务跑 dryRun 升版，事务内立即读出调整后的卡片值并解析为分离态 POJO（{@code SideValues}
     * 不持有任何 Hibernate 托管引用，可安全带出事务边界）；方法返回时子事务因 dryRun=true 已被
     * {@code upgrade()} 内部标记 rollback-only 自动回滚，不落任何痕迹。
     *
     * <p>repair-0803（BL-0108 同根因续集）：本方法内部调 {@code materialVersionUpgradeService
     * .upgrade(dryRun=true)} → S5 重算核价卡片 → {@code BomTreeRenderService.render()} →
     * {@code DataLoader}（{@code @RequestScoped}）。本方法又是**嵌套**在 {@link #processMaterial}
     * 的 REQUIRES_NEW 内的**第二层** REQUIRES_NEW（会挂起外层事务另开一个）——外层
     * {@code processMaterial} 补了 {@code @ActivateRequestContext} 不代表这一层自动继承，
     * 与 {@code executeItem} 的教训完全一致：**每一层 REQUIRES_NEW 边界都要单独补**，不能只补最外层。
     */
    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    DryRunSnapshot runDryRunSnapshot(UUID lineItemId, UUID targetVersionId) {
        DryRunSnapshot snap = new DryRunSnapshot();
        snap.upgradeResult = materialVersionUpgradeService.upgrade(lineItemId, targetVersionId, true);
        if (snap.upgradeResult.status == UpgradeResult.Status.SUCCESS
                || snap.upgradeResult.status == UpgradeResult.Status.SKIPPED) {
            QuotationLineItem li = QuotationLineItem.findById(lineItemId);
            if (li != null) {
                snap.quoteSide = comparisonViewService.extractSide(li.quoteCardValues);
                Quotation q = Quotation.findById(li.quotationId);
                if (q != null && q.costingCardTemplateId != null) {
                    snap.costingSide = comparisonViewService.extractSide(li.costingCardValues);
                }
            }
        }
        return snap;
    }

    // -------------------------------------------------------------------------
    // 比对列解析（B4.3「比对列定位」）
    // -------------------------------------------------------------------------

    /**
     * 料号 → 依据单 → quotation.customer_template_id → template_series_id → 查
     * comparison_column_config；未配则用默认「产品总价」列（kind=PRODUCT_TOTAL，
     * 不依赖 componentId，跨模板通用），阈值取策略的 cost_diff_threshold（E13）。
     */
    List<ComparisonColumnDef> resolveComparisonColumns(String customerNo, UUID templateSeriesId, BigDecimal costDiffThreshold) {
        if (templateSeriesId != null) {
            ComparisonColumnConfig cfg = ComparisonColumnConfig.find(customerNo, templateSeriesId);
            if (cfg != null && cfg.columns != null) {
                try {
                    JsonNode arr = MAPPER.readTree(cfg.columns);
                    if (arr.isArray() && arr.size() > 0) {
                        List<ComparisonColumnDef> out = new ArrayList<>();
                        for (JsonNode n : arr) {
                            out.add(MAPPER.treeToValue(n, ComparisonColumnDef.class));
                        }
                        return out;
                    }
                } catch (Exception e) {
                    LOG.warnf("[price-adjust-budget] comparison_column_config 解析失败 customer=%s series=%s: %s",
                        customerNo, templateSeriesId, e.getMessage());
                }
            }
        }
        ComparisonColumnDef def = new ComparisonColumnDef();
        def.id = "col-default";
        def.kind = "PRODUCT_TOTAL";
        def.sortOrder = 0;
        def.threshold = costDiffThreshold != null ? costDiffThreshold : BigDecimal.ZERO;
        List<ComparisonColumnDef> out = new ArrayList<>();
        out.add(def);
        return out;
    }
}
