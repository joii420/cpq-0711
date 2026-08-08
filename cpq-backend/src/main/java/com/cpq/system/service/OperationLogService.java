package com.cpq.system.service;

import com.cpq.system.entity.OperationLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class OperationLogService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Transactional
    public UUID log(UUID operatorId, String operationType, String targetType, UUID targetId, String summary) {
        return log(operatorId, operationType, targetType, targetId, summary, null);
    }

    /**
     * task-0806：带结构化 {@code details}（改前改后 diff）的审计写入，供 admin 后门
     * （ConfigCenterResource.refreshAllSnapshots / TemplateResource 的 delete-tcs /
     * promote-override-to-component）在 {@code confirm=true} 时调用。审计与写入必须
     * 同生共死——本方法与业务写入共享调用方的事务，任一失败全回滚。
     *
     * @return 新建审计行的 id
     */
    @Transactional
    public UUID log(UUID operatorId, String operationType, String targetType, UUID targetId, String summary,
                     Map<String, Object> details) {
        OperationLog entry = new OperationLog();
        entry.operatorId = operatorId;
        entry.operationType = operationType;
        entry.targetType = targetType;
        entry.targetId = targetId;
        entry.summary = summary;
        if (details != null) {
            try {
                entry.details = MAPPER.writeValueAsString(details);
            } catch (Exception e) {
                entry.details = null;
            }
        }
        entry.persist();
        return entry.id;
    }

    /**
     * task-0806：批量审计——A5/A7 后门一次操作影响多个模板时，按 target_id 各写一行
     * （低频运维路径，行数=受影响模板数，通常个位数，不受 CLAUDE.md N+1 铁律约束——
     * 那条铁律管的是随数据量线性增长的热路径查询，不是"故意给每个受影响实体各留一条审计痕迹"）。
     *
     * @return 每个 targetId 对应新建的审计行 id（保持入参顺序）
     */
    @Transactional
    public List<UUID> logBatch(UUID operatorId, String operationType, String targetType,
                                Collection<UUID> targetIds, String summary, Map<String, Object> details) {
        List<UUID> ids = new ArrayList<>();
        if (targetIds == null || targetIds.isEmpty()) {
            ids.add(log(operatorId, operationType, targetType, null, summary, details));
            return ids;
        }
        for (UUID targetId : targetIds) {
            ids.add(log(operatorId, operationType, targetType, targetId, summary, details));
        }
        return ids;
    }
}
