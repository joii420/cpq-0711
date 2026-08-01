package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * buildCardValues 改用冻结的 quotation_view_structure 算值后的形状兼容护栏。
 *
 * <p><b>背景</b>：结构创建即冻（68fed021），前端渲染与它算的 li.subtotal 都读这份冻结结构；
 * 而 buildCardValues 原先读 template.components_snapshot（随组件保存自动刷新）。两个源一旦
 * 分叉（典型：给组件新加 conditional_formula），同一张卡就会出现「后端算的小计 214 vs
 * 前端算的总额 14」。改动把配置源归一到冻结结构。
 *
 * <p><b>本测试锁死的不变量</b>：冻结结构是 <em>camelCase 精简投影</em>
 * （{@code fieldType}/{@code isSubtotal}/{@code conditionalFormula}），与模板快照的
 * snake_case 形状不同。算值链路必须对两种形状给出<b>逐值相同</b>的结果——否则换源就是静默改数。
 */
@QuarkusTest
@DisplayName("CardSnapshotFrozenShapeTest — 冻结结构(camelCase) 与 模板快照(snake_case) 算值等价")
class CardSnapshotFrozenShapeTest {

    @Inject CardSnapshotService svc;
    private static final ObjectMapper M = new ObjectMapper();

    /** 模板快照形状：snake_case（template.components_snapshot 的原始形状）。 */
    private static final String SNAKE_SHAPE = """
        [ { "componentId":"c1", "componentCode":"C1", "tabName":"材料成本", "componentType":"NORMAL", "sortOrder":1,
            "fields":[
              {"name":"元素","field_type":"INPUT","sort_order":1},
              {"name":"元素单价","field_type":"INPUT_NUMBER","sort_order":2},
              {"name":"材料成本","field_type":"FORMULA","sort_order":3,"is_subtotal":true,"is_amount":true,
               "conditional_formula":{
                 "rules":[{"when":{"kind":"leaf","left":"元素","op":"eq","rhs":{"type":"literal","value":"Ag"}},"formula":"f_ag"}],
                 "default":"f_base"}}
            ],
            "formulas":[
              {"name":"f_ag","expression":[{"type":"field","value":"元素单价"},{"type":"operator","value":"*"},{"type":"number","value":"2"}]},
              {"name":"f_base","expression":[{"type":"number","value":"0"}]}
            ],
            "formula_assignments":[] } ]
        """;

    /**
     * 冻结结构形状：camelCase 精简投影（quotation_view_structure.structure.tabs 的形状）。
     * 逐字对应 buildCardStructure 的搬运结果——字段只保留投影键，无 sort_order/notes 等。
     */
    private static final String CAMEL_SHAPE = """
        [ { "componentId":"c1", "componentCode":"C1", "tabName":"材料成本", "componentType":"NORMAL", "sortOrder":1,
            "rowKeyFields":[],
            "fields":[
              {"name":"元素","label":"元素","fieldType":"INPUT","sortOrder":1,"editable":true,"width":0},
              {"name":"元素单价","label":"元素单价","fieldType":"INPUT_NUMBER","sortOrder":2,"editable":true,"width":0},
              {"name":"材料成本","label":"材料成本","fieldType":"FORMULA","sortOrder":3,"editable":false,"width":0,
               "isSubtotal":true,"isAmount":true,
               "conditionalFormula":{
                 "rules":[{"when":{"kind":"leaf","left":"元素","op":"eq","rhs":{"type":"literal","value":"Ag"}},"formula":"f_ag"}],
                 "default":"f_base"}}
            ],
            "formulas":[
              {"name":"f_ag","expression":[{"type":"field","value":"元素单价"},{"type":"operator","value":"*"},{"type":"number","value":"2"}]},
              {"name":"f_base","expression":[{"type":"number","value":"0"}]}
            ],
            "formula_assignments":[] } ]
        """;

    /** 两行：Ag 命中 f_ag → 单价×2；Cu 落 default f_base → 0。列小计 = 200。 */
    private JsonNode evalWith(String snapshotJson) throws Exception {
        JsonNode snapshot = M.readTree(snapshotJson);
        var baseRowsByComp = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.node.ArrayNode>();
        var baseRows = M.createArrayNode();

        var rowAg = M.createObjectNode();
        var drAg = M.createObjectNode(); drAg.put("元素", "Ag"); drAg.put("元素单价", 100);
        rowAg.set("driverRow", drAg); rowAg.set("basicDataValues", M.createObjectNode());
        baseRows.add(rowAg);

        var rowCu = M.createObjectNode();
        var drCu = M.createObjectNode(); drCu.put("元素", "Cu"); drCu.put("元素单价", 50);
        rowCu.set("driverRow", drCu); rowCu.set("basicDataValues", M.createObjectNode());
        baseRows.add(rowCu);

        baseRowsByComp.put("c1", baseRows);
        return M.readTree(svc.assembleTabsWithFormulaResultsForTest(snapshot, baseRowsByComp, null));
    }

    @Test
    @DisplayName("① 冻结结构(camelCase) 的 conditionalFormula 生效：Ag 行 200、Cu 行 0")
    void camelShapeConditionalFormulaWorks() throws Exception {
        JsonNode tab = evalWith(CAMEL_SHAPE).path("tabs").get(0);
        JsonNode fr = tab.path("formulaResults");
        assertEquals(2, fr.size(), "两行都应出结果");
        assertEquals(200.0, fr.get(0).path("values").path("材料成本").asDouble(), 1e-9,
            "Ag 行命中 conditionalFormula 的 f_ag = 100×2；若 camelCase 键没被识别会退回 default → 0");
        assertEquals(0.0, fr.get(1).path("values").path("材料成本").asDouble(), 1e-9,
            "Cu 行落 default f_base = 0");
    }

    @Test
    @DisplayName("② 冻结结构(camelCase) 的 isSubtotal 被识别 → 页签小计 = 200")
    void camelShapeSubtotalRecognized() throws Exception {
        JsonNode tab = evalWith(CAMEL_SHAPE).path("tabs").get(0);
        assertEquals(200.0, tab.path("subtotal").asDouble(), 1e-9,
            "isSubtotal(camelCase) 未被识别时该列不进小计 → 0，正是「卡片小计与总额分叉」的成因");
    }

    @Test
    @DisplayName("③ 两种形状逐值等价 —— 换配置源不得改变任何计算结果")
    void bothShapesProduceIdenticalValues() throws Exception {
        JsonNode camel = evalWith(CAMEL_SHAPE).path("tabs").get(0);
        JsonNode snake = evalWith(SNAKE_SHAPE).path("tabs").get(0);

        assertEquals(snake.path("subtotal").asDouble(), camel.path("subtotal").asDouble(), 1e-9,
            "页签小计必须一致");
        assertEquals(snake.path("formulaResults").toString(), camel.path("formulaResults").toString(),
            "逐行公式结果必须逐字节一致 —— 不一致即说明换源会静默改数");
    }
}
