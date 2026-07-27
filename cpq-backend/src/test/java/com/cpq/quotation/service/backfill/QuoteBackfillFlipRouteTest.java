package com.cpq.quotation.service.backfill;

import com.cpq.quotation.dto.backfill.BackfillGroupDTO;
import com.cpq.quotation.dto.backfill.BackfillPreviewDTO;
import com.cpq.quotation.dto.backfill.BackfillProductDTO;
import com.cpq.quotation.entity.Quotation;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * task-0721 报价数据版本升级 · B5 路径②（FLIP）自测。
 *
 * <p>场景：{@code unit_price} 里存在一行本单 pending（{@code is_current=false}），但报价单没有任何
 * line item / 页签渲染表征它（模拟"导入了但当前无任何报价模板渲染"，backtask B5.2 路径②，
 * 现网原型是 {@code plating_scheme}，这里用 {@code unit_price} 复现同款场景更易独立起夹具）。
 * 执行回填后应直接 flip：{@code is_current=true, pending_quotation_id=NULL}，不走升版写入器
 * （newRows 为空会被 I1 拒绝，故不能走 REBUILD），且清理干净不残留 pending。
 */
@QuarkusTest
class QuoteBackfillFlipRouteTest {

    @Inject QuoteBackfillService backfillService;
    @Inject QuoteBackfillPreviewService previewService;
    @Inject EntityManager em;

    @SuppressWarnings("unchecked")
    private UUID resolveCustomerId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    @SuppressWarnings("unchecked")
    private UUID resolveUserId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    @Test
    @TestTransaction
    void noSnapshotRepresentation_flipsToOfficial() {
        UUID customerId = resolveCustomerId();
        UUID userId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(userId != null, "需要共享 DB 中存在 user 行");

        String customerNo = (String) em.createNativeQuery("SELECT code FROM customer WHERE id = :cid")
            .setParameter("cid", customerId).getSingleResult();

        Quotation q = new Quotation();
        q.quotationNumber = "TEST-B5-FLIP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "QuoteBackfillFlipRouteTest";
        q.customerId = customerId;
        q.salesRepId = userId;
        q.status = "SUBMITTED";
        q.persist();
        em.flush();

        String code = "TEST-B5-FLIP-CODE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID rowId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO unit_price (id, system_type, price_type, version_no, code, cost_type, customer_no, " +
                "  currency, unit, is_current, pending_quotation_id, created_at, updated_at) " +
                "VALUES (:id, 'QUOTE', 'ELEMENT', '2000', :code, '元素价格', :cn, 'CNY', 'kg', false, :pq, now(), now())")
            .setParameter("id", rowId).setParameter("code", code).setParameter("cn", customerNo)
            .setParameter("pq", q.id)
            .executeUpdate();
        em.flush();
        em.clear();

        QuoteBackfillService.Summary summary = backfillService.execute(q.id, userId);
        assertTrue(summary.versionedGroups >= 1, "应识别到至少 1 个 FLIP 组");

        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT is_current, pending_quotation_id FROM unit_price WHERE id = :id")
            .setParameter("id", rowId).getSingleResult();
        assertEquals(Boolean.TRUE, row[0], "路径②应 flip is_current=true");
        assertNull(row[1], "路径②应清空 pending_quotation_id");

