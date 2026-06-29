package com.cpq.quotation.service;

import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Phase 5 — submit 冻结前端 quoteExcelValues 快照，无后端重算漂移。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>{@code QuotationService.submit} 只做：状态校验 → 客户快照 → 行键唯一性校验
 *       → product snapshot → 路由审批（try/catch）→ SubmissionSnapshot（try/catch）
 *       → freezeSqlViews（try/catch）→ lineDiscountService.recompute（只改折扣合计字段）
 *       → status = SUBMITTED。</li>
 *   <li>submit 全程 <b>不写 quote_excel_values</b>，前端在草稿期由 saveDraft 落的快照
 *       在 submit 后保持原值不漂移。</li>
 *   <li>本测试通过哨兵值（物理上 submit 不可能产出）进行证伪：
 *       若 submit 内出现任何重算覆盖逻辑，哨兵必然消失，断言必然失败。</li>
 * </ul>
 *
 * <h3>前置条件处理</h3>
 * <ol>
 *   <li>行键唯一性校验：自建 line item 无 QuotationLineComponentData，
 *       comps 列表为空，collectConflicts 直接返回空列表，校验通过。</li>
 *   <li>lineDiscountService.recompute：cdList 为空 → subtotalCid=null →
 *       s0=ZERO → lineTotalAmount=ZERO，不碰 quote_excel_values。</li>
 *   <li>approver 路由、SubmissionSnapshot、freezeSqlViews 均 try/catch 非阻塞。</li>
 *   <li>submit 调用改为 submit(qId)（无 userId 重载），与生产代码向后兼容。</li>
 * </ol>
 *
 * <h3>DB 污染防护</h3>
 * <ul>
 *   <li>{@code @TestTransaction} 保证事务在测试结束时自动回滚。</li>
 *   <li>自建行 sort_order=99（哨兵），测试后通过 DB 查询确认 0 残留。</li>
 * </ul>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("SubmitFreezeSnapshotTest — Phase5 submit 冻结前端 quoteExcelValues 无漂移")
public class SubmitFreezeSnapshotTest {

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    /**
     * Phase 5 核心哨兵：物理上 submit 不可能产出的 JSON。
     * submit 不调 buildExcelValues / getExcelView，故不可能写出含此哨兵的值。
     * 若断言失败说明 submit 内出现了意外的重算覆盖。
     */
    private static final String FREEZE_SENTINEL =
            "{\"rows\":[{\"__freeze_sentinel__\":\"FREEZE_KEEP_ME\"}]}";

    // -----------------------------------------------------------------------
    // Helpers（与 SaveDraftExcelSnapshotTest / ExportFromSnapshotTest 对齐）
    // -----------------------------------------------------------------------

    /** 找一条 DRAFT quotation ID；不存在返回 null。 */
    @SuppressWarnings("unchecked")
    private UUID findDraftQuotationId() {
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM quotation WHERE status = 'DRAFT' ORDER BY created_at LIMIT 1")
                .getResultList();
        if (rows.isEmpty()) return null;
        Object o = rows.get(0);
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }

