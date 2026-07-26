package com.cpq.basicdata.v6.repository;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0726 · B7.1/B7.2 —— material_master 行级 pending 标记自测（取代已退役的
 * {@code pending_material_master_staging} 暂存机制自测 {@code MaterialMasterStagingTest}）。
 * spec: dev-docs/task-0708-导入报价单和导入核价单的数据落库规则澄清/repair-0726-BOM中料件类投入料号没有落库/
 * {需求说明.md §12.1, backtask.md §2}
 *
 * <p><b>B7.1 改造既有</b>（3 条，语义已从"暂存表"整体迁移到"正表 + pending_quotation_id 标记列"）：
 * <ol>
 *   <li>{@link #pendingWriteLandsInRealTableWithMarker}：原
 *       {@code pendingWriteStagesNotUpsertsRealTable} 断言"正表 count=0"已反转为"正表有行且带标记"；</li>
 *   <li>{@link #flipPendingClearsMarkerRowStays}：原 {@code promoteStagingUpsertsRealTableThenStagingEmpty}
 *       改为验证 {@code flipPending} 清空标记、行仍在（不再有 promote/clearStaging 两阶段）；</li>
 *   <li>{@link #deletePendingWithGuardDeletesUnreferenced_KeepsReferencedByOtherQuotation}：原
 *       {@code clearStagingRemovesAll}（暂存表整表清空）改为验证带引用守卫的
 *       {@code deletePendingWithGuard}（无引用删、别单引用不删）。</li>
 * </ol>
 *
 * <p><b>B7.2 新增用例</b>（T1~T5，见各方法 javadoc）。
 *
 * <p>每个用例用独立随机后缀的 material_no，配合 {@code @TestTransaction}（方法结束自动回滚），
 * 不污染共享 dev DB，无需显式清理。
 */
@QuarkusTest
class MaterialMasterPendingTest {

    @Inject MaterialMasterRepository repo;
    @Inject EntityManager em;

    /** 8 位大写十六进制随机后缀，保证 material_no 在共享 dev DB 里不与既有数据碰撞。 */
    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // =====================================================================
    // B7.1 改造既有（3 条）
    // =====================================================================

    @Test
    @TestTransaction
    void pendingWriteLandsInRealTableWithMarker() {
        UUID qid = UUID.randomUUID();
        String materialNo = "T9A" + rand();

        repo.upsertBatchWithWeight(
            List.of(new MaterialMasterRepository.WeightRow(materialNo, new BigDecimal("1.500000"))),
            null, qid);

        var mm = repo.findByMaterialNo(materialNo);
        assertTrue(mm.isPresent(), "repair-0726：pending 写入应直接落 material_master 正表（原断言"
            + "\"不应写正表\"已反转）");
        assertEquals(qid, mm.get().pendingQuotationId, "INSERT 分支应带上本单 pending 标记");
        assertEquals(0, new BigDecimal("1.500000").compareTo(mm.get().unitWeight));
    }

    @Test
    @TestTransaction
    void flipPendingClearsMarkerRowStays() {
        UUID qid = UUID.randomUUID();
        String materialNo = "T9B" + rand();

        repo.upsertBatchWithWeight(
            List.of(new MaterialMasterRepository.WeightRow(materialNo, new BigDecimal("2.750000"))),
            null, qid);
        var before = repo.findByMaterialNo(materialNo).orElseThrow();
        assertEquals(qid, before.pendingQuotationId, "通过前应仍带 pending 标记");

        int flipped = repo.flipPending(qid);
        assertEquals(1, flipped, "核价通过：本单 pending 料号应转正 1 行");

        // flipPending 是原生 SQL UPDATE，绕过 Hibernate 一级缓存：上面 before 那次 find 已把该行
        // 实体装进本事务的持久化上下文身份映射，若不 clear() 直接再 find，会拿到 find 时刻缓存的
        // 旧实例（pendingQuotationId 仍是 before 读到的值），而不是原生 UPDATE 刚写入 DB 的新值——
        // 本用例最初就是这样假绿/误红过一次，实测确认必须 clear() 才能读到 flipPending 后的真实状态。
        em.clear();
        var after = repo.findByMaterialNo(materialNo);
        assertTrue(after.isPresent(), "flipPending 只清标记，不删行");
        assertNull(after.get().pendingQuotationId, "转正后标记应清空");
        assertEquals(0, new BigDecimal("2.750000").compareTo(after.get().unitWeight), "转正不改其余列");
    }

    @Test
    @TestTransaction
    void deletePendingWithGuardDeletesUnreferenced_KeepsReferencedByOtherQuotation() {
        UUID qid = UUID.randomUUID();
        String unrefNo = "T9C" + rand();
        String refNo = "T9D" + rand();
        UUID otherQid = UUID.randomUUID();

        repo.upsertBatchMaterialNoOnly(List.of(unrefNo, refNo), null, qid);
        assertTrue(repo.findByMaterialNo(unrefNo).isPresent());
        assertTrue(repo.findByMaterialNo(refNo).isPresent());

        // refNo 被"别的"报价单的 material_bom_item 引用为 component_no（pending_quotation_id=otherQid，
        // 与本单 qid 不同 → 命中守卫 SQL 的 `x.pending_quotation_id <> :qid` 分支，构成拦截条件）。
        insertBomItemReference(refNo, "T9CD-PARENT-CUST", "T9CD-PARENT-MAT", otherQid);

        int deleted = repo.deletePendingWithGuard(qid);
        assertEquals(1, deleted, "本单 2 条 pending 行里只有 unrefNo 无阻塞引用，应删 1 条");
        assertTrue(repo.findByMaterialNo(unrefNo).isEmpty(), "无引用应被删除（原 clearStaging 语义的等价物）");
        assertTrue(repo.findByMaterialNo(refNo).isPresent(), "被别单引用的行应保留，不能粗暴清空");
    }

    // =====================================================================
    // B7.2 新增用例（T1~T5）
    // =====================================================================

    /**
     * T1：已存在正式行（pending_quotation_id IS NULL）被 pending 单再次 upsert ——
     * 该列仍为 NULL（不降级），描述列（material_name/material_type）按 preserveDescriptive=true
     * 语义"只补空不覆盖"（生产侧 8 个 Q0x/MaterialBomMergeHandler 全部固定传 preserveDescriptive=true，
     * 见 upsertBatchNameType 调用点）。分两个子场景：老值非空应保留 / 老值为空应被补上。
     */
    @Test
    @TestTransaction
    void t1_formalRowNotDowngraded_descriptiveColumnsOnlyFillBlank() {
        // 子场景 A：老行描述列非空 —— 不应被 pending 单的新值覆盖
        String materialNoA = "T9E" + rand();
        repo.upsertByMaterialNo(materialNoA, "老名称", null, null, null, "老类型",
            null, null, null, null, null, false); // 12 参重载：preserveDescriptive=false，走 13 参核心 pendingQuotationId=null
        var beforeA = repo.findByMaterialNo(materialNoA).orElseThrow();
        assertNull(beforeA.pendingQuotationId, "前置：应为正式行（pending_quotation_id IS NULL）");

        UUID qid = UUID.randomUUID();
        repo.upsertBatchNameType(
            List.of(new MaterialMasterRepository.NameTypeRow(materialNoA, "新名称", "新类型")),
            null, true, qid);

        // 见 flipPendingClearsMarkerRowStays 的同型注释：beforeA 那次 find 已把实体缓存进持久化上下文，
        // 原生 UPDATE(ON CONFLICT) 不会同步该缓存，必须 clear() 才能验证 DB 里的真实结果而非旧缓存。
        em.clear();
        var afterA = repo.findByMaterialNo(materialNoA).orElseThrow();
        assertNull(afterA.pendingQuotationId, "正式行不应被 pending 单降级为 pending（ON CONFLICT 不写该列）");
        assertEquals("老名称", afterA.materialName, "preserveDescriptive=true：已有名称不应被覆盖");
        assertEquals("老类型", afterA.materialType, "preserveDescriptive=true：已有类型不应被覆盖");

        // 子场景 B：老行描述列为空 —— pending 单应能把空值补上（"仅空才回填"）
        String materialNoB = "T9F" + rand();
        repo.upsertByMaterialNo(materialNoB, null, null, null, null, null,
            null, null, null, null, null, false);
        UUID qid2 = UUID.randomUUID();
        repo.upsertBatchNameType(
            List.of(new MaterialMasterRepository.NameTypeRow(materialNoB, "补的名称", "补的类型")),
            null, true, qid2);

        var afterB = repo.findByMaterialNo(materialNoB).orElseThrow();
        assertEquals("补的名称", afterB.materialName, "preserveDescriptive=true：老值为空应被 pending 单补上");
        assertEquals("补的类型", afterB.materialType);
        assertNull(afterB.pendingQuotationId, "正式行（即便描述列原本为空）同样不应被降级为 pending");
    }

    /** T2：归属别单的 pending 行被本单 upsert —— 归属不变（不抢占，已属别单 pending 的行不被抢占）。 */
    @Test
    @TestTransaction
    void t2_pendingRowOwnedByOtherQuotationNotHijacked() {
        String materialNo = "T9G" + rand();
        UUID ownerQid = UUID.randomUUID();
        UUID otherQid = UUID.randomUUID();

        repo.upsertBatchMaterialNoOnly(List.of(materialNo), null, ownerQid);
        var before = repo.findByMaterialNo(materialNo).orElseThrow();
        assertEquals(ownerQid, before.pendingQuotationId, "前置：应归属 ownerQid");

        // 另一张未核准报价单尝试 upsert 同料号（真实场景：两张报价单在同一批未核价窗口内都引用了
        // 同一个新料号——先到者建号，后到者只能补充描述列，不能抢占归属）。
        repo.upsertBatchNameType(
            List.of(new MaterialMasterRepository.NameTypeRow(materialNo, "别单改的名称", "别单改的类型")),
            null, true, otherQid);

        // 同上：before 那次 find 已缓存实体，不 clear() 会让本断言"假绿"（即使 SQL 真的错误地
        // 抢占了归属，缓存的旧实体也会让断言看起来通过）——必须 clear() 才是验证 DB 真实状态。
        em.clear();
        var after = repo.findByMaterialNo(materialNo).orElseThrow();
        assertEquals(ownerQid, after.pendingQuotationId, "已属别单 pending 的行不应被本次 upsert 抢占归属");
    }

    /** T3：引用守卫 —— 料号被别单的 material_bom_item.component_no 引用时，deletePendingWithGuard 不删该行、返回 0。 */
    @Test
    @TestTransaction
    void t3_deletePendingWithGuard_blockedByOtherQuotationBomItemReference() {
        String materialNo = "T9H" + rand();
        UUID qid = UUID.randomUUID();
        UUID otherQid = UUID.randomUUID();

        repo.upsertBatchMaterialNoOnly(List.of(materialNo), null, qid);
        assertTrue(repo.findByMaterialNo(materialNo).isPresent());

        insertBomItemReference(materialNo, "T9H-PARENT-CUST", "T9H-PARENT-MAT", otherQid);

        int deleted = repo.deletePendingWithGuard(qid);
        assertEquals(0, deleted, "被别单引用应拦下删除，返回 0");
        assertTrue(repo.findByMaterialNo(materialNo).isPresent(), "料号行应保留");
    }

    /**
     * T4：删单顺序 —— 生产代码里 QuoteImportService.clearPreviousPending / QuotationService.cleanupPendingV6Data
     * 都是"先删 8 张 V6 表 pending 行、再删 material_master pending 行"。本用例验证"料号行确实被回收"，
     * 并诚实验证"顺序颠倒是否真的会让回收失败"这个问题。
     *
     * <p><b>诚实验证结论</b>：{@code deletePendingWithGuard} 的守卫 SQL 是
     * {@code NOT EXISTS (... WHERE x.component_no = mm.material_no
     * AND (x.pending_quotation_id IS NULL OR x.pending_quotation_id <> :qid))}——
     * 注意 {@code <> :qid} 这个条件：它把"本单自己的" pending 引用行（pending_quotation_id = :qid）
     * 排除在"构成阻塞"的条件之外。也就是说，即使调用方 <b>不先删 8 张表</b>，本单自己在
     * material_bom_item 里留下的引用行也 <b>不会</b> 让 deletePendingWithGuard 判定"被引用"。
     * 本用例故意反着来——先 upsert 料号行 + 插入本单自己的 material_bom_item 引用行，
     * <b>不删除</b> 该引用行就直接调用 deletePendingWithGuard——实测 deleted=1（料号行确被回收），
     * 证明"先删 8 表再删料号"这个顺序 <b>不是</b> 这里守卫逻辑的必要条件；它是生产代码里的
     * "防御性顺序"（配合别的读路径/避免残留孤儿引用行的数据卫生考虑），不是让"deleted 计数"
     * 由 0 变 1 的承重墙。真正的承重墙是 {@code <> :qid}（区分"别单引用" vs "本单引用"）——
     * 已在 {@link #t3_deletePendingWithGuard_blockedByOtherQuotationBomItemReference} 里验证。
     * 若未来 SQL 改成不排除本单自己的引用（比如误删了 {@code <> :qid}），本用例的
     * {@code assertEquals(1, deleted)} 会失败，从而守住这条真正的不变量。
     */
    @Test
    @TestTransaction
    void t4_deletePendingWithGuard_ownQuotationReferenceDoesNotBlockRecycling() {
        String materialNo = "T9I" + rand();
        UUID qid = UUID.randomUUID();

        repo.upsertBatchMaterialNoOnly(List.of(materialNo), null, qid);
        // 故意不删除、也不先跑"8 张表清理"——模拟"顺序颠倒"的极端情况：
        // 本单自己的 material_bom_item 引用行仍在，直接调用 deletePendingWithGuard。
        insertBomItemReference(materialNo, "T9I-PARENT-CUST", "T9I-PARENT-MAT", qid);

        int deleted = repo.deletePendingWithGuard(qid);
        assertEquals(1, deleted,
            "本单自己的引用行不应顶住回收（守卫 <> :qid 子句已排除同单引用）——" +
            "若此断言失败说明顺序确实是必要条件，需要回去改写本用例为"
            + "\"先删引用行再删料号\"并如实更正 javadoc 结论");
        assertTrue(repo.findByMaterialNo(materialNo).isEmpty(), "料号行应被回收");
    }

    /** T5：listPending 排序稳定 —— 两次调用返回顺序一致，且严格等于 ORDER BY material_no。 */
    @Test
    @TestTransaction
    void t5_listPending_orderedByMaterialNo_stableAcrossCalls() {
        UUID qid = UUID.randomUUID();
        // 故意按"倒序"批量写入（后缀相同长度，只有 A/B 前缀不同，排序不受随机后缀干扰）。
        String suffix = rand();
        String matB = "T9J-B-" + suffix; // 插入顺序在前，但按 material_no 排序应排在后
        String matA = "T9J-A-" + suffix; // 插入顺序在后，但按 material_no 排序应排在前
        repo.upsertBatchMaterialNoOnly(List.of(matB, matA), null, qid);

        List<String> first = toMaterialNos(repo.listPending(qid));
        assertEquals(List.of(matA, matB), first, "listPending 应严格按 material_no ASC 排序，不依赖插入顺序");

        List<String> second = toMaterialNos(repo.listPending(qid));
        assertEquals(first, second, "两次调用应返回一致顺序（稳定排序）");
    }

    private static List<String> toMaterialNos(List<MaterialMasterRepository.StagedRow> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (MaterialMasterRepository.StagedRow r : rows) out.add(r.materialNo());
        return out;
    }

    /** 插入一条 material_bom_item 行，把 componentNo 作为投入料号（component_no）引用，带 pending 标记。 */
    private void insertBomItemReference(String componentNo, String parentCustomerNo, String parentMaterialNo,
                                        UUID pendingQuotationId) {
        em.createNativeQuery(
            "INSERT INTO material_bom_item (system_type, customer_no, material_no, component_no, pending_quotation_id) " +
            "VALUES ('QUOTE', :cust, :mat, :comp, :pq)")
            .setParameter("cust", parentCustomerNo)
            .setParameter("mat", parentMaterialNo)
            .setParameter("comp", componentNo)
            .setParameter("pq", pendingQuotationId)
            .executeUpdate();
    }
}
