package com.cpq.engine.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy compatibility helper for callers that do not yet have a target-unit context.
 * New code must resolve a directed source → target factor from UnitService.
 * 前端镜像见 cpq-frontend/src/utils/unitConversion.ts，两端由对拍测试守一致。
 * 设计见 docs/superpowers/specs/2026-06-15-unit-conversion-design.md §3。
 */
public final class UnitConversion {

    private UnitConversion() {}

    /** 归一化后的单位 token → 对 C 原值的系数。 */
    private static final Map<String, BigDecimal> FACTORS = Map.ofEntries(
        Map.entry("G", new BigDecimal("0.001")),
        Map.entry("KG", BigDecimal.ONE),
        Map.entry("T", new BigDecimal("1000")),
        Map.entry("PCS", BigDecimal.ONE),
        // KPCS / 千片：按「每千片 → 每片」的分母口径 ÷1000（2026-07-28 业务修订，原为 ×1000）。
        Map.entry("KPCS", new BigDecimal("0.001"))
    );

    private static final Map<String, BigDecimal> DIRECTED = Map.ofEntries(
        Map.entry("EA\u0000PCS", new BigDecimal("1")), Map.entry("EA\u0000KPCS", new BigDecimal("0.001")),
        Map.entry("PCS\u0000EA", new BigDecimal("1")), Map.entry("PCS\u0000KPCS", new BigDecimal("0.001")),
        Map.entry("KPCS\u0000EA", new BigDecimal("1000")), Map.entry("KPCS\u0000PCS", new BigDecimal("1000")),
        Map.entry("G\u0000KG", new BigDecimal("0.001")), Map.entry("G\u0000T", new BigDecimal("0.000001")),
        Map.entry("G\u0000盎司", new BigDecimal("0.03527396194958")), Map.entry("KG\u0000G", new BigDecimal("1000")),
        Map.entry("KG\u0000T", new BigDecimal("0.001")), Map.entry("KG\u0000盎司", new BigDecimal("35.27396194958")),
        Map.entry("T\u0000G", new BigDecimal("1000000")), Map.entry("T\u0000KG", new BigDecimal("1000")),
        Map.entry("T\u0000盎司", new BigDecimal("35273.96194958")), Map.entry("盎司\u0000G", new BigDecimal("28.349523125")),
        Map.entry("盎司\u0000KG", new BigDecimal("0.028349523125")), Map.entry("盎司\u0000T", new BigDecimal("0.000028349523125"))
    );

    /** Runtime relation cache populated from unit_definition/unit_conversion_rate at startup and on writes. */
    private static final Map<String, BigDecimal> RUNTIME_DIRECTED = new ConcurrentHashMap<>(DIRECTED);

    public static void replaceSourceRelations(String sourceUnit, Map<String, BigDecimal> targets) {
        String source = normalize(sourceUnit);
        if (source.isEmpty()) return;
        RUNTIME_DIRECTED.keySet().removeIf(k -> k.startsWith(source + "\u0000"));
        if (targets != null) {
            for (Map.Entry<String, BigDecimal> e : targets.entrySet()) {
                String target = normalize(e.getKey());
                if (!target.isEmpty() && e.getValue() != null && e.getValue().signum() > 0) {
                    RUNTIME_DIRECTED.put(source + "\u0000" + target, e.getValue());
                }
            }
        }
    }

    public static void removeUnit(String unitText) {
        String unit = normalize(unitText);
        if (unit.isEmpty()) return;
        RUNTIME_DIRECTED.keySet().removeIf(k -> k.startsWith(unit + "\u0000") || k.endsWith("\u0000" + unit));
    }

    public static void clearRuntimeRelations() {
        RUNTIME_DIRECTED.clear();
    }

    /** 归一化：trim → 去所有内部空格 → 转大写（中文别名原样保留，已在表中）。 */
    static String normalize(String unitText) {
        if (unitText == null) return "";
        String s = unitText.trim().replaceAll("\\s+", "");
        return s.toUpperCase(Locale.ROOT);
    }

    /** 单位 → 系数；未知 / 空 → 1（原值透传）。 */
    public static BigDecimal factorFor(String unitText) {
        String key = normalize(unitText);
        if (key.isEmpty()) return BigDecimal.ONE;
        return FACTORS.getOrDefault(key, BigDecimal.ONE);
    }

