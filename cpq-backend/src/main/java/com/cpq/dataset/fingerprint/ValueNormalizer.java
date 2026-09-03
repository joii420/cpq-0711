package com.cpq.dataset.fingerprint;

import com.cpq.common.PrecisionPolicy;

import java.math.BigDecimal;

/**
 * 行指纹的值规范化（task-260902 · 需求文档 R-3 · B-4 · AC-16/17/18）。
 *
 * <p>本类是<b>纯函数</b>，不依赖 CDI、不依赖 Registry —— 便于单测直接覆盖 R-3 的四条规范化规则：
 * <ul>
 *   <li>NULL / 空串 / 纯空白（含全角空格 U+3000）→ 统一为<b>空</b>，三者同值；</li>
 *   <li>数值 → {@link BigDecimal} 后 {@code stripTrailingZeros().toPlainString()}，
 *       故 {@code 1} / {@code 1.0} / {@code 1.00} / {@code 1.000000} <b>同指纹</b>；</li>
 *   <li>文本 → {@code strip()}，<b>保留大小写</b>（{@code "a"} 与 {@code "A"} 不同指纹）；</li>
 *   <li>布尔 → {@code "true"} / {@code "false"}。</li>
 * </ul>
 *
 * <p>🚨 小数口径<b>一律复用</b> {@link PrecisionPolicy}（{@code RECORD.md}「小数口径三层」：
 * 计算 12 位 / 显示 9 位 / 存储看列 scale），本类<b>不自造</b>任何舍入常量。
 * 列声明了 {@code scale}（= DB 列 scale）时先按该 scale 归一再去尾零，
 * 防「库里存 12 位、Excel 给 15 位」造成的<b>虚假升版</b>（同 {@code PricingSheetDef.decimalScales} 的既有教训）。
 */
public final class ValueNormalizer {

    /** 规范化后的「空」。NULL / 空串 / 纯空白三者都落到这里。 */
    public static final String EMPTY = "";

    private ValueNormalizer() {
    }

    /** 类型判定：Registry 的 type 串是否表示十进制数值列。 */
    public static boolean isDecimalType(String type) {
        if (type == null) return false;
        return switch (type.toUpperCase()) {
            case "NUMBER", "DECIMAL", "NUMERIC", "INTEGER", "INT", "BIGINT" -> true;
            default -> false;
        };
    }

    /** 类型判定：Registry 的 type 串是否表示整数列。 */
    public static boolean isIntegerType(String type) {
        if (type == null) return false;
        return switch (type.toUpperCase()) {
            case "INTEGER", "INT", "BIGINT", "SMALLINT" -> true;
            default -> false;
        };
    }

    /** 类型判定：布尔列。 */
    public static boolean isBooleanType(String type) {
        return type != null && "BOOLEAN".equalsIgnoreCase(type);
    }

    /**
     * 是否为「空」值（NULL / 空串 / 纯空白，含全角空格）。
     * <p>必填校验、轴列校验一律用本方法判空，保证与指纹口径同源。
     */
    public static boolean isBlank(Object raw) {
        return raw == null || toRawString(raw).isEmpty();
    }

    /** 原始值 → strip 后的字符串（{@code String.strip()} 覆盖 U+3000 全角空格）。 */
    public static String toRawString(Object raw) {
        if (raw == null) return EMPTY;
        if (raw instanceof String s) return s.strip();
        if (raw instanceof BigDecimal bd) return bd.toPlainString();
        return String.valueOf(raw).strip();
    }

    /**
     * 按列类型规范化。
     *
     * @param raw   原始值（Excel 单元格串 / DB 读回的对象 / 前端 JSON 值）
     * @param type  Registry 声明的列类型（STRING / NUMBER / DECIMAL / INTEGER / BOOLEAN / ENUM）
     * @param scale DB 列 scale（numeric(p,s) 的 s）；null 表示未知，跳过 scale 归一
     */
    public static String normalize(Object raw, String type, Integer scale) {
        String s = toRawString(raw);
        if (s.isEmpty()) return EMPTY;                       // NULL / "" / "   " / "　" 同值
        if (isBooleanType(type)) return normalizeBoolean(s);
        if (isDecimalType(type)) {
            BigDecimal bd = parseDecimal(s);
            if (bd == null) return s;                        // 不可解析：留原串（Phase 1 已判「不是合法数值」）
            return normalizeDecimal(bd, scale);
        }
        return s;                                            // 文本：strip，保留大小写
    }

    /** 数值规范化：先按列 scale 归一（若已知），再 stripTrailingZeros。 */
    public static String normalizeDecimal(BigDecimal value, Integer scale) {
        if (value == null) return EMPTY;
        BigDecimal v = value;
        if (scale != null && scale >= 0 && scale <= PrecisionPolicy.CALCULATION_SCALE) {
            v = v.setScale(scale, PrecisionPolicy.ROUNDING);
        }
        return PrecisionPolicy.toPlainDecimalString(v);      // 去尾零 + 零统一为 "0"
    }

    /** 布尔规范化：宽进（true/1/是/Y/yes → true），严出（只有 "true" / "false"）。 */
    public static String normalizeBoolean(String s) {
        String t = s.strip().toLowerCase();
        return switch (t) {
            case "true", "1", "y", "yes", "是", "t" -> "true";
            default -> "false";
        };
    }

    /**
     * 严格十进制解析。<b>不</b>走 {@link PrecisionPolicy#of(Object)} ——
     * 那个方法把无法解析的值兜底成 0（「缺值按 0 参与运算」语义），
     * 而导入校验必须能区分「合法 0」与「非法 abc」（AC-9），所以这里返回 null 表示不可解析。
     */
    public static BigDecimal parseDecimal(String s) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 严格整数解析：可解析为 BigDecimal 且小数部分为 0。返回 null 表示不是合法整数。 */
    public static java.math.BigInteger parseInteger(String s) {
        BigDecimal bd = parseDecimal(s);
        if (bd == null) return null;
        try {
            return bd.toBigIntegerExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }
}
