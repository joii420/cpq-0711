package com.cpq.priceadjust.exception;

import com.cpq.common.exception.BusinessException;

/**
 * api.md §0.3 · 409 PENDING_VERSION_EXISTS —— 已有待处理版本，需 confirmSupersede=true（E14-9）。
 * 携带影响面（pendingVersionNo/pendingReviewCount/approvedReviewCount），供前端弹二次确认。
 */
public class PendingVersionExistsException extends BusinessException {

    private final String pendingVersionNo;
    private final long pendingReviewCount;
    private final long approvedReviewCount;

    public PendingVersionExistsException(String pendingVersionNo, long pendingReviewCount, long approvedReviewCount) {
        super(409, "已有待处理版本 " + pendingVersionNo + "，需二次确认后作废重建");
        this.pendingVersionNo = pendingVersionNo;
        this.pendingReviewCount = pendingReviewCount;
        this.approvedReviewCount = approvedReviewCount;
    }

    public String getPendingVersionNo() { return pendingVersionNo; }
    public long getPendingReviewCount() { return pendingReviewCount; }
    public long getApprovedReviewCount() { return approvedReviewCount; }
}
