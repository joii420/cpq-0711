package com.cpq.priceadjust.exception;

import com.cpq.common.exception.BusinessException;

import java.util.List;

/**
 * api.md §1.6 · 409 UNSELECT_NEEDS_CONFIRM —— 参与调价元素矩阵保存时有元素被取消勾选，
 * 未带 {@code confirmUnselect=true} 时抛出，携带影响面供前端二次确认弹窗。
 */
public class ElementUnselectNeedsConfirmException extends BusinessException {

    private final List<String> removedElementCodes;
    private final long unlockedQuotationCount;

    public ElementUnselectNeedsConfirmException(List<String> removedElementCodes, long unlockedQuotationCount) {
        super(409, "有 " + removedElementCodes.size() + " 个元素将被取消勾选，需二次确认");
        this.removedElementCodes = removedElementCodes;
        this.unlockedQuotationCount = unlockedQuotationCount;
    }

    public List<String> getRemovedElementCodes() { return removedElementCodes; }
    public long getUnlockedQuotationCount() { return unlockedQuotationCount; }
}
