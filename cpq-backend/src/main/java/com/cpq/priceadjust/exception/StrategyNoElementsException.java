package com.cpq.priceadjust.exception;

import com.cpq.common.exception.BusinessException;

/**
 * api.md §0.3 · 400 STRATEGY_NO_ELEMENTS —— 参与调价元素为空，或勾选元素全部既无本期价也无
 * 历史价，不能生成版本（E14-10）。
 */
public class StrategyNoElementsException extends BusinessException {
    public StrategyNoElementsException(String customerNo) {
        super(400, "客户 " + customerNo + " 的调价策略参与元素为空，或勾选元素全部既无本期价也无历史价，不生成版本");
    }
}
