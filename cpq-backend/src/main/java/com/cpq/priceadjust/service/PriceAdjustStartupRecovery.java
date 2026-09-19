package com.cpq.priceadjust.service;

import com.cpq.priceadjust.entity.ElementPriceVersion;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJob;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * repair-260918 B-11 / B-12 / B-14 · closes out work a previous JVM left half-done.
 *
 * <ul>
 *   <li><b>Interrupted update jobs</b> ({@link #recoverJobs}): a job still {@code RUNNING} whose items all stopped
 *       changing before this instance started can never finish (its executor thread died with the old JVM) and the
 *       progress drawer would poll it forever. Unfinished items become {@code FAILED / EXECUTION_INTERRUPTED}
 *       (retryable); SUCCESS items whose quotation's current-period revision misses them (interrupted between
 *       "row committed" and "group snapshot written") get the snapshot written; job counters/status are recomputed
 *       with {@link MaterialPriceUpdateJob#recountFrom} (same rule as {@code finalizeJob}).</li>
 *   <li><b>Unfinished budgets</b> ({@link #resumeBudgets}): pending versions whose scope still has materials with
 *       neither a review row nor a pointer at the version get their budget loop resumed asynchronously (the loop
 *       skips already processed materials, B-12①).</li>
 * </ul>
 *
 * <p>🔒 {@link #runOnStartup} only acts when {@code cpq.price-adjust.startup-recovery.enabled=true} (default; the
 * test profile and every temporary backend switch it off). Premise: a <b>single backend instance</b> — with several
 * instances each would treat the others' running jobs as interrupted. {@link #recoverJobs} /
 * {@link #resumeBudgets} called directly ignore the switch and only touch the ids passed in.
 *
 * <p>🚫 SQL count does not grow with the number of jobs / items / versions: detection, marking and recounting are
 * batched. The only per-unit statements are the snapshot writes, one per quotation that actually misses materials
 * (normally zero) — each is a single INSERT … SELECT in its own transaction so one failure cannot block the others.
 */
@ApplicationScoped
public class PriceAdjustStartupRecovery {

    private static final Logger LOG = Logger.getLogger(PriceAdjustStartupRecovery.class);

    @ConfigProperty(name = "cpq.price-adjust.startup-recovery.enabled", defaultValue = "true")
    boolean enabled;

    @Inject EntityManager em;
    @Inject CurrentPeriodRevisionWriter revisionWriter;
    @Inject PriceAdjustBudgetService budgetService;
    @Inject ManagedExecutor managedExecutor;

    /** Instant this instance finished booting; jobs whose items were all last touched before it are orphaned. */
    private volatile OffsetDateTime startedAt;

    void onStart(@Observes StartupEvent ev) {
        startedAt = OffsetDateTime.now();
        runOnStartup();
    }

    /** B-11 / B-12 startup entry. Reads the B-14 switch; disabled ⇒ returns immediately without touching anything. */
    public void runOnStartup() {
        if (!enabled) {
            LOG.info("[price-adjust-recovery] cpq.price-adjust.startup-recovery.enabled=false，跳过启动收尾与预算续跑");
            return;
        }
        OffsetDateTime cutoff = startedAt != null ? startedAt : OffsetDateTime.now();
        try {
            List<UUID> jobIds = findInterruptedJobIds(cutoff);
            LOG.infof("[price-adjust-recovery] 启动收尾：中断批次 %d 个 %s", jobIds.size(), jobIds);
            if (!jobIds.isEmpty()) recoverJobs(jobIds);
        } catch (Exception e) {
            LOG.errorf(e, "[price-adjust-recovery] 启动收尾中断批次失败（不影响服务启动）");
        }
        try {
            List<UUID> versionIds = findVersionsNeedingBudgetResume();
            LOG.infof("[price-adjust-recovery] 预算续跑：待续跑版本 %d 个 %s", versionIds.size(), versionIds);
            if (!versionIds.isEmpty()) resumeBudgets(versionIds);
        } catch (Exception e) {
            LOG.errorf(e, "[price-adjust-recovery] 预算续跑判定失败（不影响服务启动）");
        }
    }

    // -------------------------------------------------------------------------
    // B-11 interrupted jobs
    // -------------------------------------------------------------------------

    /**
     * Closes out the given jobs (only those). Safe to call on a job that is not actually interrupted only in the sense
     * that it is idempotent on finished jobs; callers must not pass a job another thread is still executing.
     */
    public void recoverJobs(Collection<UUID> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) return;
        List<UUID> ids = new ArrayList<>(new java.util.LinkedHashSet<>(jobIds));

        int failed = markInterrupted(ids);

        // SUCCESS items missing from their quotation's current-period revision → one write per quotation.
        List<Object[]> missing = findSuccessMissingFromRevision(ids);
        Map<List<UUID>, RevisionFix> fixes = new LinkedHashMap<>();
        for (Object[] r : missing) { // pure in-memory grouping
            UUID itemId = (UUID) r[0];
            UUID quotationId = (UUID) r[1];
            String materialNo = (String) r[2];
            UUID versionId = (UUID) r[3];
            RevisionFix fix = fixes.computeIfAbsent(List.of(quotationId, versionId), k -> new RevisionFix(quotationId, versionId));
            fix.itemIds.add(itemId);
            fix.materialNos.add(materialNo);
        }
        int written = 0;
        for (RevisionFix fix : fixes.values()) {
            try {
                writeRevisionInNewTx(fix.quotationId, fix.versionId, fix.materialNos);
                written++;
            } catch (Exception e) {
                LOG.errorf(e, "[price-adjust-recovery] quotation=%s 补写本期版本记录失败，%d 条成功明细改 %s",
                    fix.quotationId, fix.itemIds.size(), PriceAdjustFailureTranslator.REVISION_WRITE_FAILED);
                try {
                    markRevisionWriteFailed(fix.itemIds);
                } catch (Exception x) {
                    LOG.errorf(x, "[price-adjust-recovery] quotation=%s 标记 REVISION_WRITE_FAILED 失败", fix.quotationId);
                }
            }
        }

        recount(ids);
        LOG.infof("[price-adjust-recovery] 收尾完成：批次 %d 个，未完成明细置 %s %d 条，补写本期版本记录 %d/%d 张单",
            ids.size(), PriceAdjustFailureTranslator.EXECUTION_INTERRUPTED, failed, written, fixes.size());
    }

    private static final class RevisionFix {
        final UUID quotationId;
        final UUID versionId;
        final List<UUID> itemIds = new ArrayList<>();
        final List<String> materialNos = new ArrayList<>();

        RevisionFix(UUID quotationId, UUID versionId) {
            this.quotationId = quotationId;
            this.versionId = versionId;
        }
    }

    @Transactional
    List<UUID> findInterruptedJobIds(OffsetDateTime cutoff) {
        @SuppressWarnings("unchecked")
        List<UUID> rows = em.createNativeQuery(
                "SELECT j.id FROM material_price_update_job j " +
                " WHERE j.status = 'RUNNING' AND j.triggered_at < :cutoff " +
                "   AND NOT EXISTS (SELECT 1 FROM material_price_update_job_item i " +
                "                    WHERE i.job_id = j.id AND i.updated_at >= :cutoff)")
            .setParameter("cutoff", cutoff)
            .getResultList();
        return rows;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    int markInterrupted(List<UUID> jobIds) {
        return MaterialPriceUpdateJobItem.failUnfinished(jobIds, null,
            PriceAdjustFailureTranslator.EXECUTION_INTERRUPTED, PriceAdjustFailureTranslator.MSG_EXECUTION_INTERRUPTED);
    }

    /** {@code [itemId, quotationId, materialNo, versionId]} of SUCCESS items absent from their current-period revision. */
    @Transactional
    List<Object[]> findSuccessMissingFromRevision(List<UUID> jobIds) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT i.id, i.quotation_id, i.material_no, j.version_id " +
                "  FROM material_price_update_job_item i " +
                "  JOIN material_price_update_job j ON j.id = i.job_id " +
                "  LEFT JOIN quotation_price_revision r " +
                "         ON r.quotation_id = i.quotation_id AND r.based_version_id = j.version_id " +
                " WHERE i.job_id = ANY(:ids) AND i.status = 'SUCCESS' AND j.version_id IS NOT NULL " +
                "   AND (r.id IS NULL OR NOT (r.upgraded_material_nos @> jsonb_build_array(i.material_no))) " +
                " ORDER BY i.quotation_id, i.created_at")
            .setParameter("ids", jobIds.toArray(new UUID[0]))
            .getResultList();
        return rows;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void writeRevisionInNewTx(UUID quotationId, UUID versionId, List<String> materialNos) {
        revisionWriter.write(quotationId, versionId, materialNos);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void markRevisionWriteFailed(List<UUID> itemIds) {
        MaterialPriceUpdateJobItem.markRevisionWriteFailed(itemIds,
            PriceAdjustFailureTranslator.REVISION_WRITE_FAILED, PriceAdjustFailureTranslator.MSG_REVISION_WRITE_FAILED);
    }

    /** Same counting rule as {@code finalizeJob} ({@link MaterialPriceUpdateJob#recountFrom}); two batched reads. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void recount(List<UUID> jobIds) {
        List<MaterialPriceUpdateJob> jobs = MaterialPriceUpdateJob.list("id in ?1", jobIds);
        List<MaterialPriceUpdateJobItem> items = MaterialPriceUpdateJobItem.list("jobId in ?1", jobIds);
        Map<UUID, List<MaterialPriceUpdateJobItem>> byJob = new HashMap<>();
        for (MaterialPriceUpdateJobItem it : items) { // pure in-memory grouping
            byJob.computeIfAbsent(it.jobId, k -> new ArrayList<>()).add(it);
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (MaterialPriceUpdateJob job : jobs) { // pure in-memory recount; dirty entities flushed at commit
            boolean wasRunning = MaterialPriceUpdateJob.RUNNING.equals(job.status);
            job.recountFrom(byJob.getOrDefault(job.id, List.of()));
            if (wasRunning || job.finishedAt == null) job.finishedAt = now;
            LOG.infof("[price-adjust-recovery] jobId=%s 重算 status=%s total=%d success=%d failed=%d conflict=%d stale=%d skipped=%d",
                job.id, job.status, job.totalCount, job.successCount, job.failedCount, job.conflictCount,
                job.staleCount, job.skippedCount);
        }
    }

    // -------------------------------------------------------------------------
    // B-12 budget resume
    // -------------------------------------------------------------------------

    /**
     * Asynchronously resumes the budget loop of the given versions (only PENDING ones). A version whose loop is
     * already running is skipped (the loop entry itself also guards and logs).
     */
    public void resumeBudgets(Collection<UUID> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) return;
        List<UUID> ids = new ArrayList<>(new java.util.LinkedHashSet<>(versionIds));
        Map<UUID, String> statusById = loadVersionStatuses(ids);
        for (UUID vid : ids) { // no DB access in this loop: status map loaded above, dispatch is async
            String status = statusById.get(vid);
            if (!ElementPriceVersion.STATUS_PENDING.equals(status)) {
                LOG.infof("[price-adjust-recovery] versionId=%s 状态=%s（非 PENDING），不续跑预算", vid, status);
                continue;
            }
            if (budgetService.isBudgetLoopRunning(vid)) {
                LOG.infof("[price-adjust-recovery] versionId=%s 预算循环已在跑，跳过续跑", vid);
                continue;
            }
            LOG.infof("[price-adjust-recovery] versionId=%s 异步续跑预算", vid);
            managedExecutor.runAsync(() -> budgetService.onVersionGenerated(vid))
                .exceptionally(t -> {
                    LOG.errorf(t, "[price-adjust-recovery] versionId=%s 预算续跑异常", vid);
                    return null;
                });
        }
    }

    @Transactional
    Map<UUID, String> loadVersionStatuses(List<UUID> ids) {
        Map<UUID, String> out = new HashMap<>();
        for (ElementPriceVersion v : ElementPriceVersion.<ElementPriceVersion>list("id in ?1", ids)) {
            out.put(v.id, v.status);
        }
        return out;
    }

    /**
     * Pending versions whose material scope (same definition as {@code PriceAdjustBudgetService#resolveScopeMaterials}:
     * SPECIFIED ⇒ strategy material list, otherwise every product part number the customer ever quoted) still contains
     * a material with neither a review row for the version nor a version pointer at it. One statement for all versions.
     */
    @Transactional
    List<UUID> findVersionsNeedingBudgetResume() {
        @SuppressWarnings("unchecked")
        List<UUID> rows = em.createNativeQuery(
                "WITH pv AS (" +
                "  SELECT v.id AS vid, v.customer_no, s.id AS sid, s.material_scope_mode AS mode" +
                "    FROM element_price_version v" +
                "    JOIN customer_price_adjust_strategy s ON s.customer_no = v.customer_no" +
                "   WHERE v.status = 'PENDING'" +
                "), scope AS (" +
                "  SELECT pv.vid, pv.customer_no, m.material_no" +
                "    FROM pv JOIN customer_price_adjust_material m ON m.strategy_id = pv.sid" +
                "   WHERE pv.mode = 'SPECIFIED'" +
                "  UNION" +
                "  SELECT pv.vid, pv.customer_no, li.product_part_no_snapshot" +
                "    FROM pv JOIN customer c ON c.code = pv.customer_no" +
                "    JOIN quotation q ON q.customer_id = c.id" +
                "    JOIN quotation_line_item li ON li.quotation_id = q.id" +
                "   WHERE (pv.mode IS NULL OR pv.mode <> 'SPECIFIED') AND li.product_part_no_snapshot IS NOT NULL" +
                ")" +
                "SELECT DISTINCT s.vid FROM scope s" +
                " WHERE NOT EXISTS (SELECT 1 FROM material_price_review r" +
                "                    WHERE r.version_id = s.vid AND r.material_no = s.material_no)" +
                "   AND NOT EXISTS (SELECT 1 FROM material_price_version_ref f" +
                "                    WHERE f.customer_no = s.customer_no AND f.material_no = s.material_no" +
                "                      AND f.version_id = s.vid)")
            .getResultList();
        return rows;
    }
}
