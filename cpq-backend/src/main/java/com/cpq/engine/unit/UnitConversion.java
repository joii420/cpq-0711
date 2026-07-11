package com.cpq.engine.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        Map.entry("G/PCS", new BigDecimal("0.001")),
        Map.entry("G/KPCS", new BigDecimal("0.000001"))
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

    /** 字段取值键：name 优先，回退 key（与各引擎 fieldName 口径一致）。 */
    private static String fieldKey(JsonNode f) {
        String n = f.path("name").asText(null);
        if (n != null && !n.isBlank()) return n;
        return f.path("key").asText(null);
    }

    /** 解析 (字段名 → 单位来源字段名) 仅含配了 unit_source_field 的列。 */
    private static Map<String, String> configuredColumns(JsonNode fields) {
        Map<String, String> m = new HashMap<>();
        if (fields == null || !fields.isArray()) return m;
        for (JsonNode f : fields) {
            // 与 FormulaCalculator 同款：camelCase 优先、snake_case 兜底（生产快照为 snake）。
            String usf = f.path("unitSourceField").asText(null);
            if (usf == null || usf.isBlank()) usf = f.path("unit_source_field").asText(null);
            if (usf == null || usf.isBlank()) continue;
            String c = fieldKey(f);
            if (c != null && !c.isBlank()) m.put(c, usf);
        }
        return m;
    }

    private static BigDecimal toBig(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(v.toString().trim()); } catch (Exception e) { return null; }
    }

    /**
     * 返回换算后新 Object 行（原 row 不变）。配换算列 C → rawC × factorFor(同行 D 文本)。
     * 用于 parseEffectiveRows / backfillSubtotalsFromResolved 等 Object 行。
     */
    public static Map<String, Object> convertObjectRow(JsonNode fields, Map<String, Object> row) {
        Map<String, String> cols = configuredColumns(fields);
        if (cols.isEmpty() || row == null) return row;
        Map<String, Object> out = new LinkedHashMap<>(row);
        for (Map.Entry<String, String> e : cols.entrySet()) {
            String c = e.getKey(), d = e.getValue();
            BigDecimal raw = toBig(row.get(c));
            if (raw == null) continue;
            Object dv = row.get(d);
            BigDecimal factor = factorFor(dv == null ? null : dv.toString());
            out.put(c, raw.multiply(factor));
        }
        return out;
    }

    /**
     * 返回换算后新 JsonNode 行（原 mergedRow 不变）。用于 FormulaCalculator.computeRows。
     */
    public static Map<String, JsonNode> convertNodeRow(JsonNode fields, Map<String, JsonNode> mergedRow) {
        Map<String, String> cols = configuredColumns(fields);
        if (cols.isEmpty() || mergedRow == null) return mergedRow;
        Map<String, JsonNode> out = new LinkedHashMap<>(mergedRow);
        for (Map.Entry<String, String> e : cols.entrySet()) {
            String c = e.getKey(), d = e.getValue();
            JsonNode cn = mergedRow.get(c);
            if (cn == null || cn.isNull()) continue;
            BigDecimal raw = toBig(cn.isNumber() ? cn.numberValue() : cn.asText());
            if (raw == null) continue;
            JsonNode dn = mergedRow.get(d);
            BigDecimal factor = factorFor(dn == null ? null : dn.asText());
            out.put(c, DecimalNode.valueOf(raw.multiply(factor)));
        }
        return out;
    }

    /**
     * 值解析后换算（与前端 computeAllFormulas "值解析后换算"段对称）：对配 unit_source_field 的列 C，
     * 用 currentRowRaw 里同行已解析的单位文本 D，把已解析的 fieldValues[C] 与 currentRowRaw[C] × 系数。
     * 就地修改（两者均为 per-row 局部 map，非共享渲染行）。供 FormulaCalculator.computeRows 在
     * collectFieldValues + fillInputDefaultSourceByFieldName 之后调用，覆盖 driver / data-source 列。
     */
    public static void convertResolvedRow(JsonNode fields,
                                          Map<String, Double> fieldValues,
                                          Map<String, Object> currentRowRaw) {
        Map<String, String> cols = configuredColumns(fields);
        if (cols.isEmpty()) return;
        for (Map.Entry<String, String> e : cols.entrySet()) {
            String c = e.getKey(), d = e.getValue();
            Object unitObj = currentRowRaw != null ? currentRowRaw.get(d) : null;
            BigDecimal factor = factorFor(unitObj == null ? null : unitObj.toString());
            if (factor.compareTo(BigDecimal.ONE) == 0) continue;
            if (fieldValues != null) {
                Double cv = fieldValues.get(c);
                if (cv != null) fieldValues.put(c, factor.multiply(BigDecimal.valueOf(cv)).doubleValue());
            }
            if (currentRowRaw != null) {
                BigDecimal raw = toBig(currentRowRaw.get(c));
                if (raw != null) currentRowRaw.put(c, factor.multiply(raw));
            }
        }
    }
}
