package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.common.exception.StaleVersionException;
import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.dto.SaveDraftResponse;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260901 B-5a / B-5c — 增量协议 + 卡片值有条件失效 + 版本指纹 的集成测试。
 *
 * <p>覆盖 AC-3（显式删除）/ AC-4（纯单头保存不动明细）/ AC-6·AC-7（未变行不失效卡片值）/
 * AC-10（值真变了必须失效）/ AC-11（版本 +1）/ AC-12（409 STALE_VERSION）/ AC-20（未点名的行不丢工序）。
 *
 * <h3>夹具口径（读之前先看这段，否则会误判失败原因）</h3>
 * 测试在 {@code @TestTransaction} 里把该报价单的 {@code customer_template_id} 临时置 NULL。
 * 这不是偷懒——B-1c 的失效判定里有一条 ③'：「随后的 {@code snapshotQuotation(id,true)} 会重 expand
 * 缺 driver {@code snapshot_rows} 的行 ⇒ 也要失效」。若夹具挂着一个带 driver 页签的真实模板，
 * 而自建的最小行没有对应的 {@code snapshot_rows}，③' 会先于 ①/② 命中，卡片值必然被置 NULL，
 * 本测试就永远测不到「rowData 语义比对」这条主线，还会被误读成「B-1a 没生效」。
 * 置 NULL 模板 ⇒ driver 集合为空 ⇒ ③' 不参与 ⇒ 断言精确指向 ①/②。事务回滚，不留痕。
 */
@QuarkusTest
@DisplayName("SaveDraftIncrementalProtocolTest — 三数组协议 / 条件失效 / 版本指纹")
public class SaveDraftIncrementalProtocolTest {

    @Inject QuotationService quotationService;
    @Inject EntityManager em;

    private static final String SENTINEL = "{\"tabs\":[{\"baseRows\":[{\"__keep__\":\"NOT_INVALIDATED\"}]}]}";
    /** 入库后 PG 会规范化成「键按字节长度重排 + 空格」的形态。 */
    private static final String ROW_DATA_DB = "[{\"row_index\": 0, \"单位\": \"g\", \"材料净重\": \"3.3\"}]";
    /** 前端 JSON.stringify 形态：键序不同、无空格，语义与上面完全一致。 */
    private static final String ROW_DATA_FRONTEND = "[{\"材料净重\":\"3.3\",\"单位\":\"g\",\"row_index\":0}]";
    /** 只改最后一位小数。 */
    private static final String ROW_DATA_CHANGED = "[{\"材料净重\":\"3.4\",\"单位\":\"g\",\"row_index\":0}]";

    // ────────────────────────────────── 夹具 ──────────────────────────────────

    private record Fixture(UUID quotationId, UUID lineItemId, UUID componentId,
                           UUID productId, UUID templateId) {}

    @SuppressWarnings("unchecked")
    private Fixture buildFixture() {
        List<Object> qs = em.createNativeQuery(
                "SELECT id FROM quotation WHERE status = 'DRAFT' ORDER BY created_at LIMIT 1").getResultList();
        assertFalse(qs.isEmpty(), "库里没有 DRAFT 报价单 —— 本测试需要至少一张草稿单做 FK 宿主");
        UUID qid = toUUID(qs.get(0));

        // 见类注释：置空模板，隔离 B-1c 条件③'
        em.createNativeQuery("UPDATE quotation SET customer_template_id = NULL WHERE id = :q")
            .setParameter("q", qid).executeUpdate();

        List<Object> products = em.createNativeQuery("SELECT id FROM product LIMIT 1").getResultList();
        List<Object> templates = em.createNativeQuery("SELECT id FROM template LIMIT 1").getResultList();
        assertFalse(products.isEmpty(), "库里没有 product —— 基础数据缺失");
        assertFalse(templates.isEmpty(), "库里没有 template —— 基础数据缺失");
        UUID productId = toUUID(products.get(0));
        UUID templateId = toUUID(templates.get(0));

        UUID lineId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, product_id, template_id, sort_order, " +
                " created_at, subtotal, quote_card_values, costing_card_values) " +
                "VALUES (:id, :qid, :pid, :tid, 7, :now, 12.000000000000, " +
                " CAST(:cv AS jsonb), CAST(:cv AS jsonb))")
            .setParameter("id", lineId).setParameter("qid", qid)
            .setParameter("pid", productId).setParameter("tid", templateId)
            .setParameter("now", OffsetDateTime.now()).setParameter("cv", SENTINEL)
            .executeUpdate();

