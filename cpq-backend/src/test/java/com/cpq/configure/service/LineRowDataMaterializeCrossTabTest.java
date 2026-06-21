package com.cpq.configure.service;

import com.cpq.quotation.service.FormulaCalculator;
import com.cpq.quotation.service.RowDataMaterializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 失焦同步「整行按拓扑序重物化 → 跨页签依赖随编辑传播」回归（TDD）。
 *
 * <p>纯函数单测：直接 {@code new ConfigureSnapshotService()} 并注入真实
 * {@link RowDataMaterializer}（包内可见字段），只调 {@link ConfigureSnapshotService#computeLineRowData}
 * （不落库，无需 Quarkus 容器）。
 *
 * <p><b>场景（faithful cross-tab）</b>：
 * <ul>
 *   <li>组件「元素」(code=ELEM)：INPUT_NUMBER {@code 单价}。</li>
 *   <li>组件「来料」(code=FEEDING)：FORMULA {@code 材料成本} = {@code component_subtotal(ELEM#单价)}
 *       —— 引用「元素」的单价列小计（跨页签依赖）。</li>
 * </ul>
 *
 * <p><b>RED（旧逐组件物化）</b>：只重物化被编辑的「元素」→「来料」row_data 仍是配置态旧值。
 * <p><b>GREEN（整行拓扑序物化）</b>：编辑「元素.单价」10→100 后，按拓扑序先物化「元素」（列小计=100），
 * 再物化「来料」→ 其 {@code 材料成本} = 100（读到依赖方最新列小计）。
 */
class LineRowDataMaterializeCrossTabTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID ELEM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FEEDING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ConfigureSnapshotService newService() {
        ConfigureSnapshotService svc = new ConfigureSnapshotService();
        svc.rowDataMaterializer = new RowDataMaterializer(new FormulaCalculator());
        return svc;
    }

    /** components_snapshot：元素(INPUT 单价) + 来料(FORMULA 材料成本 = component_subtotal(ELEM#单价))。 */
    private JsonNode componentsSnapshot() throws Exception {
        return MAPPER.readTree("""
            [
              {
                "componentId": "11111111-1111-1111-1111-111111111111",
                "componentCode": "ELEM",
                "componentType": "NORMAL",
                "tabName": "元素",
                "fields": [
                  {"name": "单价", "field_type": "INPUT_NUMBER"}
                ],
                "formulas": []
              },
              {
                "componentId": "22222222-2222-2222-2222-222222222222",
                "componentCode": "FEEDING",
                "componentType": "NORMAL",
                "tabName": "来料",
                "fields": [
                  {"name": "材料成本", "field_type": "FORMULA", "formula_name": "材料成本"}
                ],
                "formulas": [
                  {"name": "材料成本", "expression": [
                    {"type": "component_subtotal", "component_code": "ELEM", "value": "单价"}
                  ]}
                ]
              }
            ]
            """);
    }

    private Map<UUID, JsonNode> baseRows() throws Exception {
        Map<UUID, JsonNode> base = new LinkedHashMap<>();
        // 元素一行，配置态 单价=10
        base.put(ELEM_ID, MAPPER.readTree("""
            [ {"driverRow": {"单价": 10}, "basicDataValues": {}} ]
            """));
        // 来料一行（FORMULA 叶子由物化算出）
        base.put(FEEDING_ID, MAPPER.readTree("""
            [ {"driverRow": {}, "basicDataValues": {}} ]
            """));
        return base;
    }

    /** 基线：无编辑时，来料.材料成本 = 元素.单价 列小计 = 10。 */
    @Test
    void configureLikeNoEdit_feedingMaterialCostEqualsElemSubtotal() throws Exception {
        LinkedHashMap<UUID, ArrayNode> out = newService().computeLineRowData(
                UUID.randomUUID(), componentsSnapshot(), baseRows(),
                Map.of(), Map.of(), Map.of());

        ArrayNode feeding = out.get(FEEDING_ID);
        assertNotNull(feeding, "来料 必须被物化");
        assertEquals(10.0, feeding.get(0).path("材料成本").asDouble(), 1e-6,
                "无编辑：来料.材料成本 = 元素.单价 列小计 = 10");
    }

    /**
     * 核心回归：编辑「元素.单价」10→100，整行按拓扑序重物化后，
     * <b>来料</b> 的持久化 row_data.材料成本 必须随依赖方（元素）刷新到 100（而非配置态 10）。
     */
    @Test
    void editElemUnitPrice_propagatesCrossTabToFeedingMaterialCost() throws Exception {
        // 元素的 editRows：单价 10→100（rowKey 用位置下标 "0"，因无 rowKeyFields → effKey 退化位置）。
        Map<UUID, JsonNode> editRowsByComp = new LinkedHashMap<>();
        editRowsByComp.put(ELEM_ID, MAPPER.readTree("""
            [ {"rowKey": "0", "values": {"单价": 100}} ]
            """));

        LinkedHashMap<UUID, ArrayNode> out = newService().computeLineRowData(
                UUID.randomUUID(), componentsSnapshot(), baseRows(),
                editRowsByComp, Map.of(), Map.of());

        // 元素自身：单价 反映编辑值 100。
        ArrayNode elem = out.get(ELEM_ID);
        assertNotNull(elem, "元素 必须被物化");
        assertEquals(100.0, elem.get(0).path("单价").asDouble(), 1e-6,
                "元素.单价 应反映编辑值 100");

        // 来料（跨页签依赖）：材料成本 随元素列小计刷新到 100（RED：旧逐组件物化会停在 10）。
        ArrayNode feeding = out.get(FEEDING_ID);
        assertNotNull(feeding, "来料 必须被物化");
        assertEquals(100.0, feeding.get(0).path("材料成本").asDouble(), 1e-6,
                "来料.材料成本 必须随元素编辑跨页签传播到 100（不能停在配置态 10）");
    }
}
