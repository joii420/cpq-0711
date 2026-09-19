package com.cpq.priceadjust.impl260918;

import com.cpq.common.DecimalJacksonCustomizer;
import com.cpq.common.perf.SqlStatementCounter;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJob;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
import com.cpq.priceadjust.service.CurrentPeriodRevisionWriter;
import com.cpq.priceadjust.service.MaterialVersionUpgradeService;
import com.cpq.priceadjust.service.PriceAdjustFailureTranslator;
import com.cpq.priceadjust.service.PriceAdjustJobExecutionService;
import com.cpq.priceadjust.service.PriceAdjustStartupRecovery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260918 · backend developer self-test (not the formal AC suite — that lives in {@code ac260918}).
 *
 * <p>Runs against {@code cpq_db_test} on a private fixture ({@link Rp0918bFixture}: prefix {@code RP0918B}, BIG = copy of
 * the 1845-line {@code QT-20260909-0629}, SMALL = its first 12 lines; same template / component structure), created
 * in {@code @BeforeAll} and deleted by id in {@code @AfterAll}. Skips when the source quotation is absent.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Repair260918BackendSelfTest {

    private static final ObjectMapper MAPPER = DecimalJacksonCustomizer.newMapper();

    @Inject EntityManager em;
    @Inject CurrentPeriodRevisionWriter writer;
    @Inject MaterialVersionUpgradeService upgradeService;
    @Inject PriceAdjustJobExecutionService jobService;
    @Inject PriceAdjustStartupRecovery recovery;

    UUID customerId, versionId, qBig, qSmall;
    final List<String> perfLines = new CopyOnWriteArrayList<>();
    Handler handler;

    Rp0918bFixture fx;

    @BeforeAll
    void setUp() {
        fx = Rp0918bFixture.create(em);
        org.junit.jupiter.api.Assumptions.assumeTrue(fx != null, Rp0918bFixture.SOURCE_QUOTATION + " missing in this database");
        customerId = fx.customerId;
        versionId = fx.versionId;
        qBig = fx.qBig;
        qSmall = fx.qSmall;
        handler = new Handler() {
            @Override public void publish(LogRecord r) {
                String m = r.getMessage();
                if (m != null && m.contains("[perf]")) {
                    Object[] p = r.getParameters();
                    perfLines.add(p != null && p.length > 0 ? String.format(m.replace("%b", "%s"), p) : m);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        java.util.logging.Logger.getLogger(MaterialVersionUpgradeService.class.getName()).addHandler(handler);
        java.util.logging.Logger.getLogger(CurrentPeriodRevisionWriter.class.getName()).addHandler(handler);
    }

    @AfterAll
    void tearDown() {
        if (handler != null) {
            java.util.logging.Logger.getLogger(MaterialVersionUpgradeService.class.getName()).removeHandler(handler);
            java.util.logging.Logger.getLogger(CurrentPeriodRevisionWriter.class.getName()).removeHandler(handler);
        }
        if (fx != null) fx.destroy(em);
    }

    /** Deletes only the fixture's revision / job rows so the class can be re-run. */
    void resetFixtureState() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM material_price_update_job_item WHERE quotation_id IN (:a, :b)")
                .setParameter("a", qBig).setParameter("b", qSmall).executeUpdate();
            em.createNativeQuery("DELETE FROM material_price_update_job WHERE customer_no = :c").setParameter("c", fx.customerCode).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_price_revision WHERE quotation_id IN (:a, :b)")
                .setParameter("a", qBig).setParameter("b", qSmall).executeUpdate();
        });
    }

    // ------------------------------------------------------------------ B-6 translator (pure)

    @Test @Order(1)
    void translator() {
        Exception arjuna = new RuntimeException("Error invoking subclass method",
            new jakarta.transaction.RollbackException("ARJUNA016102: The transaction is not active! Uid is 0:ffff"));
        assertArrayEquals(new String[]{"EXECUTION_TIMEOUT", "执行超时（超过 60 秒）被中止，本行未更新，可重试"},
            PriceAdjustFailureTranslator.forJobItem(arjuna));
        Exception cancel = new IllegalStateException("x", new SQLException("canceling statement", "57014"));
        assertEquals("EXECUTION_TIMEOUT", PriceAdjustFailureTranslator.forJobItem(cancel)[0]);
        Exception boom = new RuntimeException("Error invoking subclass method", new IllegalStateException("boom"));
        String[] f = PriceAdjustFailureTranslator.forJobItem(boom);
        assertEquals("UNEXPECTED_ERROR", f[0]);
        assertEquals("IllegalStateException: boom", f[1]);
        assertEquals("预算试算超时（超过 60 秒）", PriceAdjustFailureTranslator.forBudget(arjuna));
        assertEquals("AssertionError: x", PriceAdjustFailureTranslator.forJobItem(new AssertionError("x"))[1]);
    }

    // ------------------------------------------------------------------ B-1 writer vs. old Java assembly (AC-12)

    @Test @Order(2)
    void writerMatchesLegacyAssembly_bigQuotation() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> writer.write(qBig, versionId, List.of("A", "B", "A")));
        JsonNode[] expected = legacySnapshot(qBig);
        Object[] rev = revisionRow(qBig, versionId);
        assertNotNull(rev, "current revision written");
        assertEquals(expected[0], MAPPER.readTree((String) rev[0]), "quote_card_values");
        assertEquals(expected[1], MAPPER.readTree((String) rev[1]), "costing_card_values");
        assertEquals(expected[2], MAPPER.readTree((String) rev[2]), "snapshot_rows");
        assertEquals(1845, expected[0].size());
        assertEquals("[\"A\",\"B\"]", MAPPER.readTree((String) rev[3]).toString());
        String revNo = (String) rev[4];

        QuarkusTransaction.requiringNew().run(() -> writer.write(qBig, versionId, List.of("C", "B", "D")));
        Object[] rev2 = revisionRow(qBig, versionId);
        assertEquals("[\"A\",\"B\",\"C\",\"D\"]", MAPPER.readTree((String) rev2[3]).toString(), "append, dedupe, keep order");
        assertEquals(revNo, rev2[4], "revision_no unchanged on merge");
        assertEquals(1L, count("SELECT count(*) FROM quotation_price_revision WHERE quotation_id = :q AND based_version_id = :v", qBig, versionId));
        System.out.println("[selftest] big current revision_no=" + revNo + " perf=" + perfLines);
    }

    @Test @Order(3)
    void initialSealOnceAndDistinctRevisionNo() {
        boolean first = QuarkusTransaction.requiringNew().call(() -> writer.sealInitialIfNeeded(qBig));
        boolean second = QuarkusTransaction.requiringNew().call(() -> writer.sealInitialIfNeeded(qBig));
        assertTrue(first);
        assertFalse(second);
        List<Object[]> rows = QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(
                "SELECT revision_no, based_version_id, sealed FROM quotation_price_revision WHERE quotation_id = :q ORDER BY revision_no")
            .setParameter("q", qBig).getResultList());
        assertEquals(2, rows.size());
        assertNotEquals(rows.get(0)[0], rows.get(1)[0]);
        for (Object[] r : rows) System.out.println("[selftest] big revision " + r[0] + " based=" + r[1] + " sealed=" + r[2]);
        resetFixtureState();
    }

    // ------------------------------------------------------------------ AC-13 / AC-20 / B-2 / B-3

    @Test @Order(4)
    void sqlCountIndependentOfLineCount_andSinglePathFirstUpgrade() throws Exception {
        UUID liSmall = lineOf(qSmall, "PERFHOT-B00001");
        UUID liBig = lineOf(qBig, "PERFHOT-B00001");
        String[] beforeSmall = lineState(liSmall);

        long[] dry = new long[2];
        long[] real = new long[2];
        perfLines.clear();
        dry[0] = runUpgrade(liSmall, true);
        dry[1] = runUpgrade(liBig, true);
        assertEquals(0L, count("SELECT count(*) FROM quotation_price_revision WHERE quotation_id IN (:q, :v)", qSmall, qBig),
            "dryRun writes no revision (B-2)");
        assertArrayEquals(beforeSmall, lineState(liSmall), "dryRun leaves the line untouched");
        real[0] = runUpgrade(liSmall, false);
        real[1] = runUpgrade(liBig, false);
        perfLines.forEach(l -> System.out.println("[selftest] " + l));
        System.out.printf("[selftest] test-side sql delta: dry small=%d big=%d | real small=%d big=%d%n", dry[0], dry[1], real[0], real[1]);

        List<String> upgradeLogs = perfLines.stream().filter(l -> l.contains("[perf] upgrade")).toList();
        assertEquals(4, upgradeLogs.size());
        assertEquals(sqlOf(upgradeLogs.get(0)), sqlOf(upgradeLogs.get(1)), "dryRun sql 12 vs 1845 lines");
        assertEquals(sqlOf(upgradeLogs.get(2)), sqlOf(upgradeLogs.get(3)), "real sql 12 vs 1845 lines");

        // AC-20 single path first upgrade: initial (sealed, pre-upgrade value) + current (post-upgrade value), distinct numbers.
        List<Object[]> revs = QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(
                "SELECT revision_no, based_version_id, sealed, CAST(quote_card_values -> CAST(:li AS text) AS text), " +
                "       CAST(upgraded_material_nos AS text) " +
                "  FROM quotation_price_revision WHERE quotation_id = :q ORDER BY revision_no")
            .setParameter("li", liSmall.toString()).setParameter("q", qSmall).getResultList());
        assertEquals(2, revs.size());
        assertNull(revs.get(0)[1]);
        assertEquals(versionId, revs.get(1)[1]);
        assertNotEquals(revs.get(0)[0], revs.get(1)[0]);
        String[] afterSmall = lineState(liSmall);
        assertEquals(MAPPER.readTree(beforeSmall[1]), MAPPER.readTree((String) revs.get(0)[3]), "initial = pre-upgrade card values");
        assertEquals(MAPPER.readTree(afterSmall[1]), MAPPER.readTree((String) revs.get(1)[3]), "current = post-upgrade card values");
        assertEquals("[\"PERFHOT-B00001\"]", revs.get(1)[4]);
        System.out.println("[selftest] small revisions " + revs.get(0)[0] + " / " + revs.get(1)[0]
            + " subtotal " + beforeSmall[0] + " -> " + afterSmall[0]);

        // AC-12 口径 on the single path: whole snapshot equals independent assembly
        JsonNode[] expected = legacySnapshot(qBig);
        Object[] rev = revisionRow(qBig, versionId);
        assertEquals(expected[0], MAPPER.readTree((String) rev[0]));
        assertEquals(expected[2], MAPPER.readTree((String) rev[2]));
    }

    // ------------------------------------------------------------------ B-4 grouped path / B-5 / B-13

    @Test @Order(5)
    void groupedPath_oneCurrentWritePerQuotation_andRetryIncludesFailed() throws Exception {
        List<String> bigMaterials = List.of("PERFHOT-B00002", "PERFHOT-B00003", "PERFHOT-B00004", "PERFHOT-B00005", "PERFHOT-B00006");
        List<String> smallMaterials = List.of("PERFHOT-B00002", "PERFHOT-B00003");
        UUID jobId = createJob(List.of(new Object[]{qBig, bigMaterials}, new Object[]{qSmall, smallMaterials}));
        perfLines.clear();
        long t0 = System.nanoTime();
        activateAndRun(() -> jobService.executeJob(jobId));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        perfLines.forEach(l -> System.out.println("[selftest] " + l));
        System.out.println("[selftest] grouped job 7 items ms=" + ms + " per item=" + ms / 7);

        List<Object[]> items = itemsOf(jobId);
        for (Object[] it : items) assertEquals("SUCCESS", it[1], "item " + it[0] + " " + it[2] + " " + it[3]);
        long bigWrites = perfLines.stream().filter(l -> l.contains("revision-write quotation=" + qBig + " kind=CURRENT")).count();
        long smallWrites = perfLines.stream().filter(l -> l.contains("revision-write quotation=" + qSmall + " kind=CURRENT")).count();
        assertEquals(1, bigWrites);
        assertEquals(1, smallWrites);
        Object[] rev = revisionRow(qBig, versionId);
        assertEquals("[\"PERFHOT-B00001\",\"PERFHOT-B00002\",\"PERFHOT-B00003\",\"PERFHOT-B00004\",\"PERFHOT-B00005\",\"PERFHOT-B00006\"]",
            MAPPER.readTree((String) rev[3]).toString());
        JsonNode[] expected = legacySnapshot(qBig);
        assertEquals(expected[0], MAPPER.readTree((String) rev[0]), "grouped snapshot = current state");
        assertEquals(expected[2], MAPPER.readTree((String) rev[2]));
        assertEquals(1L, count("SELECT count(*) FROM quotation_price_revision WHERE quotation_id = :q AND based_version_id = :v", qBig, versionId));
        MaterialPriceUpdateJob job = QuarkusTransaction.requiringNew().call(() -> MaterialPriceUpdateJob.findById(jobId));
        assertEquals("SUCCESS", job.status);

        // B-13: FAILED + CONFLICT re-run, STALE untouched
        List<UUID> ids = items.stream().map(r -> (UUID) r[0]).toList();
        QuarkusTransaction.requiringNew().run(() -> {
            setItem(ids.get(0), "FAILED", "EXECUTION_TIMEOUT");
            setItem(ids.get(1), "CONFLICT", "ROW_VERSION_CONFLICT");
            setItem(ids.get(2), "STALE", null);
        });
        activateAndRun(() -> jobService.retryJob(jobId));
        List<Object[]> after = itemsOf(jobId);
        for (Object[] it : after) {
            if (it[0].equals(ids.get(2))) assertEquals("STALE", it[1]);
            else assertEquals("SUCCESS", it[1], "retried " + it[0]);
        }
    }

    // ------------------------------------------------------------------ B-11 recoverJobs / B-14 switch

    @Test @Order(6)
    void recoverJobs_closesOutInterruptedJob() throws Exception {
        UUID jobId = createJob(List.<Object[]>of(new Object[]{qBig, List.of("PERFHOT-B00007", "PERFHOT-B00008", "PERFHOT-B00009")}));
        List<Object[]> items = itemsOf(jobId);
        // B00007 upgraded for real (single path would also write the snapshot, so upgrade deferred = "row ok, snapshot missing")
        UUID liSucc = (UUID) items.get(0)[4];
        activateAndRun(() -> com.cpq.priceadjust.service.CurrentRevisionDeferral.callDeferred(
            () -> upgradeService.upgrade(liSucc, versionId, false)));
        OffsetDateTime past = OffsetDateTime.now().minusMinutes(10);
        QuarkusTransaction.requiringNew().run(() -> {
            setItem((UUID) items.get(0)[0], "SUCCESS", null);
            setItem((UUID) items.get(1)[0], "RUNNING", null);
            em.createNativeQuery("UPDATE material_price_update_job_item SET updated_at = :t WHERE job_id = :j")
                .setParameter("t", past).setParameter("j", jobId).executeUpdate();
            em.createNativeQuery("UPDATE material_price_update_job SET status = 'RUNNING', triggered_at = :t WHERE id = :j")
                .setParameter("t", past).setParameter("j", jobId).executeUpdate();
        });

        // B-14: switch is false in the test profile → runOnStartup must not touch the job
        recovery.runOnStartup();
        assertEquals("RUNNING", QuarkusTransaction.requiringNew().call(() ->
            ((MaterialPriceUpdateJob) MaterialPriceUpdateJob.findById(jobId)).status));

        recovery.recoverJobs(List.of(jobId));
        List<Object[]> after = itemsOf(jobId);
        assertEquals("SUCCESS", after.get(0)[1]);
        assertEquals("FAILED", after.get(1)[1]);
        assertEquals("EXECUTION_INTERRUPTED", after.get(1)[2]);
        assertEquals("执行中断（服务重启），本行未更新，可重试", after.get(1)[3]);
        assertEquals("FAILED", after.get(2)[1]);
        Object[] rev = revisionRow(qBig, versionId);
        assertTrue(MAPPER.readTree((String) rev[3]).toString().contains("PERFHOT-B00007"), "snapshot back-filled");
        MaterialPriceUpdateJob job = QuarkusTransaction.requiringNew().call(() -> MaterialPriceUpdateJob.findById(jobId));
        assertEquals("PARTIAL", job.status);
        assertEquals(1, job.successCount);
        assertEquals(2, job.failedCount);
        assertNotNull(job.finishedAt);
    }

    // ------------------------------------------------------------------ helpers

    long runUpgrade(UUID li, boolean dryRun) {
        long[] d = new long[1];
        activateAndRun(() -> {
            long s0 = SqlStatementCounter.current();
            upgradeService.upgrade(li, versionId, dryRun);
            d[0] = SqlStatementCounter.current() - s0;
        });
        return d[0];
    }

    static long sqlOf(String perfLine) {
        int i = perfLine.indexOf(" sql=");
        return Long.parseLong(perfLine.substring(i + 5, perfLine.indexOf(' ', i + 5)));
    }

    void activateAndRun(Runnable r) {
        ManagedContext rc = Arc.container().requestContext();
        boolean activated = false;
        if (!rc.isActive()) { rc.activate(); activated = true; }
        try { r.run(); } finally { if (activated) rc.terminate(); }
    }

    UUID createJob(List<Object[]> quotationMaterials) {
        return QuarkusTransaction.requiringNew().call(() -> {
            MaterialPriceUpdateJob job = new MaterialPriceUpdateJob();
            job.customerNo = fx.customerCode;
            job.versionId = versionId;
            job.versionNo = fx.versionNo;
            job.status = MaterialPriceUpdateJob.RUNNING;
            job.persist();
            int n = 0;
            for (Object[] qm : quotationMaterials) {
                UUID q = (UUID) qm[0];
                @SuppressWarnings("unchecked") List<String> mats = (List<String>) qm[1];
                for (String m : mats) {
                    MaterialPriceUpdateJobItem it = new MaterialPriceUpdateJobItem();
                    it.jobId = job.id;
                    it.quotationId = q;
                    it.materialNo = m;
                    it.lineItemId = lineOf(q, m);
                    it.status = MaterialPriceUpdateJobItem.WAITING;
                    it.createdAt = OffsetDateTime.now().plusNanos(n * 1000L);
                    it.persist();
                    n++;
                }
            }
            job.totalCount = n;
            return job.id;
        });
    }

    List<Object[]> itemsOf(UUID jobId) {
        return QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(
                "SELECT id, status, error_code, error_message, line_item_id FROM material_price_update_job_item " +
                " WHERE job_id = :j ORDER BY created_at, material_no")
            .setParameter("j", jobId).getResultList());
    }

    void setItem(UUID id, String status, String errorCode) {
        em.createNativeQuery("UPDATE material_price_update_job_item SET status = :s, error_code = :e WHERE id = :id")
            .setParameter("s", status).setParameter("e", errorCode).setParameter("id", id).executeUpdate();
    }

    UUID lineOf(UUID q, String material) {
        return QuarkusTransaction.requiringNew().call(() -> (UUID) em.createNativeQuery(
                "SELECT id FROM quotation_line_item WHERE quotation_id = :q AND product_part_no_snapshot = :m")
            .setParameter("q", q).setParameter("m", material).getSingleResult());
    }

    String[] lineState(UUID li) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object[] r = (Object[]) em.createNativeQuery(
                    "SELECT CAST(subtotal AS text), CAST(quote_card_values AS text) FROM quotation_line_item WHERE id = :id")
                .setParameter("id", li).getSingleResult();
            return new String[]{(String) r[0], (String) r[1]};
        });
    }

    Object[] revisionRow(UUID q, UUID v) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<?> rows = em.createNativeQuery(
                    "SELECT CAST(quote_card_values AS text), CAST(costing_card_values AS text), CAST(snapshot_rows AS text), " +
                    "       CAST(upgraded_material_nos AS text), revision_no " +
                    "  FROM quotation_price_revision WHERE quotation_id = :q AND based_version_id = :v")
                .setParameter("q", q).setParameter("v", v).getResultList();
            return rows.isEmpty() ? null : (Object[]) rows.get(0);
        });
    }

    /**
     * Independent re-implementation of the legacy {@code buildWholeQuotationSnapshot} + {@code putJsonOrNull}
     * semantics (key = line id; NULL card value ⇒ null; no component data ⇒ {}; null component id ⇒ "null").
     */
    JsonNode[] legacySnapshot(UUID quotationId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ObjectNode quote = MAPPER.createObjectNode(), costing = MAPPER.createObjectNode(), rows = MAPPER.createObjectNode();
            @SuppressWarnings("unchecked")
            List<Object[]> lines = em.createNativeQuery(
                    "SELECT CAST(id AS text), CAST(quote_card_values AS text), CAST(costing_card_values AS text) " +
                    "  FROM quotation_line_item WHERE quotation_id = :q")
                .setParameter("q", quotationId).getResultList();
            for (Object[] l : lines) {
                put(quote, (String) l[0], (String) l[1]);
                put(costing, (String) l[0], (String) l[2]);
                rows.set((String) l[0], MAPPER.createObjectNode());
            }
            @SuppressWarnings("unchecked")
            List<Object[]> cds = em.createNativeQuery(
                    "SELECT CAST(cd.line_item_id AS text), CAST(cd.component_id AS text), CAST(cd.snapshot_rows AS text) " +
                    "  FROM quotation_line_component_data cd JOIN quotation_line_item li ON li.id = cd.line_item_id " +
                    " WHERE li.quotation_id = :q")
                .setParameter("q", quotationId).getResultList();
            for (Object[] cd : cds) {
                ObjectNode comp = (ObjectNode) rows.get((String) cd[0]);
                put(comp, cd[1] != null ? (String) cd[1] : "null", (String) cd[2]);
            }
            return new JsonNode[]{quote, costing, rows};
        });
    }

    static void put(ObjectNode target, String key, String json) {
        if (json == null || json.isBlank()) { target.putNull(key); return; }
        try { target.set(key, MAPPER.readTree(json)); } catch (Exception e) { target.putNull(key); }
    }

    long count(String sql, UUID a, UUID b) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(sql
                .replace(":q", ":p1").replace(":v", ":p2"))
            .setParameter("p1", a).setParameter("p2", b).getSingleResult()).longValue());
    }

    UUID uuidOrNull(String sql) {
        List<?> r = em.createNativeQuery(sql).getResultList();
        return r.isEmpty() ? null : (UUID) r.get(0);
    }

    @SuppressWarnings("unused")
    static BigDecimal bd(Object o) { return o == null ? null : new BigDecimal(o.toString()); }

    @SuppressWarnings("unused")
    static <T> List<T> list(Iterator<T> it) { List<T> l = new ArrayList<>(); it.forEachRemaining(l::add); return l; }
}
