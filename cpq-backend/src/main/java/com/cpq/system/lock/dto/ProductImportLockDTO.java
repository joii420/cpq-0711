package com.cpq.system.lock.dto;

import com.cpq.system.lock.entity.ProductImportLock;

import java.time.OffsetDateTime;
import java.util.UUID;

public class ProductImportLockDTO {

    public UUID id;
    public UUID customerId;
    public String partNo;
    public String granularity;
    public UUID lockedBy;
    public UUID importRecordId;
    public OffsetDateTime lockedAt;
    public OffsetDateTime lastHeartbeatAt;
    public OffsetDateTime expiresAt;
    public String status;
    public OffsetDateTime releasedAt;
    public String releaseReason;

    public static ProductImportLockDTO from(ProductImportLock e) {
        ProductImportLockDTO dto = new ProductImportLockDTO();
        dto.id = e.id;
        dto.customerId = e.customerId;
        dto.partNo = e.partNo;
        dto.granularity = e.granularity.name();
        dto.lockedBy = e.lockedBy;
        dto.importRecordId = e.importRecordId;
        dto.lockedAt = e.lockedAt;
        dto.lastHeartbeatAt = e.lastHeartbeatAt;
        dto.expiresAt = e.expiresAt;
        dto.status = e.status.name();
        dto.releasedAt = e.releasedAt;
        dto.releaseReason = e.releaseReason;
        return dto;
    }
}
