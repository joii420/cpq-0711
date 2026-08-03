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

    @Test
    void computeSlotIfDue_daily_wrongMinute_notDue() {
        CustomerPriceAdjustStrategy s = strategy("DAILY", null, null, null);
        var now = java.time.OffsetDateTime.of(2026, 8, 2, 9, 1, 0, 0, java.time.ZoneOffset.UTC);
        assertNull(svc.computeSlotIfDue(s, now));
    }

    @Test
    void computeSlotIfDue_weekly_wrongWeekday_notDue() {
        CustomerPriceAdjustStrategy s = strategy("WEEKLY", (short) 3, null, null); // 周三
        // 2026-08-02 是周日
        var now = java.time.OffsetDateTime.of(2026, 8, 2, 9, 0, 0, 0, java.time.ZoneOffset.UTC);
        assertNull(svc.computeSlotIfDue(s, now));
    }
}
