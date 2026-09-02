package com.cpq.configure.rules;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260901 · 共享判据的纯单测。
 *
 * <p>🚨 本类验的是 <b>UI 新建材质与 Excel 导入共用的那一份代码</b>（M-0a）。
 * FT-4 证伪实验就是改这里的判据，看两个入口是不是同时变红。
 */
class MaterialRecipeRulesTest {

    // ── M-0a / M-5b：各组元素种类集合必须全相同 ──

    private static Set<String> set(String... s) {
        return new LinkedHashSet<>(List.of(s));
    }

    @Test
    void elementSetMismatch_returnsNullWhenAllGroupsMatch() {
        assertNull(MaterialRecipeRules.findFirstElementSetMismatch(
            List.of(set("Ag", "Ni"), set("Ag", "Ni"))));
        assertNull(MaterialRecipeRules.findFirstElementSetMismatch(
            List.of(set("Ag", "Ni"), set("Ni", "Ag"))),
            "判据是<b>集合</b>相等，与组内元素的先后无关（AC-32 顺序无关性的一半）");
        assertNull(MaterialRecipeRules.findFirstElementSetMismatch(List.of(set("Ag"))),
            "只有一组 ⇒ 没有可比对象 ⇒ 一致");
        assertNull(MaterialRecipeRules.findFirstElementSetMismatch(List.of()));
        assertNull(MaterialRecipeRules.findFirstElementSetMismatch(null));
    }

    @Test
    void elementSetMismatch_pointsAtFirstOffendingPair() {
        int[] m = MaterialRecipeRules.findFirstElementSetMismatch(
            List.of(set("Ag", "Ni"), set("Ag", "Cu")));
        assertArrayEquals(new int[]{0, 1}, m, "第 1 组与第 2 组不一致");

        int[] m2 = MaterialRecipeRules.findFirstElementSetMismatch(
            List.of(set("Ag", "Ni"), set("Ag", "Ni"), set("Ag", "Cu")));
        assertArrayEquals(new int[]{0, 2}, m2, "一律以第 1 组为参照 ⇒ 报 (0, 2)");
    }

    /** 「多了」「少了」都算不一致 —— 不是子集关系。 */
    @Test
    void elementSetMismatch_supersetAndSubsetAreBothMismatches() {
        assertArrayEquals(new int[]{0, 1}, MaterialRecipeRules.findFirstElementSetMismatch(
            List.of(set("Ag", "Ni"), set("Ag", "Ni", "Cu"))), "多了一个元素也算不一致");
        assertArrayEquals(new int[]{0, 1}, MaterialRecipeRules.findFirstElementSetMismatch(
            List.of(set("Ag", "Ni"), set("Ag"))), "少了一个元素也算不一致");
    }

    @Test
    void elementSetsEqual_isSetSemantics() {
        assertTrue(MaterialRecipeRules.elementSetsEqual(List.of("Ag", "Ni"), List.of("Ni", "Ag")));
        assertFalse(MaterialRecipeRules.elementSetsEqual(List.of("Ag", "Ni"), List.of("Ag", "Cu")));
        assertFalse(MaterialRecipeRules.elementSetsEqual(List.of("Ag"), List.of("Ag", "Ni")));
        assertTrue(MaterialRecipeRules.elementSetsEqual(null, List.of()));
    }

    // ── M-4：配置的相等判据 ──

