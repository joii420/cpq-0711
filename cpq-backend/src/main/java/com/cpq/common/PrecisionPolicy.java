package com.cpq.common;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * task-0801 公式计算精度优化 — 精度策略单一来源。
 *
 * <p>把散落全工程的精度决策收敛到本类，杜绝"某处改了某处没改"。核心原则（详见
 * {@code dev-docs/task-0801-公式计算精度优化/需求说明.md} §4.3、{@code api.md} §1）：
 * <b>计算精度</b>与<b>呈现精度</b>分离。公式节点、跨节点缓存和持久化工作值统一保留
 * {@link #CALCULATION_SCALE} 位；UI、HTML、PDF 和 Excel 最多显示 {@link #DISPLAY_SCALE} 位。
 * 精度敏感链路只允许 {@link BigDecimal}，不得通过 {@code double}/{@code Double} 中转。
 */
public final class PrecisionPolicy {

    /** 公式节点、跨节点缓存和持久化工作值精度。 */
    public static final int CALCULATION_SCALE = 12;

    /** UI / HTML / PDF / Excel 的最大小数位数。 */
    public static final int DISPLAY_SCALE = 9;

    /** 单元格公式最终结果精度。未来由系统参数覆盖；当前值是默认值。 */
    public static final int FORMULA_RESULT_SCALE = 9;

    /** 产品卡片小计结果精度。未来由系统参数覆盖；当前值是默认值。 */
    public static final int PRODUCT_CARD_SUBTOTAL_SCALE = 9;

    /** 报价单总金额结果精度。未来由系统参数覆盖；当前值是默认值。 */
    public static final int QUOTATION_TOTAL_SCALE = 9;

    /** 除法中间精度：无限小数（如 1/3）的落点，远高于呈现精度以避免中间损失。 */
    public static final int DIVISION_SCALE = CALCULATION_SCALE;

    /** 统一舍入方式。 */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** 计算用 MathContext（34 位有效数字，等价 DECIMAL128）。 */
    public static final MathContext MC = MathContext.DECIMAL128;

    private PrecisionPolicy() {
    }

    /** 规整公式节点和持久化工作值（null 安全）。 */
    public static BigDecimal roundForCalculation(BigDecimal v) {
        if (v == null) return null;
        return v.setScale(CALCULATION_SCALE, ROUNDING);
    }

    /** 规整显示值（null 安全）。 */
    public static BigDecimal roundForDisplay(BigDecimal v) {
        if (v == null) return null;
        return v.setScale(DISPLAY_SCALE, ROUNDING);
    }

    public static BigDecimal roundFormulaResult(BigDecimal v) {
        return round(v, FORMULA_RESULT_SCALE);
    }

    public static BigDecimal roundProductCardSubtotal(BigDecimal v) {
        return round(v, PRODUCT_CARD_SUBTOTAL_SCALE);
    }

    public static BigDecimal roundQuotationTotal(BigDecimal v) {
        return round(v, QUOTATION_TOTAL_SCALE);
    }

    private static BigDecimal round(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, ROUNDING);
    }

    public static BigDecimal roundForResultScale(BigDecimal value, int scale) {
        if (scale < 0 || scale > CALCULATION_SCALE) {
            throw new IllegalArgumentException("Result scale must be between 0 and " + CALCULATION_SCALE);
        }
        return round(value, scale);
    }

    /**
     * 精确除法：除数为 0（或 null 操作数）→ 返回 {@link BigDecimal#ZERO}
     * （与既有 NaN/Infinite→0 语义一致，见 api.md G-9："除以 0 → 返回 0，不抛异常"）。
     * 中间精度取 {@link #DIVISION_SCALE} 位（12 位），避免无限小数（如 1/3）抛
     * {@code ArithmeticException}（风险 R-4）。
     */
    public static BigDecimal divide(BigDecimal a, BigDecimal b) {
        BigDecimal dividend = a != null ? a : BigDecimal.ZERO;
        if (b == null || b.signum() == 0) return BigDecimal.ZERO;
        return dividend.divide(b, DIVISION_SCALE, ROUNDING);
    }

    /**
     * Number / String / null 统一转换为 BigDecimal，无法解析一律返回 {@link BigDecimal#ZERO}
     * （不抛异常，与既有"缺值按 0 参与运算"语义一致，见 api.md G-8）。
     *
     * <p>已经是 {@link BigDecimal} 的直接返回；浮点类型被拒绝，防止业务层把已经损坏的二进制
     * 浮点值重新包装成十进制。整数类型和规范十进制字符串可精确转换。
     */
    public static BigDecimal of(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Double || v instanceof Float) {
            throw new IllegalArgumentException("Precision-sensitive values must not use floating point");
        }
        if (v instanceof Number n) {
            try {
                return new BigDecimal(n.toString());
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
        if (v instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) return BigDecimal.ZERO;
            try {
                return new BigDecimal(t);
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    /** 规范 decimal string：普通十进制、去尾零、零统一为 {@code "0"}。 */
    public static String toPlainDecimalString(BigDecimal value) {
        if (value == null) return null;
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    /** BigDecimal 精确累加（null 安全：null 元素按 0 处理，与既有"缺值按 0"语义一致）。 */
    public static BigDecimal sum(Iterable<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        if (values == null) return total;
        for (BigDecimal v : values) {
            if (v != null) total = total.add(v);
        }
        return total;
    }
}
