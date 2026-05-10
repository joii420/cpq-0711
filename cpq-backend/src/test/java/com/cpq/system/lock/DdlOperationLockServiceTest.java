package com.cpq.system.lock;

import com.cpq.common.exception.BusinessException;
import com.cpq.system.lock.dto.DdlLockStatusDTO;
import com.cpq.system.lock.entity.DdlOperationLock;
import com.cpq.system.lock.entity.ProductImportLock;
import com.cpq.system.lock.service.DdlOperationLockService;
import com.cpq.system.lock.service.ProductImportLockService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DdlOperationLockService integration tests.
 * Covers AC-7.1, AC-7.2, AC-9.1 + Scenario 3 (DDL↔Import mutual exclusion).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DdlOperationLockServiceTest {

    @Inject
    DdlOperationLockService ddlService;

    @Inject
    ProductImportLockService importLockService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    private static final UUID USER_A      = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_B      = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CUSTOMER_DDL = UUID.fromString("dddddddd-dddd-dddd-dddd-000000000001");

    private static boolean customerInserted = false;

    @BeforeEach
    void setup() throws Exception {
        if (!customerInserted) {
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery(
                    "INSERT INTO customer(id, name, code, level, accumulated_amount, status, created_at, updated_at) " +
                    "VALUES (:id, 'DDL Test Customer', 'TEST-DDL-CUST', 'STANDARD', 0, 'ACTIVE', NOW(), NOW()) " +
                    "ON CONFLICT (id) DO NOTHING")
                    .setParameter("id", CUSTOMER_DDL)
                    .executeUpdate();
            utx.commit();
            customerInserted = true;
        }

        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM product_import_lock WHERE customer_id = :cid")
                .setParameter("cid", CUSTOMER_DDL)
                .executeUpdate();
        em.createNativeQuery(
                "UPDATE ddl_operation_lock SET expires_at = NOW() - INTERVAL '1 second' WHERE lock_key = 'global'")
                .executeUpdate();
        utx.commit();
    }

    // ======== AC-7.2: No active import lock → DDL acquire succeeds ========

    @Test
    @Order(1)
    void ac7_2_acquire_noActiveImportLock_succeeds() {
        long activeBefore = ProductImportLock.countAllActive();
        assertEquals(0, activeBefore, "Pre-condition: no active import locks");

        assertDoesNotThrow(() -> ddlService.acquire(USER_A, "test-ddl-v7_2"));

        DdlLockStatusDTO status = ddlService.status();
        assertTrue(status.locked, "AC-7.2: DDL lock must be active after acquire");
        assertEquals(USER_A, status.lockedBy);
        assertEquals("test-ddl-v7_2", status.operationDesc);
    }

    // ======== AC-7.1: Active import lock → DDL acquire returns 423 ========

    @Test
    @Order(2)
    void ac7_1_acquire_withActiveImportLock_throws423() {
        importLockService.acquireLocks(CUSTOMER_DDL, List.of("PART-DDL-BLOCK"), USER_A, null);
        assertTrue(ProductImportLock.countAllActive() > 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ddlService.acquire(USER_B, "test-ddl-blocked"));

        assertEquals(423, ex.getCode(),
                "AC-7.1: DDL acquire must return 423 when active import locks exist");
        assertTrue(ex.getMessage().contains("导入锁") || ex.getMessage().contains("import"),
                "Error message should mention import locks");
    }

    // ======== Scenario 3: DDL lock active → acquireProduct returns 423 ========

    @Test
    @Order(3)
    void scenario3_ddlLockBlocks_importLockAcquire_423() {
        ddlService.acquire(USER_A, "test-ddl-mutual-excl");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                importLockService.acquireLocks(CUSTOMER_DDL, List.of("PART-MX"), USER_B, null));

        assertEquals(423, ex.getCode(),
                "Scenario 3: Active DDL lock must block product import lock acquire with 423");
    }

    // ======== AC-9.1: Force-release DDL lock sets expires_at to past ========

    @Test
    @Order(4)
    void ac9_1_forceRelease_ddlLock_setsExpiresAtToPast() throws Exception {
        ddlService.acquire(USER_A, "test-ddl-force-release");

        // Verify lock is active via native SQL (avoids L1 cache)
        Object expiresAtBefore = readExpiresAtNative();
        assertNotNull(expiresAtBefore, "Pre-condition: DDL lock must exist");

        ddlService.forceRelease(USER_B);

        // Verify via native SQL — bypasses Hibernate L1 cache (Bug-4 workaround)
        Object expiresAtAfter = readExpiresAtNative();
        assertNotNull(expiresAtAfter);
        // The native query returns the DB value — forceRelease sets expires_at = NOW() - 1 second
        // We verify the field changed by checking the service status in a fresh transaction
        boolean lockedAfter = readLockedStatusNative();
        assertFalse(lockedAfter,
                "AC-9.1: After force-release, expires_at must be in the past (locked=false). " +
                "If this fails, Bug-4: DdlOperationLockService native UPDATE not visible via Panache findById " +
                "due to Hibernate L1 cache not being cleared after native SQL.");
    }

    // ======== DDL UPSERT: expired lock can be replaced ========

    @Test
    @Order(5)
    void ddl_acquireAfterExpiry_upserts_successfully() throws Exception {
        ddlService.acquire(USER_A, "first-op");

        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "UPDATE ddl_operation_lock SET expires_at = NOW() - INTERVAL '1 second' WHERE lock_key = 'global'")
                .executeUpdate();
        utx.commit();

        assertDoesNotThrow(() -> ddlService.acquire(USER_B, "second-op"),
                "DDL acquire must succeed after previous lock expires");

        DdlLockStatusDTO status = ddlService.status();
        assertTrue(status.locked);
        assertEquals(USER_B, status.lockedBy);
    }

    // ======== DDL concurrent acquire fails with 409 ========

    @Test
    @Order(6)
    void ddl_acquireWhileActive_throws409or423() {
        ddlService.acquire(USER_A, "op-1");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ddlService.acquire(USER_B, "op-2"));

        assertTrue(ex.getCode() == 409 || ex.getCode() == 423,
                "DDL acquire while held must return 409 or 423, got: " + ex.getCode());
    }

    // ======== DDL status when lock expired ========

    @Test
    @Order(7)
    void ddl_status_whenLockExpired_returnsNotLocked() {
        DdlLockStatusDTO status = ddlService.status();
        assertFalse(status.locked, "DDL status must return locked=false when expires_at is in the past");
    }

    // ======== DDL release by owner ========

    @Test
    @Order(8)
    void ddl_release_byOwner_succeeds() {
        ddlService.acquire(USER_A, "test-release-by-owner");

        ddlService.release(USER_A);

        // Verify via native SQL to bypass Hibernate L1 cache
        boolean lockedAfter = readLockedStatusNative();
        assertFalse(lockedAfter,
                "DDL lock must be released by owner. " +
                "If this fails, Bug-4: Hibernate L1 cache not invalidated after native SQL UPDATE in release().");
    }

    // ======== DDL release by non-owner — no effect ========

    @Test
    @Order(9)
    void ddl_release_byNonOwner_noEffect() {
        ddlService.acquire(USER_A, "test-release-nonowner");

        ddlService.release(USER_B); // non-owner → no-op

        assertTrue(ddlService.status().locked, "DDL lock must remain active when released by non-owner");
    }

    // ======== Native SQL helpers (bypass Hibernate L1 cache) ========

    /** Read expires_at directly from DB to bypass Hibernate L1 cache. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    Object readExpiresAtNative() {
        return em.createNativeQuery(
                "SELECT expires_at FROM ddl_operation_lock WHERE lock_key = 'global'")
                .getSingleResultOrNull();
    }

    /** Check if DDL lock is currently active (expires_at > NOW()) via native SQL. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    boolean readLockedStatusNative() {
        Object result = em.createNativeQuery(
                "SELECT expires_at > NOW() FROM ddl_operation_lock WHERE lock_key = 'global'")
                .getSingleResultOrNull();
        if (result == null) return false;
        return Boolean.TRUE.equals(result);
    }
}
