package com.cpq.formula.function.business;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * ELEMENT_PRICE(element, customer_id?, date?) — 取元素实际单价。
 *
 * <p>v5.1 §3.2 元素价格函数。
 * v1 决策（v5.1 §3.3）：元素单价由销售在报价单内手填（row_data），
 * 不读 element_price / daily_price 表。v1 此函数不可用。
 *
 * <p>v2 路径：走 fetch_rule + element_price + element_daily_price（SUCCESS 行）。
 */
@ApplicationScoped
public class ElementPriceFunction implements FormulaFunction {

    @Override
    public String name() {
        return "ELEMENT_PRICE";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        throw new UnsupportedOperationException(
            "ELEMENT_PRICE v2 启用。v1 元素单价由销售在报价单 row_data 手填，不通过公式获取。" +
            "（v5.1 §3.3 TECH-3 决策）");
    }
}