    private static Map<String, BigDecimal> content(Object... kv) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], new BigDecimal((String) kv[i + 1]));
        return m;
    }

    @Test
    void sameContent_ignoresScaleDifferences() {
        assertTrue(MaterialRecipeRules.sameContent(
            content("Ag", "90", "Ni", "10"),
            content("Ag", "90.000000000000", "Ni", "10.000000000000")),
            "M-4：值相等即可，忽略 scale 差异（BigDecimal.compareTo == 0，不是 equals）");
    }

    @Test
    void sameContent_isFalseWhenAnyValueDiffers() {
        assertFalse(MaterialRecipeRules.sameContent(
            content("Ag", "90", "Ni", "10"), content("Ag", "85", "Ni", "15")));
    }

    /** 🚨 M-4 明确：<b>不套用 Σ 的 0.02 容差</b> —— 容差只回答「Σ 是不是 1」。 */
    @Test
    void sameContent_doesNotApplySumTolerance() {
        assertFalse(MaterialRecipeRules.sameContent(
            content("Ag", "90", "Ni", "10"),
            content("Ag", "90.000000000001", "Ni", "9.999999999999")),
            "差 1e-12 也算不同配置 —— 容差绝不能渗进相等判据");
    }

    @Test
    void sameContent_isFalseWhenElementSetsDiffer() {
        assertFalse(MaterialRecipeRules.sameContent(
            content("Ag", "90", "Ni", "10"), content("Ag", "90", "Cu", "10")));
        assertFalse(MaterialRecipeRules.sameContent(
            content("Ag", "100"), content("Ag", "90", "Ni", "10")));
        assertFalse(MaterialRecipeRules.sameContent(null, content("Ag", "100")));
    }

    // ── Σ 与单值范围 ──

    @Test
    void sumTolerance_isTwoPercentInRatioScale() {
        assertTrue(MaterialRecipeRules.sumIsOneRatio(new BigDecimal("1.00")));
        assertTrue(MaterialRecipeRules.sumIsOneRatio(new BigDecimal("1.02")), "恰在容差边界内");
        assertTrue(MaterialRecipeRules.sumIsOneRatio(new BigDecimal("0.98")));
        assertFalse(MaterialRecipeRules.sumIsOneRatio(new BigDecimal("1.20")), "AC-9 的 1.20 必须被拒");
        assertFalse(MaterialRecipeRules.sumIsOneRatio(new BigDecimal("1.41")),
            "WZHF26-25 的 Σ≈1.41 仍须被跳过（沿用既有容差，不得放宽）");
    }

    @Test
    void sumTolerance_hundredScaleMirrorsRatioScale() {
        assertTrue(MaterialRecipeRules.sumIsOnePct(new BigDecimal("100")));
        assertTrue(MaterialRecipeRules.sumIsOnePct(new BigDecimal("102")));
        assertFalse(MaterialRecipeRules.sumIsOnePct(new BigDecimal("120")));
    }

    @Test
    void ratioSum_isFormattedWithTwoDecimals() {
        assertEquals("1.20", MaterialRecipeRules.formatRatioSum(new BigDecimal("1.2")),
            "AC-9 的报告文案是「含量合计≠1(实际1.20)」");
        assertEquals("1.08", MaterialRecipeRules.formatRatioSum(new BigDecimal("1.08")));
        assertEquals("1.41", MaterialRecipeRules.formatRatioSum(new BigDecimal("1.4142")));
    }

    @Test
    void pctRange_isExclusiveZeroInclusiveMax() {
        assertFalse(MaterialRecipeRules.pctInRange(new BigDecimal("0"), BigDecimal.ONE), "0 非法（AC-25）");
        assertFalse(MaterialRecipeRules.pctInRange(new BigDecimal("-0.1"), BigDecimal.ONE));
        assertFalse(MaterialRecipeRules.pctInRange(new BigDecimal("1.5"), BigDecimal.ONE), "1.5 非法（AC-25）");
        assertFalse(MaterialRecipeRules.pctInRange(null, BigDecimal.ONE), "解析失败（abc / 空）也是非法");
        assertTrue(MaterialRecipeRules.pctInRange(new BigDecimal("1"), BigDecimal.ONE), "1 合法（单元素材质）");
        assertTrue(MaterialRecipeRules.pctInRange(new BigDecimal("0.123456789012"), BigDecimal.ONE));
        assertTrue(MaterialRecipeRules.pctInRange(new BigDecimal("100"), MaterialRecipeRules.HUNDRED));
        assertFalse(MaterialRecipeRules.pctInRange(new BigDecimal("100.1"), MaterialRecipeRules.HUNDRED));
    }

    /**
     * 🚨 集合渲染必须<b>排序</b>而不是保插入序：AC-32 要求行序对调后报告逐字相同，
     * 保插入序会让同一份数据在不同行序下报出 {@code {Ag,Ni}} 与 {@code {Ni,Ag}} 两种文案。
     */
    @Test
    void formatSet_sortsSoThatMessagesAreRowOrderIndependent() {
        assertEquals("{Ag,Ni}", MaterialRecipeRules.formatSet(List.of("Ag", "Ni"), ","));
        assertEquals("{Ag,Ni}", MaterialRecipeRules.formatSet(List.of("Ni", "Ag"), ","),
            "行序对调后必须渲染成同一串");
        assertEquals("{Ag, Cu}", MaterialRecipeRules.formatSet(List.of("Cu", "Ag"), ", "));
        assertEquals("{}", MaterialRecipeRules.formatSet(List.of(), ","));
    }
}
