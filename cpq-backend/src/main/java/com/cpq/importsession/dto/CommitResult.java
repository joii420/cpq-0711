package com.cpq.importsession.dto;

import java.util.UUID;

/**
 * POST /sessions/{id}/commit 响应体。
 * commit 成功后返回新建的报价单 ID，前端据此跳转至报价单编辑页。
 */
public class CommitResult {

    /** 新建的报价单 ID，前端用于跳转 /quotations/{quotationId}/edit */
    public UUID quotationId;

    /** commit 完成的 import_session.id（状态已变为 COMMITTED，保留作审计） */
    public UUID sessionId;

    public CommitResult() {}

    public CommitResult(UUID quotationId, UUID sessionId) {
        this.quotationId = quotationId;
        this.sessionId = sessionId;
    }
}
