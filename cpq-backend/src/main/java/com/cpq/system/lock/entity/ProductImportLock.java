package com.cpq.system.lock.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "product_import_lock")
public class ProductImportLock extends PanacheEntityBase {

    public enum Granularity {
        PART_LEVEL, CUSTOMER_LEVEL
    }

    public enum LockStatus {
        ACTIVE, RELEASED, EXPIRED
    }

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "customer_id", nullable = false)
    public UUID customerId;

    @Column(name = "part_no", length = 64)
    public String partNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "granularity", nullable = false, length = 16)
    public Granularity granularity;

    @Column(name = "locked_by", nullable = false)
    public UUID lockedBy;

    @Column(name = "import_record_id")
    public UUID importRecordId;

    @Column(name = "locked_at", nullable = false)
    public OffsetDateTime lockedAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    public OffsetDateTime lastHeartbeatAt;

    @Column(name = "expires_at", nullable = false)
    public OffsetDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    public LockStatus status = LockStatus.ACTIVE;

    @Column(name = "released_at")
    public OffsetDateTime releasedAt;

    @Column(name = "released_by")
    public UUID releasedBy;

    @Column(name = "release_reason", length = 32)
    public String releaseReason;

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
        if (lastHeartbeatAt == null) lastHeartbeatAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // ---- static finders ----

    public static List<ProductImportLock> listActive() {
        return list("status", LockStatus.ACTIVE);
    }

    public static List<ProductImportLock> findExpiredActive() {
        return list("status = ?1 AND expiresAt < ?2", LockStatus.ACTIVE, OffsetDateTime.now());
    }

    public static ProductImportLock findActiveByCustomerAndPartNo(UUID customerId, String partNo) {
        return find("customerId = ?1 AND partNo = ?2 AND status = ?3",
                customerId, partNo, LockStatus.ACTIVE).firstResult();
    }

    public static ProductImportLock findActiveCustomerLevel(UUID customerId) {
        return find("customerId = ?1 AND granularity = ?2 AND status = ?3",
                customerId, Granularity.CUSTOMER_LEVEL, LockStatus.ACTIVE).firstResult();
    }

    public static long countActiveByCustomer(UUID customerId) {
        return count("customerId = ?1 AND status = ?2", customerId, LockStatus.ACTIVE);
    }

    public static List<ProductImportLock> findByImportRecord(UUID importRecordId) {
        return list("importRecordId = ?1 AND status = ?2", importRecordId, LockStatus.ACTIVE);
    }

    public static long countAllActive() {
        return count("status", LockStatus.ACTIVE);
    }
}
