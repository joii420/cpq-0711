package com.cpq.dataset.fingerprint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260902 · B-4 行指纹规范化单测（需求文档 R-3 / AC-16 / AC-17 / AC-18）。
 * <p>纯 JUnit，不起 Quarkus、不连库 —— 与共享开发库 {@code cpq_db_0724} 零交互。
 */
class ValueNormalizerTest {

    private static final List<FpColumn> NUM = List.of(new FpColumn("v", "NUMBER", null));
    private static final List<FpColumn> TXT = List.of(new FpColumn("v", "STRING", null));

    private static String fpNum(Object v) { return RowFingerprints.compute(NUM, row(v)); }
    private static String fpTxt(Object v) { return RowFingerprints.compute(TXT, row(v)); }

    private static Map<String, Object> row(Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", v);
        return m;
    }

    @Test
    @DisplayName("AC-18 数值规范化：1 / 1.0 / 1.00 / 1.000000 四者指纹相等")
    void numericTrailingZerosEquivalent() {
        String a = fpNum("1"), b = fpNum("1.0"), c = fpNum("1.00"), d = fpNum("1.000000");
        assertEquals(a, b, "1 vs 1.0");
        assertEquals(a, c, "1 vs 1.00");
        assertEquals(a, d, "1 vs 1.000000");
        assertEquals(64, a.length(), "指纹必须是 64 位 hex");
        assertTrue(a.matches("[0-9a-f]{64}"), "指纹必须是小写 hex: " + a);
    }

    @Test
    @DisplayName("AC-18 数值规范化：5.5 与 5.500000 同指纹（需求文档原文用例）")
    void ac18ProcessingFee() {
        assertEquals(fpNum("5.5"), fpNum("5.500000"));
        assertEquals(fpNum(new BigDecimal("5.500000000000")), fpNum("5.5"));
    }

    @Test
    @DisplayName("R-3 文本 trim 保留大小写：\"a\" 与 \"A\" 指纹不等；\" a \" 与 \"a\" 相等")
    void textCaseSensitiveButTrimmed() {
        assertNotEquals(fpTxt("a"), fpTxt("A"), "大小写必须区分");
        assertEquals(fpTxt(" a "), fpTxt("a"), "首尾空白必须被 strip");
        assertEquals(fpTxt("　a　"), fpTxt("a"), "全角空格 U+3000 也必须被 strip");
    }

    @Test
    @DisplayName("R-3 空值三态：null / \"\" / \"  \" 三者指纹相等")
    void blankTriStateEquivalent() {
        String n = fpTxt(null), e = fpTxt(""), s = fpTxt("   "), fw = fpTxt("　　");
        assertEquals(n, e, "null vs \"\"");
        assertEquals(n, s, "null vs 纯空格");
        assertEquals(n, fw, "null vs 全角空格");
        assertTrue(ValueNormalizer.isBlank(null));
        assertTrue(ValueNormalizer.isBlank(""));
        assertTrue(ValueNormalizer.isBlank("   "));
        assertTrue(ValueNormalizer.isBlank("　"));
        assertFalse(ValueNormalizer.isBlank("0"), "\"0\" 不是空值");
    }

    @Test
    @DisplayName("零值规范化：0 / 0.00 / -0.0 同指纹（PrecisionPolicy.toPlainDecimalString 统一为 \"0\"）")
    void zeroNormalization() {
        assertEquals(fpNum("0"), fpNum("0.00"));
        assertEquals(fpNum("0"), fpNum("-0.0"));
        assertNotEquals(fpNum("0"), fpTxt(""), "数值 0 与空值不得同指纹");
    }

    @Test
    @DisplayName("列 scale 归一：scale=12 时 1.0000000000004 与 1 同指纹（防库内 12 位截断造成虚假升版）")
    void scaleNormalization() {
        List<FpColumn> s12 = List.of(new FpColumn("v", "NUMBER", 12));
        String a = RowFingerprints.compute(s12, row("1"));
        String b = RowFingerprints.compute(s12, row("1.0000000000004"));
        assertEquals(a, b);
        // scale 未知时不归一 —— 15 位小数与 1 不同指纹
        assertNotEquals(fpNum("1"), fpNum("1.0000000000004"));
    }

