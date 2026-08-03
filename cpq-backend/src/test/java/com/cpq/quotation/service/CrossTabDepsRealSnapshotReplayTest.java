package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0803 真实快照 replay：用<b>线上出错那一份冻结结构原样</b>驱动依赖建图，
 * 锁死「产品 ⇄ 物料 假环」不再复发。
 *
 * <p>数据来源：QT-20260803-0052（模板 施耐德BUG2 v1.3）的
 * {@code quotation_view_structure.QUOTE_CARD}，9 个页签（8 NORMAL + 1 SUBTOTAL）逐字导出，
 * 仅把 fields 裁剪为建图实际读取的 {@code name + field_type}（formulas 全量保留）。
 *
 * <p>该结构里的致命组合：
 * <ul>
 *   <li>COMP-0160「产品」·管理费(FORMULA) 经 cross_tab_ref + component_subtotal 引用 COMP-0157「物料」</li>
 *   <li>COMP-0157「物料」·v2-原材料成本公式(银点类) 经 component_subtotal 引用 COMP-0160「产品」·<b>税率</b></li>
 *   <li>而「税率」是 INPUT_NUMBER —— 手工输入、零依赖，与页签算序无关</li>
 * </ul>
 * 按页签粒度建图 → 产品⇄物料 闭环 → topoOrder 抛「页签公式存在循环引用」→ 整卡渲染失败；
 * 按列粒度建图 → 物料对产品无边 → 正常排序，物料先算。
 */
class CrossTabDepsRealSnapshotReplayTest {

    private static final String PRODUCT_CID = "56c8a517-e770-4429-82c7-72f216daab45"; // COMP-0160 产品
    private static final String MATERIAL_CID = "74c0cede-094e-478c-a8fe-8f0028d538cd"; // COMP-0157 物料

    private static List<JsonNode> normalTabs() throws Exception {
        try (InputStream in = CrossTabDepsRealSnapshotReplayTest.class
                .getResourceAsStream("/repair-0803/qt-20260803-0052-quote-card.json")) {
            assertNotNull(in, "缺少 replay 资源");
            JsonNode root = new ObjectMapper().readTree(in);
            List<JsonNode> out = new ArrayList<>();
            for (JsonNode t : root.path("tabs")) {
                // 与生产一致：仅 NORMAL 进拓扑（SUBTOTAL / EXCEL 不参与）
                if ("NORMAL".equals(t.path("componentType").asText("NORMAL"))) out.add(t);
            }
            return out;
        }
    }

    private static List<CrossTabComponentOrder.TabDep> tabDeps(List<JsonNode> tabs) {
        List<CrossTabComponentOrder.TabDep> out = new ArrayList<>();
        for (JsonNode t : tabs) {
            out.add(new CrossTabComponentOrder.TabDep(
                t.path("componentId").asText(""), t.path("componentCode").asText(""),
                t.path("tabName").asText(""), t.path("formulas"), t.path("fields")));
        }
        return out;
    }

    private static List<String> compIds(List<JsonNode> tabs) {
        List<String> out = new ArrayList<>();
        for (JsonNode t : tabs) out.add(t.path("componentId").asText(""));
        return out;
    }

    /** 修复前的页签粒度建图（CardSnapshotService 旧实现逐行复刻）—— 证明这份数据确实触发假环。 */
    private static Map<String, Set<String>> legacyTabGranularityDeps(List<JsonNode> tabs) {
        Map<String, String> refToCid = new HashMap<>();
        for (JsonNode tab : tabs) {
            String cid = tab.path("componentId").asText("");
            if (!cid.isBlank()) refToCid.put(cid, cid);
            String code = tab.path("componentCode").asText("");
            if (!code.isBlank()) refToCid.put(code, cid);
            String tn = tab.path("tabName").asText("");
            if (!tn.isBlank()) refToCid.put(tn, cid);
        }
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (JsonNode tab : tabs) {
            String cid = tab.path("componentId").asText("");
            Set<String> d = new LinkedHashSet<>(CrossTabComponentOrder.extractSourceRefs(tab.path("formulas")));
            for (String r : CrossTabComponentOrder.extractSubtotalRefs(tab.path("formulas"))) {
                String tcid = refToCid.get(r);
                if (tcid != null && !tcid.equals(cid)) d.add(tcid);
            }
            deps.put(cid, d);
        }
        return deps;
    }

