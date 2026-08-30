package com.cpq.quotation.service;

import com.cpq.component.entity.Component;
import com.cpq.quotation.dto.SaveDraftRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829 · T-17（AC-21，B-6 墓碑保留）—— UPSERT 路径下 {@code deleted_row_keys} 逐字保留。
 *
 * <p><b>验的是什么</b>：B-6 的 UPSERT 路径明确规定"{@code snapshot_rows}/{@code deleted_row_keys}
 * 两列完全不碰"。原全删全建路径靠 {@code preservedTombstones} 读出旧值 → 删除 → 写回来保留墓碑；
 * UPSERT 路径下这行记录本身就没被删，理论上天然保留。本测试直接在 DB 里预置一条带非空
 * {@code deleted_row_keys} 的 {@code quotation_line_component_data} 行，然后走一次<b>结构不变</b>
 * （componentId 集合与库中现状相同）的 {@code saveDraft} 保存（应命中 UPSERT 路径），断言保存后
 * {@code deleted_row_keys} 与保存前<b>逐字相同</b>。
 *
 * <p>黑盒验证：不假设/不依赖 UPSERT 具体判定逻辑的实现细节，只比对保存前后该列的持久化内容。
 */
@QuarkusTest
@DisplayName("SaveDraftUpsertTombstonePreservedTest — repair-260829 T-17(AC-21) UPSERT路径墓碑保留")
class SaveDraftUpsertTombstonePreservedTest {

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    private static final UUID TEST_USER_ID = UUID.fromString("896ed7d9-bf12-4ea7-9ff1-09cb14496311");
    private static final String TOMBSTONE_JSON = "[{\"effKey\":\"dr-key-1\",\"fp\":\"T17-fingerprint-marker\"}]";

    private final List<UUID> quotationIds = new ArrayList<>();
    private final List<UUID> componentIds = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private UUID anyCustomerId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
        assertFalse(rows.isEmpty(), "DB 无任何 customer,无法建 fixture");
        Object o = rows.get(0);
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    @Transactional
    Component buildPlainComponent(String tag) {
        Component c = new Component();
        c.name = "T17测试-" + tag;
        c.code = "T17-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
        c.fields = "[{\"name\":\"值\",\"field_type\":\"INPUT_TEXT\"}]";
        c.formulas = "[]";
        c.persist();
        componentIds.add(c.id);
        return c;
    }

