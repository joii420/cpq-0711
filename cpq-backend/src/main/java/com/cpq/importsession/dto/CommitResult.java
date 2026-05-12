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

    /**
     * 本次 commit 创建的 import_record.id（V6 写入 mat_process/fee/plating_fee 的 import_record_id）。
     * 前端跳转 /quotations/{id}/edit 时附带 ?importRecordId=... 参数，
     * 报价单 Step2 的 listCandidates 接口据此精确过滤"本次导入涉及的料号"，
     * 避免拉客户全部历史 mapping → 显示多余产品（参见 AP-23 / CustomerPartCandidateService 注释）。
     */
    public UUID importRecordId;

    public CommitResult() {}

    public CommitResult(UUID quotationId, UUID sessionId) {
        this.quotationId = quotationId;
        this.sessionId = sessionId;
    }

    public CommitResult(UUID quotationId, UUID sessionId, UUID importRecordId) {
        this.quotationId = quotationId;
        this.sessionId = sessionId;
        this.importRecordId = importRecordId;
    }
}