    /** 前提校验：这份快照确有「产品⇄物料」双向引用，且被引用的产品列是 INPUT_NUMBER 的「税率」。 */
    @Test void snapshot_hasTheBidirectionalRefWithInputColumn() throws Exception {
        var tabs = normalTabs();
        assertEquals(8, tabs.size(), "该卡片应有 8 个 NORMAL 页签");

        JsonNode product = tabs.stream().filter(t -> PRODUCT_CID.equals(t.path("componentId").asText()))
            .findFirst().orElseThrow();
        String taxRateType = null;
        for (JsonNode f : product.path("fields")) {
            if ("税率".equals(f.path("name").asText())) taxRateType = f.path("field_type").asText();
        }
        assertEquals("INPUT_NUMBER", taxRateType, "产品·税率 必须是零依赖输入列（假环的关键前提）");

        assertTrue(CrossTabComponentOrder.extractSourceRefs(product.path("formulas")).contains(MATERIAL_CID),
            "产品应引用物料");
        JsonNode material = tabs.stream().filter(t -> MATERIAL_CID.equals(t.path("componentId").asText()))
            .findFirst().orElseThrow();
        assertTrue(CrossTabComponentOrder.extractSubtotalRefs(material.path("formulas")).contains("COMP-0160"),
            "物料应经 component_subtotal 反向引用产品");
    }

    /** 修复前：页签粒度建图 → 假环 → 整卡渲染失败（线上实际表现）。 */
    @Test void legacyTabGranularity_reproducesFalseCycle() throws Exception {
        var tabs = normalTabs();
        var ex = assertThrows(BusinessException.class,
            () -> CrossTabComponentOrder.topoOrder(compIds(tabs), legacyTabGranularityDeps(tabs)));
        assertTrue(ex.getMessage().contains("循环引用"), ex.getMessage());
        assertTrue(ex.getMessage().contains(PRODUCT_CID) && ex.getMessage().contains(MATERIAL_CID),
            "环成员应正是产品与物料：" + ex.getMessage());
    }

    /** 修复后：列粒度建图 → 无环，8 个页签全部排进序，且物料先于产品计算。 */
    @Test void columnGranularity_resolvesOrderWithoutCycle() throws Exception {
        var tabs = normalTabs();
        var deps = CrossTabComponentOrder.buildComponentDeps(tabDeps(tabs));

        assertFalse(deps.get(MATERIAL_CID).contains(PRODUCT_CID),
            "物料只引用产品的 INPUT 列，不应产生页签依赖边");
        assertTrue(deps.get(PRODUCT_CID).contains(MATERIAL_CID),
            "产品对物料的真实依赖必须保留");

        List<String> order = assertDoesNotThrow(
            () -> CrossTabComponentOrder.topoOrder(compIds(tabs), deps));
        assertEquals(8, order.size(), "8 个 NORMAL 页签应全部排入计算序");
        assertTrue(order.indexOf(MATERIAL_CID) < order.indexOf(PRODUCT_CID),
            "物料必须先于产品计算（产品·管理费 依赖物料成本）");
    }

    /** 其余 6 个页签无跨页签公式，不应因本次改动凭空多出依赖边。 */
    @Test void otherTabsKeepEmptyDeps() throws Exception {
        var tabs = normalTabs();
        var deps = CrossTabComponentOrder.buildComponentDeps(tabDeps(tabs));
        for (JsonNode t : tabs) {
            String cid = t.path("componentId").asText("");
            if (PRODUCT_CID.equals(cid) || MATERIAL_CID.equals(cid)) continue;
            assertEquals(Set.of(), deps.get(cid),
                "页签 " + t.path("componentCode").asText() + " 不应有依赖边");
        }
    }
}
