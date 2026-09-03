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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829 · T-16（AC-20，B-6 回落路径）—— 页签结构变化时自动回落"全删全建"。
 *
 * <p><b>验的是什么</b>：B-6 把 {@code componentData} 的持久化从"全删全建"改成"结构不变则 UPSERT，
 * 结构变化则回落全删全建"。本测试构造一次真实的结构变化（第二次保存的 componentId 集合与库中
 * 现有集合不同：删掉一个页签、加一个新页签），断言结果<b>正确</b>——最终 componentData 集合与
 * <b>新</b>结构完全一致（旧页签的行真的被清掉，不是"没删干净"留下僵尸行；新页签的行真的建出来
 * 了，不是因为回落判断误判而漏建），且过程不抛错、不写坏已保留的那个页签的数据。
 *
 * <p>本测试是<b>黑盒的持久化结果比对</b>，不依赖 B-6 判定"结构相同/不同"内部具体怎么实现
 * （UPSERT 键选择、SQL 语句形态等实现细节均不读、不假设）——只断言 {@code saveDraft} 两次调用
 * 前后 {@code quotation_line_component_data} 表最终落库内容对不对。
 */
@QuarkusTest
@DisplayName("SaveDraftTabStructureFallbackTest — repair-260829 T-16(AC-20) 页签结构变化回落全删全建")
class SaveDraftTabStructureFallbackTest {

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    private static final UUID TEST_USER_ID = UUID.fromString("896ed7d9-bf12-4ea7-9ff1-09cb14496311");

    private final List<UUID> quotationIds = new ArrayList<>();
    private final List<UUID> componentIds = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private UUID anyCustomerId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
        assertFalse(rows.isEmpty(), "DB 无任何 customer,无法建 fixture");
        Object o = rows.get(0);
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    /** 造一个纯手工输入型简单组件(无 driver,rowData 由客户端直接提供)。 */
    @Transactional
    Component buildPlainComponent(String tag) {
        Component c = new Component();
        c.name = "T16测试-" + tag;
        c.code = "T16-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
        c.fields = "[{\"name\":\"值\",\"field_type\":\"INPUT_TEXT\"}]";
        c.formulas = "[]";
        c.persist();
        componentIds.add(c.id);
        return c;
    }

