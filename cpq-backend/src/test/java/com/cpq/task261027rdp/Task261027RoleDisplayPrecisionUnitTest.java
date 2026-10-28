package com.cpq.task261027rdp;

import com.cpq.common.NumberFormatUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-261027 · S-UNIT（AC-3 / AC-6 / AC-9）。
 *
 * <p>断言只来自需求文档的 4/9 位、显式位数取较小值、HALF_UP、去尾零以及
 * 非计算数值不受角色上限影响这些公开契约。测试不启动 Quarkus、不连接数据库。
 */
@DisplayName("T261027_RDP_ · 后端角色显示精度纯单元契约")
class Task261027RoleDisplayPrecisionUnitTest {

    private static final int SALES_LIMIT = 4;
    private static final int OTHER_LIMIT = 9;

    @Test
    @DisplayName("T261027_RDP_UNIT_BE_01 / AC-3：显式 2/6 位与角色上限取较小值")
    void explicitDecimalsUseTheLowerRoleLimit() {
        BigDecimal value = new BigDecimal("1.2345678915");

        assertEquals("1.23", formatComputed(value, 2, SALES_LIMIT));
        assertEquals("1.23", formatComputed(value, 2, OTHER_LIMIT));
        assertEquals("1.2346", formatComputed(value, 6, SALES_LIMIT));
        assertEquals("1.234568", formatComputed(value, 6, OTHER_LIMIT));
    }

    @Test
    @DisplayName("T261027_RDP_UNIT_BE_02 / AC-6：销售上限保留 HALF_UP、尾零、负数和负零语义")
    void salesBoundaryKeepsHalfUpAndTrimSemantics() {
        assertEquals("12.5", formatComputed(new BigDecimal("12.500000000000"), null, SALES_LIMIT));
        assertEquals("1.2344", formatComputed(new BigDecimal("1.23444"), null, SALES_LIMIT));
        assertEquals("1.2345", formatComputed(new BigDecimal("1.23445"), null, SALES_LIMIT));
        assertEquals("-1.2344", formatComputed(new BigDecimal("-1.23444"), null, SALES_LIMIT));
        assertEquals("-1.2345", formatComputed(new BigDecimal("-1.23445"), null, SALES_LIMIT));
        assertEquals("0", formatComputed(new BigDecimal("-0.00001"), null, SALES_LIMIT));
    }

    @Test
    @DisplayName("T261027_RDP_UNIT_BE_03 / AC-9：非计算值忽略角色上限；省略上限仍为既有 9 位")
    void rawValuesIgnoreRoleLimitAndDefaultContractStaysNine() {
        BigDecimal raw = new BigDecimal("1.234567891500");

        for (String semantic : new String[]{"用户输入", "汇率", "税率", "利润快照", "基础资料取数"}) {
            assertEquals("1.2345678915", formatRaw(raw, null, SALES_LIMIT),
                    semantic + "不得被销售角色统一压到 4 位");
        }
        assertEquals("1.234567892", NumberFormatUtil.format(raw, null, true),
                "未提供角色上限的既有调用必须继续最多显示 9 位");
    }

    /**
     * D-1 只规定“通用格式化函数增加可选上限参数”，未规定 Java 参数名。
     * 这里仅按现有公开 format(BigDecimal,Integer,boolean) 的兼容扩展验证一个公开四参重载，
     * 不绑定任何报价服务、Resource 或私有实现类。
     */
    private static String formatComputed(BigDecimal value, Integer explicitDecimals, int roleLimit) {
        return invokeRoleAwareFormat(value, explicitDecimals, true, roleLimit);
    }

    private static String formatRaw(BigDecimal value, Integer explicitDecimals, int roleLimit) {
        return invokeRoleAwareFormat(value, explicitDecimals, false, roleLimit);
    }

    private static String invokeRoleAwareFormat(
            BigDecimal value,
            Integer explicitDecimals,
            boolean computed,
            int roleLimit) {
        Method method = Arrays.stream(NumberFormatUtil.class.getMethods())
                .filter(candidate -> candidate.getName().equals("format"))
                .filter(candidate -> Modifier.isStatic(candidate.getModifiers()))
                .filter(candidate -> candidate.getParameterCount() == 4)
                .filter(candidate -> candidate.getParameterTypes()[0] == BigDecimal.class)
                .filter(candidate -> candidate.getParameterTypes()[1] == Integer.class)
                .filter(candidate -> candidate.getParameterTypes()[2] == boolean.class
                        || candidate.getParameterTypes()[2] == Boolean.class)
                .filter(candidate -> candidate.getParameterTypes()[3] == int.class
                        || candidate.getParameterTypes()[3] == Integer.class)
                .findFirst()
                .orElse(null);

        assertNotNull(method,
                "D-1 要求通用格式化函数增加可选显示上限；应保留三参 API 并提供公开四参重载");
        assertTrue(Modifier.isPublic(method.getModifiers()), "角色上限重载必须可由输出边界调用");
        try {
            return (String) method.invoke(null, value, explicitDecimals, computed, roleLimit);
        } catch (IllegalAccessException e) {
            throw new AssertionError("角色上限格式化重载不可访问", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new AssertionError("角色上限格式化失败: " + cause, cause);
        }
    }
}
