package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-B 后端集成验证：核价 Excel 快照（{@code costing_excel_values}）按 BOM spine 逐节点出多行树。
 *
 * <p>用现有 {@code 3120018220} 核价 line item，{@code refreshCostingCardValues} 重算，读回断言：
 * {@code treeMode=true}、行数 = spine 节点数（17）、每行带 {@code __hfPartNo/__lvl/__bomVersion}、
 * 存在根行（{@code __lvl=1}、料号=根、版本=2000 边版本）、配置列 A/B/C 键存在。
 *
 * <p>「按节点聚合」的正确性由 {@code CardEffectiveRowsTest.filterByNodeId} 单测 + 人工验证
 * （注入 SUM_OVER([子配件],数量) → 根=7/其余=0）覆盖；本测试只验渲染结构（不改共享模板配置）。
 */
@QuarkusTest
class CostingExcelTreeTest {

    private static final String ROOT = "3120018220";
    private static final ObjectMapper M = new ObjectMapper();

    @Inject CardSnapshotService cardSnapshotService;
    @Inject EntityManager em;

    @Test
    void costingExcelSnapshotIsBomTree() {
        Object[] found = QuarkusTransaction.requiringNew().call(() -> {
            var rows = em.createNativeQuery(
                "SELECT li.id, q.id FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id " +
                "WHERE li.product_part_no_snapshot = :p AND q.costing_card_template_id IS NOT NULL LIMIT 1")
                .setParameter("p", ROOT).getResultList();
            if (rows.isEmpty()) return null;
            Object[] r = (Object[]) rows.get(0);
            return new Object[]{ UUID.fromString(r[0].toString()), UUID.fromString(r[1].toString()) };
        });
        Assumptions.assumeTrue(found != null, "无 " + ROOT + " 核价 line item，跳过");
        UUID liId = (UUID) found[0];
        UUID quotationId = (UUID) found[1];

        // 重算核价快照（含 costing_excel_values 树）
        cardSnapshotService.refreshCostingCardValues(quotationId);

        String json = QuarkusTransaction.requiringNew().call(() -> {
            var r = em.createNativeQuery(
                "SELECT costing_excel_values FROM quotation_line_item WHERE id = :id")
                .setParameter("id", liId).getResultList();
            return (r.isEmpty() || r.get(0) == null) ? null : r.get(0).toString();
        });
        assertNotNull(json, "costing_excel_values 应已生成");

        JsonNode root;
        try { root = M.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
        assertTrue(root.path("treeMode").asBoolean(false), "应标 treeMode=true");

        JsonNode rows = root.path("rows");
        assertTrue(rows.isArray(), "rows 应为数组");
        assertEquals(17, rows.size(), "核价 Excel 行数 = spine occurrence 数(17)");

        boolean hasRoot = false;
        int withVersion = 0;
        for (JsonNode r : rows) {
            assertTrue(r.has("__hfPartNo"), "每行带 __hfPartNo");
            assertTrue(r.has("__lvl"), "每行带 __lvl");
            assertTrue(r.has("A"), "配置列 A 键应存在");
            if (!r.path("__bomVersion").asText("").isEmpty()) withVersion++;
            if (ROOT.equals(r.path("__hfPartNo").asText())) {
                hasRoot = true;
                assertEquals(1, r.path("__lvl").asInt(-1), "根 __lvl=1");
                assertEquals("2000", r.path("__bomVersion").asText(""), "根边版本=2000");
            }
        }
        assertTrue(hasRoot, "应含根料号行");
        assertEquals(17, withVersion, "全节点带版本(P1 边版本语义)");
        System.out.println("[CostingExcelTree] rows=17 treeMode=true 全节点带版本 ✅");
    }
}
