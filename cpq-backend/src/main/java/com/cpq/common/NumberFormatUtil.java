package com.cpq.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 统一数字显示/导出格式化工具（与前端 formatNumber 同口径）。
 *
 * <p>规则：HALF_UP；"至多 N 位小数" → 去掉末尾 0（0.10→"0.1"，5.00→"5"）。
 * 显式位数优先；否则计算列（isComputed）兜底 {@link PrecisionPolicy#DISPLAY_SCALE} 位；
 * 否则原始/取数列保留原精度（汇率 6.9755 保留）。
 *
 * <p><b>task-0801（2026-08-01）精度口径反转</b>：本类曾在 2026-06-21 采用「计算列/列小计/页签合计
 * 兜底 4 位，仅最终产品小计+对外导出总额固定 2 位」的策略（见 docs/RECORD.md 2026-06-21 记录）。
 * 该策略已被 task-0801 推翻并作废——现统一口径：<b>所有计算类数值（含最终产品小计、导出总额）
 * 一律兜底 {@link PrecisionPolicy#DISPLAY_SCALE}（6）位</b>，不再区分"卡片小计固定 2 位"的例外。
 * 之所以不删除旧记录，是为了保留"为什么当初改成 4 位/2 位"的追溯链，避免后人当 bug 改回。
 *
 * <p>仅用于显示/导出格式化，内部计算精度（engine 全精度 BigDecimal，不做中间截断）不受影响。
 */
public final class NumberFormatUtil {

    // ⚠️ 与前端 formatNumber.COMPUTED_FALLBACK + ExcelViewService.COMPUTED_FALLBACK_DECIMALS 保持同步；
    // 单一来源 PrecisionPolicy.DISPLAY_SCALE，不再自持字面量。
    private static final int COMPUTED_FALLBACK = PrecisionPolicy.DISPLAY_SCALE;

    private NumberFormatUtil() {
    }

    /**
     * @param value      数值（null → 返回空串）
     * @param decimals   显式位数（null=未配）
     * @param isComputed 计算列（未配兜底 4 位，输入/取数列保留原精度）
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
