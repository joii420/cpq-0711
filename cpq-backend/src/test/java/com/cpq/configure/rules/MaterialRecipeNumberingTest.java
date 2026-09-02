package com.cpq.configure.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260901 · 三个发号器的纯单测（test.md 的 T-U-01 / T-U-02 / T-U-03）。
 *
 * <p>纯内存、不连库 —— 这正是把发号逻辑抽成纯函数的目的：
 * 扩位 / 脏值过滤 / 编号不回收这三类边界不该依赖共享库的当前状态才能验。
 */
class MaterialRecipeNumberingTest {

    // ═══════════ T-U-03：配置编号（M-1 / M-2）→ AC-14 / AC-15 / AC-26 ═══════════

    @Test
    void configNo_isRecipeCodePlusTwoDigitSeq() {
        assertEquals("00006-01", MaterialRecipeNumbering.formatConfigNo("00006", 1));
        assertEquals("00006-02", MaterialRecipeNumbering.formatConfigNo("00006", 2));
        assertEquals("00006-09", MaterialRecipeNumbering.formatConfigNo("00006", 9));
        assertEquals("00006-10", MaterialRecipeNumbering.formatConfigNo("00006", 10));
    }

    /** AC-26 边界：99 → "-99"，100 → "-100"（三位，自然扩位）。 */
    @Test
    void configNo_widensToThreeDigitsAtHundred() {
        assertEquals("00006-99", MaterialRecipeNumbering.formatConfigNo("00006", 99));
        assertEquals("00006-100", MaterialRecipeNumbering.formatConfigNo("00006", 100),
            "🚨 %02d 是「至少两位」：seq=100 必须给出 -100。"
                + "PG 的 lpad('100',2,'0') 会截成 '10' —— 这正是 M-1 禁用它的原因");
        assertEquals("00006-101", MaterialRecipeNumbering.formatConfigNo("00006", 101));
    }

    @Test
    void configSeq_isMaxPlusOne_countingInactive() {
        assertEquals(1, MaterialRecipeNumbering.nextConfigSeq(List.of()), "无配置 → 从 1 起");
        assertEquals(2, MaterialRecipeNumbering.nextConfigSeq(List.of(1)));
        assertEquals(4, MaterialRecipeNumbering.nextConfigSeq(List.of(1, 2, 3)));
    }

    /**
     * 🚨 AC-15 的核心：删掉中间一条后<b>编号不回收</b>。
     * 这条是 FT-1 证伪实验的靶子 —— 把水位改成只统计 ACTIVE，这里必须变红。
     */
    @Test
    void configSeq_doesNotRecycleAfterSoftDelete() {
        // -01 ACTIVE, -02 已软删(INACTIVE 但 seq 仍在), -03 ACTIVE
        List<Integer> allSeqsIncludingInactive = List.of(1, 2, 3);
        assertEquals(4, MaterialRecipeNumbering.nextConfigSeq(allSeqsIncludingInactive),
            "软删的 seq 仍占水位 ⇒ 下一条是 4（00006-04），不是被回收的 2");

        // 反面：若错误地只统计 ACTIVE（1,3），仍应得 4 —— 但若实现取的是「ACTIVE 的个数+1」就会得 3。
        assertEquals(4, MaterialRecipeNumbering.nextConfigSeq(List.of(1, 3)),
            "取 max 而不是 count：即使只看到 1 和 3，也应是 4");
    }

    @Test
    void configNo_rejectsIllegalInput() {
        assertThrows(IllegalArgumentException.class,
            () -> MaterialRecipeNumbering.formatConfigNo(null, 1));
        assertThrows(IllegalArgumentException.class,
            () -> MaterialRecipeNumbering.formatConfigNo("  ", 1));
        assertThrows(IllegalArgumentException.class,
            () -> MaterialRecipeNumbering.formatConfigNo("00006", 0));
    }

    // ═══════════ T-U-01：材质编号（B-6）→ AC-3 / AC-4 ═══════════

    @Test
    void recipeCode_isMaxFiveDigitPlusOne() {
        assertEquals("00263", MaterialRecipeNumbering.nextRecipeCode(List.of("00001", "00262", "00100")));
        assertEquals("00002", MaterialRecipeNumbering.nextRecipeCode(List.of("00001")));
    }

    /** D8：只看五位补零的。脏值 '992'（三位）必须被排除，否则会算成 993。 */
    @Test
    void recipeCode_ignoresNonFiveDigitCodes() {
        List<String> codes = Arrays.asList("00262", "992", "AgCu85", "ZZ999", null, "  ", "123456");
        assertEquals("00263", MaterialRecipeNumbering.nextRecipeCode(codes),
            "'992' 不是五位 ⇒ 不参与 max；'00993' 与 '992' 是不同字符串，永不撞键");
    }

    @Test
    void recipeCode_startsAtOneWhenNoFiveDigitCodeExists() {
        assertEquals("00001", MaterialRecipeNumbering.nextRecipeCode(List.of("992", "AgCu85")));
        assertEquals("00001", MaterialRecipeNumbering.nextRecipeCode(List.of()));
        assertEquals("00001", MaterialRecipeNumbering.nextRecipeCode(null));
    }

    @Test
    void recipeCode_overflowsToSixDigitsGracefully() {
        assertEquals("100000", MaterialRecipeNumbering.nextRecipeCode(List.of("99999")),
            "%05d 同样是「至少五位」：越过 99999 自然扩位，不静默截断");
    }

    // ═══════════ T-U-02：元素编号（B-7）→ AC-6 ═══════════

    @Test
    void elementNo_isMaxNumericPlusOne() {
        assertEquals("10096", MaterialRecipeNumbering.nextElementNo(List.of("10001", "10095", "10012")));
    }

    /**
     * 🚨 主表存在脏行 {@code element_no='白银'}。
     * 正则过滤不可省 —— 少了它，SQL 侧 {@code ::bigint} 直接抛异常、Java 侧 parse 直接崩。
     */
    @Test
    void elementNo_filtersNonNumericDirtyRows() {
        List<String> nos = new ArrayList<>(Arrays.asList("10001", "白银", "10095", null, " ", "A10"));
        assertEquals("10096", MaterialRecipeNumbering.nextElementNo(nos),
            "'白银' 等非纯数字编号必须被过滤，不能参与 max、更不能让转换崩掉");
    }

    @Test
    void elementNo_startsAt10001WhenNoNumericExists() {
        assertEquals("10001", MaterialRecipeNumbering.nextElementNo(List.of("白银")));
        assertEquals("10001", MaterialRecipeNumbering.nextElementNo(List.of()));
        assertEquals("10001", MaterialRecipeNumbering.nextElementNo(null));
    }

    /** 编号可能超出 long 的范围（历史数据里出现过很长的数字串），用 BigInteger 兜住。 */
    @Test
    void elementNo_handlesVeryLargeNumbers() {
        assertEquals("99999999999999999999",
            MaterialRecipeNumbering.nextElementNo(List.of("99999999999999999998")));
    }

    /** 连续发号：拿上一次的结果继续往下发（导入侧一批新元素就是这么递增的）。 */
    @Test
    void elementNo_canBeChainedInMemory() {
        String n1 = MaterialRecipeNumbering.nextElementNo(List.of("10095"));
        String n2 = MaterialRecipeNumbering.nextElementNo(List.of(n1));
        String n3 = MaterialRecipeNumbering.nextElementNo(List.of(n2));
        assertEquals(List.of("10096", "10097", "10098"), List.of(n1, n2, n3));
    }
}
