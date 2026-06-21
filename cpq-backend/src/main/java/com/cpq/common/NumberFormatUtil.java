package com.cpq.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 统一数字显示/导出格式化工具（与前端 formatNumber 同口径）。
 *
 * <p>规则：HALF_UP；"至多 N 位小数" → 去掉末尾 0（0.10→"0.1"，5.00→"5"）。
 * 显式位数优先；否则计算列（isComputed）兜底 2 位；否则原始/取数列保留原精度（汇率 6.9755 保留）。
 *
 * <p>仅用于显示/导出格式化，内部计算精度（engine 4dp）不受影响。
 */
public final class NumberFormatUtil {

    private static final int COMPUTED_FALLBACK = 2;

    private NumberFormatUtil() {
    }

    /**
     * @param value      数值（null → 返回空串）
     * @param decimals   显式位数（null=未配）
     * @param isComputed 计算列（未配兜底 2 位，输入/取数列保留原精度）
     * @return 已格式化字符串（去末尾 0；0 → "0"；无科学计数法）
     */
    public static String format(BigDecimal value, Integer decimals, boolean isComputed) {
        if (value == null) return "";
        Integer d = decimals != null ? decimals : (isComputed ? COMPUTED_FALLBACK : null);
        BigDecimal r = (d == null) ? value : value.setScale(d, RoundingMode.HALF_UP);
        r = r.stripTrailingZeros();
        if (r.compareTo(BigDecimal.ZERO) == 0) return "0";
        return r.toPlainString();
    }
}
