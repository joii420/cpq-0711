package com.cpq.common.exception;

import com.cpq.quotation.service.reconcile.SubmitConflictDTO;

import java.util.List;

/**
 * task-0806 阶段① API-3：提交期对账闸门专用异常：HTTP 409 + {@code reason} + 结构化差异清单。
 * 契约见 dev-docs/task-0806-报价编辑链路优化与前后端对账/api.md §API-3。
 * 形态照既有先例 {@link RowKeyConflictException}。
 */
public class ReconcilePendingException extends BusinessException {

    /** {@code RECONCILE_PENDING} | {@code WRITE_IN_FLIGHT}，前端按此分支处理（api.md §API-3）。 */
    private final String reason;
    private final List<SubmitConflictDTO> conflicts;

    public ReconcilePendingException(String reason, String message, List<SubmitConflictDTO> conflicts) {
        super(409, message);
        this.reason = reason;
        this.conflicts = conflicts;
    }

    public String getReason() { return reason; }
    public List<SubmitConflictDTO> getConflicts() { return conflicts; }
}