    /** Apply an already-resolved directed factor without rounding. */
    public static BigDecimal convert(BigDecimal raw, BigDecimal directedFactor) {
        if (raw == null || directedFactor == null || directedFactor.signum() <= 0) return raw;
        return raw.multiply(directedFactor);
    }

    /** Built-in seed relation mirror used by offline callers and compatibility tests. */
    public static BigDecimal convert(BigDecimal raw, String sourceUnit, String targetUnit) {
        if (raw == null || sourceUnit == null || targetUnit == null) return raw;
        String source = normalize(sourceUnit), target = normalize(targetUnit);
        if (source.isEmpty() || target.isEmpty() || source.equals(target)) return raw;
        return convert(raw, RUNTIME_DIRECTED.get(source + "\u0000" + target));
    }

    /** Resolve through a caller-provided case-insensitive source→target map. */
    public static BigDecimal convert(BigDecimal raw, String sourceUnit, String targetUnit,
                                     Map<String, BigDecimal> directedFactors) {
        if (raw == null || sourceUnit == null || targetUnit == null) return raw;
        String source = normalize(sourceUnit), target = normalize(targetUnit);
        if (source.isEmpty() || target.isEmpty() || source.equals(target)) return raw;
        if (directedFactors == null) return raw;
        BigDecimal f = directedFactors.get(source + "\u0000" + target);
        return convert(raw, f);
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
        if (row != null && Boolean.TRUE.equals(row.get("__unitConverted"))) return row;
        Map<String, String> cols = configuredColumns(fields);
        if (cols.isEmpty() || row == null) return row;
        Map<String, Object> out = new LinkedHashMap<>(row);
        for (Map.Entry<String, String> e : cols.entrySet()) {
            String c = e.getKey(), d = e.getValue();
            BigDecimal raw = toBig(row.get(c));
            if (raw == null) continue;
            Object dv = row.get(d);
            Object targetObj = row.get("__settlementUnit");
            if (targetObj == null) targetObj = row.get("settlementUnit");
            if (targetObj == null) targetObj = row.get("settlement_unit");
            // A missing customer settlement/pricing target means no conversion.
            // Legacy source-only factors remain available through factorFor for
            // explicit compatibility callers, never through business row paths.
            BigDecimal factor = targetObj == null
                    ? BigDecimal.ONE
                    : convert(BigDecimal.ONE, dv == null ? null : dv.toString(), targetObj.toString());
            out.put(c, raw.multiply(factor));
        }
        return out;
    }

    /**
     * 返回换算后新 JsonNode 行（原 mergedRow 不变）。用于 FormulaCalculator.computeRows。
     */
    public static Map<String, JsonNode> convertNodeRow(JsonNode fields, Map<String, JsonNode> mergedRow) {
        if (mergedRow != null && Boolean.TRUE.equals(mergedRow.get("__unitConverted"))) return mergedRow;
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
            JsonNode target = mergedRow.get("__settlementUnit");
            if (target == null) target = mergedRow.get("settlementUnit");
            BigDecimal factor = target == null
                    ? BigDecimal.ONE
                    : convert(BigDecimal.ONE, dn == null ? null : dn.asText(), target.asText());
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
                                          Map<String, BigDecimal> fieldValues,
                                          Map<String, Object> currentRowRaw) {
        Map<String, String> cols = configuredColumns(fields);
        if (cols.isEmpty()) return;
        for (Map.Entry<String, String> e : cols.entrySet()) {
            String c = e.getKey(), d = e.getValue();
            Object unitObj = currentRowRaw != null ? currentRowRaw.get(d) : null;
            Object targetObj = currentRowRaw == null ? null : currentRowRaw.get("__settlementUnit");
            BigDecimal factor = targetObj == null
                    ? BigDecimal.ONE
                    : convert(BigDecimal.ONE, unitObj == null ? null : unitObj.toString(), targetObj.toString());
            if (factor.compareTo(BigDecimal.ONE) == 0) continue;
            if (fieldValues != null) {
                BigDecimal cv = fieldValues.get(c);
                if (cv != null) fieldValues.put(c, factor.multiply(cv));
            }
            if (currentRowRaw != null) {
                BigDecimal raw = toBig(currentRowRaw.get(c));
                if (raw != null) currentRowRaw.put(c, factor.multiply(raw));
            }
        }
    }
}
