package com.cpq.quotation.dto;

import java.time.Instant;
import java.util.List;

/**
 * task-0806 阶段① API-5 请求体：{@code POST .../line-items/{lineItemId}/reconcile-report}。
 * fire-and-forget，前端不阻塞、不处理响应体（D1：只记录，服务端不据此改任何数据）。
 */
public class ReconcileReportRequest {
    public Instant reconciledAt;
    public List<ReconcileDiffEntry> diffs;
}
