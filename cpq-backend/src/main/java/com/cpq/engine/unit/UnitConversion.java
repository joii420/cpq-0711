package com.cpq.engine.unit;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 单位换算预设表（硬编码）。把列原值按同行单位文本归一到 KG / PCS。
 * 前端镜像见 cpq-frontend/src/utils/unitConversion.ts，两端由对拍测试守一致。
 * 设计见 docs/superpowers/specs/2026-06-15-unit-conversion-design.md §3。
 */
public final class UnitConversion {

    private UnitConversion() {}

    /** 归一化后的单位 token → 对 C 原值的系数。 */
    private static final Map<String, BigDecimal> FACTORS = Map.ofEntries(
        Map.entry("克", new BigDecimal("0.001")),
        Map.entry("G", new BigDecimal("0.001")),
        Map.entry("千克", BigDecimal.ONE),
        Map.entry("KG", BigDecimal.ONE),
        Map.entry("吨", new BigDecimal("1000")),
        Map.entry("T", new BigDecimal("1000")),
        Map.entry("片", BigDecimal.ONE),
        Map.entry("PCS", BigDecimal.ONE),
        Map.entry("KPCS", new BigDecimal("1000")),
        Map.entry("千片", new BigDecimal("1000")),
        Map.entry("G/PCS", new BigDecimal("0.001"))
    );

    /** 归一化：trim → 去所有内部空格 → 转大写（中文别名原样保留，已在表中）。 */
    static String normalize(String unitText) {
        if (unitText == null) return "";
        String s = unitText.trim().replaceAll("\\s+", "");
        return s.toUpperCase();
    }

    /** 单位 → 系数；未知 / 空 → 1（原值透传）。 */
    public static BigDecimal factorFor(String unitText) {
        String key = normalize(unitText);
        if (key.isEmpty()) return BigDecimal.ONE;
        return FACTORS.getOrDefault(key, BigDecimal.ONE);
    }
}
