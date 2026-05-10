package com.cpq.system.lock.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public class DdlLockStatusDTO {

    public boolean locked;
    public UUID lockedBy;
    public OffsetDateTime lockedAt;
    public OffsetDateTime expiresAt;
    public String operationDesc;
}
