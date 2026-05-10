package com.cpq.system.lock.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ddl_operation_lock")
public class DdlOperationLock extends PanacheEntityBase {

    public static final String GLOBAL_KEY = "global";

    @Id
    @Column(name = "lock_key", length = 64)
    public String lockKey;

    @Column(name = "locked_by", nullable = false)
    public UUID lockedBy;

    @Column(name = "locked_at", nullable = false)
    public OffsetDateTime lockedAt;

    @Column(name = "expires_at", nullable = false)
    public OffsetDateTime expiresAt;

    @Column(name = "operation_desc", columnDefinition = "TEXT")
    public String operationDesc;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "updated_by")
    public UUID updatedBy;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (lockedAt == null) lockedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // ---- static finders ----

    public static DdlOperationLock findGlobal() {
        return findById(GLOBAL_KEY);
    }

    public static boolean isGlobalLocked() {
        DdlOperationLock lock = findGlobal();
        if (lock == null) return false;
        return lock.expiresAt.isAfter(OffsetDateTime.now());
    }
}
