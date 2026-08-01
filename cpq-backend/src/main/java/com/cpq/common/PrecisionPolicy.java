package com.cpq.common;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * task-0801 公式计算精度优化 — 精度策略单一来源。
 *
 * <p>把散落全工程的精度决策收敛到本类，杜绝"某处改了某处没改"。核心原则（详见
 * {@code dev-docs/task-0801-公式计算精度优化/需求说明.md} §4.3、{@code api.md} §1）：
 * <b>计算精度</b>与<b>呈现精度</b>分离 —— 计算过程中不做任何规整，只在四个边界
 * （落库 / API 返回 / 界面显示 / 导出）统一规整到 {@link #DISPLAY_SCALE} 位。
 *
 * <p><b>链路承载纪律</b>：
 * <ul>
 *   <li>链路一（公式内部，单元格级，金额 ≤10⁶）：单次运算/累加须十进制精确，跨层可继续用
 *       {@code Double}/{@code Map<String,Double>} 承载（余量充足，不强改承载类型）；</li>
 *   <li>链路二（产品小计 → ×年用量 → 行合计 → 整单总额 → 核价汇总，金额可达 10⁸~10⁹）：
 *       全程 {@link BigDecimal}，禁止任何 {@code .doubleValue()} 回落。</li>
 * </ul>
 */
public final class PrecisionPolicy {

    /** 呈现精度：落库 / API / 显示 / 导出四个边界统一规整到 6 位。 */
    public static final int DISPLAY_SCALE = 6;

    /** 除法中间精度：无限小数（如 1/3）的落点，远高于呈现精度以避免中间损失。 */
    public static final int DIVISION_SCALE = 12;

    /** 统一舍入方式。 */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** 计算用 MathContext（34 位有效数字，等价 DECIMAL128）。 */
    public static final MathContext MC = MathContext.DECIMAL128;

    private PrecisionPolicy() {
    }

    /** 规整到呈现精度（null 安全，null 原样返回）。计算过程中不得调用——只在呈现边界调用。 */
    public static BigDecimal round(BigDecimal v) {
        if (v == null) return null;
        return v.setScale(DISPLAY_SCALE, ROUNDING);
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
     * double → BigDecimal 的唯一入口：走 {@link BigDecimal#valueOf(double)}（最短十进制还原，
     * 与 {@code Double.toString(d)} 语义一致），绝不能用 {@code new BigDecimal(double)}
     * （后者会把 0.1 变成 0.1000000000000000055511151231257827…，误差当场引入）。
     */
    public static BigDecimal of(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return BigDecimal.ZERO;
        return BigDecimal.valueOf(d);
    }

    /**
     * Number / String / null 统一转换为 BigDecimal，无法解析一律返回 {@link BigDecimal#ZERO}
     * （不抛异常，与既有"缺值按 0 参与运算"语义一致，见 api.md G-8）。
     *
     * <p>已经是 {@link BigDecimal} 的直接返回（避免多余的字符串往返）；{@link Double}/{@link Float}
     * 走 {@link #of(double)} 的安全路径；其它 {@link Number}（Long/Integer/BigInteger 等）用
     * {@code toString()} 精确转换（整数类型无精度损失）；{@link String} trim 后直接 parse。
     */
    public static BigDecimal of(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Double d) return of(d.doubleValue());
        if (v instanceof Float f) return of(f.doubleValue());
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
