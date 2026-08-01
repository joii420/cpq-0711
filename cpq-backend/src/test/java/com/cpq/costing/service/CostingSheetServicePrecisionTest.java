package com.cpq.costing.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0801 公式计算精度优化 — B8 T2（求值点 #6 — CostingSheetService {@code evaluateInlineFormula}）。
 *
 * <p>{@code evaluateInlineFormula} 是私有方法，但自包含（只用 {@code COL_REF_PATTERN} +
 * {@code toDecimal} + {@code DecimalJexl.newEngine()}，不触达任何 @Inject 依赖），
 * 可用 {@code new CostingSheetService()} 直接反射测试，不需要 CDI 容器 / DataLoader。
 */
class CostingSheetServicePrecisionTest {

    private final CostingSheetService svc = new CostingSheetService();

    private Object invokeEvaluateInlineFormula(String formula, Map<String, Object> cellValues) throws Exception {
        Method m = CostingSheetService.class.getDeclaredMethod("evaluateInlineFormula", String.class, Map.class);
        m.setAccessible(true);
        return m.invoke(svc, formula, cellValues);
    }

    /**
     * 0.1+0.2 必须十进制精确 = 0.3。走真实生产路径：[A]/[B] 列引用替换为 "B" 后缀字面量，
     * 安全校验正则放行 B 后缀字符，DecimalJexl.newEngine() 求值。
     */
    @Test
    void t0801_decimalPrecision_pointOnePlusPointTwo() throws Exception {
        Map<String, Object> cellValues = Map.of("A", new BigDecimal("0.1"), "B", new BigDecimal("0.2"));
        Object result = invokeEvaluateInlineFormula("=[A]+[B]", cellValues);
        assertTrue(result instanceof BigDecimal, "结果应为 BigDecimal，实际=" + result);
        assertEquals(0, new BigDecimal("0.3").compareTo((BigDecimal) result),
                "0.1+0.2 必须精确等于 0.3，实际=" + result);
    }

    /** 安全校验正则必须放行 "B" 后缀（否则每条含字面量替换的公式都会被正则挡回 null）。 */
    @Test
    void t0801_safetyRegex_allowsBSuffix() throws Exception {
        Map<String, Object> cellValues = Map.of("X", new BigDecimal("5"));
        Object result = invokeEvaluateInlineFormula("=[X]*2", cellValues);
        assertTrue(result instanceof BigDecimal, "结果应为 BigDecimal（未被安全正则挡回 null），实际=" + result);
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) result));
    }
}
