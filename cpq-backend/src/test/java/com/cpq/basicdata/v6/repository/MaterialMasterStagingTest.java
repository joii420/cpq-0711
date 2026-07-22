package com.cpq.basicdata.v6.repository;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0721 报价数据版本升级 · B9 —— material_master 主档暂存自测（方案甲）。
 *
 * <p>验证：①{@code pendingQuotationId} 非空调用不写 {@code material_master}，只写暂存表；
 * ②{@code promoteStaging} 后覆盖式落 {@code material_master}（backtask B9 自检"导入改了单重→
 * 通过前 unit_weight 不变；通过后变为新值"）；③{@code clearStaging} 清理干净。
 */
@QuarkusTest
class MaterialMasterStagingTest {

    @Inject MaterialMasterRepository repo;
    @Inject EntityManager em;

    @Test
    @TestTransaction
    void pendingWriteStagesNotUpsertsRealTable() {
        UUID qid = UUID.randomUUID();
        String materialNo = "T9A" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        repo.upsertBatchWithWeight(
            List.of(new MaterialMasterRepository.WeightRow(materialNo, new BigDecimal("1.500000"))),
            null, qid);

        assertTrue(repo.findByMaterialNo(materialNo).isEmpty(), "pending 模式不应直接写 material_master");
        List<MaterialMasterRepository.StagedRow> staged = repo.listStaging(qid);
        assertEquals(1, staged.size());
        assertEquals(materialNo, staged.get(0).materialNo());
        assertEquals(0, new BigDecimal("1.500000").compareTo(staged.get(0).unitWeight()));
    }

    @Test
    @TestTransaction
    void promoteStagingUpsertsRealTableThenStagingEmpty() {
        UUID qid = UUID.randomUUID();
        String materialNo = "T9B" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        repo.upsertBatchWithWeight(
            List.of(new MaterialMasterRepository.WeightRow(materialNo, new BigDecimal("2.750000"))),
            null, qid);
        assertTrue(repo.findByMaterialNo(materialNo).isEmpty(), "通过前 material_master 不应有该料号");

        int promoted = repo.promoteStaging(qid, null);
        assertEquals(1, promoted);

        var mm = repo.findByMaterialNo(materialNo);
        assertTrue(mm.isPresent(), "通过后应已 upsert 进 material_master");
        assertEquals(0, new BigDecimal("2.750000").compareTo(mm.get().unitWeight));

        // promoteStaging 本身不清理暂存行（由调用方 QuoteBackfillService 在同事务内统一清理，
        // 避免"部分成功部分残留"）——显式验证该约定：此刻暂存应仍在，clearStaging 后才清空。
        assertEquals(1, repo.listStaging(qid).size(), "promote 不应自行清理暂存行");
        repo.clearStaging(qid);
        assertTrue(repo.listStaging(qid).isEmpty(), "clearStaging 后暂存应清空");
    }

    @Test
    @TestTransaction
    void clearStagingRemovesAll() {
        UUID qid = UUID.randomUUID();
        String materialNo = "T9C" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        repo.upsertBatchMaterialNoOnly(List.of(materialNo), null, qid);
        assertEquals(1, repo.listStaging(qid).size());
        repo.clearStaging(qid);
        assertTrue(repo.listStaging(qid).isEmpty());
    }
}
