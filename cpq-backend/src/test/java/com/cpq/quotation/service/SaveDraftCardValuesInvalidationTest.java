package com.cpq.quotation.service;

import com.cpq.quotation.dto.SaveDraftRequest;
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
 * TDD — D-1 失效:saveDraft 重建复用行的子表后,该行旧卡片值(quote/costing card values)必须被置 NULL。
 *
 * <p><b>背景:</b> 卡片值计算已从 saveDraft 移除(原 Phase1 块含 jsonb 上的 {@code btrim(quote_card_values)}
 * 原生查询,在解析期抛异常被 {@code catch (Exception ignore)} 吞掉 → 卡片值从未落库 → 前端打开风暴)。
 * 现卡片值由 lazy {@code ensureCardValues}(IS NULL 谓词)按最新 {@code snapshot_rows} 重算。
 * 因 saveDraft 每次保存都全删全建复用行的子表(snapshot_rows),旧卡片值会过期,
 * 故 saveDraft 在 {@code li.persist()} 后立即把复用行的 quoteCardValues/costingCardValues 置 NULL,
 * 让下次 {@code ensureCardValues} 重新选中并重算。本测试验证该失效行为。
 *
 * <p><b>测试策略</b>(沿用 SaveDraftExcelSnapshotTest 取舍):
 * <ol>
 *   <li>查一条 DRAFT quotation(满足 FK)。</li>
 *   <li>@TestTransaction 内自建最小 QuotationLineItem 并预置非空 quote/costing card values。</li>
 *   <li>构造 SaveDraftRequest 以 id 复用该行 → saveDraft 重建其子表 → D-1 置 NULL。</li>
 *   <li>绕过 L1 缓存直读库,断言两份卡片值均为 NULL。</li>
 *   <li>无 DRAFT quotation / 无基础数据时显式 fail(可见红,非假绿 skip)。</li>
 * </ol>
 */
@QuarkusTest
@DisplayName("SaveDraftCardValuesInvalidationTest — D-1 重建行卡片值置 NULL")
public class SaveDraftCardValuesInvalidationTest {

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    private static final String SENTINEL_CARD_VALUES =
            "{\"tabs\":[{\"baseRows\":[{\"__stale__\":\"OLD_CARD_VALUE\"}]}]}";

    @SuppressWarnings("unchecked")
    private UUID findDraftQuotationId() {
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM quotation WHERE status = 'DRAFT' ORDER BY created_at LIMIT 1")
                .getResultList();
        if (rows.isEmpty()) return null;
        return toUUID(rows.get(0));
    }

    /** 自建最小 line item 并预置非空 quote/costing card values;返回 {lineItemId, productId, templateId}。 */
    @SuppressWarnings("unchecked")
    private UUID[] createMinimalLineItemWithCardValues(UUID quotationId) {
        List<Object> products = em.createNativeQuery("SELECT id FROM product LIMIT 1").getResultList();
        if (products.isEmpty()) return null;
        UUID productId = toUUID(products.get(0));

        List<Object> templates = em.createNativeQuery("SELECT id FROM template LIMIT 1").getResultList();
        if (templates.isEmpty()) return null;
        UUID templateId = toUUID(templates.get(0));

        UUID newId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item " +
                "(id, quotation_id, product_id, template_id, sort_order, created_at, " +
                " quote_card_values, costing_card_values) " +
                "VALUES (:id, :qid, :pid, :tid, 99, :now, " +
                " CAST(:cv AS jsonb), CAST(:cv AS jsonb))")
                .setParameter("id",  newId)
                .setParameter("qid", quotationId)
                .setParameter("pid", productId)
                .setParameter("tid", templateId)
                .setParameter("now", OffsetDateTime.now())
                .setParameter("cv",  SENTINEL_CARD_VALUES)
                .executeUpdate();
        return new UUID[]{ newId, productId, templateId };
    }

    @SuppressWarnings("unchecked")
    private String[] readCardValues(UUID lineItemId) {
        List<Object[]> r = em.createNativeQuery(
                "SELECT quote_card_values::text, costing_card_values::text " +
                "FROM quotation_line_item WHERE id = :lid")
                .setParameter("lid", lineItemId)
                .getResultList();
        if (r.isEmpty()) return new String[]{ "<<ROW_MISSING>>", "<<ROW_MISSING>>" };
        Object[] row = r.get(0);
        return new String[]{ (String) row[0], (String) row[1] };
    }

    @Test
    @DisplayName("saveDraft 重建复用行 → quote/costing card values 置 NULL(D-1 失效)")
    @TestTransaction
    void saveDraft_rebuildsLine_invalidatesCardValues() {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId,
                "DB 无 DRAFT 报价单 — 请先通过 UI 创建至少一条 DRAFT 状态报价单后再运行本测试");

        UUID[] ids = createMinimalLineItemWithCardValues(quotationId);
        assertNotNull(ids,
                "无法自建 QuotationLineItem(无可用 product_id/template_id),请检查基础数据");
        UUID lineItemId = ids[0];

        // 前置断言:卡片值确为非空哨兵(否则后续 NULL 断言无意义)
        em.flush();
        em.clear();
        String[] before = readCardValues(lineItemId);
        assertNotNull(before[0], "前置:quote_card_values 应非空");
        assertNotNull(before[1], "前置:costing_card_values 应非空");
        assertTrue(before[0].contains("OLD_CARD_VALUE"), "前置:quote 应含哨兵,实际:" + before[0]);

        // 构造请求以 id 复用该行(saveDraft 全删全建子表 → 触发 D-1 失效)
        SaveDraftRequest req = new SaveDraftRequest();
        SaveDraftRequest.LineItemDraft liDraft = new SaveDraftRequest.LineItemDraft();
        liDraft.id         = lineItemId;
        liDraft.productId  = ids[1];
        liDraft.templateId = ids[2];
        liDraft.sortOrder  = 99;
        req.lineItems = List.of(liDraft);

        quotationService.saveDraft(quotationId, req);

        // 绕过 L1 缓存直读库
        em.flush();
        em.clear();
        String[] after = readCardValues(lineItemId);

        assertNotEquals("<<ROW_MISSING>>", after[0],
                "复用行应仍存在(以 id 复用),不应被删");
        assertNull(after[0],
                "D-1 失效:重建后 quote_card_values 应为 NULL,实际:" + after[0]);
        assertNull(after[1],
                "D-1 失效:重建后 costing_card_values 应为 NULL,实际:" + after[1]);
        // 事务回滚(@TestTransaction)自动清理自建数据
    }

    private static UUID toUUID(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }
}
