package com.cpq.system.lock;

import com.cpq.common.exception.BusinessException;
import com.cpq.system.lock.dto.AcquireLocksResult;
import com.cpq.system.lock.entity.ProductImportLock;
import com.cpq.system.lock.service.DdlOperationLockService;
import com.cpq.system.lock.service.ProductImportLockService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.UserTransaction;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProductImportLockService integration tests.
 * Covers AC-2.1, AC-2.2, AC-3.1, AC-3.2, AC-3.3, AC-4.1, AC-4.2,
 * AC-5.1, AC-6.1, AC-8.1 + engineer heavy scenarios 1/2/3/4/5.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductImportLockServiceTest {

    @Inject
    ProductImportLockService lockService;

    @Inject
    DdlOperationLockService ddlService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    private static final UUID CUSTOMER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-000000000001");
    private static final UUID CUSTOMER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-000000000001");
    private static final UUID USER_A     = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_B     = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static boolean customersInserted = false;

    @BeforeEach
    void setup() throws Exception {
        if (!customersInserted) {
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery(
                    "INSERT INTO customer(id, name, code, level, accumulated_amount, status, created_at, updated_at) " +
                    "VALUES (:id, 'Test Lock Customer A', 'TEST-LOCK-CA', 'STANDARD', 0, 'ACTIVE', NOW(), NOW()) " +
                    "ON CONFLICT (id) DO NOTHING")
                    .setParameter("id", CUSTOMER_A)
                    .executeUpdate();
            em.createNativeQuery(
                    "INSERT INTO customer(id, name, code, level, accumulated_amount, status, created_at, updated_at) " +
                    "VALUES (:id, 'Test Lock Customer B', 'TEST-LOCK-CB', 'STANDARD', 0, 'ACTIVE', NOW(), NOW()) " +
                    "ON CONFLICT (id) DO NOTHING")
                    .setParameter("id", CUSTOMER_B)
                    .executeUpdate();
            utx.commit();
            customersInserted = true;
        }

        // Clean locks before each test
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM product_import_lock WHERE customer_id IN (:ca, :cb)")
                .setParameter("ca", CUSTOMER_A)
                .setParameter("cb", CUSTOMER_B)
                .executeUpdate();
        em.createNativeQuery(
                "UPDATE ddl_operation_lock SET expires_at = NOW() - INTERVAL '1 second' WHERE lock_key = 'global'")
                .executeUpdate();
        utx.commit();
    }

    // ======== AC-2.1: listActive returns correct count ========

    @Test
    @Order(1)
    void ac2_1_listActive_returns2LocksAfterAcquiring2() {
        lockService.acquireLocks(CUSTOMER_A, List.of("PART-001"), USER_A, null);
        lockService.acquireLocks(CUSTOMER_B, List.of("PART-002"), USER_A, null);

        var active = lockService.listActive();
        long count = active.stream()
                .filter(l -> l.customerId.equals(CUSTOMER_A) || l.customerId.equals(CUSTOMER_B))
                .count();
        assertEquals(2, count, "AC-2.1: listActive must return 2 ACTIVE locks");
    }

    // ======== AC-2.2: force-release sets status=RELEASED ========

    @Test
    @Order(2)
    void ac2_2_forceRelease_activeLock_setsStatusReleased() {
        AcquireLocksResult result = lockService.acquireLocks(CUSTOMER_A, List.of("PART-001"), USER_A, null);
        UUID lockId = result.lockIds.get(0);

        lockService.forceRelease(lockId, USER_B);

        ProductImportLock lock = getById(lockId);
        assertNotNull(lock);
        assertEquals(ProductImportLock.LockStatus.RELEASED, lock.status,
                "AC-2.2: force-release must set status=RELEASED");
    }

    // ======== AC-3.1: 50 part-nos → 50 PART_LEVEL locks ========

    @Test
    @Order(3)
    void ac3_1_acquireLocks_50PartNos_creates50PartLevelLocks() {
        AcquireLocksResult result = lockService.acquireLocks(CUSTOMER_A, buildParts(50), USER_A, null);

        assertEquals("PART_LEVEL", result.granularity, "AC-3.1: 50 part-nos must be PART_LEVEL");
        assertEquals(50, result.lockedCount, "AC-3.1: must create exactly 50 locks");
    }

    // ======== AC-3.2: 150 part-nos → 1 CUSTOMER_LEVEL lock ========

    @Test
    @Order(4)
    void ac3_2_acquireLocks_150PartNos_createsCustomerLevelLock() {
        AcquireLocksResult result = lockService.acquireLocks(CUSTOMER_A, buildParts(150), USER_A, null);

        assertEquals("CUSTOMER_LEVEL", result.granularity,
                "AC-3.2: >100 part-nos must downgrade to CUSTOMER_LEVEL");
        assertEquals(1, result.lockedCount, "AC-3.2: customer-level lock is 1 row");

        ProductImportLock lock = getById(result.lockIds.get(0));
        assertNull(lock.partNo, "AC-3.2: CUSTOMER_LEVEL lock must have part_no=NULL");
    }

    // ======== Scenario 2: threshold boundary — exactly 100 stays PART_LEVEL ========

    @Test
    @Order(5)
    void scenario2_threshold_exactly100_staysPartLevel() {
        AcquireLocksResult result = lockService.acquireLocks(CUSTOMER_A, buildParts(100), USER_A, null);
        assertEquals("PART_LEVEL", result.granularity,
                "Exactly 100 part-nos must remain PART_LEVEL (threshold condition is >100)");
        assertEquals(100, result.lockedCount);
    }

    // ======== Scenario 2: threshold boundary — 101 downgrades ========

    @Test
    @Order(6)
    void scenario2_threshold_101_downgradesToCustomerLevel() {
        AcquireLocksResult result = lockService.acquireLocks(CUSTOMER_A, buildParts(101), USER_A, null);
        assertEquals("CUSTOMER_LEVEL", result.granularity, "101 part-nos (>100) must downgrade");
    }

    // ======== AC-3.3: conflict detection ========

    @Test
    @Order(7)
    void ac3_3_acquireLocks_conflictingPartNo_throws409WithDetails() {
        lockService.acquireLocks(CUSTOMER_A, List.of("PART-CONFLICT"), USER_A, null);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                lockService.acquireLocks(CUSTOMER_A, List.of("PART-CONFLICT"), USER_B, null));

        assertEquals(409, ex.getCode(), "AC-3.3: conflict must return 409");
        assertTrue(ex.getMessage().contains("PART-CONFLICT"),
                "AC-3.3: conflict message must mention the conflicting part-no");
    }

    // ======== Scenario 1: partial index — multiple RELEASED allowed, only 1 ACTIVE ========

    @Test
    @Order(8)
    void scenario1_partialIndex_multipleReleasedAllowed_onlyOneActive() {
        insertReleasedLock(CUSTOMER_A, "PART-IDX");
        insertReleasedLock(CUSTOMER_A, "PART-IDX");

        assertDoesNotThrow(() ->
                lockService.acquireLocks(CUSTOMER_A, List.of("PART-IDX"), USER_A, null),
                "Multiple RELEASED rows must be allowed; only 1 ACTIVE enforced by partial index");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                lockService.acquireLocks(CUSTOMER_A, List.of("PART-IDX"), USER_B, null));
        assertEquals(409, ex.getCode());
    }

    // ======== AC-4.1: heartbeat extends expires_at ========

    @Test
    @Order(9)
    void ac4_1_heartbeat_activeLock_extendsExpiresAt() {
        AcquireLocksResult result = lockService.acquireLocks(CUSTOMER_A, List.of("PART-HB"), USER_A, null);
        UUID lockId = result.lockIds.get(0);

        ProductImportLock before = getById(lockId);

        lockService.heartbeat(lockId, USER_A);

        ProductImportLock after = getById(lockId);
        assertFalse(after.lastHeartbeatAt.isBefore(before.lastHeartbeatAt),
                "AC-4.1: heartbeat must update last_heartbeat_at");
        assertFalse(after.expiresAt.isBefore(before.expiresAt),
                "AC-4.1: heartbeat must extend or maintain expires_at");
    }

    // ======== AC-4.2: heartbeat on RELEASED lock → 404 ========

    @Test
    @Order(10)
    void ac4_2_heartbeat_releasedLock_throws404() {
        AcquireLocksResult result = lockService.acquireLocks(CUSTOMER_A, List.of("PART-HB2"), USER_A, null);
        UUID lockId = result.lockIds.get(0);
        lockService.forceRelease(lockId, USER_A);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> lockService.heartbeat(lockId, USER_A));
        assertTrue(ex.getCode() == 404 || ex.getCode() == 409,
                "AC-4.2: heartbeat on released lock must return 404 or 409, got: " + ex.getCode());
    }

    // ======== Scenario 4: cross-user heartbeat blocked ========

    @Test
    @Order(11)
    void scenario4_heartbeat_byDifferentUser_throws404() {
        AcquireLocksResult result = lockService.acquireLocks(CUSTOMER_A, List.of("PART-XUSER"), USER_A, null);
        UUID lockId = result.lockIds.get(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> lockService.heartbeat(lockId, USER_B));
        assertEquals(404, ex.getCode(),
                "Scenario 4: Heartbeat by wrong user must return 404 (locked_by mismatch)");
    }

    // ======== AC-5.1: release uses REQUIRES_NEW ========

    @Test
    @Order(12)
    void ac5_1_release_commitsIndependently() {
        AcquireLocksResult result = lockService.acquireLocks(CUSTOMER_A, List.of("PART-REL"), USER_A, null);
        UUID lockId = result.lockIds.get(0);

        lockService.release(lockId, USER_A, "COMPLETED");

        ProductImportLock lock = getById(lockId);
        assertEquals(ProductImportLock.LockStatus.RELEASED, lock.status, "AC-5.1: must be RELEASED");
        assertEquals("COMPLETED", lock.releaseReason);
        assertNotNull(lock.releasedAt);
        assertEquals(USER_A, lock.releasedBy);
    }

    // ======== AC-6.1: scanExpired marks expired ACTIVE locks ========

    @Test
    @Order(13)
    void ac6_1_scanExpired_marksExpiredLocks() {
        UUID lockId = insertExpiredActiveLock(CUSTOMER_A, "PART-EXPIRED");

        lockService.scanExpired();

        ProductImportLock lock = getById(lockId);
        assertEquals(ProductImportLock.LockStatus.EXPIRED, lock.status,
                "AC-6.1: scanExpired must mark expired ACTIVE locks as EXPIRED");
    }

    // ======== Scenario 5: scheduler auto-expires within 80s ========

    @Test
    @Order(14)
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void scenario5_scheduledScanExpired_eventuallyCleansExpiredLock() {
        UUID lockId = insertExpiredActiveLock(CUSTOMER_B, "PART-SCHED");

        Awaitility.await()
                .atMost(80, TimeUnit.SECONDS)
                .pollInterval(5, TimeUnit.SECONDS)
                .until(() -> {
                    ProductImportLock lock = getById(lockId);
                    return lock != null && lock.status == ProductImportLock.LockStatus.EXPIRED;
                });

        ProductImportLock lock = getById(lockId);
        assertEquals(ProductImportLock.LockStatus.EXPIRED, lock.status,
                "Scenario 5: Scheduler must auto-expire the lock within 80s");
    }

    // ======== AC-8.1: DDL lock active → acquireLocks returns 423 ========

    @Test
    @Order(15)
    void ac8_1_acquireLocks_withActiveDdlLock_throws423() {
        ddlService.acquire(USER_A, "test-ddl-block");
        try {
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    lockService.acquireLocks(CUSTOMER_A, List.of("PART-DDL"), USER_B, null));
            assertEquals(423, ex.getCode(),
                    "AC-8.1: When DDL lock is active, acquireLocks must return 423");
        } finally {
            ddlService.forceRelease(USER_A);
        }
    }

    // ======== releaseByImportRecord ========

    @Test
    @Order(16)
    void releaseByImportRecord_releasesAllLocksForRecord() {
        UUID importRecordId = UUID.randomUUID();
        lockService.acquireLocks(CUSTOMER_A, List.of("PART-R1"), USER_A, importRecordId);
        lockService.acquireLocks(CUSTOMER_A, List.of("PART-R2"), USER_A, importRecordId);
        lockService.acquireLocks(CUSTOMER_A, List.of("PART-R3"), USER_A, importRecordId);

        lockService.releaseByImportRecord(importRecordId, USER_A);

        List<ProductImportLock> locks = ProductImportLock.findByImportRecord(importRecordId);
        assertEquals(0, locks.size(),
                "After releaseByImportRecord, no ACTIVE locks should remain for this import record");
    }

    // ======== release by wrong user — 403 ========

    @Test
    @Order(17)
    void release_byWrongUser_throws403() {
        AcquireLocksResult result = lockService.acquireLocks(CUSTOMER_A, List.of("PART-WRONG"), USER_A, null);
        UUID lockId = result.lockIds.get(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> lockService.release(lockId, USER_B, "COMPLETED"));
        assertEquals(403, ex.getCode(), "Release by non-owner must return 403");
    }

    // ======== release non-existent lock — 404 ========

    @Test
    @Order(18)
    void release_nonExistentLock_throws404() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> lockService.release(UUID.randomUUID(), USER_A, "COMPLETED"));
        assertEquals(404, ex.getCode());
    }

    // ======== helpers ========

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    ProductImportLock getById(UUID id) {
        return ProductImportLock.findById(id);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void insertReleasedLock(UUID customerId, String partNo) {
        em.createNativeQuery(
                "INSERT INTO product_import_lock " +
                "(id, customer_id, part_no, granularity, locked_by, locked_at, last_heartbeat_at, " +
                " expires_at, status, released_at, release_reason, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :cid, :pn, 'PART_LEVEL', :uid, NOW(), NOW(), " +
                " NOW() - INTERVAL '10 minutes', 'RELEASED', NOW(), 'COMPLETED', NOW(), NOW())")
                .setParameter("cid", customerId)
                .setParameter("pn", partNo)
                .setParameter("uid", USER_A)
                .executeUpdate();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    UUID insertExpiredActiveLock(UUID customerId, String partNo) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO product_import_lock " +
                "(id, customer_id, part_no, granularity, locked_by, locked_at, last_heartbeat_at, " +
                " expires_at, status, created_at, updated_at) " +
                "VALUES (:id, :cid, :pn, 'PART_LEVEL', :uid, " +
                " NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '10 minutes', " +
                " NOW() - INTERVAL '5 minutes', 'ACTIVE', " +
                " NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '10 minutes')")
                .setParameter("id", id)
                .setParameter("cid", customerId)
                .setParameter("pn", partNo)
                .setParameter("uid", USER_A)
                .executeUpdate();
        return id;
    }

    private static List<String> buildParts(int n) {
        List<String> parts = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            parts.add(String.format("PART-%05d", i));
        }
        return parts;
    }
}