    @Transactional
    UUID createDraftQuotationWithOneLine(UUID lineItemId) {
        UUID qid = UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO quotation (id, quotation_number, customer_id, sales_rep_id, name, status, " +
                                "total_amount, original_amount, system_discount_rate, final_discount_rate, " +
                                "tax_rate, tax_amount, is_manually_adjusted, created_at, updated_at) " +
                                "VALUES (:id, :num, :cid, :sid, :name, 'DRAFT', 0, 0, 100, 100, 0, 0, false, NOW(), NOW())")
                .setParameter("id", qid)
                .setParameter("num", "TEST-T16-FALLBACK-" + System.nanoTime())
                .setParameter("cid", anyCustomerId())
                .setParameter("sid", TEST_USER_ID)
                .setParameter("name", "repair-260829 T-16 结构变化回落")
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
                System.err.println("[T-16 cleanup] quotation " + qid + " failed: " + e.getMessage());
            }
        }
        for (UUID cid : componentIds) {
            try {
                deleteComponentById(cid);
            } catch (Exception e) {
                System.err.println("[T-16 cleanup] component " + cid + " failed: " + e.getMessage());
            }
        }
        quotationIds.clear();
        componentIds.clear();
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> currentComponentIds(UUID lineItemId) {
        List<Object> rows = em.createNativeQuery(
                        "SELECT component_id FROM quotation_line_component_data WHERE line_item_id = :lid")
                .setParameter("lid", lineItemId).getResultList();
        Set<UUID> set = new HashSet<>();
        for (Object o : rows) {
            set.add((o instanceof UUID u) ? u : UUID.fromString(o.toString()));
        }
        return set;
    }

    private String rowDataFor(UUID lineItemId, UUID componentId) {
        List<Object> rows = em.createNativeQuery(
                        "SELECT row_data::text FROM quotation_line_component_data " +
                                "WHERE line_item_id = :lid AND component_id = :cid")
                .setParameter("lid", lineItemId).setParameter("cid", componentId).getResultList();
        return rows.isEmpty() ? null : (String) rows.get(0);
    }

    private SaveDraftRequest.LineItemDraft draft(UUID lineItemId, List<Component> comps, List<String> tabs,
                                                  List<String> markers) {
        SaveDraftRequest.LineItemDraft d = new SaveDraftRequest.LineItemDraft();
        d.id = lineItemId;
        d.sortOrder = 0;
        List<SaveDraftRequest.ComponentDataDraft> cds = new ArrayList<>();
        for (int i = 0; i < comps.size(); i++) {
            SaveDraftRequest.ComponentDataDraft cd = new SaveDraftRequest.ComponentDataDraft();
            cd.componentId = comps.get(i).id;
            cd.tabName = tabs.get(i);
            cd.rowData = "[{\"值\":\"" + markers.get(i) + "\"}]";
            cd.sortOrder = i;
            cds.add(cd);
        }
        d.componentData = cds;
        return d;
    }

    @Test
    @DisplayName("AC-20: 第二次保存换掉一个页签的组件(结构变化) → 回落全删全建,结果与新结构一致")
    void saveDraft_tabStructureChanges_fallsBackToFullRebuild() {
        UUID lineItemId = UUID.randomUUID();
        UUID qid = createDraftQuotationWithOneLine(lineItemId);

        Component compA = buildPlainComponent("A-常驻");
        Component compB = buildPlainComponent("B-第一次");
        Component compC = buildPlainComponent("C-第二次新增");

        // 第一次保存：结构 = {A, B}
        SaveDraftRequest req1 = new SaveDraftRequest();
        req1.lineItems = List.of(draft(lineItemId, List.of(compA, compB), List.of("页签A", "页签B"),
                List.of("A-v1", "B-v1")));
        assertDoesNotThrow(() -> quotationService.saveDraft(qid, req1));

        Set<UUID> afterFirst = currentComponentIds(lineItemId);
        assertFalse(afterFirst.isEmpty(), "第一次保存后 componentData 不应为空(非空验证)");
        assertEquals(Set.of(compA.id, compB.id), afterFirst, "第一次保存后应恰好是 {A,B} 两个页签");

        // 第二次保存：结构变化 = {A(更新), C(新增)} —— B 被去掉,C 是全新组件
        SaveDraftRequest req2 = new SaveDraftRequest();
        req2.lineItems = List.of(draft(lineItemId, List.of(compA, compC), List.of("页签A", "页签C"),
                List.of("A-v2", "C-v1")));
        assertDoesNotThrow(() -> quotationService.saveDraft(qid, req2),
                "页签结构变化时保存不应抛错(应自动回落全删全建)");

        Set<UUID> afterSecond = currentComponentIds(lineItemId);
        assertFalse(afterSecond.isEmpty(), "第二次保存后 componentData 不应为空(非空验证)");
        assertEquals(Set.of(compA.id, compC.id), afterSecond,
                "结构变化后 componentData 应恰好等于新结构 {A,C} —— 旧页签B应被清掉,新页签C应建出来,实际=" + afterSecond);
        assertFalse(afterSecond.contains(compB.id), "旧页签B的组件不应再残留于 componentData 中");

        // 内容也须正确(不是只有 componentId 对但内容是错位/残留数据)
        String rowA = rowDataFor(lineItemId, compA.id);
        String rowC = rowDataFor(lineItemId, compC.id);
        assertNotNull(rowA, "页签A的row_data不应为空");
        assertNotNull(rowC, "页签C的row_data不应为空");
        assertTrue(rowA.contains("A-v2"), "页签A应反映第二次保存的最新值(A-v2),而不是第一次的A-v1或写坏的数据,实际=" + rowA);
        assertTrue(rowC.contains("C-v1"), "页签C应正确落库新增内容(C-v1),实际=" + rowC);
    }
}