    @Test
    @DisplayName("0x1F 分隔符防撞串：(\"ab\",\"c\") 与 (\"a\",\"bc\") 指纹不等")
    void separatorPreventsCollision() {
        List<FpColumn> two = List.of(new FpColumn("a", "STRING", null), new FpColumn("b", "STRING", null));
        Map<String, Object> r1 = new LinkedHashMap<>(); r1.put("a", "ab"); r1.put("b", "c");
        Map<String, Object> r2 = new LinkedHashMap<>(); r2.put("a", "a");  r2.put("b", "bc");
        assertNotEquals(RowFingerprints.compute(two, r1), RowFingerprints.compute(two, r2));
    }

    @Test
    @DisplayName("AC-16 多重集比较：行序对调相等；重复次数不同不相等")
    void multisetComparison() {
        assertTrue(RowFingerprints.sameMultiset(List.of("a", "b"), List.of("b", "a")), "对调顺序必须判等");
        assertTrue(RowFingerprints.sameMultiset(List.of("a", "a", "b"), List.of("b", "a", "a")));
        assertFalse(RowFingerprints.sameMultiset(List.of("a", "a"), List.of("a", "b")), "重复次数必须计入");
        assertFalse(RowFingerprints.sameMultiset(List.of("a"), List.of("a", "a")), "行数不同必须判不等");
        assertTrue(RowFingerprints.sameMultiset(List.of(), List.of()));
    }

    @Test
    @DisplayName("AC-9 / AC-40 校验用解析：abc 不是合法数值；1.5 不是合法整数")
    void strictParsing() {
        assertNull(ValueNormalizer.parseDecimal("abc"), "abc 必须解析失败（不能兜底成 0）");
        assertNull(ValueNormalizer.parseDecimal(""), "空串解析失败");
        assertEquals(new BigDecimal("5.5"), ValueNormalizer.parseDecimal(" 5.5 "));
        assertEquals(BigDecimal.ZERO, ValueNormalizer.parseDecimal("0"), "合法 0 必须解析成功");
        assertNull(ValueNormalizer.parseInteger("1.5"), "1.5 不是合法整数");
        assertNotNull(ValueNormalizer.parseInteger("10"));
        assertNotNull(ValueNormalizer.parseInteger("10.00"), "10.00 是合法整数（Excel 数字单元格常见形态）");
    }

    @Test
    @DisplayName("布尔规范化：true/1/是/Y 归一为 true，其余为 false")
    void booleanNormalization() {
        List<FpColumn> b = List.of(new FpColumn("v", "BOOLEAN", null));
        String t = RowFingerprints.compute(b, row("true"));
        assertEquals(t, RowFingerprints.compute(b, row("1")));
        assertEquals(t, RowFingerprints.compute(b, row("是")));
        assertEquals(t, RowFingerprints.compute(b, row("Y")));
        assertNotEquals(t, RowFingerprints.compute(b, row("false")));
        assertNotEquals(t, RowFingerprints.compute(b, row("0")));
    }

    @Test
    @DisplayName("非比对项不进指纹：compared 列相同则指纹相同（AC-17 项次 10→99 不升版）")
    void nonComparedColumnsExcluded() {
        // 调用方只传 compared=true 的列；item_seq 不在列表里 → 值不同也不影响指纹
        Map<String, Object> r1 = new LinkedHashMap<>(); r1.put("v", "X"); r1.put("item_seq", "10");
        Map<String, Object> r2 = new LinkedHashMap<>(); r2.put("v", "X"); r2.put("item_seq", "99");
        assertEquals(RowFingerprints.compute(TXT, r1), RowFingerprints.compute(TXT, r2));
    }
}
