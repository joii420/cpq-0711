package com.cpq.priceadjust.service;

import com.cpq.priceadjust.entity.CustomerPriceAdjustStrategy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0729 B3 · 定时扫描周期边界单元测试（backtask B3「周期边界」条款）。
 *
 * <p>纯逻辑测试，不需要 CDI 容器（computeSlotIfDue/isMonthlyDayDue/isNthWeekDue 均未触碰
 * 注入字段），直接 new 实例即可。
 */
class PriceAdjustScheduledScanServiceTest {

    private final PriceAdjustScheduledScanService svc = new PriceAdjustScheduledScanService();

    private CustomerPriceAdjustStrategy strategy(String cycleType, Short weekday, Short dayOfMonth, Short nthWeek) {
        CustomerPriceAdjustStrategy s = new CustomerPriceAdjustStrategy();
        s.cycleType = cycleType;
        s.cycleWeekday = weekday;
        s.cycleDayOfMonth = dayOfMonth;
        s.cycleNthWeek = nthWeek;
        s.executeTime = LocalTime.of(9, 0);
        return s;
    }

    // ── MONTHLY_DAY：小月顺延月末 ──────────────────────────────────────────

    @Test
    void monthlyDay_31_onFebruary_dueOnLastDay() {
        CustomerPriceAdjustStrategy s = strategy("MONTHLY_DAY", null, (short) 31, null);
        // 2026 是平年，2 月只有 28 天
        assertFalse(svc.isMonthlyDayDue(LocalDate.of(2026, 2, 27), (short) 31));
        assertTrue(svc.isMonthlyDayDue(LocalDate.of(2026, 2, 28), (short) 31));
    }

    @Test
    void monthlyDay_31_onLeapFebruary_dueOn29() {
        // 2028 是闰年，2 月 29 天
        assertTrue(svc.isMonthlyDayDue(LocalDate.of(2028, 2, 29), (short) 31));
        assertFalse(svc.isMonthlyDayDue(LocalDate.of(2028, 2, 28), (short) 31));
    }

    @Test
    void monthlyDay_31_onMonthWith31Days_dueOn31() {
        assertTrue(svc.isMonthlyDayDue(LocalDate.of(2026, 8, 31), (short) 31));
        assertFalse(svc.isMonthlyDayDue(LocalDate.of(2026, 8, 30), (short) 31));
    }

    @Test
    void monthlyDay_15_normalDay_notAffectedByMonthLength() {
        assertTrue(svc.isMonthlyDayDue(LocalDate.of(2026, 2, 15), (short) 15));
        assertTrue(svc.isMonthlyDayDue(LocalDate.of(2026, 8, 15), (short) 15));
    }

    // ── MONTHLY_NTH_WEEK：第 N 周不足按当月最后一个该星期几 ──────────────────

    @Test
    void nthWeek_5thMonday_whenMonthHas5Mondays_dueOnThat5th() {
        // 2026-08：8/3,10,17,24,31 均为周一 —— 5 个周一，第 5 个 = 8/31
        assertTrue(svc.isNthWeekDue(LocalDate.of(2026, 8, 31), (short) 5, (short) 1));
        assertFalse(svc.isNthWeekDue(LocalDate.of(2026, 8, 24), (short) 5, (short) 1));
    }

    @Test
    void nthWeek_5thMonday_whenMonthOnlyHas4_fallsBackToLastMonday() {
        // 2026-02：2/2,9,16,23 只有 4 个周一，没有第 5 个 —— 落到最后一个（2/23）
        assertTrue(svc.isNthWeekDue(LocalDate.of(2026, 2, 23), (short) 5, (short) 1));
        assertFalse(svc.isNthWeekDue(LocalDate.of(2026, 2, 16), (short) 5, (short) 1));
    }

    @Test
    void nthWeek_wrongWeekday_neverDue() {
        assertFalse(svc.isNthWeekDue(LocalDate.of(2026, 8, 31), (short) 5, (short) 2)); // 8/31 是周一不是周二
    }

    // ── computeSlotIfDue：整合 executeTime 命中判定 ─────────────────────────

