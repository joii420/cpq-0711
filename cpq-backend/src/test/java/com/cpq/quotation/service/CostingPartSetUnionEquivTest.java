package com.cpq.quotation.service;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.transaction.Transactional;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-C4 等价护栏：证明核价侧「跨行 partSet union 预取 + 每组件一次 expandMulti」与原逐行
 * {@code expandForPartSet} 产出 <b>逐位相同</b> 的 costingCardValues。
 *
 * <p>只读真实数据：选库里最小的(≥2 行、含核价模板)报价单,对前若干行各算两路 costingCardValues —
 * 路径 A(null union=逐行) vs 路径 B(precomputeCostingDriverUnion + union),断言字符串逐位相等。
 * {@code buildCostingCardValues} 纯读不写 managed,{@code precompute} 也只读 → 无 DB 污染、无需清理。
 */
@QuarkusTest
class CostingPartSetUnionEquivTest {

    @Inject CardSnapshotService svc;
    @Inject EntityManager em;
    @Inject EntityManagerFactory emf;

    record Pick(UUID quotationId, UUID costingTemplateId, UUID customerId) {}

    @Transactional
    Pick pickSmallestCostingQuotation() {
        // 本测试是 P2-C4 时代产物，只测「非树页签平铺路径」下 unionByComp 预取 vs 逐行 expandForPartSet
        // 的等价性 + 往返查询数下降。2026-07 buildCostingCardValues 加了正确性兜底后，树页签模板
        // 无论传不传 unionByComp 都统一走 costingTreeRenderService.render()（忽略 unionByComp），
        // 会让 roundTripReduction_unionVsPerRow 的 "NEW < OLD" 语义失真（两路都 render 同样次数）。
        // 排除含树页签的模板，让候选范围回到本测试原始设计前提（纯 driver 平铺 expand）。
        @SuppressWarnings("unchecked")
        List<Object[]> rs = em.createNativeQuery(
            "SELECT q.id, q.costing_card_template_id, q.customer_id " +
            "FROM quotation q JOIN quotation_line_item li ON li.quotation_id = q.id " +
            "WHERE q.costing_card_template_id IS NOT NULL AND li.product_part_no_snapshot IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM template_component tc JOIN component c ON c.id = tc.component_id " +
            "  WHERE tc.template_id = q.costing_card_template_id AND c.bom_recursive_expand = true) " +
            "GROUP BY q.id, q.costing_card_template_id, q.customer_id HAVING count(li.id) >= 2 " +
            "ORDER BY count(li.id) ASC LIMIT 1").getResultList();
        if (rs.isEmpty()) return null;
        Object[] r = rs.get(0);
        return new Pick((UUID) r[0], (UUID) r[1], (UUID) r[2]);
    }

