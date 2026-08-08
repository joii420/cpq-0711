package com.cpq.priceadjust.service;

import com.cpq.priceadjust.entity.MaterialPriceUpdateJob;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-0807 AC-18 · {@code SKIPPED} 项不被批量重试 {@code loadWaitingItems} 捞回来（api.md §4
 * 「SKIPPED 会被 loadWaitingItems 的 status in (WAITING, CONFLICT) 过滤掉，不会被重试——这是期望
 * 行为」）。本测试固定该契约，防止后续有人"顺手"把 SKIPPED 加进过滤条件。
 */
@QuarkusTest
class PriceAdjustJobExecutionServiceSkippedTest {

    @Inject PriceAdjustJobExecutionService svc;
    @Inject EntityManager em;

    private UUID jobId;
    private UUID skippedItemId, waitingItemId, conflictItemId;

    @AfterEach
    @Transactional
    void cleanup() {
        if (jobId != null) {
            em.createNativeQuery("DELETE FROM material_price_update_job_item WHERE job_id=:id").setParameter("id", jobId).executeUpdate();
            em.createNativeQuery("DELETE FROM material_price_update_job WHERE id=:id").setParameter("id", jobId).executeUpdate();
        }
    }

    @Test
    @Transactional
    void loadWaitingItems_excludesSkipped_includesWaitingAndConflict() {
        // quotation_id 有 FK 约束（REFERENCES quotation(id)），取库里已有的任意一条，跨环境稳
        // （与 MaterialVersionUpgradeServiceS3Test 等既有测试同一手法）。
        UUID anyQuotationId = (UUID) em.createNativeQuery("SELECT id FROM quotation LIMIT 1").getSingleResult();

        MaterialPriceUpdateJob job = new MaterialPriceUpdateJob();
        job.customerNo = "TEST-AC18-" + UUID.randomUUID().toString().substring(0, 8);
        job.status = MaterialPriceUpdateJob.RUNNING;
        job.persist();
        jobId = job.id;

        skippedItemId = newItem(job.id, anyQuotationId, MaterialPriceUpdateJobItem.SKIPPED);
        waitingItemId = newItem(job.id, anyQuotationId, MaterialPriceUpdateJobItem.WAITING);
        conflictItemId = newItem(job.id, anyQuotationId, MaterialPriceUpdateJobItem.CONFLICT);
        newItem(job.id, anyQuotationId, MaterialPriceUpdateJobItem.SUCCESS);
        newItem(job.id, anyQuotationId, MaterialPriceUpdateJobItem.FAILED);

        List<MaterialPriceUpdateJobItem> waiting = svc.loadWaitingItems(job.id);
        List<UUID> ids = waiting.stream().map(it -> it.id).toList();

        assertTrue(ids.contains(waitingItemId), "WAITING 项应被捞回来重试");
        assertTrue(ids.contains(conflictItemId), "CONFLICT 项应被捞回来重试");
        assertFalse(ids.contains(skippedItemId), "AC-18：SKIPPED 项不得被批量重试捞回来");
        assertEquals(2, waiting.size(), "只应捞到 WAITING+CONFLICT 两项，SUCCESS/FAILED/SKIPPED 都不应出现");
    }

    private UUID newItem(UUID jobId, UUID quotationId, String status) {
        MaterialPriceUpdateJobItem item = new MaterialPriceUpdateJobItem();
        item.jobId = jobId;
        item.quotationId = quotationId;
        item.materialNo = "TEST-MAT";
        item.status = status;
        item.persist();
        return item.id;
    }
}