    @Test
    void computeSlotIfDue_daily_matchesExecuteTimeMinute() {
        CustomerPriceAdjustStrategy s = strategy("DAILY", null, null, null);
        var now = java.time.OffsetDateTime.of(2026, 8, 2, 9, 0, 0, 0, java.time.ZoneOffset.UTC);
        assertNotNull(svc.computeSlotIfDue(s, now));
    }

    /**
     * ⚠️ <b>2026-08-04 改写：本用例原先断言的是 bug 本身</b>。
     *
     * <p>原文是 {@code executeTime=09:00, now=09:01 → assertNull}，即「过了那一分钟就不再执行」，
     * 与 {@code testcases.md:116} 的补跑要求（"把 executeTime 设为过去 5 分钟前……下一分钟扫描
     * 应立即补上该时刻的版本"）直接冲突。它把「精确分钟相等」这个错误判据锁死在测试里，
     * 是补跑缺失能长期不被发现的原因之一——10 条测试全绿，其中一条绿在错的地方。
     *
     * <p>现改为断言正确语义：执行时刻已过 ⟹ <b>补跑</b>，且返回的 slot 是<b>原定时刻</b>
     * （09:00），不是补跑发生的当前时刻（09:01）——{@code testcases.md:121} 专门盯这一点，
     * 因为 slot 同时是 {@code UNIQUE(customer_no, scheduled_slot)} 幂等键。
     */
    @Test
    void computeSlotIfDue_daily_afterExecuteTime_catchesUpWithOriginalSlot() {
        CustomerPriceAdjustStrategy s = strategy("DAILY", null, null, null);
        var now = java.time.OffsetDateTime.of(2026, 8, 2, 9, 1, 0, 0, java.time.ZoneOffset.UTC);
        var slot = svc.computeSlotIfDue(s, now);
        assertNotNull(slot, "执行时刻已过且当天尚未生成 → 必须补跑");
        assertEquals(java.time.OffsetDateTime.of(2026, 8, 2, 9, 0, 0, 0, java.time.ZoneOffset.UTC), slot,
                "slot 必须是原定时刻 09:00，不是补跑发生的 09:01——否则每分钟算出不同幂等键，唯一约束拦不住");
    }

    /** 补跑跨越数小时后仍指向同一个原定 slot（幂等键在一整天内恒定）。 */
    @Test
    void computeSlotIfDue_daily_hoursLate_stillSameOriginalSlot() {
        CustomerPriceAdjustStrategy s = strategy("DAILY", null, null, null);
        var now = java.time.OffsetDateTime.of(2026, 8, 2, 23, 47, 0, 0, java.time.ZoneOffset.UTC);
        assertEquals(java.time.OffsetDateTime.of(2026, 8, 2, 9, 0, 0, 0, java.time.ZoneOffset.UTC),
                svc.computeSlotIfDue(s, now));
    }

    /** 真正的「尚未到点」：执行时刻之前不得生成（补跑不能反向提前）。 */
    @Test
    void computeSlotIfDue_daily_beforeExecuteTime_notDue() {
        CustomerPriceAdjustStrategy s = strategy("DAILY", null, null, null);
        var now = java.time.OffsetDateTime.of(2026, 8, 2, 8, 59, 0, 0, java.time.ZoneOffset.UTC);
        assertNull(svc.computeSlotIfDue(s, now));
    }

    @Test
    void computeSlotIfDue_weekly_wrongWeekday_notDue() {
        CustomerPriceAdjustStrategy s = strategy("WEEKLY", (short) 3, null, null); // 周三
        // 2026-08-02 是周日
        var now = java.time.OffsetDateTime.of(2026, 8, 2, 9, 0, 0, 0, java.time.ZoneOffset.UTC);
        assertNull(svc.computeSlotIfDue(s, now));
        // 🔒 2026-08-04 补：补跑判据【不得越过】周期判定——今天本就不该执行，
        //    哪怕 now 远超 executeTime 也必须不生成（否则补跑会把非执行日也补出版本）。
        assertNull(svc.computeSlotIfDue(s,
                java.time.OffsetDateTime.of(2026, 8, 2, 23, 59, 0, 0, java.time.ZoneOffset.UTC)));
    }
}