    @Transactional
    UUID createDraftQuotationWithLine(UUID lineItemId) {
        UUID qid = UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO quotation (id, quotation_number, customer_id, sales_rep_id, name, status, " +
                                "total_amount, original_amount, system_discount_rate, final_discount_rate, " +
                                "tax_rate, tax_amount, is_manually_adjusted, created_at, updated_at) " +
                                "VALUES (:id, :num, :cid, :sid, :name, 'DRAFT', 0, 0, 100, 100, 0, 0, false, NOW(), NOW())")
                .setParameter("id", qid)
                .setParameter("num", "TEST-T17-TOMBSTONE-" + System.nanoTime())
                .setParameter("cid", anyCustomerId())
                .setParameter("sid", TEST_USER_ID)
                .setParameter("name", "repair-260829 T-17 墓碑保留")
                .executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO quotation_line_item (id, quotation_id, sort_order, created_at) " +
                                "VALUES (:id, :qid, 0, NOW())")
                .setParameter("id", lineItemId)
                .setParameter("qid", qid)
                .executeUpdate();
        quotationIds.add(qid);
        return qid;
    }

    @Transactional
    void seedComponentDataWithTombstone(UUID lineItemId, UUID componentId, String tabName, String rowData) {
        em.createNativeQuery(
                        "INSERT INTO quotation_line_component_data " +
                                "(id, line_item_id, component_id, tab_name, sort_order, row_data, deleted_row_keys, created_at) " +
                                "VALUES (:id, :lid, :cid, :tab, 0, CAST(:rd AS jsonb), CAST(:drk AS jsonb), NOW())")
                .setParameter("id", UUID.randomUUID())
                .setParameter("lid", lineItemId)
                .setParameter("cid", componentId)
                .setParameter("tab", tabName)
                .setParameter("rd", rowData)
                .setParameter("drk", TOMBSTONE_JSON)
                .executeUpdate();
    }

    private String readDeletedRowKeys(UUID lineItemId, UUID componentId) {
        List<?> rows = em.createNativeQuery(
                        "SELECT deleted_row_keys::text FROM quotation_line_component_data " +
                                "WHERE line_item_id = :lid AND component_id = :cid")
                .setParameter("lid", lineItemId).setParameter("cid", componentId).getResultList();
        assertFalse(rows.isEmpty(), "该 componentId 的 componentData 行应存在(非空验证)");
        return (String) rows.get(0);
    }

    // repair-260829 事务修复：DB 写操作(DELETE)必须在自己的活跃事务里执行，@AfterEach 方法本身
    // 没有事务上下文；沿用 SaveDraftZeroLineItemsTest 已验证可用的手法——把实际的 DB 操作
    // 拆到单独的、非 private 的 @Transactional 辅助方法，@AfterEach 只负责循环 + 兜底 try/catch。
    @Transactional
    void deleteQuotationCascade(UUID qid) {
        em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN " +
                        "(SELECT id FROM quotation_line_item WHERE quotation_id = :qid)")
                .setParameter("qid", qid).executeUpdate();
        em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :qid")
                .setParameter("qid", qid).executeUpdate();
        em.createNativeQuery("DELETE FROM quotation WHERE id = :qid")
                .setParameter("qid", qid).executeUpdate();
    }

    @Transactional
    void deleteComponentById(UUID cid) {
        em.createNativeQuery("DELETE FROM component WHERE id = :cid").setParameter("cid", cid).executeUpdate();
    }

    @AfterEach
    void cleanup() {
        for (UUID qid : quotationIds) {
            try {
                deleteQuotationCascade(qid);
            } catch (Exception e) {
                System.err.println("[T-17 cleanup] quotation " + qid + " failed: " + e.getMessage());
            }
        }
        for (UUID cid : componentIds) {
            try {
                deleteComponentById(cid);
            } catch (Exception e) {
                System.err.println("[T-17 cleanup] component " + cid + " failed: " + e.getMessage());
            }
        }
        quotationIds.clear();
        componentIds.clear();
    }

    @Test
    @DisplayName("AC-21: 结构不变(UPSERT路径)保存 → deleted_row_keys 逐字保留,不被清空/覆盖")
    void saveDraft_upsertPath_preservesDeletedRowKeys() {
        UUID lineItemId = UUID.randomUUID();
        UUID qid = createDraftQuotationWithLine(lineItemId);

        Component comp = buildPlainComponent("投料");
        seedComponentDataWithTombstone(lineItemId, comp.id, "投料", "[{\"值\":\"seed-v0\"}]");

        String before = readDeletedRowKeys(lineItemId, comp.id);
        assertNotNull(before, "保存前 deleted_row_keys 不应为 null(前置数据非空)");
        assertTrue(before.contains("dr-key-1"), "前置数据应确实带有墓碑标记, 实际=" + before);

        // 结构不变：本次保存的 componentId 集合与库中现状完全相同({comp}) —— 应命中 UPSERT 路径
        SaveDraftRequest req = new SaveDraftRequest();
        SaveDraftRequest.LineItemDraft d = new SaveDraftRequest.LineItemDraft();
        d.id = lineItemId;
        d.sortOrder = 0;
        SaveDraftRequest.ComponentDataDraft cd = new SaveDraftRequest.ComponentDataDraft();
        cd.componentId = comp.id;
        cd.tabName = "投料";
        cd.rowData = "[{\"值\":\"seed-v1-updated\"}]"; // 内容有更新,但组件集合不变
        cd.sortOrder = 0;
        d.componentData = List.of(cd);
        req.lineItems = List.of(d);

        assertDoesNotThrow(() -> quotationService.saveDraft(qid, req));

        String after = readDeletedRowKeys(lineItemId, comp.id);
        assertNotNull(after, "保存后 deleted_row_keys 不应为 null(非空验证)");
        assertEquals(before, after,
                "结构不变(UPSERT路径)保存后 deleted_row_keys 应与保存前逐字相同,实际 before=" + before + " after=" + after);

        // 同时确认 row_data 确实是"活的"(真的走了保存流程,不是空跑导致行原封未动)
        List<?> rd = em.createNativeQuery(
                        "SELECT row_data::text FROM quotation_line_component_data " +
                                "WHERE line_item_id = :lid AND component_id = :cid")
                .setParameter("lid", lineItemId).setParameter("cid", comp.id).getResultList();
        assertFalse(rd.isEmpty());
        assertTrue(((String) rd.get(0)).contains("seed-v1-updated"),
                "row_data 应反映本次保存的新值,证明保存流程确实执行了(不是空跑导致墓碑'保留'只是因为整行都没动过),实际=" + rd.get(0));
    }
}
