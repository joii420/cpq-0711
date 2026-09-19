package com.cpq.priceadjust.ac260918;

import com.cpq.priceadjust.service.CurrentPeriodRevisionWriter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * test.md §3.2 点名的注入缝：{@code CurrentPeriodRevisionWriter#write(UUID quotationId, UUID targetVersionId,
 * Collection<String> materialNos)}。
 *
 * <p>⚠️ 本文件引用本任务新增的类 —— AC-19 在 master 上跑基线时<b>不要</b>拷贝本文件。
 *
 * <p>用法：{@link #armFailure(UUID)} 后，对该报价单的下一次（及之后每一次）{@code write} 调用抛
 * {@link RuntimeException}；{@link #disarm()} 恢复真实行为。所有调用都记录参数，供断言「分组只写一次」与
 * 「注入确实触发过」。
 */
public final class RevisionWriterInterceptor {

    public record WriteCall(UUID quotationId, UUID targetVersionId, List<String> materialNos,
                            boolean injectedFailure, String thread, long atMillis) {
    }

    private final List<WriteCall> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<UUID> failQuotation = new AtomicReference<>();
    private final AtomicInteger injectedFailures = new AtomicInteger();

    private RevisionWriterInterceptor() {
    }

    public static RevisionWriterInterceptor install() {
        RevisionWriterInterceptor ic = new RevisionWriterInterceptor();
        DelegatingMock.install(CurrentPeriodRevisionWriter.class, ic::before);
        return ic;
    }

    public void armFailure(UUID quotationId) {
        failQuotation.set(quotationId);
    }

    public void disarm() {
        failQuotation.set(null);
    }

    public int injectedFailures() {
        return injectedFailures.get();
    }

    public List<WriteCall> calls() {
        return new ArrayList<>(calls);
    }

    public List<WriteCall> callsFor(UUID quotationId, long fromMillis) {
        List<WriteCall> out = new ArrayList<>();
        for (WriteCall c : calls) {
            if (quotationId.equals(c.quotationId()) && c.atMillis() >= fromMillis) out.add(c);
        }
        return out;
    }

    private void before(Method m, Object[] args) {
        if (!"write".equals(m.getName())) return;
        UUID q = null;
        UUID v = null;
        List<String> mats = new ArrayList<>();
        if (args != null) {
            for (Object a : args) {
                if (a instanceof UUID u) {
                    if (q == null) q = u;
                    else if (v == null) v = u;
                } else if (a instanceof Collection<?> col) {
                    for (Object o : col) mats.add(String.valueOf(o));
                }
            }
        }
        UUID target = failQuotation.get();
        boolean inject = target != null && target.equals(q);
        calls.add(new WriteCall(q, v, List.copyOf(mats), inject, Thread.currentThread().getName(),
            System.currentTimeMillis()));
        if (inject) {
            injectedFailures.incrementAndGet();
            throw new RuntimeException("RP0918A 注入：分组写本期快照失败 quotation=" + q);
        }
    }
}
