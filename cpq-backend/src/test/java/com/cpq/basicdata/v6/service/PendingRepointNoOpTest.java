package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260909 · V6 老表退役 · <b>AC-8 / A2 行为面</b>（`需求文档.md` §⑥ 2026-09-09 订正版）。
 *
 * <p><b>为什么需要这个测试，而原判据不行</b>：AC-8 原文写「改动前后各跑一次完整建单流程，
 * 10 张表行数与 pending 指纹均不变」—— 那条断言<b>不可能失败</b>：
 * <ul>
 *   <li>{@code repointPendingOwnership} 的 {@code WHERE pending_quotation_id = :importRecordId}
 *       在共享库上<b>当前命中面已为 0</b>（P-2 实证）⇒ 老代码本来就不写；</li>
 *   <li>10 张表里 8 张 pending 为空，指纹恒为空串 md5 ⇒「空集等于空集」永远成立。</li>
 * </ul>
 * ⇒ 把 B-8 整个撤回重跑，那条断言照样绿。<b>本测试反过来先把命中面造出来</b>：
 * 造一条真实挂在 {@code import_record.id} 上的 pending 行，再调 {@code repointPendingOwnership}，
 * 断言归属<b>未被改写</b>。老代码在这里必然改写 ⇒ 必然变红（AC-8/A3 还原实验已实跑验证）。
 *
 * <p>🚫 <b>只能在独立测试库跑</b>：本用例会写 {@code material_master} / {@code import_record}。
 * {@code application-test.properties} 已于本任务批次 0（B-1 / AC-1）改指 {@code cpq_db_test}，
 * <b>不再是共享 dev 库</b>；配合 {@code @TestTransaction} 方法结束自动回滚，零残留。
 * ⚠️ 若有人把测试库改回 {@code cpq_db_0724}，本用例会与 AC-10「10 张表行数不变」直接冲突。
 */
@QuarkusTest
class PendingRepointNoOpTest {

    @Inject V6QuotationCommitService commitService;
    @Inject MaterialMasterRepository materialMasterRepo;
    @Inject EntityManager em;

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * 造一条真实 {@code import_record}（NOT NULL 列：original_file_name / import_status / imported_by；
     * {@code imported_by} 带 FK {@code import_record_imported_by_fkey} ⇒ 必须取库里已存在的用户）。
     */
    private UUID insertRealImportRecord() {
        List<?> users = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
        assertFalse(users.isEmpty(), "前置：测试库需至少一个 user 表行（imported_by 有外键约束）");
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO import_record (id, original_file_name, import_status, imported_by, created_at, system_type) "
                + "VALUES (:id, :fn, 'SUCCESS', :by, NOW(), 'QUOTE')")
            .setParameter("id", id)
            .setParameter("fn", "AC8-A2-" + rand() + ".xlsx")
            .setParameter("by", (UUID) users.get(0))
            .executeUpdate();
        em.flush();
        // 阳性对照：这条 import_record 真的存在（生产路径下 pending 归属 key 必是 import_record.id）
        Number n = (Number) em.createNativeQuery("SELECT count(*) FROM import_record WHERE id = :id")
            .setParameter("id", id).getSingleResult();
        assertEquals(1L, n.longValue(), "夹具 import_record 应已落库");
        return id;
    }

    private UUID pendingOwnerOf(String materialNo) {
        List<?> r = em.createNativeQuery(
                "SELECT pending_quotation_id FROM material_master WHERE material_no = :no")
            .setParameter("no", materialNo).getResultList();
        assertEquals(1, r.size(), "夹具行应恰好一行");
        return (UUID) r.get(0);
    }

    /**
     * AC-8 / A2：{@code repointPendingOwnership} 已 no-op —— 即便命中面非空，pending 归属也不被改写。
     *
     * <p>三段结构，缺一不可：
     * <ol>
     *   <li><b>造命中面</b>：pending 行挂真实 {@code import_record.id}（老代码的 WHERE 必然命中它）；</li>
     *   <li><b>阳性对照</b>：先断言这一行确实带上了 {@code importRecordId} —— 证明量具够得着，
     *       不是「因为根本没造出行来所以什么都没变」；</li>
     *   <li><b>目标断言</b>：调用后归属仍是 {@code importRecordId}，没被改成 {@code quotationId}。</li>
     * </ol>
     */
    @Test
    @TestTransaction
    void repointPendingOwnership_isNoOp_ownerNotRewritten() {
        UUID importRecordId = insertRealImportRecord();
        UUID quotationId = UUID.randomUUID();
        assertNotEquals(importRecordId, quotationId, "前置：两个 id 必须不同，否则老代码天然 no-op，用例失去判别力");

        String materialNo = "T260909A2" + rand();
        materialMasterRepo.upsertBatchMaterialNoOnly(List.of(materialNo), null, importRecordId);
        em.flush();

        // ② 阳性对照：命中面确实存在
        assertEquals(importRecordId, pendingOwnerOf(materialNo),
            "阳性对照：夹具行应带上 importRecordId 作 pending 归属；若此断言就失败，说明命中面根本没造出来，"
            + "后面的「未被改写」是空验证");

        // ③ 目标断言
        commitService.repointPendingOwnership(importRecordId, quotationId);
        em.flush();
        em.clear();

        assertEquals(importRecordId, pendingOwnerOf(materialNo),
            "AC-8/A2：repointPendingOwnership 已随 task-260909 摘除（A0-1 乙 · no-op），"
            + "pending 归属不应被改写为 quotationId。若此断言失败 = B-8 的摘除没生效（或被回退）");
    }
}
