package com.cpq.formula.function.business;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * PREMIUM_PRICE(element, customer_id) — 仅升水价。
 *
 * <p>v5.1 §3.2 元素价格函数。v1 不可用，v2 启用。
 * （v5.1 §3.3 TECH-3 决策）
 */
@ApplicationScoped
public class PremiumPriceFunction implements FormulaFunction {

    @Override
    public String name() {
        return "PREMIUM_PRICE";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        throw new UnsupportedOperationException(
            "PREMIUM_PRICE v2 启用。v1 不抓取元素价格，此函数不可用。" +
            "（v5.1 §3.3 TECH-3 决策）");
    }
}
