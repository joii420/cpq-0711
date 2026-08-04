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
 * <p><b>幂等 + 补跑</b>（两件事，靠两个机制，别混为一谈）：
 * <ul>
 *   <li><b>补跑</b>靠 {@link #computeSlotIfDue} 的判据是「今天该执行 <b>且 now ≥ 今天的
 *       execute_time</b>」——只要当天执行时刻已过而版本还没生成，<b>此后每一分钟的扫描都会
 *       重新命中</b>，服务重启/停机跨过那一分钟也补得回来。</li>
 *   <li><b>去重</b>靠 {@code UNIQUE(customer_no, scheduled_slot)}：补跑期间每分钟算出的
 *       {@code slot} 都是<b>同一个原定时刻</b>，首次插入成功、其余被
 *       {@link PriceAdjustVersionGenerationService#generateVersion} 的
 *       {@code alreadyExisted} 分支短路。</li>
 * </ul>
 * {@code SKIP} 只防同一进程内扫描线程重叠执行，<b>不是</b>幂等保障。
 *
 * <p>⚠️ <b>2026-08-04 订正</b>：本段原文声称「服务重启错过某个整分钟的执行点，下次扫描用
 * 同一个 slot 重新计算并插入，天然补上」——<b>当时那是假的</b>。{@code computeSlotIfDue}
 * 第一行是精确到分钟的相等判断，下次扫描在第一行就 return 了，根本走不到 slot 计算；
 * 那句话描述的实际只是「同一分钟内被扫描两次」的幂等保护。补跑能力本次才真正补上，
 * 注释同步改对。<b>教训：实现与注释有出入时改注释，别留着——这个 bug 藏这么久，
 * 那句注释有责任（它让每个读代码的人都以为补跑已经有了）。</b>
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
     * 计算该策略今天是否已到（或已过）其周期执行点；是则返回该次的 {@code scheduledSlot}
     * （幂等键 = <b>当天日期 + execute_time</b>，即<b>原定时刻</b>），否则 null。
     *
     * <p>🔒 <b>判据是「今天该执行 且 now ≥ 今天的 execute_time」，不是「now 正好等于
     * execute_time 的那一分钟」</b>（验收 #4 补跑场景 / testcases.md:116）。
     *
     * <p><b>2026-08-04 修复前的错误写法</b>：方法第一行是
     * {@code if (now.getHour() != executeTime.getHour() || now.getMinute() != executeTime.getMinute()) return null;}
     * —— 精确到分钟相等才继续。后果：服务只要停机跨过目标分钟一次（或策略在执行时刻之后
     * 才被启用），当天这次生成就<b>永久丢失</b>，DAILY 要等到明天同一时刻、WEEKLY 要等到
     * 下周同一天才会再次命中。类头注释当时声称的"下次扫描天然补上"<b>根本走不到</b>——
     * 第一行就 return 了。
     *
     * <p><b>为什么返回原定时刻而不是补跑发生的当前时刻</b>：{@code scheduledSlot} 同时是
     * 幂等键。返回当前时刻会让每分钟算出不同的键，唯一约束拦不住，变成每分钟生成一个版本；
     * 返回原定时刻则当天所有后续扫描都算出同一个键，首次插入成功、其余被
     * {@code UNIQUE(customer_no, scheduled_slot)} 短路（{@code generateVersion} 里
     * {@code alreadyExisted} 分支）。验收断言 testcases.md:121 专门盯这一点。
     */
    OffsetDateTime computeSlotIfDue(CustomerPriceAdjustStrategy s, OffsetDateTime now) {
        if (s.executeTime == null) return null;

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

        // 原定时刻 = 幂等键。now 尚未到达则本轮不执行；已到达/已过则返回它（= 补跑）。
        OffsetDateTime slot = OffsetDateTime.of(today, s.executeTime, now.getOffset());
        if (now.isBefore(slot)) return null;
        return slot;
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
