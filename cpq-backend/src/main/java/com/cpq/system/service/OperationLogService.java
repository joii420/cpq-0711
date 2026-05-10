package com.cpq.system.service;

import com.cpq.system.entity.OperationLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.UUID;

@ApplicationScoped
public class OperationLogService {

    @Transactional
    public void log(UUID operatorId, String operationType, String targetType, UUID targetId, String summary) {
        OperationLog entry = new OperationLog();
        entry.operatorId = operatorId;
        entry.operationType = operationType;
        entry.targetType = targetType;
        entry.targetId = targetId;
        entry.summary = summary;
        entry.persist();
    }
}