        long remainingPending = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE pending_quotation_id = :qid")
            .setParameter("qid", q.id).getSingleResult()).longValue();
        assertEquals(0L, remainingPending, "回填后本单 pending 残留应清理干净");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 验收 Bug-1 修复锁定：Phase C 的 FLIP 组（本单 pending 但无任何页签渲染表征）预览必须显示
    // 真实行数，不能恒为 0→0（financial 会误以为"这次通过什么都没发生"，实际会转正 N 行）。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void bug1_phaseCFlipGroup_previewShowsRealRowCount_notZeroToZero() {
        UUID customerId = resolveCustomerId();
        UUID userId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(userId != null, "需要共享 DB 中存在 user 行");
        String customerNo = (String) em.createNativeQuery("SELECT code FROM customer WHERE id = :cid")
            .setParameter("cid", customerId).getSingleResult();

        Quotation q = new Quotation();
        q.quotationNumber = "TEST-BUG1-FLIP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "QuoteBackfillFlipRouteTest-Bug1";
        q.customerId = customerId;
        q.salesRepId = userId;
        q.status = "SUBMITTED";
        q.persist();
        em.flush();

        // 同一组两条 pending 行（同 price_type+customer_no+finished_material_no，只 seq_no 不同），
        // 组内没有任何 line item/页签渲染表征——纯 Phase C 场景。
        String materialNo = "BUG1MAT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        insertPendingUnitPrice(q.id, customerNo, materialNo, 1, new BigDecimal("1.00"));
        insertPendingUnitPrice(q.id, customerNo, materialNo, 2, new BigDecimal("2.00"));
        em.flush();
        em.clear();

        BackfillPreviewDTO preview = previewService.preview(q.id);
        BackfillGroupDTO group = preview.groups.stream()
            .filter(g -> "unit_price".equals(g.table) && materialNo.equals(String.valueOf(g.groupKey.get("finished_material_no"))))
            .findFirst().orElse(null);
        assertNotNull(group, "Phase C FLIP 组应出现在预览里");
        assertEquals("FLIP", group.route);
        assertEquals("PENDING", group.baseSource);
        assertEquals(2, group.baseRowCount, "★锁定 Bug-1：基底行数应为真实的 2，不是恒 0");
        assertEquals(2, group.resultRowCount, "★锁定 Bug-1：FLIP 不改内容，结果行数应等于基底行数 2，不是恒 0");

        // execute 侧不应受影响（仍按轴直改，不读 baseRows/effectiveNewRows）。
        QuoteBackfillService.Summary summary = backfillService.execute(q.id, userId);
        assertTrue(summary.versionedGroups >= 1);
        long currentCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE finished_material_no = :mn AND is_current = true")
            .setParameter("mn", materialNo).getSingleResult()).longValue();
        assertEquals(2L, currentCount, "flip 后两行都应转正");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 验收 Bug-2 修复锁定：产品聚合只按 productNo，不拼 customerNo——capacity 组轴没有 customer_no
    // 列，不能因此把同一产品拆成两张卡；customerName 必须来自报价单自身客户。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void bug2_productAggregation_capacityAndUnitPriceSameMaterialMergeIntoOneCard() {
        UUID customerId = resolveCustomerId();
        UUID userId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(userId != null, "需要共享 DB 中存在 user 行");
        Object[] custRow = (Object[]) em.createNativeQuery("SELECT code, name FROM customer WHERE id = :cid")
            .setParameter("cid", customerId).getSingleResult();
        String customerNo = (String) custRow[0];
        String customerName = (String) custRow[1];

        Quotation q = new Quotation();
        q.quotationNumber = "TEST-BUG2-AGG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "QuoteBackfillFlipRouteTest-Bug2";
        q.customerId = customerId;
        q.salesRepId = userId;
        q.status = "SUBMITTED";
        q.persist();
        em.flush();

        // 同一 material_no：一条 unit_price pending（轴含 customer_no）+ 一条 capacity pending
        // （轴 = system_type/material_no/resource_group_no，完全没有 customer_no 列）。两者都走
        // Phase C（无页签表征），都会被回填但归属同一个产品。
        String materialNo = "BUG2MAT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        insertPendingUnitPrice(q.id, customerNo, materialNo, 1, new BigDecimal("3.00"));
        insertPendingCapacity(q.id, materialNo, "PROC-01");
        em.flush();
        em.clear();

        BackfillPreviewDTO preview = previewService.preview(q.id);

        // ★锁定 Bug-2 核心不变式：products.length 必须恒等于 summary.affectedProducts。
        assertEquals(preview.summary.affectedProducts, preview.products.size(),
            "products.length 必须恒等于 summary.affectedProducts（否则财务端产品数与卡片数自相矛盾）");

        List<BackfillProductDTO> matching = preview.products.stream()
            .filter(p -> materialNo.equals(p.productNo)).toList();
        assertEquals(1, matching.size(), "★锁定 Bug-2：同一 productNo 不应因某组轴没有 customer_no 而拆成两张卡片");
        BackfillProductDTO product = matching.get(0);
        assertEquals(customerNo, product.customerNo, "customerName/customerNo 应来自报价单自身客户，不是从组轴推导");
        assertEquals(customerName, product.customerName);
        assertEquals(2, product.groupIndexes.size(), "该产品卡片应同时挂 unit_price 和 capacity 两个组");

        Set<String> tablesUnderProduct = new java.util.HashSet<String>();
        for (int idx : product.groupIndexes) tablesUnderProduct.add(preview.groups.get(idx).table);
        assertTrue(tablesUnderProduct.contains("unit_price") && tablesUnderProduct.contains("capacity"),
            "同一产品卡片应同时包含 unit_price 与 capacity 两个组，实际=" + tablesUnderProduct);
    }

    private void insertPendingUnitPrice(UUID quotationId, String customerNo, String materialNo, int seqNo, BigDecimal price) {
        em.createNativeQuery(
                "INSERT INTO unit_price (id, system_type, price_type, version_no, code, finished_material_no, " +
                "  seq_no, pricing_price, unit, customer_no, is_current, pending_quotation_id, created_at, updated_at) " +
                "VALUES (:id, 'QUOTE', 'PROCESS', '2001', :code, :fmn, :seq, :price, '元', :cn, false, :pq, now(), now())")
            .setParameter("id", UUID.randomUUID()).setParameter("code", materialNo).setParameter("fmn", materialNo)
            .setParameter("seq", seqNo).setParameter("price", price).setParameter("cn", customerNo)
            .setParameter("pq", quotationId)
            .executeUpdate();
    }

    private void insertPendingCapacity(UUID quotationId, String materialNo, String processNo) {
        em.createNativeQuery(
                "INSERT INTO capacity (id, system_type, material_no, process_no, resource_group_no, " +
                "  production_type, calc_version, is_current, pending_quotation_id, created_at, updated_at) " +
                "VALUES (:id, 'QUOTE', :mn, :pn, 'RG-01', 'UNIT', '2001', false, :pq, now(), now())")
            .setParameter("id", UUID.randomUUID()).setParameter("mn", materialNo).setParameter("pn", processNo)
            .setParameter("pq", quotationId)
            .executeUpdate();
    }
}
