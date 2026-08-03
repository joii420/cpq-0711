package com.cpq.priceadjust.exception;

import com.cpq.common.exception.BusinessException;

import java.util.List;

/**
 * api.md §1.4 · 409 REMOVAL_NEEDS_CONFIRM —— 指定料号矩阵保存时有料号被移出范围，
 * 未带 {@code confirmRemoval=true} 时抛出，携带影响面供前端二次确认弹窗。
 */
public class MaterialRemovalNeedsConfirmException extends BusinessException {

    private final List<String> removedMaterialNos;
    private final long pendingReviewCount;
    private final long unlockedQuotationCount;

    public MaterialRemovalNeedsConfirmException(List<String> removedMaterialNos, long pendingReviewCount, long unlockedQuotationCount) {
        super(409, "有 " + removedMaterialNos.size() + " 个料号将被移出范围，需二次确认");
        this.removedMaterialNos = removedMaterialNos;
        this.pendingReviewCount = pendingReviewCount;
        this.unlockedQuotationCount = unlockedQuotationCount;
    }

    public List<String> getRemovedMaterialNos() { return removedMaterialNos; }
    public long getPendingReviewCount() { return pendingReviewCount; }
    public long getUnlockedQuotationCount() { return unlockedQuotationCount; }
}
