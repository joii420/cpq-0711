package com.cpq.priceadjust.impl260918;

import com.cpq.priceadjust.entity.MaterialPriceUpdateJob;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
import com.cpq.priceadjust.service.CurrentPeriodRevisionWriter;
import com.cpq.priceadjust.service.MaterialVersionUpgradeService;
import com.cpq.priceadjust.service.PriceAdjustBudgetService;
import com.cpq.priceadjust.service.PriceAdjustJobExecutionService;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;

/**
 * repair-260918 · backend developer self-test of the failure paths (B-4⑤ / B-6 / B-7 / B-8 / B-10 / B-12) on the
 * private {@link Rp0918bFixture} (created / deleted by this class). Skips when the source quotation is absent.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Repair260918InjectionSelfTest {

    @Inject EntityManager em;
    @InjectSpy MaterialVersionUpgradeService upgradeService;
    @InjectSpy CurrentPeriodRevisionWriter writer;
    @Inject PriceAdjustJobExecutionService jobService;
    @Inject PriceAdjustBudgetService budgetService;

    UUID versionId, strategyId, qSmall, qBig;

    Rp0918bFixture fx;

    @BeforeAll
    void setUp() {
        fx = Rp0918bFixture.create(em);
        org.junit.jupiter.api.Assumptions.assumeTrue(fx != null, Rp0918bFixture.SOURCE_QUOTATION + " missing in this database");
        versionId = fx.versionId;
        strategyId = fx.strategyId;
        qSmall = fx.qSmall;
        qBig = fx.qBig;
    }

    @AfterAll
    void tearDown() {
        if (fx != null) fx.destroy(em);
    }

    void cleanupBudgetAndJobs() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM material_price_update_job_item WHERE quotation_id IN (:a, :b)")
                .setParameter("a", qSmall).setParameter("b", qBig).executeUpdate();
            em.createNativeQuery("DELETE FROM material_price_update_job WHERE customer_no = :c").setParameter("c", fx.customerCode).executeUpdate();
            em.createNativeQuery("DELETE FROM material_price_review_column WHERE review_id IN (SELECT id FROM material_price_review WHERE version_id = :v)")
                .setParameter("v", versionId).executeUpdate();
            em.createNativeQuery("DELETE FROM material_price_review WHERE version_id = :v").setParameter("v", versionId).executeUpdate();
            em.createNativeQuery("DELETE FROM material_price_version_ref WHERE customer_no = :c").setParameter("c", fx.customerCode).executeUpdate();
            em.createNativeQuery("DELETE FROM customer_price_adjust_material WHERE strategy_id = :s").setParameter("s", strategyId).executeUpdate();
        });
    }

    // ------------------------------------------------------------------ B-10 / AC-22: Error must not leave the job RUNNING

    @Test @Order(1)
    void errorFromItemFinalizesJob() {
        UUID jobId = createJob(qSmall, List.of("PERFHOT-B00004", "PERFHOT-B00005"));
        Mockito.doThrow(new AssertionError("injected")).when(upgradeService).upgrade(any(), any(), anyBoolean(), any());
        try {
            assertThrows(AssertionError.class, () -> activateAndRun(() -> jobService.executeJob(jobId)));
        } finally {
            Mockito.reset(upgradeService);
        }
        MaterialPriceUpdateJob job = QuarkusTransaction.requiringNew().call(() -> MaterialPriceUpdateJob.findById(jobId));
        assertEquals("FAILED", job.status);
        for (Object[] it : items(jobId)) {
            assertEquals("FAILED", it[1]);
            assertEquals("UNEXPECTED_ERROR", it[2]);
            assertEquals("AssertionError: injected", it[3]);
        }
    }

    // ------------------------------------------------------------------ B-6 / AC-15 (grouped + single retry)

    @Test @Order(2)
    void timeoutAndUnexpectedAreReadable() {
        UUID jobId = createJob(qSmall, List.of("PERFHOT-B00004"));
        Mockito.doThrow(new RuntimeException("Error invoking subclass method",
                new jakarta.transaction.RollbackException("ARJUNA016102: The transaction is not active! Uid is x")))
            .when(upgradeService).upgrade(any(), any(), anyBoolean(), any());
        try {
            activateAndRun(() -> jobService.executeJob(jobId));
        } finally {
            Mockito.reset(upgradeService);
        }
        Object[] it = items(jobId).get(0);
        assertEquals("FAILED", it[1]);
        assertEquals("EXECUTION_TIMEOUT", it[2]);
        assertEquals("执行超时（超过 60 秒）被中止，本行未更新，可重试", it[3]);

        // single retry path: exception is recorded and the job recounted
        Mockito.doThrow(new RuntimeException("Error invoking subclass method", new IllegalStateException("boom")))
            .when(upgradeService).upgrade(any(), any(), anyBoolean(), any());
        try {
            activateAndRun(() -> jobService.retryJobItem((UUID) it[0]));
        } finally {
            Mockito.reset(upgradeService);
        }
        Object[] after = items(jobId).get(0);
        assertEquals("FAILED", after[1]);
        assertEquals("UNEXPECTED_ERROR", after[2]);
        assertEquals("IllegalStateException: boom", after[3]);
        MaterialPriceUpdateJob job = QuarkusTransaction.requiringNew().call(() -> MaterialPriceUpdateJob.findById(jobId));
        assertEquals(1, job.failedCount);
        assertEquals("FAILED", job.status);
    }

    // ------------------------------------------------------------------ B-4⑤ / AC-14

    @Test @Order(3)
    void groupRevisionWriteFailureThenSingleRetry() throws Exception {
        UUID jobId = createJob(qSmall, List.of("PERFHOT-B00006", "PERFHOT-B00007"));
        Mockito.doThrow(new RuntimeException("injected revision failure")).when(writer).write(any(), any(), any());
        try {
            activateAndRun(() -> jobService.executeJob(jobId));
        } finally {
            Mockito.reset(writer);
        }
        List<Object[]> its = items(jobId);
        for (Object[] it : its) {
            assertEquals("FAILED", it[1]);
            assertEquals("REVISION_WRITE_FAILED", it[2]);
            assertEquals("价格已更新，但版本记录写入失败，请重试", it[3]);
        }
        UUID li = (UUID) its.get(0)[4];
        String subtotalBefore = QuarkusTransaction.requiringNew().call(() ->
            String.valueOf(em.createNativeQuery("SELECT subtotal FROM quotation_line_item WHERE id = :id").setParameter("id", li).getSingleResult()));
        activateAndRun(() -> jobService.retryJobItem((UUID) its.get(0)[0]));
        Object[] after = items(jobId).get(0);
        assertEquals("SUCCESS", after[1]);
        String subtotalAfter = QuarkusTransaction.requiringNew().call(() ->
            String.valueOf(em.createNativeQuery("SELECT subtotal FROM quotation_line_item WHERE id = :id").setParameter("id", li).getSingleResult()));
        assertEquals(subtotalBefore, subtotalAfter);
        String mats = QuarkusTransaction.requiringNew().call(() -> String.valueOf(em.createNativeQuery(
                "SELECT CAST(upgraded_material_nos AS text) FROM quotation_price_revision WHERE quotation_id = :q AND based_version_id = :v")
            .setParameter("q", qSmall).setParameter("v", versionId).getSingleResult()));
        assertTrue(mats.contains("PERFHOT-B00006"), mats);
    }

    // ------------------------------------------------------------------ B-6/B-7 in-tx budget failure, B-12 skip, B-7 recordBudgetFailure, B-8 stop

    @Test @Order(4)
    void budgetFailureVisible_resumeSkips_supersedeStops() throws Exception {
        List<String> mats = List.of("PERFHOT-B00020", "PERFHOT-B00021", "PERFHOT-B00022", "PERFHOT-B00023");
        QuarkusTransaction.requiringNew().run(() -> {
            for (String m : mats) {
                em.createNativeQuery("INSERT INTO customer_price_adjust_material(id, strategy_id, material_no, created_at) VALUES (gen_random_uuid(), :s, :m, now())")
                    .setParameter("s", strategyId).setParameter("m", m).executeUpdate();
            }
        });
        // dry run of B00020 times out → review FAILED with the readable budget text
        UUID li20b = lineOf(qBig, "PERFHOT-B00020"); // B00020..23 only exist in the big copy → it is the basis quotation
        Mockito.doThrow(new RuntimeException("x", new jakarta.transaction.RollbackException("ARJUNA016102: The transaction is not active!")))
            .when(upgradeService).upgrade(eq(li20b), any(), eq(true));
        try {
            activateAndRun(() -> budgetService.onVersionGenerated(versionId));
        } finally {
            Mockito.reset(upgradeService);
        }
        List<Object[]> reviews = reviews();
        assertEquals(4, reviews.size(), "all four in the pool");
        for (Object[] r : reviews) {
            if ("PERFHOT-B00020".equals(r[0])) {
                assertEquals("FAILED", r[1]);
                assertEquals("预算试算超时（超过 60 秒）", r[2]);
            } else {
                assertEquals("READY", r[1], "material " + r[0] + " " + r[2]);
            }
        }

        // B-7 recordBudgetFailure (processMaterial itself failing) — invoked through the CDI proxy
        Method rec = PriceAdjustBudgetService.class.getDeclaredMethod("recordBudgetFailure", UUID.class, String.class, String.class, Throwable.class);
        rec.setAccessible(true);
        boolean ok = (boolean) rec.invoke(budgetService, versionId, fx.customerCode, "PERFHOT-B00099",
            new RuntimeException("Error invoking subclass method", new IllegalStateException("boom")));
        assertTrue(ok);
        Object[] r99 = reviews().stream().filter(r -> "PERFHOT-B00099".equals(r[0])).findFirst().orElseThrow();
        assertEquals("FAILED", r99[1]);
        assertEquals("IllegalStateException: boom", r99[2]);
        assertEquals("PENDING", r99[3]);

        // B-12①: resume skips already processed materials (updated_at unchanged)
        List<Object[]> before = reviews();
        activateAndRun(() -> budgetService.onVersionGenerated(versionId));
        List<Object[]> afterResume = reviews();
        for (int i = 0; i < before.size(); i++) assertEquals(before.get(i)[4], afterResume.get(i)[4], "updated_at unchanged " + before.get(i)[0]);

        // B-8: version superseded → loop stops, nothing written, pending reviews voided
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO customer_price_adjust_material(id, strategy_id, material_no, created_at) VALUES (gen_random_uuid(), :s, 'PERFHOT-B00030', now())")
                .setParameter("s", strategyId).executeUpdate();
            em.createNativeQuery("UPDATE element_price_version SET status = 'SUPERSEDED' WHERE id = :v").setParameter("v", versionId).executeUpdate();
        });
        activateAndRun(() -> budgetService.onVersionGenerated(versionId));
        List<Object[]> afterStop = reviews();
        assertTrue(afterStop.stream().noneMatch(r -> "PERFHOT-B00030".equals(r[0])), "no review written for superseded version");
        assertTrue(afterStop.stream().noneMatch(r -> "PENDING".equals(r[3])), "pending reviews voided");
        QuarkusTransaction.requiringNew().run(() ->
            em.createNativeQuery("UPDATE element_price_version SET status = 'PENDING' WHERE id = :v").setParameter("v", versionId).executeUpdate());
    }

    // ------------------------------------------------------------------ helpers

    List<Object[]> reviews() {
        return QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(
                "SELECT material_no, budget_status, budget_error, status, CAST(updated_at AS text) FROM material_price_review " +
                " WHERE version_id = :v ORDER BY material_no")
            .setParameter("v", versionId).getResultList());
    }

    void activateAndRun(Runnable r) {
        ManagedContext rc = Arc.container().requestContext();
        boolean activated = false;
        if (!rc.isActive()) { rc.activate(); activated = true; }
        try { r.run(); } finally { if (activated) rc.terminate(); }
    }

    UUID createJob(UUID q, List<String> materials) {
        return QuarkusTransaction.requiringNew().call(() -> {
            MaterialPriceUpdateJob job = new MaterialPriceUpdateJob();
            job.customerNo = fx.customerCode;
            job.versionId = versionId;
            job.versionNo = fx.versionNo;
            job.status = MaterialPriceUpdateJob.RUNNING;
            job.persist();
            int n = 0;
            for (String m : materials) {
                MaterialPriceUpdateJobItem it = new MaterialPriceUpdateJobItem();
                it.jobId = job.id;
                it.quotationId = q;
                it.materialNo = m;
                it.lineItemId = lineOf(q, m);
                it.status = MaterialPriceUpdateJobItem.WAITING;
                it.createdAt = OffsetDateTime.now().plusNanos(n++ * 1000L);
                it.persist();
            }
            job.totalCount = n;
            return job.id;
        });
    }

    List<Object[]> items(UUID jobId) {
        return QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(
                "SELECT id, status, error_code, error_message, line_item_id FROM material_price_update_job_item " +
                " WHERE job_id = :j ORDER BY created_at, material_no")
            .setParameter("j", jobId).getResultList());
    }

    UUID lineOf(UUID q, String material) {
        return QuarkusTransaction.requiringNew().call(() -> (UUID) em.createNativeQuery(
                "SELECT id FROM quotation_line_item WHERE quotation_id = :q AND product_part_no_snapshot = :m")
            .setParameter("q", q).setParameter("m", material).getSingleResult());
    }

    UUID uuid(String sql) {
        List<?> r = em.createNativeQuery(sql).getResultList();
        return r.isEmpty() ? null : (UUID) r.get(0);
    }
}
