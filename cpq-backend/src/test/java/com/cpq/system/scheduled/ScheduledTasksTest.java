package com.cpq.system.scheduled;

import com.cpq.system.service.ScheduledTaskService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scheduled task integration tests — directly invokes @Scheduled methods
 * bypassing the cron trigger so assertions are synchronous and deterministic.
 *
 * Test cases:
 *   QOUT-EXPIRE-11  : SENT quotation with past expiryDate -> markExpiredQuotations() -> status=EXPIRED
 *   CL-RETENTION-07 : cleanupChangeLog not yet implemented -> @Disabled placeholder
 *   QIMP-RETENTION-19: cleanupImportFiles not yet implemented -> @Disabled placeholder
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ScheduledTasksTest - scheduled task direct-call tests")
class ScheduledTasksTest {

    @Inject
    ScheduledTaskService scheduledTaskService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    // Fixed UUIDs for test isolation
    private static final UUID TEST_CUSTOMER_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID TEST_SALES_REP_ID = UUID.fromString("70000000-0000-0000-0000-000000000002");

    private static boolean seedInserted = false;

    @BeforeEach
    void setupSeed() throws Exception {
        if (!seedInserted) {
            utx.begin();
            em.joinTransaction();

            // Insert test customer
            em.createNativeQuery(
                    "INSERT INTO customer(id, name, code, level, accumulated_amount, status, created_at, updated_at) " +
                    "VALUES (:id, 'SchedTest Customer', 'SCHED-CUST-01', 'STANDARD', 0, 'ACTIVE', NOW(), NOW()) " +
                    "ON CONFLICT (id) DO NOTHING")
                    .setParameter("id", TEST_CUSTOMER_ID)
                    .executeUpdate();

            // Insert test user (sales rep)
            em.createNativeQuery(
                    "INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, status, is_first_login, created_at, updated_at) " +
                    "VALUES (:id, 'sched-sales', 'Sched Sales Rep', 'sched-sales@test.com', 'hash', 'SALES_MANAGER', 'ACTIVE', false, NOW(), NOW()) " +
                    "ON CONFLICT (id) DO NOTHING")
                    .setParameter("id", TEST_SALES_REP_ID)
                    .executeUpdate();

            utx.commit();
            seedInserted = true;
        }
    }

    // =========================================================================
    // QOUT-EXPIRE-11: SENT quotation with past expiry_date -> EXPIRED
    // =========================================================================

    /**
     * QOUT-EXPIRE-11
     * PRD reference: scheduled task chapter - markExpiredQuotations
     *
     * Pre-condition : A quotation with status=SENT and expiry_date=yesterday exists in DB.
     * Action        : Call markExpiredQuotations() directly (bypasses cron schedule).
     * Expected      : The quotation status changes to EXPIRED.
     */
    @Test
    @Order(1)
    @DisplayName("QOUT-EXPIRE-11: SENT quotation with past expiry_date is marked EXPIRED by scheduled task")
    void qout_expire_11_sentQuotationWithPastExpiryBecomesExpired() throws Exception {
        UUID quotationId = UUID.randomUUID();

        // Persist a SENT quotation with expiry_date = yesterday
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO quotation(" +
                "  id, quotation_number, customer_id, name, sales_rep_id, status, " +
                "  expiry_date, total_amount, tax_rate, tax_amount, created_at, updated_at" +
                ") VALUES (" +
                "  :id, :num, :cid, 'QOUT-EXPIRE-11 Test Quotation', :sid, 'SENT', " +
                "  CURRENT_DATE - INTERVAL '1 day', 0, 0, 0, NOW(), NOW()" +
                ")")
                .setParameter("id", quotationId)
                .setParameter("num", "QEXP-" + quotationId.toString().substring(0, 8))
                .setParameter("cid", TEST_CUSTOMER_ID)
                .setParameter("sid", TEST_SALES_REP_ID)
                .executeUpdate();
        utx.commit();

        // Directly invoke the scheduled method (bypasses cron trigger)
        scheduledTaskService.markExpiredQuotations();

        // Assert status is now EXPIRED
        utx.begin();
        em.joinTransaction();
        Object result = em.createNativeQuery(
                "SELECT status FROM quotation WHERE id = :id")
                .setParameter("id", quotationId)
                .getSingleResult();
        utx.commit();

        assertEquals("EXPIRED", result,
                "QOUT-EXPIRE-11: SENT quotation with past expiry_date must be updated to EXPIRED by markExpiredQuotations()");

        // Cleanup
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM quotation WHERE id = :id")
                .setParameter("id", quotationId)
                .executeUpdate();
        utx.commit();
    }

    // =========================================================================
    // CL-RETENTION-07: 5-year cleanup of basic_data_change_log
    // =========================================================================

