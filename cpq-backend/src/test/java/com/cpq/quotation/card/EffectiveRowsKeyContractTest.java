package com.cpq.quotation.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.FormulaCalculator;
import com.cpq.quotation.service.card.CardEffectiveRows;
import com.cpq.quotation.service.card.ComponentDataEffectiveRows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 契约测试：两条 effective-rows 求值路径的 <b>键口径必须一致</b>。
 *
 * <p>系统里有两条产出 {@code Map<tabKey, TabRows>} 的路径，被同一批消费方
 * （{@code CardDataProvider} → {@code TabJoinPlanEvaluator} / {@code CardFormulaEvaluator}）精确查表：
 * <ul>
 *   <li>{@link ComponentDataEffectiveRows#compute} —— 从持久化 {@code component_data} 现算（报价侧读时路径）</li>
 *   <li>{@link CardEffectiveRows#parse} —— 从卡片值快照解析（核价 Excel 树 / 同侧快照透传路径）</li>
 * </ul>
 * 两类消费方用<b>不同形状</b>的键：Excel 列配置的 {@code tabKey} 是<b>裸 componentId</b>，
 * 而 CardRef / {@code tabDefsOfTemplate} 用 {@code componentId:sortOrder}。因此两条路径都必须
 * <b>双键登记</b>，否则「精确命中、无回退」的 {@code CardDataProvider} 会静默 miss → 列恒 0。
 *
 * <p><b>为什么要有这条测试</b>：2026-06-19 的 {@code 5f1b2d72} 已经诊断出这个根因并做了双键登记，
 * 但只落地在 {@link ComponentDataEffectiveRows} 一条路径上，{@link CardEffectiveRows#parse} 漏了
 * —— 核价 Excel 视图四列因此恒 0 达三个月无人察觉。该约定此前<b>只写在注释里，没有任何测试守着</b>。
 * 本测试把它固化成契约：任何一条路径退回单键都会在这里红。
 */
class EffectiveRowsKeyContractTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String CID = "1e41ecb6-b663-42a1-b8da-122961699d26";
    private static final int SORT = 2;

    private static JsonNode json(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---- 路径 A：卡片值快照 → CardEffectiveRows.parse ----------------------------------

    private static Map<String, CardEffectiveRows.TabRows> viaCardValues(String cid, int sortOrder) {
        // ⚠️ 夹具必须是**生产形态**（实查 cpq_db_0910 的 costing_card_values）：
        //    ① tab 里**没有** sortOrder（sortOrder 只在 componentsSnapshot 里）
        //    ② subtotal / subtotalByColumn 的值是 **JSON 字符串**（写侧 task-0810 精度契约）
        // 用 number 写夹具会让 R2（decimalValue() 读 TextNode 静默得 0）在测试里根本不暴露。
        JsonNode cardValues = json("""
            {"tabs":[{"componentId":"%s","tabName":"材质元素","componentType":"NORMAL",
                      "subtotal":"489985",
                      "subtotalByColumn":{"元素成本":"489985"},
                      "resolvedRows":[{"元素代码":"Ag","金额":489985}]}]}
            """.formatted(cid));
        JsonNode componentsSnapshot = json("""
            [{"componentId":"%s","sortOrder":%d,"fields":[]}]
            """.formatted(cid, sortOrder));
        return CardEffectiveRows.parse(cardValues, componentsSnapshot, c -> null);
    }

    // ---- 路径 B：持久化 component_data → ComponentDataEffectiveRows.compute -------------

    private static Map<String, CardEffectiveRows.TabRows> viaComponentData(String cid, int sortOrder) {
        QuotationLineComponentData d = new QuotationLineComponentData();
        d.componentId = UUID.fromString(cid);
        d.sortOrder = sortOrder;
        d.rowData = "[{\"元素代码\":\"Ag\",\"金额\":489985}]";
        d.subtotal = new BigDecimal("489985");
        Map<UUID, ComponentDataEffectiveRows.Meta> metas = Map.of(
            UUID.fromString(cid),
            new ComponentDataEffectiveRows.Meta("COMP-TEST", "材质元素", "DETAIL", null));
        return ComponentDataEffectiveRows.compute(List.of(d), metas, new FormulaCalculator());
    }

    // ---- 契约 1：两条路径各自都必须双键登记，且两键指向同一 TabRows ----------------------

    @Test
    @DisplayName("CardEffectiveRows.parse 必须双键登记（裸 componentId + componentId:sortOrder）")
    void cardValuesPathRegistersBothKeys() {
        Map<String, CardEffectiveRows.TabRows> out = viaCardValues(CID, SORT);

        CardEffectiveRows.TabRows byBare = out.get(CID);
        CardEffectiveRows.TabRows bySort = out.get(CID + ":" + SORT);

        assertNotNull(byBare,
            "裸 componentId 键缺失 —— Excel 列配置的 tabKey 就是裸 componentId，"
            + "CardDataProvider.subtotalOf 精确查表会 miss → TAB_JOIN_FORMULA 列恒 0");
        assertNotNull(bySort, "componentId:sortOrder 键缺失（CardRef 约定）");
        assertSame(byBare, bySort, "双键必须指向同一 TabRows 实例");
        assertEquals(0, new BigDecimal("489985").compareTo(byBare.subtotal));
    }

    @Test
    @DisplayName("ComponentDataEffectiveRows.compute 必须双键登记（参照实现，防反向退化）")
    void componentDataPathRegistersBothKeys() {
        Map<String, CardEffectiveRows.TabRows> out = viaComponentData(CID, SORT);

        CardEffectiveRows.TabRows byBare = out.get(CID);
        CardEffectiveRows.TabRows bySort = out.get(CID + ":" + SORT);

        assertNotNull(byBare, "裸 componentId 键缺失（Excel 列 tabKey 约定）");
        assertNotNull(bySort, "componentId:sortOrder 键缺失（CardRef 约定）");
        assertSame(byBare, bySort, "双键必须指向同一 TabRows 实例");
    }

    // ---- 契约 2：同一份逻辑输入下，两条路径的键集合逐字相同 ------------------------------

    @Test
    @DisplayName("同一 componentId+sortOrder 下，两条路径产出的键集合必须完全一致")
    void bothPathsProduceIdenticalKeySets() {
        Set<String> fromCardValues   = viaCardValues(CID, SORT).keySet();
        Set<String> fromComponentData = viaComponentData(CID, SORT).keySet();

        assertEquals(Set.of(CID, CID + ":" + SORT), fromCardValues,
            "卡片值路径键集合不符合约定");
        assertEquals(fromComponentData, fromCardValues,
            "两条 effective-rows 路径键口径漂移 —— 消费方是同一个 CardDataProvider，"
            + "任一路径少一种键形状都会让对应消费方静默取不到值");
    }

    // ---- 契约 3：双键优先级非对称（裸键 put 后者胜 / 复合键 putIfAbsent 首者胜）----------

    @Test
    @DisplayName("裸键用 put（后者覆盖）、复合键用 putIfAbsent（首者胜）—— 两条路径同款")
    void dualKeyPriorityIsAsymmetricOnBothPaths() {
        // 路径 A：同一 componentId 在快照里出现两次（componentsSnapshot 只给一个 sortOrder，
        // 故两个 tab 的复合键相同）→ 裸键取后者、复合键取前者。
        JsonNode cardValues = json("""
            {"tabs":[
              {"componentId":"%s","subtotal":1,"resolvedRows":[{"标记":"first"}]},
              {"componentId":"%s","subtotal":2,"resolvedRows":[{"标记":"last"}]}]}
            """.formatted(CID, CID));
        JsonNode cs = json("[{\"componentId\":\"" + CID + "\",\"sortOrder\":0,\"fields\":[]}]");
        Map<String, CardEffectiveRows.TabRows> a = CardEffectiveRows.parse(cardValues, cs, c -> null);
        assertEquals("last", a.get(CID).rows.get(0).get("标记"),
            "裸键必须是 put 语义（后出现者覆盖）");
        assertEquals("first", a.get(CID + ":0").rows.get(0).get("标记"),
            "复合键必须是 putIfAbsent 语义（首出现者胜）");

        // 路径 B（参照实现）：同 componentId 两个实例 sortOrder=0/1 → 裸键取后者。
        QuotationLineComponentData d0 = new QuotationLineComponentData();
        d0.componentId = UUID.fromString(CID); d0.sortOrder = 0;
        d0.rowData = "[{\"标记\":\"first\"}]"; d0.subtotal = BigDecimal.ONE;
        QuotationLineComponentData d1 = new QuotationLineComponentData();
        d1.componentId = UUID.fromString(CID); d1.sortOrder = 1;
        d1.rowData = "[{\"标记\":\"last\"}]"; d1.subtotal = BigDecimal.TEN;
        Map<UUID, ComponentDataEffectiveRows.Meta> metas = Map.of(
            UUID.fromString(CID),
            new ComponentDataEffectiveRows.Meta("COMP-TEST", "材质元素", "DETAIL", null));
        Map<String, CardEffectiveRows.TabRows> b =
            ComponentDataEffectiveRows.compute(List.of(d0, d1), metas, new FormulaCalculator());
        assertEquals("last", b.get(CID).rows.get(0).get("标记"),
            "参照实现的裸键同样是 put 语义（后者覆盖）");
        assertEquals("first", b.get(CID + ":0").rows.get(0).get("标记"));
        assertEquals("last", b.get(CID + ":1").rows.get(0).get("标记"));
    }

    // ---- 契约 4：真实核价模板形态（4 页签）—— 每个页签都双键，共 8 个键 -------------------

    @Test
    @DisplayName("多页签核价快照：每个 componentId 都产出裸键与复合键，供 Excel 列 tabKey 命中")
    void multiTabCostingSnapshotRegistersBareKeyForEveryTab() {
        String bom   = "a2d0dc3a-9506-4b7e-8d5e-8e512433a201";
        String elem  = "1e41ecb6-b663-42a1-b8da-122961699d26";
        String proc  = "36eb1efb-8e35-45bd-8dc7-199521a759b9";
        String total = "203382c5-38fd-4a81-978e-610afc8da3bd";

        // 🚨 生产形态夹具（逐字对齐 cpq_db_0910 里 QT-20260911-0010 / S0001 的 costing_card_values）：
        //    tab 无 sortOrder · subtotal 与 subtotalByColumn 值均为字符串 ·
        //    SUBTOTAL 页签（核价小计1）**没有** subtotalByColumn 键。
        //    ⚠️ 用 number 写这四个 subtotal，本用例在「只还原 R2」时会假绿。
        JsonNode cardValues = json("""
            {"tabs":[
              {"componentId":"%s","tabName":"BOM","componentType":"NORMAL","subtotal":"5438667.5",
               "subtotalByColumn":{"物料成本":"5438667.5"},"resolvedRows":[{"金额":5438667.5}]},
              {"componentId":"%s","tabName":"材质元素","componentType":"NORMAL","subtotal":"489985",
               "subtotalByColumn":{"元素成本":"489985"},"resolvedRows":[{"金额":489985}]},
              {"componentId":"%s","tabName":"加工费","componentType":"NORMAL","subtotal":"5.8",
               "subtotalByColumn":{"加工费":"5.8"},"resolvedRows":[{"金额":5.8}]},
              {"componentId":"%s","tabName":"核价小计1","componentType":"SUBTOTAL","subtotal":"5438673.3",
               "resolvedRows":[]}]}
            """.formatted(bom, elem, proc, total));
        JsonNode cs = json("""
            [{"componentId":"%s","sortOrder":0,"fields":[]},
             {"componentId":"%s","sortOrder":1,"fields":[]},
             {"componentId":"%s","sortOrder":2,"fields":[]},
             {"componentId":"%s","sortOrder":3,"fields":[]}]
            """.formatted(bom, elem, proc, total));

        Map<String, CardEffectiveRows.TabRows> out = CardEffectiveRows.parse(cardValues, cs, c -> null);

        assertEquals(8, out.size(), "4 个页签 × 双键 = 8 个键");
        assertEquals(0, new BigDecimal("489985").compareTo(out.get(elem).subtotal));
        assertEquals(0, new BigDecimal("5438667.5").compareTo(out.get(bom).subtotal));
        assertEquals(0, new BigDecimal("5.8").compareTo(out.get(proc).subtotal));
        assertEquals(0, new BigDecimal("5438673.3").compareTo(out.get(total).subtotal));
        assertEquals(0, new BigDecimal("5438667.5").compareTo(out.get(bom).subtotalByColumn.get("物料成本")),
            "subtotalByColumn 的列值也是字符串，同样要读出来（[页签.列名(总计)] 引用）");
        assertTrue(out.get(total).subtotalByColumn.isEmpty(),
            "SUBTOTAL 页签生产上无 subtotalByColumn 键 → 空 Map，不得 NPE");
    }

    // ---- 契约 5：AC-1 的单测镜像 —— 生产形态快照 + 生产列配置 → 四列必须出真值 -----------

    @Test
    @DisplayName("AC-1 单测镜像：核价通用1 的四个 TAB_JOIN 列（裸 tabKey + 字符串 subtotal）求出真值")
    void productionShapedSnapshot_yieldsAc1Values() {
        String bom   = "a2d0dc3a-9506-4b7e-8d5e-8e512433a201";
        String elem  = "1e41ecb6-b663-42a1-b8da-122961699d26";
        String proc  = "36eb1efb-8e35-45bd-8dc7-199521a759b9";
        String total = "203382c5-38fd-4a81-978e-610afc8da3bd";

        JsonNode cardValues = json("""
            {"tabs":[
              {"componentId":"%s","tabName":"BOM","subtotal":"5438667.5","resolvedRows":[{"金额":5438667.5}]},
              {"componentId":"%s","tabName":"材质元素","subtotal":"489985","resolvedRows":[{"金额":489985}]},
              {"componentId":"%s","tabName":"加工费","subtotal":"5.8","resolvedRows":[{"金额":5.8}]},
              {"componentId":"%s","tabName":"核价小计1","subtotal":"5438673.3","resolvedRows":[]}]}
            """.formatted(bom, elem, proc, total));
        JsonNode cs = json("""
            [{"componentId":"%s","sortOrder":0,"fields":[]},
             {"componentId":"%s","sortOrder":1,"fields":[]},
             {"componentId":"%s","sortOrder":2,"fields":[]},
             {"componentId":"%s","sortOrder":3,"fields":[]}]
            """.formatted(bom, elem, proc, total));

        // 列配置逐字取自现网 `核价通用1` 绑定的 EXCEL 组件 excel_columns（tabKey 均为裸 componentId）
        var provider = com.cpq.quotation.service.card.CardDataProvider.fromEffectiveRows(
            CardEffectiveRows.parse(cardValues, cs, c -> null));
        var ev = new com.cpq.quotation.service.tabjoin.TabJoinPlanEvaluator();

        record Col(String key, String alias, String tabKey, String expected) {}
        List<Col> cols = List.of(
            new Col("col_1", "材质元素",   elem,  "489985"),
            new Col("col_2", "BOM",       bom,   "5438667.5"),
            new Col("col_3", "加工费",     proc,  "5.8"),
            new Col("col_4", "核价小计1",  total, "5438673.3"));

        for (Col c : cols) {
            Map<String, Object> col = Map.of(
                "col_key", c.key(),
                "source_type", "TAB_JOIN_FORMULA",
                "expression", "[" + c.alias() + "(总计)]",
                "tabs", List.of(Map.of("alias", c.alias(), "tabKey", c.tabKey(), "rowKeyFields", List.of())));
            BigDecimal v = ev.evaluateColumn(col, provider);
            assertEquals(0, new BigDecimal(c.expected()).compareTo(v),
                c.key() + " [" + c.alias() + "(总计)] 期望 " + c.expected() + " 实得 " + v
                    + " —— R1（裸键登记）/ R2（字符串 subtotal 可读）任一缺失都会得 0");
        }
    }
}
