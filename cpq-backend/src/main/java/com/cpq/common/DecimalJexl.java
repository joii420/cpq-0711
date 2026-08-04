package com.cpq.common;

import org.apache.commons.jexl3.JexlArithmetic;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;

/**
 * task-0801 公式计算精度优化 — 统一 JEXL 引擎工厂。
 *
 * <p><b>风险 R-3</b>：JEXL 默认把数字字面量解析成 {@code Double}，仅设置
 * {@link JexlArithmetic} 的 {@link java.math.MathContext} <b>不生效</b>——必须<b>同时</b>做两件事：
 * <ol>
 *   <li>① 引擎配 BigDecimal 算术（本类：{@link JexlArithmetic#JexlArithmetic(boolean, java.math.MathContext, int)}）；</li>
 *   <li>② 调用方在拼接表达式字符串时，给数字字面量追加 {@code "B"} 后缀
 *       （JEXL BigDecimal 字面量语法，如 {@code "0.1B+0.2B"}），否则字面量仍按
 *       {@code Double} 解析，两个 Double 相加会得 {@code 0.30000000000000004}。</li>
 * </ol>
 *
 * <p>commons-jexl3 版本已确认为 3.3，{@code JexlArithmetic(boolean, MathContext, int)}
 * 构造器与 {@code B} 后缀字面量语法均可用。
 */
public final class DecimalJexl {

    private DecimalJexl() {
    }

    /**
     * 新建一个按 {@link PrecisionPolicy} 统一配置（{@link PrecisionPolicy#MC} +
     * {@link PrecisionPolicy#DIVISION_SCALE}）的 JEXL 引擎：{@code strict=false}
     * （null 参与运算按 0，语义与既有引擎一致）、{@code silent=true}、{@code cache(512)}。
     */
    public static JexlEngine newEngine() {
        return new JexlBuilder()
                .strict(false)
                .silent(true)
                .cache(512)
                .arithmetic(new JexlArithmetic(false, PrecisionPolicy.MC, PrecisionPolicy.DIVISION_SCALE))
                .create();
    }
}
