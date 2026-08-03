package com.cpq.priceadjust.service;

import com.cpq.priceadjust.entity.CustomerPriceAdjustStrategy;
import com.cpq.priceadjust.exception.StrategyNoElementsException;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * task-0729 B3 · 定时扫描客户调价策略，命中周期执行点即生成价格版本。
 *
 * <p>🔒 {@code concurrentExecution = SKIP} 必须带（本期新增任务，与现有 7 个未配置的
 * {@code @Scheduled} 区分开）——否则上一轮扫描还没跑完，下一轮又叠一份，验收 #4
 * 「同一周期点只生成一次」判不出对错（backtask B3）。
 *
 * <p>幂等 + 补跑：真正的去重靠 {@code UNIQUE(customer_no, scheduled_slot)}（服务重启错过
 * 某个整分钟的执行点，下次扫描用同一个 slot 重新计算并插入，天然补上）；这里的
 * {@code SKIP} 只是防止同一进程内的扫描线程重叠执行，不是幂等的唯一保障。
 */
@ApplicationScoped
public class PriceAdjustScheduledScanService {

    private static final Logger LOG = Logger.getLogger(PriceAdjustScheduledScanService.class);

    @Inject PriceAdjustVersionGenerationService versionService;

    @Scheduled(every = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scanAndGenerate() {
        OffsetDateTime now = OffsetDateTime.now();
        List<CustomerPriceAdjustStrategy> strategies = CustomerPriceAdjustStrategy.list("enabled", true);
        for (CustomerPriceAdjustStrategy s : strategies) {
            try {
                OffsetDateTime slot = computeSlotIfDue(s, now);
                if (slot == null) continue;
                // 定时触发：与手动生成走同一服务方法，confirmSupersede 恒为 true
                // （定时任务不会等人二次确认，直接作废旧 PENDING 版本 —— backtask B3 第 5 点）。
                versionService.generateVersionAndEnqueueBudget(s.customerNo, true, "SCHEDULED", slot);
            } catch (StrategyNoElementsException e) {
                LOG.infof("[price-adjust-scan] customer=%s skip（E14-10）: %s", s.customerNo, e.getMessage());
            } catch (Exception e) {
                LOG.errorf(e, "[price-adjust-scan] customer=%s failed", s.customerNo);
            }
        }
    }

    /**
     * 计算该策略在 {@code now} 这一分钟是否命中其周期执行点；命中则返回该次的
     * {@code scheduledSlot}（幂等键 = 当天日期 + execute_time），否则 null。
     */
    OffsetDateTime computeSlotIfDue(CustomerPriceAdjustStrategy s, OffsetDateTime now) {
        if (s.executeTime == null) return null;
        if (now.getHour() != s.executeTime.getHour() || now.getMinute() != s.executeTime.getMinute()) return null;

        LocalDate today = now.toLocalDate();
        boolean due;
        switch (s.cycleType == null ? "" : s.cycleType) {
            case "DAILY":
                due = true;
                break;
            case "WEEKLY":
                due = s.cycleWeekday != null && today.getDayOfWeek().getValue() == s.cycleWeekday;
                break;
            case "MONTHLY_DAY":
                due = isMonthlyDayDue(today, s.cycleDayOfMonth);
                break;
            case "MONTHLY_NTH_WEEK":
                due = isNthWeekDue(today, s.cycleNthWeek, s.cycleWeekday);
                break;
            default:
                due = false;
        }
        if (!due) return null;
        return OffsetDateTime.of(today, s.executeTime, now.getOffset());
    }

    /** 每月 dayOfMonth 号；31 号等小月没有的日期 → 顺延至月末（backtask B3 周期边界）。 */
    boolean isMonthlyDayDue(LocalDate today, Short dayOfMonth) {
        if (dayOfMonth == null) return false;
        int lastDay = today.lengthOfMonth();
        int targetDay = Math.min(dayOfMonth, lastDay);
        return today.getDayOfMonth() == targetDay;
    }

    /** 第 nthWeek 个 weekday；第 N 周不足 → 按当月最后一个该星期几（backtask B3 周期边界）。 */
    boolean isNthWeekDue(LocalDate today, Short nthWeek, Short weekday) {
        if (nthWeek == null || weekday == null) return false;
        if (today.getDayOfWeek().getValue() != weekday) return false;

        int occurrence = (today.getDayOfMonth() - 1) / 7 + 1;
        if (occurrence == nthWeek) return true;

        // 本月该星期几总共出现几次；若 nthWeek 超过总次数，"最后一次出现" 顶替第 N 次
        LocalDate lastOccurrence = today.withDayOfMonth(today.lengthOfMonth());
        while (lastOccurrence.getDayOfWeek().getValue() != weekday) {
            lastOccurrence = lastOccurrence.minusDays(1);
        }
        int lastOccurrenceCount = (lastOccurrence.getDayOfMonth() - 1) / 7 + 1;
        return nthWeek >= lastOccurrenceCount && today.equals(lastOccurrence);
    }
}
