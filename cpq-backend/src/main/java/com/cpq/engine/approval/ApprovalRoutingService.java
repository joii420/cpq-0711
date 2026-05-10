package com.cpq.engine.approval;

import java.util.UUID;

public interface ApprovalRoutingService {
    UUID routeApprover(UUID salesRepRegionId, UUID salesRepDepartmentId);
}
