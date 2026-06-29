package com.cpq.common.exception;

import com.cpq.quotation.service.rowkey.RowKeyConflictDTO;

import java.util.List;

/**
 * 提交期行键冲突专用异常：HTTP 422 + 结构化冲突列表（供前端定位）。
 * message 仍是原「行键重复，无法提交：…」文本，向后兼容旧的纯文本展示。
 */
public class RowKeyConflictException extends BusinessException {

    private final List<RowKeyConflictDTO> conflicts;

    public RowKeyConflictException(String message, List<RowKeyConflictDTO> conflicts) {
        super(422, message);
        this.conflicts = conflicts;
    }

    public List<RowKeyConflictDTO> getConflicts() {
        return conflicts;
    }
}
