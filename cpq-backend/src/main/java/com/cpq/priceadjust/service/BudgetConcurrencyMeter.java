package com.cpq.priceadjust.service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * task-260920 B-5 返工 · 试算名额的计量器（包内可见，供测试 / 诊断读峰值）。
 * 「在算」= 已取得名额、尚未归还（名额 → 依据单锁 → 抢占 → 试算事务 → 条件完成 → 放锁 → 还名额 这一整段）。
 */
public final class BudgetConcurrencyMeter {

    private final AtomicInteger background = new AtomicInteger();
    private final AtomicInteger backgroundPeak = new AtomicInteger();
    private final AtomicInteger interactive = new AtomicInteger();
    private final AtomicInteger interactivePeak = new AtomicInteger();

    void enter(boolean isBackground) {
        if (isBackground) backgroundPeak.accumulateAndGet(background.incrementAndGet(), Math::max);
        else interactivePeak.accumulateAndGet(interactive.incrementAndGet(), Math::max);
    }

    void exit(boolean isBackground) {
        if (isBackground) background.decrementAndGet();
        else interactive.decrementAndGet();
    }

    public int backgroundInFlight() { return background.get(); }
    public int backgroundPeak() { return backgroundPeak.get(); }
    public int interactiveInFlight() { return interactive.get(); }
    public int interactivePeak() { return interactivePeak.get(); }

    /** 测试用：把峰值清到当前在算数。 */
    public void resetPeaks() {
        backgroundPeak.set(background.get());
        interactivePeak.set(interactive.get());
    }
}
