package com.cpq.quotation.service;

import com.cpq.quotation.entity.QuotationLineItem;
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
 * Task 2 后端集成验证：核价快照对多层 BOM 根（{@code 3120018220}）按 spine 全节点展开 + 系统列并入。
 *
 * <p>用现有 {@code 3120018220} 核价 line item，重算 {@code snapshotLineValues}，
 * 读回 {@code costing_card_values} 断言：每个核价组件 baseRows ≥ spine 节点数、含 {@code __nodeId/__parentId/__lvl}、
 * 存在根行（{@code __parentId} 为 null、{@code __nodeId}="""}）。
 *
 * <p>注：重算会覆盖该 li 的核价快照（生成正确数据，加产品/刷新会再生），dev 库可接受。
 */
@QuarkusTest
class CostingBomTreeSnapshotTest {

    private static final String ROOT = "3120018220";
    private static final ObjectMapper M = new ObjectMapper();

    @Inject CardSnapshotService cardSnapshotService;
    @Inject EntityManager em;

    @Test
    void costingSnapshotExpandsFullBomTreeWithSystemColumns() {
        // 1. 找一个 3120018220 的核价 line item（其报价单带 costing_card_template_id）
        UUID liId = QuarkusTransaction.requiringNew().call(() -> {
            var rows = em.createNativeQuery(
                "SELECT li.id FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id " +
                "WHERE li.product_part_no_snapshot = :p AND q.costing_card_template_id IS NOT NULL LIMIT 1")
                .setParameter("p", ROOT).getResultList();
            return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
        });
        Assumptions.assumeTrue(liId != null, "无 " + ROOT + " 核价 line item，跳过");

        // 2. 重算快照
        QuotationLineItem li = QuarkusTransaction.requiringNew().call(() -> QuotationLineItem.findById(liId));
        cardSnapshotService.snapshotLineValues(li);

        // 3. 读回 costing_card_values
        String json = QuarkusTransaction.requiringNew().call(() -> {
            var r = em.createNativeQuery(
                "SELECT costing_card_values FROM quotation_line_item WHERE id = :id")
                .setParameter("id", liId).getResultList();
            return (r.isEmpty() || r.get(0) == null) ? null : r.get(0).toString();
        });
        assertNotNull(json, "costing_card_values 应已生成");

        JsonNode root;
        try { root = M.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
        JsonNode tabs = root.path("tabs");
        assertTrue(tabs.isArray() && tabs.size() > 0, "应有 tabs");

        int driverTabsChecked = 0;
        for (JsonNode tab : tabs) {
            JsonNode baseRows = tab.path("baseRows");
            if (!baseRows.isArray() || baseRows.size() == 0) continue;  // 跳过空/非 driver tab
            // 至少有一行带 __nodeId 才算 driver 组件被 spine 展开
            boolean hasSystemCols = false, hasRoot = false;
            int withNodeId = 0;
            for (JsonNode rowNode : baseRows) {
                if (rowNode.has("__nodeId")) {
                    hasSystemCols = true;
                    withNodeId++;
                    assertTrue(rowNode.has("__lvl"), "系统列必须含 __lvl");
                    assertTrue(rowNode.has("__parentId"), "系统列必须含 __parentId");
                    assertTrue(rowNode.has("__hfPartNo"), "系统列必须含 __hfPartNo");
                    // 根行：__parentId 为 null 且 __nodeId="""
                    if (rowNode.get("__parentId").isNull() && "".equals(rowNode.path("__nodeId").asText("X"))) {
                        hasRoot = true;
                        assertEquals(1, rowNode.path("__lvl").asInt(-1), "根 __lvl=1");
                        assertEquals(ROOT, rowNode.path("__hfPartNo").asText(""), "根 __hfPartNo=根料号");
                    }
                }
            }
            if (hasSystemCols) {
                driverTabsChecked++;
                // spine 共 17 occurrence；每节点 ≥1 行（缺数据补空行）→ baseRows 行数 ≥ 17
                assertTrue(withNodeId >= 17,
                    "driver 组件 spine 全节点行数应 ≥17(闭包 occurrence 数), 实际=" + withNodeId
                    + " tab=" + tab.path("tabName").asText(""));
                assertTrue(hasRoot, "应含根 occurrence 行 tab=" + tab.path("tabName").asText(""));
            }
        }
        assertTrue(driverTabsChecked > 0, "至少有一个核价 driver 组件被 spine 展开");
        System.out.println("[CostingBomTree] driver tabs spine-expanded = " + driverTabsChecked);
    }
}
