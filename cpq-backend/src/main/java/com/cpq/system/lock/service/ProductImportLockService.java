package com.cpq.system.lock.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.system.config.service.SystemConfigService;
import com.cpq.system.lock.dto.AcquireLocksResult;
import com.cpq.system.lock.dto.ProductImportLockDTO;
import com.cpq.system.lock.entity.ProductImportLock;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProductImportLockService {

    private static final Logger LOG = Logger.getLogger(ProductImportLockService.class);

    @Inject
    SystemConfigService configService;

    @Inject
    EntityManager em;

    /**
     * Acquire locks for an import operation.
     * Order: 1) check DDL lock, 2) determine granularity, 3) INSERT lock rows.
     */
    @Transactional
    public AcquireLocksResult acquireLocks(UUID customerId, List<String> partNos, UUID userId, UUID importRecordId) {
        // Step 1: Check DDL lock — SELECT FOR UPDATE on ddl_operation_lock
        List<?> ddlResult = em.createNativeQuery(
                "SELECT 1 FROM ddl_operation_lock WHERE lock_key = 'global' AND expires_at > NOW() FOR UPDATE SKIP LOCKED")
                .getResultList();
        if (!ddlResult.isEmpty()) {
            throw new BusinessException(423, "系统正在执行 DDL 操作，请稍后重试");
        }

        // Step 2: Determine granularity
        int threshold = (int) configService.getNumber("import.product_lock_downgrade_threshold");
        long timeoutSec = (long) configService.getNumber("import.product_lock_timeout_seconds");
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plusSeconds(timeoutSec);

        boolean useCustomerLevel = (partNos == null || partNos.isEmpty() || partNos.size() > threshold);

        AcquireLocksResult result = new AcquireLocksResult();
        List<UUID> lockIds = new ArrayList<>();

        if (useCustomerLevel) {
            // Customer-level lock: single row, part_no IS NULL
            ProductImportLock existing = ProductImportLock.findActiveCustomerLevel(customerId);
            if (existing != null) {
                throw new BusinessException(409,
                        "客户 " + customerId + " 已存在客户级导入锁（锁持有者: " + existing.lockedBy + "），请等待完成");
            }
            ProductImportLock lock = newLock(customerId, null,
                    ProductImportLock.Granularity.CUSTOMER_LEVEL, userId, importRecordId, expiresAt);
            try {
                lock.persist();
                em.flush();
            } catch (Exception e) {
                if (isUniqueViolation(e)) {
                    throw new BusinessException(409, "客户 " + customerId + " 已存在客户级导入锁，请等待完成");
                }
                throw e;
            }
            lockIds.add(lock.id);
            result.granularity = "CUSTOMER_LEVEL";
        } else {
            // Part-level locks: one row per partNo
            List<String> conflicts = new ArrayList<>();
            for (String partNo : partNos) {
                ProductImportLock existing = ProductImportLock.findActiveByCustomerAndPartNo(customerId, partNo);
                if (existing != null) {
                    conflicts.add(partNo + "(锁持有者: " + existing.lockedBy + ")");
                }
            }
            if (!conflicts.isEmpty()) {
                throw new BusinessException(409,
                        "以下料号已存在导入锁，请等待完成: " + String.join(", ", conflicts));
            }
            for (String partNo : partNos) {
                ProductImportLock lock = newLock(customerId, partNo,
                        ProductImportLock.Granularity.PART_LEVEL, userId, importRecordId, expiresAt);
                try {
                    lock.persist();
                    em.flush();
                } catch (Exception e) {
                    if (isUniqueViolation(e)) {
                        throw new BusinessException(409, "料号 " + partNo + " 并发冲突，请重试");
                    }
                    throw e;
                }
                lockIds.add(lock.id);
            }
            result.granularity = "PART_LEVEL";
        }

        result.lockIds = lockIds;
        result.lockedCount = lockIds.size();
        LOG.infof("Acquired %d %s lock(s) for customer %s by user %s",
                lockIds.size(), result.granularity, customerId, userId);
        return result;
    }

    /**
     * Heartbeat: extend the lock expiry to prevent timeout.
     */
    @Transactional
    public void heartbeat(UUID lockId, UUID userId) {
        long heartbeatSec = (long) configService.getNumber("import.product_lock_heartbeat_seconds");
        long timeoutSec = (long) configService.getNumber("import.product_lock_timeout_seconds");
        OffsetDateTime now = OffsetDateTime.now();

        int rows = em.createNativeQuery(
                "UPDATE product_import_lock " +
                "SET last_heartbeat_at = :now, expires_at = :expiresAt, updated_at = :now " +
                "WHERE id = :id AND locked_by = :userId AND status = 'ACTIVE'")
                .setParameter("now", now)
                .setParameter("expiresAt", now.plusSeconds(timeoutSec))
                .setParameter("id", lockId)
                .setParameter("userId", userId)
                .executeUpdate();

        if (rows == 0) {
            throw new BusinessException(404,
                    "锁不存在或已释放（id=" + lockId + "），或当前用户不是锁持有者");
        }
    }

    /**
     * Release a single lock.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void release(UUID lockId, UUID userId, String reason) {
        ProductImportLock lock = ProductImportLock.findById(lockId);
        if (lock == null || lock.status != ProductImportLock.LockStatus.ACTIVE) {
            throw new BusinessException(404, "锁不存在或已释放: " + lockId);
        }
        if (!lock.lockedBy.equals(userId)) {
            throw new BusinessException(403, "当前用户不是锁持有者，无法释放");
        }
        doRelease(lock, userId, reason);
    }

    /**
     * Release all active locks for an import record (called after import completes/fails).
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void releaseByImportRecord(UUID importRecordId, UUID userId) {
        List<ProductImportLock> locks = ProductImportLock.findByImportRecord(importRecordId);
        for (ProductImportLock lock : locks) {
            doRelease(lock, userId, "COMPLETED");
        }
        LOG.infof("Released %d lock(s) for import record %s", locks.size(), importRecordId);
    }

    /**
     * Force-release a lock (admin only — caller must verify role).
     */
    @Transactional
    public void forceRelease(UUID lockId, UUID adminUserId) {
        ProductImportLock lock = ProductImportLock.findById(lockId);
        if (lock == null) {
            throw new BusinessException(404, "锁不存在: " + lockId);
        }
        doRelease(lock, adminUserId, "ADMIN_FORCE");
        LOG.infof("Lock %s force-released by admin %s", lockId, adminUserId);
    }

    /**
     * Scheduled: scan expired locks and mark them EXPIRED (does not release business resources).
     * Runs every 60 seconds, skips if previous execution is still running.
     */
    @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    public void scanExpired() {
        int updated = em.createNativeQuery(
                "UPDATE product_import_lock SET status = 'EXPIRED', updated_at = NOW() " +
                "WHERE status = 'ACTIVE' AND expires_at < NOW()")
                .executeUpdate();
        if (updated > 0) {
            LOG.infof("scanExpired: marked %d lock(s) as EXPIRED", updated);
        }
    }

    /**
     * List all active locks.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<ProductImportLockDTO> listActive() {
        return ProductImportLock.listActive().stream()
                .map(ProductImportLockDTO::from)
                .collect(Collectors.toList());
    }

    // ---- private helpers ----

    private ProductImportLock newLock(UUID customerId, String partNo,
                                      ProductImportLock.Granularity granularity,
                                      UUID userId, UUID importRecordId, OffsetDateTime expiresAt) {
        ProductImportLock lock = new ProductImportLock();
        lock.customerId = customerId;
        lock.partNo = partNo;
        lock.granularity = granularity;
        lock.lockedBy = userId;
        lock.importRecordId = importRecordId;
        lock.expiresAt = expiresAt;
        lock.status = ProductImportLock.LockStatus.ACTIVE;
        lock.createdBy = userId;
        lock.updatedBy = userId;
        return lock;
    }

    private void doRelease(ProductImportLock lock, UUID releasedBy, String reason) {
        lock.status = ProductImportLock.LockStatus.RELEASED;
        lock.releasedAt = OffsetDateTime.now();
        lock.releasedBy = releasedBy;
        lock.releaseReason = reason;
        lock.updatedBy = releasedBy;
    }

    private boolean isUniqueViolation(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException cve) {
                String msg = cve.getMessage();
                return msg != null && (msg.contains("uq_pil_active") || msg.contains("unique") || msg.contains("duplicate"));
            }
            cause = cause.getCause();
        }
        return false;
    }
}
