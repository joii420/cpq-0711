package com.cpq.system.lock.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.system.config.service.SystemConfigService;
import com.cpq.system.lock.dto.DdlLockStatusDTO;
import com.cpq.system.lock.entity.DdlOperationLock;
import com.cpq.system.lock.entity.ProductImportLock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.UUID;

@ApplicationScoped
public class DdlOperationLockService {

    private static final Logger LOG = Logger.getLogger(DdlOperationLockService.class);

    @Inject
    SystemConfigService configService;

    @Inject
    EntityManager em;

    /**
     * Acquire the global DDL lock.
     * First checks whether any active import locks exist (FOR UPDATE SKIP LOCKED),
     * then UPSERTs the ddl_operation_lock row.
     */
    @Transactional
    public void acquire(UUID userId, String operationDesc) {
        // Step 1: Check no active product import locks
        long activeImports = ProductImportLock.countAllActive();
        if (activeImports > 0) {
            throw new BusinessException(423,
                    "存在 " + activeImports + " 个进行中的导入锁，无法执行 DDL 操作，请等待导入完成");
        }

        // Step 2: UPSERT ddl_operation_lock
        long timeoutSec = (long) configService.getNumber("import.ddl_lock_timeout_seconds");
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plusSeconds(timeoutSec);

        // Use native UPSERT to handle concurrent acquires safely
        int rows = em.createNativeQuery(
                "INSERT INTO ddl_operation_lock(lock_key, locked_by, locked_at, expires_at, operation_desc, created_at, updated_at) " +
                "VALUES ('global', :userId, :now, :expiresAt, :desc, :now, :now) " +
                "ON CONFLICT (lock_key) DO UPDATE " +
                "  SET locked_by = EXCLUDED.locked_by, " +
                "      locked_at = EXCLUDED.locked_at, " +
                "      expires_at = EXCLUDED.expires_at, " +
                "      operation_desc = EXCLUDED.operation_desc, " +
                "      updated_at = EXCLUDED.updated_at " +
                "  WHERE ddl_operation_lock.expires_at <= NOW()")
                .setParameter("userId", userId)
                .setParameter("now", now)
                .setParameter("expiresAt", expiresAt)
                .setParameter("desc", operationDesc)
                .executeUpdate();

        if (rows == 0) {
            // UPSERT was a no-op: lock is held by someone else and still valid
            DdlOperationLock existing = DdlOperationLock.findGlobal();
            throw new BusinessException(409,
                    "DDL 全局锁已被占用（操作: " + (existing != null ? existing.operationDesc : "unknown") +
                    "，过期时间: " + (existing != null ? existing.expiresAt : "unknown") + "）");
        }

        LOG.infof("DDL lock acquired by %s, expires at %s", userId, expiresAt);
    }

    /**
     * Release the DDL lock by setting expires_at to past.
     */
    @Transactional
    public void release(UUID userId) {
        int rows = em.createNativeQuery(
                "UPDATE ddl_operation_lock SET expires_at = NOW() - INTERVAL '1 second', updated_at = NOW() " +
                "WHERE lock_key = 'global' AND locked_by = :userId AND expires_at > NOW()")
                .setParameter("userId", userId)
                .executeUpdate();

        if (rows == 0) {
            LOG.warnf("DDL lock release by %s had no effect (not locked by this user or already expired)", userId);
        } else {
            LOG.infof("DDL lock released by %s", userId);
        }
        em.clear();
    }

    /**
     * Force-release (admin only — caller must verify SYSTEM_ADMIN role).
     */
    @Transactional
    public void forceRelease(UUID adminUserId) {
        em.createNativeQuery(
                "UPDATE ddl_operation_lock SET expires_at = NOW() - INTERVAL '1 second', updated_at = NOW() " +
                "WHERE lock_key = 'global'")
                .executeUpdate();
        em.clear();
        LOG.infof("DDL lock force-released by admin %s", adminUserId);
    }

    /**
     * Returns current DDL lock status.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public DdlLockStatusDTO status() {
        DdlOperationLock lock = DdlOperationLock.findGlobal();
        DdlLockStatusDTO dto = new DdlLockStatusDTO();
        if (lock == null) {
            dto.locked = false;
            return dto;
        }
        dto.locked = lock.expiresAt.isAfter(OffsetDateTime.now());
        dto.lockedBy = lock.lockedBy;
        dto.lockedAt = lock.lockedAt;
        dto.expiresAt = lock.expiresAt;
        dto.operationDesc = lock.operationDesc;
        return dto;
    }
}