        UUID compId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data " +
                "(id, line_item_id, component_id, tab_name, row_data, subtotal, sort_order, created_at, snapshot_rows) " +
                "VALUES (gen_random_uuid(), :lid, :cid, '材料成本', CAST(:rd AS jsonb), 12.000000000000, 0, :now, " +
                " CAST('[]' AS jsonb))")
            .setParameter("lid", lineId).setParameter("cid", compId)
            .setParameter("rd", ROW_DATA_DB).setParameter("now", OffsetDateTime.now())
            .executeUpdate();

        // 工序：AC-20 —— 本行不出现在 payload 时，它必须原封不动。
        // process_no 有 FK 指向 process_master，必须取库里真实存在的编号（不能编一个）。
        List<Object> procs = em.createNativeQuery(
                "SELECT process_no FROM process_master ORDER BY process_no LIMIT 1").getResultList();
        assertFalse(procs.isEmpty(), "库里没有 process_master 记录 —— 基础数据缺失，AC-20 无法验证");
        em.createNativeQuery(
                "INSERT INTO quotation_line_process (id, line_item_id, process_no) " +
                "VALUES (gen_random_uuid(), :lid, :pn)")
            .setParameter("lid", lineId).setParameter("pn", String.valueOf(procs.get(0)))
            .executeUpdate();

        em.flush();
        em.clear();
        return new Fixture(qid, lineId, compId, productId, templateId);
    }

    private SaveDraftRequest.LineItemDraft draftOf(Fixture f, String rowData) {
        SaveDraftRequest.LineItemDraft d = new SaveDraftRequest.LineItemDraft();
        d.id = f.lineItemId();
        d.productId = f.productId();
        d.templateId = f.templateId();
        d.sortOrder = 7;
        SaveDraftRequest.ComponentDataDraft cd = new SaveDraftRequest.ComponentDataDraft();
        cd.componentId = f.componentId();
        cd.tabName = "材料成本";
        cd.rowData = rowData;
        cd.sortOrder = 0;
        d.componentData = List.of(cd);
        return d;
    }

    private SaveDraftRequest incrementalRequest(Fixture f) {
        SaveDraftRequest req = new SaveDraftRequest();
        req.baseVersion = readVersion(f.quotationId());
        req.added = List.of();
        req.modified = List.of();
        req.removed = List.of();
        return req;
    }

    // ────────────────────────────────── 用例 ──────────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("AC-6/AC-7：rowData 只是键序不同 → 卡片值不被置 NULL")
    void semanticallyUnchangedRow_keepsCardValues() {
        Fixture f = buildFixture();
        assertNotEquals(ROW_DATA_DB, ROW_DATA_FRONTEND, "前置：两个串必须文本不等");

        SaveDraftRequest req = incrementalRequest(f);
        req.modified = List.of(draftOf(f, ROW_DATA_FRONTEND));
        quotationService.saveDraft(f.quotationId(), req);

        em.flush(); em.clear();
        String[] cv = readCardValues(f.lineItemId());
        assertNotNull(cv[0], "AC-7：rowData 语义未变，quote_card_values 不该被置 NULL —— "
                + "置 NULL 会让 ensureCardValues 重新选中本行，正是那 54 秒全量重算的来源");
        assertNotNull(cv[1], "AC-19：核价侧同理，costing_card_values 不该被置 NULL");
        assertTrue(cv[0].contains("NOT_INVALIDATED"), "卡片值应逐字保持原值，实际：" + cv[0]);
    }

    @Test
    @TestTransaction
    @DisplayName("AC-10：值差最后一位小数 → 卡片值必须置 NULL 并重算")
    void changedValue_invalidatesCardValues() {
        Fixture f = buildFixture();

        SaveDraftRequest req = incrementalRequest(f);
        req.modified = List.of(draftOf(f, ROW_DATA_CHANGED));
        quotationService.saveDraft(f.quotationId(), req);

        em.flush(); em.clear();
        String[] cv = readCardValues(f.lineItemId());
        assertNull(cv[0], "AC-10：3.3 → 3.4 是真改动，quote_card_values 必须置 NULL 让它重算，实际：" + cv[0]);
        assertNull(cv[1], "AC-10：costing_card_values 同理，实际：" + cv[1]);
        String rd = readRowData(f.lineItemId());
        assertTrue(rd.contains("3.4"), "新值必须落库，实际：" + rd);
    }

    @Test
    @TestTransaction
    @DisplayName("AC-4/AC-20：三数组全空 → 不动任何明细行（工序、卡片值、row_data 全部原样）")
    void emptyArrays_touchNothing() {
        Fixture f = buildFixture();

        SaveDraftRequest req = incrementalRequest(f);
        req.projectName = "AC4-" + System.currentTimeMillis();
        quotationService.saveDraft(f.quotationId(), req);

        em.flush(); em.clear();
        assertNotNull(readCardValues(f.lineItemId())[0], "AC-4：只改单头，明细行卡片值不该被碰");
        assertEquals(1L, countProcesses(f.lineItemId()),
                "AC-20：本行没出现在 payload 里，它的工序必须一条不少 —— "
              + "改造前是「全删全建」，漏发即丢工序");
        assertEquals(1L, countLineItems(f.quotationId(), f.lineItemId()),
                "AC-3 反向：removed[] 为空时，任何行都不该被删（旧协议下「没出现即删除」会把它删掉）");
    }

    @Test
    @TestTransaction
    @DisplayName("AC-3：removed[] 点名的行才被删，且子表一并清掉")
    void explicitRemoval() {
        Fixture f = buildFixture();

        SaveDraftRequest req = incrementalRequest(f);
        req.removed = List.of(f.lineItemId());
        quotationService.saveDraft(f.quotationId(), req);

        em.flush(); em.clear();
        assertEquals(0L, countLineItems(f.quotationId(), f.lineItemId()), "AC-3：被点名的行应已删除");
        assertEquals(0L, countProcesses(f.lineItemId()), "AC-3：子表 quotation_line_process 应一并清掉");
        assertEquals(0L, countComponentData(f.lineItemId()), "AC-3：子表 quotation_line_component_data 应一并清掉");
    }

    @Test
    @TestTransaction
    @DisplayName("AC-11：保存成功 → user_data_version 从 N 变 N+1，响应回传新值")
    void versionIncrements() {
        Fixture f = buildFixture();
        int before = readVersion(f.quotationId());

        SaveDraftRequest req = incrementalRequest(f);
        req.modified = List.of(draftOf(f, ROW_DATA_FRONTEND));
        SaveDraftResponse resp = quotationService.saveDraft(f.quotationId(), req);

        assertEquals(before + 1, resp.userDataVersion,
                "AC-11：响应里的 userDataVersion 必须是 N+1（前端拿它更新本地基线）");
        assertEquals(before + 1, readVersion(f.quotationId()), "AC-11：库中版本号也必须是 N+1");
    }

    @Test
    @TestTransaction
    @DisplayName("AC-12：baseVersion 过期 → 409 STALE_VERSION，且一个字节都没落库")
    void staleVersionRejected() {
        Fixture f = buildFixture();
        int current = readVersion(f.quotationId());

        SaveDraftRequest req = new SaveDraftRequest();
        req.baseVersion = current + 1;              // 模拟「别人已经先保存过一次」
        req.added = List.of();
        req.removed = List.of();
        req.modified = List.of(draftOf(f, ROW_DATA_CHANGED));
        req.projectName = "SHOULD-NOT-PERSIST";

        StaleVersionException ex = assertThrows(StaleVersionException.class,
                () -> quotationService.saveDraft(f.quotationId(), req));
        assertEquals(409, ex.getCode());
        assertEquals(current, ex.getCurrentVersion(),
                "409 响应必须带上库中当前版本号，前端据此判断落后了多少");

        em.clear();
        assertTrue(readRowData(f.lineItemId()).contains("3.3"),
                "AC-12：校验必须发生在任何写入之前 —— 冲突时不能留下半截脏数据");
    }

    @Test
    @TestTransaction
    @DisplayName("协议冲突：lineItems 与三数组同时出现 → 400（不能猜用户想用哪套删除语义）")
    void bothProtocols_rejected() {
        Fixture f = buildFixture();
        SaveDraftRequest req = incrementalRequest(f);
        req.lineItems = List.of(draftOf(f, ROW_DATA_FRONTEND));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.saveDraft(f.quotationId(), req));
        assertEquals(400, ex.getCode());
    }

    @Test
    @TestTransaction
    @DisplayName("B-2e：增量协议下 sortOrder 缺失 → 400（下标不再代表行序）")
    void sortOrderRequired() {
        Fixture f = buildFixture();
        SaveDraftRequest req = incrementalRequest(f);
        SaveDraftRequest.LineItemDraft d = draftOf(f, ROW_DATA_FRONTEND);
        d.sortOrder = null;
        req.modified = List.of(d);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.saveDraft(f.quotationId(), req));
        assertEquals(400, ex.getCode());
    }

    @Test
    @TestTransaction
    @DisplayName("B-2b 安全网：modified[] 的 id 不属于本单 → 400（绝不能默默新建一行）")
    void modifiedIdMustBelongToQuotation() {
        Fixture f = buildFixture();
        SaveDraftRequest req = incrementalRequest(f);
        SaveDraftRequest.LineItemDraft d = draftOf(f, ROW_DATA_FRONTEND);
        d.id = UUID.randomUUID();                 // 不存在的行
        req.modified = List.of(d);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.saveDraft(f.quotationId(), req));
        assertEquals(400, ex.getCode());
    }

    // ────────────────────────────────── 读库辅助 ──────────────────────────────────

    @SuppressWarnings("unchecked")
    private String[] readCardValues(UUID lineItemId) {
        List<Object[]> r = em.createNativeQuery(
                "SELECT quote_card_values::text, costing_card_values::text " +
                "FROM quotation_line_item WHERE id = :lid")
            .setParameter("lid", lineItemId).getResultList();
        if (r.isEmpty()) return new String[]{ "<<ROW_MISSING>>", "<<ROW_MISSING>>" };
        return new String[]{ (String) r.get(0)[0], (String) r.get(0)[1] };
    }

    private String readRowData(UUID lineItemId) {
        List<?> r = em.createNativeQuery(
                "SELECT row_data::text FROM quotation_line_component_data WHERE line_item_id = :lid")
            .setParameter("lid", lineItemId).getResultList();
        return r.isEmpty() ? "<<ROW_MISSING>>" : String.valueOf(r.get(0));
    }

    private int readVersion(UUID quotationId) {
        Object v = em.createNativeQuery("SELECT user_data_version FROM quotation WHERE id = :q")
            .setParameter("q", quotationId).getSingleResult();
        return v == null ? 0 : ((Number) v).intValue();
    }

    private long countProcesses(UUID lineItemId) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM quotation_line_process WHERE line_item_id = :lid")
            .setParameter("lid", lineItemId).getSingleResult()).longValue();
    }

    private long countComponentData(UUID lineItemId) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM quotation_line_component_data WHERE line_item_id = :lid")
            .setParameter("lid", lineItemId).getSingleResult()).longValue();
    }

    private long countLineItems(UUID quotationId, UUID lineItemId) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM quotation_line_item WHERE quotation_id = :q AND id = :lid")
            .setParameter("q", quotationId).setParameter("lid", lineItemId).getSingleResult()).longValue();
    }

    private static UUID toUUID(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }
}