    /**
     * 在当前 @TestTransaction 内自建最小 QuotationLineItem，挂到 quotationId 下。
     * sort_order=99 作为哨兵值，事务回滚后 DB 无残留。
     *
     * <p><b>重要：product_id 和 template_id 故意设为 null。</b>
     * <ul>
     *   <li>product_id=null → collectPartNos 的 JOIN product 无匹配 → partNos 为空
     *       → collectMasterDataSnapshot 提前 return（跳过 plating_plan 等 SQL）
     *       → 规避测试 DB 中 plating_plan.hf_part_no 列不存在导致的 SQL 错误污染事务。</li>
     *   <li>template_id=null → collectTemplateConfigs/collectGlobalVariables 的 JOIN template 无匹配 → 空结果。</li>
     *   <li>submit 代码：{@code if (li.productId == null) continue;} 跳过 product snapshot，安全。</li>
     *   <li>两个字段在 DB schema 上都不是 NOT NULL，允许为 null。</li>
     * </ul>
     */
    private UUID createMinimalLineItem(UUID quotationId) {
        UUID newId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item " +
                "(id, quotation_id, sort_order, created_at) " +
                "VALUES (:id, :qid, 99, :now)")
                .setParameter("id",   newId)
                .setParameter("qid",  quotationId)
                .setParameter("now",  OffsetDateTime.now())
                .executeUpdate();
        return newId;
    }

    /** 直写 quote_excel_values（绕过 Hibernate L1 缓存）。 */
    private void writeQuoteExcelValues(UUID lineItemId, String json) {
        em.createNativeQuery(
                "UPDATE quotation_line_item SET quote_excel_values = CAST(:val AS jsonb) WHERE id = :lid")
                .setParameter("val", json)
                .setParameter("lid", lineItemId)
                .executeUpdate();
    }

    /** 用 native SQL 直接读 quote_excel_values，绕过 Hibernate L1 缓存。 */
    @SuppressWarnings("unchecked")
    private String readQuoteExcelValues(UUID lineItemId) {
        List<Object> r = em.createNativeQuery(
                "SELECT quote_excel_values::text FROM quotation_line_item WHERE id = :lid")
                .setParameter("lid", lineItemId)
                .getResultList();
        return r.isEmpty() ? null : (String) r.get(0);
    }

    private static UUID toUUID(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }

    // -----------------------------------------------------------------------
    // T1 — submit 后 quote_excel_values 不漂移（哨兵仍在）
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("T1: submit 后 quote_excel_values 仍含哨兵 FREEZE_KEEP_ME（无重算覆盖）")
    @TestTransaction
    void submit_doesNotOverwriteQuoteExcelValues_freezeSentinelSurvives() {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId,
                "DB 无 DRAFT 报价单 — 请先通过 UI 创建至少一条 DRAFT 状态报价单后再运行本测试");

        UUID lineItemId = createMinimalLineItem(quotationId);
        assertNotNull(lineItemId,
                "无法自建 QuotationLineItem（无可用 product_id/template_id 引用），请检查基础数据");

        // 预置：写入冻结哨兵快照（模拟 saveDraft Phase3 前端算好并落库的状态）
        writeQuoteExcelValues(lineItemId, FREEZE_SENTINEL);
        em.flush();
        em.clear();

        // 验证预置成功
        String beforeSubmit = readQuoteExcelValues(lineItemId);
        assertNotNull(beforeSubmit, "预置写入后应非 null");
        assertTrue(beforeSubmit.contains("FREEZE_KEEP_ME"),
                "预置写入后应含哨兵 'FREEZE_KEEP_ME'，实际：" + beforeSubmit);

        // 核心动作：submit 报价单
        // submit(UUID id) 无 userId 重载，向后兼容，内部委托 submit(id, null)
        quotationService.submit(quotationId);

        // 断言：submit 后 quote_excel_values 仍含哨兵（无漂移/无重算覆盖）
        em.flush();
        em.clear();
        String afterSubmit = readQuoteExcelValues(lineItemId);

        assertNotNull(afterSubmit,
                "submit 后 quote_excel_values 不应变成 null（submit 不应清空前端快照）");
        assertTrue(afterSubmit.contains("FREEZE_KEEP_ME"),
                "submit 后哨兵应仍在（submit 未重算/覆盖 quote_excel_values），实际值：" + afterSubmit);

        // @TestTransaction 自动回滚，不污染 DB（quotation.status 和 line_item 回到初始状态）
    }

    // -----------------------------------------------------------------------
    // T2 — submit 后 quotation.status == SUBMITTED
    // -----------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("T2: submit 后 quotation.status 变为 SUBMITTED")
    @TestTransaction
    void submit_changesStatusToSubmitted() {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId,
                "DB 无 DRAFT 报价单 — 请先通过 UI 创建至少一条 DRAFT 状态报价单后再运行本测试");

        // submit
        var dto = quotationService.submit(quotationId);

        assertNotNull(dto, "submit 应返回非 null DTO");
        assertEquals("SUBMITTED", dto.status,
                "submit 后 quotation.status 应为 SUBMITTED，实际：" + dto.status);

        // 通过 EntityManager 直接查库二次确认（绕过 L1 缓存）
        em.flush();
        em.clear();
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                "SELECT status FROM quotation WHERE id = :id")
                .setParameter("id", quotationId)
                .getResultList();
        assertFalse(rows.isEmpty(), "DB 应能查到该 quotation");
        assertEquals("SUBMITTED", rows.get(0).toString(),
                "DB 中 status 应为 SUBMITTED");

        // @TestTransaction 回滚后 DB 中 status 恢复为 DRAFT，不污染
    }

    // -----------------------------------------------------------------------
    // T3 — 重复提交应抛 409（防漂移的防御性确认）
    // -----------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("T3: 重复 submit 同一报价单应抛 409 BusinessException")
    @TestTransaction
    void submit_twice_throwsConflict() {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId,
                "DB 无 DRAFT 报价单 — 请先通过 UI 创建至少一条 DRAFT 状态报价单后再运行本测试");

        // 第一次 submit 成功
        quotationService.submit(quotationId);

        // 第二次 submit 应抛 409
        Exception ex = assertThrows(Exception.class,
                () -> quotationService.submit(quotationId),
                "重复 submit 应抛异常");
        String msg = ex.getMessage();
        assertNotNull(msg, "异常 message 不应为 null");
        assertTrue(msg.contains("SUBMITTED") || msg.contains("409") || msg.contains("重复"),
                "异常 message 应提示已是 SUBMITTED 状态，实际：" + msg);

        // @TestTransaction 回滚
    }
}