    /**
     * CL-RETENTION-07
     * PRD reference: data retention policy - change log purge after 5 years.
     *
     * WATCH: cleanupChangeLog / purgeChangeLog method not found in ScheduledTaskService.
     * The scheduled task for 5-year retention of basic_data_change_log has NOT been
     * implemented yet. This test is a placeholder to track the requirement.
     *
     * When implemented, the test should:
     *   1. Insert a basic_data_change_log row with changed_at < NOW() - 5 years
     *   2. Call the cleanup method directly
     *   3. Assert the row no longer exists in DB
     */
    @Test
    @Order(2)
    @DisplayName("CL-RETENTION-07: change_log records older than 5 years are purged by scheduled task")
    void cl_retention_07_changeLogOlderThan5YearsIsPurged() throws Exception {
        UUID logId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();

        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO basic_data_change_log(" +
                "  id, table_name, record_id, change_type, changed_by, changed_at, created_at, updated_at" +
                ") VALUES (" +
                "  :id, 'mat_bom', :rid, 'UPDATE', :uid, " +
                "  NOW() - INTERVAL '5 years 1 day', NOW() - INTERVAL '5 years 1 day', NOW()" +
                ")")
                .setParameter("id", logId)
                .setParameter("rid", recordId)
                .setParameter("uid", TEST_SALES_REP_ID)
                .executeUpdate();
        utx.commit();

        scheduledTaskService.cleanupChangeLog();

        utx.begin();
        em.joinTransaction();
        Long count = (Long) em.createNativeQuery(
                "SELECT COUNT(*) FROM basic_data_change_log WHERE id = :id")
                .setParameter("id", logId)
                .getSingleResult();
        utx.commit();

        assertEquals(0L, count,
                "CL-RETENTION-07: change_log record older than 5 years must be deleted after cleanup");
    }

    // =========================================================================
    // QIMP-RETENTION-19: 12-month cleanup of original import files
    // =========================================================================

    /**
     * QIMP-RETENTION-19
     * PRD reference: data retention policy - purge original Excel import files after 12 months.
     *
     * WATCH: cleanupImportFiles / purgeImportFiles / cleanupOldImports method not found
     * in ScheduledTaskService. The scheduled task for 12-month retention of import files
     * has NOT been implemented yet. This test is a placeholder to track the requirement.
     *
     * When implemented, the test should:
     *   1. Write a temp file on disk (representing the original Excel)
     *   2. Insert an import_record row pointing to that file with created_at < NOW() - 12 months
     *   3. Call the cleanup method directly
     *   4. Assert the file no longer exists on disk
     *   5. Assert import_record.original_file_path is set to NULL
     */
    @Test
    @Order(3)
    @DisplayName("QIMP-RETENTION-19: original Excel files older than 12 months are deleted and path nulled")
    void qimp_retention_19_oldImportFilesAreDeletedAndPathNulled() throws Exception {
        // Create a temp file to represent the stored original Excel
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("import-test-", ".xlsx");
        java.nio.file.Files.writeString(tempFile, "dummy excel content");
        assertTrue(java.nio.file.Files.exists(tempFile), "Pre-condition: temp file must exist before cleanup");

        UUID importId = UUID.randomUUID();

        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO import_record(" +
                "  id, customer_id, original_file_name, original_file_path, " +
                "  import_status, imported_by, created_at" +
                ") VALUES (" +
                "  :id, :cid, 'old-import.xlsx', :path, " +
                "  'COMPLETED', :uid, NOW() - INTERVAL '13 months'" +
                ")")
                .setParameter("id", importId)
                .setParameter("cid", TEST_CUSTOMER_ID)
                .setParameter("path", tempFile.toString())
                .setParameter("uid", TEST_SALES_REP_ID)
                .executeUpdate();
        utx.commit();

        scheduledTaskService.cleanupImportFiles();

        // Assert file deleted from disk
        assertFalse(java.nio.file.Files.exists(tempFile),
                "QIMP-RETENTION-19: physical file must be deleted after cleanup");

        // Assert original_file_path is NULL in DB
        utx.begin();
        em.joinTransaction();
        Object pathValue = em.createNativeQuery(
                "SELECT original_file_path FROM import_record WHERE id = :id")
                .setParameter("id", importId)
                .getSingleResult();
        utx.commit();

        assertNull(pathValue,
                "QIMP-RETENTION-19: original_file_path must be set to NULL in import_record after cleanup");

        // Cleanup DB row
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM import_record WHERE id = :id")
                .setParameter("id", importId)
                .executeUpdate();
        utx.commit();

        // Cleanup temp file if still present
        java.nio.file.Files.deleteIfExists(tempFile);
    }
}
