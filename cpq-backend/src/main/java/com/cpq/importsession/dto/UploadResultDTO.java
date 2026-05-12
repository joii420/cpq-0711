package com.cpq.importsession.dto;

import java.util.UUID;

/**
 * POST /upload 端点响应 DTO。
 * 包含新建的 sessionId 和差异检测结果（供 Step 2 版本确认界面渲染）。
 */
public class UploadResultDTO {

    /** 新建的 import_session.id，前端整个 Step 2/3 流程都携带此 ID */
    public UUID sessionId;

    /** 差异检测结果（版本差异 + 客户冲突 + 孤儿行 + 校验概况） */
    public DiffPayloadDTO diffPayload;
}