    @Transactional
    List<UUID> firstLineItemIds(UUID quotationId, int k) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = em.createNativeQuery(
            "SELECT id FROM quotation_line_item WHERE quotation_id = :q AND product_part_no_snapshot IS NOT NULL " +
            "ORDER BY id LIMIT :k")
            .setParameter("q", quotationId).setParameter("k", k).getResultList();
        return ids;
    }

    @Test
    @Transactional
    void unionEqualsPerRow_costingCardValues() {
        Pick p = pickSmallestCostingQuotation();
        Assumptions.assumeTrue(p != null, "无含核价模板且 ≥2 行的报价单,跳过");

        // 整单 union 预取(覆盖全行 partSet 并集)
        Map<UUID, Map<String, ExpandDriverResponse>> unionByComp =
            svc.precomputeCostingDriverUnion(p.quotationId());
        Assumptions.assumeTrue(unionByComp != null && !unionByComp.isEmpty(),
            "该报价单无 eligible recursive 核价组件(union 未触发),本等价测试无对象,跳过");

        List<UUID> liIds = firstLineItemIds(p.quotationId(), 6);
        assertFalse(liIds.isEmpty(), "应有核价行");

        int comparedNonNull = 0;
        boolean checkedTwice = false;
        for (UUID liId : liIds) {
            QuotationLineItem li = QuotationLineItem.findById(liId);
            if (li == null) continue;
            String a = svc.buildCostingCardValues(li, p.costingTemplateId(), p.customerId(), p.quotationId(), null);
            String b = svc.buildCostingCardValues(li, p.costingTemplateId(), p.customerId(), p.quotationId(), unionByComp);
            assertEquals(a, b, "li=" + liId + " union 路径 costingCardValues 应与逐行逐位一致");
            // 连跑两次(§3.4 可变共享面):同一共享 unionByComp 再算一次须逐位一致 —— 证明分发链不就地 mutate 共享 resp.rows
            if (!checkedTwice && b != null) {
                String b2 = svc.buildCostingCardValues(li, p.costingTemplateId(), p.customerId(), p.quotationId(), unionByComp);
                assertEquals(b, b2, "li=" + liId + " 复用同一共享 union 连跑两次须一致(共享 Map 未被就地 mutate)");
                checkedTwice = true;
            }
            if (a != null) comparedNonNull++;
        }
        assertTrue(comparedNonNull > 0, "至少一行应产出非空 costingCardValues(否则测试空转)");
        System.out.println("=== P2C4-EQUIV unionComps=" + unionByComp.size()
            + " linesCompared=" + liIds.size() + " nonNull=" + comparedNonNull + " ===");
    }

    /** 往返度量:同进程内 OLD(逐行 expandForPartSet) vs NEW(整单 union + 命中)over 同一批核价行,断言 NEW < OLD。 */
    @Test
    @Transactional
    void roundTripReduction_unionVsPerRow() {
        Pick p = pickSmallestCostingQuotation();
        Assumptions.assumeTrue(p != null, "无含核价模板且 ≥2 行的报价单,跳过往返度量");
        Map<UUID, Map<String, ExpandDriverResponse>> union = svc.precomputeCostingDriverUnion(p.quotationId());
        Assumptions.assumeTrue(union != null && !union.isEmpty(), "无 eligible recursive 核价组件,跳过");

        List<UUID> liIds = firstLineItemIds(p.quotationId(), 12);
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        // 预热:precompute 一次把全单闭包(WITH RECURSIVE)+视图元数据缓存暖起来,
        // 两测量窗口都不再计闭包,只露 driver expand(expandMulti)往返差。
        svc.precomputeCostingDriverUnion(p.quotationId());

        // OLD: 逐行 expandForPartSet(每行一次 expandMulti)
        stats.clear(); long o0 = stats.getPrepareStatementCount();
        for (UUID liId : liIds) { QuotationLineItem li = QuotationLineItem.findById(liId);
            if (li != null) svc.buildCostingCardValues(li, p.costingTemplateId(), p.customerId(), p.quotationId(), null); }
        long oldCnt = stats.getPrepareStatementCount() - o0;

        // NEW: 整单 union 一次 + 逐行命中(无 driver 远程查)
        stats.clear(); long n0 = stats.getPrepareStatementCount();
        Map<UUID, Map<String, ExpandDriverResponse>> u2 = svc.precomputeCostingDriverUnion(p.quotationId());
        for (UUID liId : liIds) { QuotationLineItem li = QuotationLineItem.findById(liId);
            if (li != null) svc.buildCostingCardValues(li, p.costingTemplateId(), p.customerId(), p.quotationId(), u2); }
        long newCnt = stats.getPrepareStatementCount() - n0;

        System.out.println("=== P2C4-ROUNDTRIP lines=" + liIds.size() + " OLD(逐行)=" + oldCnt
            + " NEW(union)=" + newCnt + " ===");
        assertTrue(newCnt < oldCnt, "union 往返应少于逐行 (OLD=" + oldCnt + " NEW=" + newCnt + ")");
    }
}
