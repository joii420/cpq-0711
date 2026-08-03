package com.cpq.priceadjust.exception;

import com.cpq.common.exception.BusinessException;

import java.util.List;
import java.util.Map;

/**
 * api.md §0.3 · 409 REVIEW_BUDGET_NOT_READY / REVIEW_STATUS_CHANGED —— approve/reject 批量校验
 * 失败，携带不合格项清单（reviewId → 原因），供前端刷新列表并提示。
 */
public class ReviewNotReadyException extends BusinessException {

    private final String errorCode;
    private final List<Map<String, Object>> invalidItems;

    public ReviewNotReadyException(String errorCode, String message, List<Map<String, Object>> invalidItems) {
        super(409, message);
        this.errorCode = errorCode;
        this.invalidItems = invalidItems;
    }

    public String getErrorCode() { return errorCode; }
    public List<Map<String, Object>> getInvalidItems() { return invalidItems; }
}
