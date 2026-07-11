package com.cpq.basicdata.v6.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 把 Excel 解析出的高精度 BigDecimal 归一到 DB numeric 列的 scale,
 * 消除"全精度 vs 存储截断"导致的版本比对误判(重导虚假升版)。null 安全。
 *
 * <p>背景见 tesk-0709 Task 11（P09/P10/P12 已修复）+ Task 11b（推广到其余核价 handler）：
 * Excel 解析出的 BigDecimal 常带完整精度（如除法结果 0.013333333333333334），写入 DB
 * {@code numeric(p,s)} 列时被 Postgres 静默按 scale 截断；{@code VersionedV6Writer.tally()}
 * 比对"本次新解析值(全精度)"与"重导时从库里 load 出来的 existing(已截断)"若未同等舍入，
 * 恒不相等 → 同一份文件重导也被误判"内容变化"而错误升版，违反 §7.4"重导不升版"不变量。
 */
public final class DecimalScale {
    private DecimalScale() {}

    /** 归一到指定 scale(HALF_UP)；null 原样返回。 */
    public static BigDecimal at(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
    }
}
