package com.cpq.priceadjust.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * repair-0807 FR-4 · {@link MaterialPriceUpdateJob#recountFrom} 判定表单测（api.md §3.2）。
 *
 * <p>纯内存计数逻辑，不触库、不需要 Quarkus 上下文——直接构造 item 列表验证判定表五行：
 * SUCCESS / STALE / FAILED / PARTIAL（含"全部成功但有跳过"）。
 */
class MaterialPriceUpdateJobRecountTest {

    private static MaterialPriceUpdateJobItem itemWithStatus(String status) {
        MaterialPriceUpdateJobItem it = new MaterialPriceUpdateJobItem();
        it.status = status;
        return it;
    }

    @Test
    void allSuccess_noSkipped_isSuccess() {
        List<MaterialPriceUpdateJobItem> items = List.of(
            itemWithStatus(MaterialPriceUpdateJobItem.SUCCESS),
            itemWithStatus(MaterialPriceUpdateJobItem.SUCCESS));
        MaterialPriceUpdateJob job = new MaterialPriceUpdateJob();
        job.recountFrom(items);
        assertEquals(MaterialPriceUpdateJob.SUCCESS, job.status);
        assertEquals(2, job.successCount);
        assertEquals(0, job.skippedCount);
        assertEquals(2, job.totalCount);
    }

    /**
     * 🚨 repair-0807 FR-4 要害断言：即使全部 item 要么 SUCCESS 要么 SKIPPED（"32/32 全成功"骗过财务
     * 的原始症状），只要 skipped>0 就不许报 SUCCESS——必须是 PARTIAL。
     */
    @Test
    void allSuccessOrSkipped_isPartial_notSuccess() {
        List<MaterialPriceUpdateJobItem> items = new ArrayList<>();
        for (int i = 0; i < 31; i++) items.add(itemWithStatus(MaterialPriceUpdateJobItem.SUCCESS));
        items.add(itemWithStatus(MaterialPriceUpdateJobItem.SKIPPED));

        MaterialPriceUpdateJob job = new MaterialPriceUpdateJob();
        job.recountFrom(items);

        assertEquals(MaterialPriceUpdateJob.PARTIAL, job.status,
            "有跳过就不许报 SUCCESS——本次缺陷的传播路径正是「32/32 全成功」骗过了财务");
        assertEquals(31, job.successCount);
        assertEquals(1, job.skippedCount);
        assertEquals(32, job.totalCount);
    }

    @Test
    void allSkipped_noSuccess_isPartial_notFailed() {
        // success==0 且 skipped>0：不满足 "success==0 && skipped==0"(FAILED) 也不满足
        // "success==0 && stale==total"(STALE)，落在"其余"→ PARTIAL。
        List<MaterialPriceUpdateJobItem> items = List.of(
            itemWithStatus(MaterialPriceUpdateJobItem.SKIPPED),
            itemWithStatus(MaterialPriceUpdateJobItem.SKIPPED));
        MaterialPriceUpdateJob job = new MaterialPriceUpdateJob();
        job.recountFrom(items);
        assertEquals(MaterialPriceUpdateJob.PARTIAL, job.status);
        assertEquals(2, job.skippedCount);
    }

    @Test
    void allStale_isStale() {
        List<MaterialPriceUpdateJobItem> items = List.of(
            itemWithStatus(MaterialPriceUpdateJobItem.STALE),
            itemWithStatus(MaterialPriceUpdateJobItem.STALE));
        MaterialPriceUpdateJob job = new MaterialPriceUpdateJob();
        job.recountFrom(items);
        assertEquals(MaterialPriceUpdateJob.STALE, job.status);
    }

    @Test
    void noSuccessNoSkipped_hasFailed_isFailed() {
        List<MaterialPriceUpdateJobItem> items = List.of(
            itemWithStatus(MaterialPriceUpdateJobItem.FAILED),
            itemWithStatus(MaterialPriceUpdateJobItem.CONFLICT));
        MaterialPriceUpdateJob job = new MaterialPriceUpdateJob();
        job.recountFrom(items);
        assertEquals(MaterialPriceUpdateJob.FAILED, job.status);
    }

    @Test
    void mixedSuccessAndFailed_isPartial() {
        List<MaterialPriceUpdateJobItem> items = List.of(
            itemWithStatus(MaterialPriceUpdateJobItem.SUCCESS),
            itemWithStatus(MaterialPriceUpdateJobItem.FAILED));
        MaterialPriceUpdateJob job = new MaterialPriceUpdateJob();
        job.recountFrom(items);
        assertEquals(MaterialPriceUpdateJob.PARTIAL, job.status);
    }
}
