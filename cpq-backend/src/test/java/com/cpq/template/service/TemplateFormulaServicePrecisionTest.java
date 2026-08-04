package com.cpq.template.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0801 公式计算精度优化 — B8 T2（求值点 #3 — TemplateFormulaService {@code rowJexl}）。
 *
 * <p>{@code evalRowExpression} / {@code toNumericLiteral} 均为私有方法，且本服务无参构造可用
 * （{@code new TemplateFormulaService()}，见类内注释"可由 new TemplateFormulaService() 直接测试"），
 * 反射调用不依赖 DataLoader / GlobalVariableService，纯粹验证 rowJexl 引擎 + 字面量拼接的
 * 十进制精度修复（R-3）。
 */
class TemplateFormulaServicePrecisionTest {

    private final TemplateFormulaService svc = new TemplateFormulaService();

    private Object invokePrivate(String name, Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = TemplateFormulaService.class.getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        return m.invoke(svc, args);
    }

    /**
     * 0.1+0.2 必须十进制精确 = 0.3。走真实生产路径：evalRowExpression 把 row 字段值通过
     * toJexlValue（已是 BigDecimal 安全转换）绑定为 rowJexl 变量，再由本次修复后的
     * rowJexl（3 参构造器配 PrecisionPolicy.MC/DIVISION_SCALE）求值。
     */
    @Test
    void t0801_decimalPrecision_pointOnePlusPointTwo() throws Exception {
        Map<String, Object> row = Map.of("a", new BigDecimal("0.1"), "b", new BigDecimal("0.2"));
        Object result = invokePrivate("evalRowExpression",
                new Class<?>[]{String.class, Map.class}, "a+b", row);
        assertTrue(result instanceof BigDecimal, "结果应为 BigDecimal，实际=" + result);
        assertEquals(0, new BigDecimal("0.3").compareTo((BigDecimal) result),
                "0.1+0.2 必须精确等于 0.3，实际=" + result);
    }

    /** toNumericLiteral 必须给数字字面量追加 "B" 后缀（R-3 的另一个必要条件）。 */
    @Test
    void t0801_toNumericLiteral_appendsBSuffix() throws Exception {
        Object literal = invokePrivate("toNumericLiteral", new Class<?>[]{Object.class}, new BigDecimal("0.1"));
        assertEquals("0.1B", literal);

        Object nullLiteral = invokePrivate("toNumericLiteral", new Class<?>[]{Object.class}, new Object[]{null});
        assertEquals("0B", nullLiteral);
    }
}
