package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.entity.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯 JUnit 单测（无 @QuarkusTest / DB）：task-0803 Task5 闸②③④ —— BOM 父子取值
 * （tree_ref/tree_attr）的 tabType 联动校验 + BOM 页签禁用 previous_row_subtotal。
 *
 * <p>{@code assertTreeTokenGates} / {@code applyTabType} 均为 package-private，
 * 直接同包 new ComponentService() 调用，不落库（与既有 ComponentServiceCrossTabValidateTest /
 * ComponentServiceConditionalValidationTest 同款做法）。
 */
@DisplayName("ComponentService — tree_ref/tree_attr/previous_row_subtotal 的 tabType 联动闸门")
class ComponentServiceTreeTokenGateTest {

    private final ComponentService svc = new ComponentService();

    // ------------------------------------------------------------------
    // token / formula 构造 helper
    // ------------------------------------------------------------------

    private static Map<String, Object> treeRefToken(String dir) {
        Map<String, Object> token = new HashMap<>();
        token.put("type", "tree_ref");
        token.put("dir", dir);
        token.put("agg", "PARENT".equals(dir) ? "NONE" : "SUM");
        token.put("targetExpr", List.of(Map.of("type", "field", "value", "用量")));
        return token;
    }

    private static Map<String, Object> treeAttrToken() {
        Map<String, Object> token = new HashMap<>();
        token.put("type", "tree_attr");
        token.put("attr", "LVL");
        return token;
    }

    private static Map<String, Object> prevToken() {
        Map<String, Object> token = new HashMap<>();
        token.put("type", "previous_row_subtotal");
        return token;
    }

    private static String formulasJsonWith(String formulaName, Map<String, Object> token) {
        Map<String, Object> formula = new HashMap<>();
        formula.put("name", formulaName);
        formula.put("expression", List.of(token));
        return toJson(List.of(formula));
    }

    private static String toJson(List<Map<String, Object>> list) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // 闸②（正向闸）：非 BOM 页签配父子公式 → 拒绝；BOM 页签放行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("闸②: 非 BOM tabType + tree_ref 公式 → 400，消息点名公式")
    void nonBomTabType_withTreeRef_rejected() {
        String formulas = formulasJsonWith("累计用量公式", treeRefToken("PARENT"));
        BusinessException ex = assertThrows(BusinessException.class,
            () -> svc.assertTreeTokenGates("零件", formulas));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("累计用量公式"), ex.getMessage());
        assertTrue(ex.getMessage().contains("tree_ref"), ex.getMessage());
    }

    @Test
    @DisplayName("闸②: tabType=null（未配置）+ tree_attr 公式 → 400")
    void nullTabType_withTreeAttr_rejected() {
        String formulas = formulasJsonWith("层级公式", treeAttrToken());
        BusinessException ex = assertThrows(BusinessException.class,
            () -> svc.assertTreeTokenGates(null, formulas));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("层级公式"), ex.getMessage());
    }

    @Test
    @DisplayName("放行: BOM tabType + tree_ref(PARENT) 公式 → 不抛")
    void bomTabType_withTreeRefParent_passes() {
        String formulas = formulasJsonWith("累计用量公式", treeRefToken("PARENT"));
        assertDoesNotThrow(() -> svc.assertTreeTokenGates("BOM", formulas));
    }

    @Test
    @DisplayName("放行: BOM tabType + tree_ref(CHILD) 公式 → 不抛")
    void bomTabType_withTreeRefChild_passes() {
        String formulas = formulasJsonWith("子件汇总公式", treeRefToken("CHILD"));
        assertDoesNotThrow(() -> svc.assertTreeTokenGates("BOM", formulas));
    }

    @Test
    @DisplayName("放行: BOM tabType + tree_attr 公式 → 不抛")
    void bomTabType_withTreeAttr_passes() {
        String formulas = formulasJsonWith("层级公式", treeAttrToken());
        assertDoesNotThrow(() -> svc.assertTreeTokenGates("BOM", formulas));
    }

    @Test
    @DisplayName("放行: 无父子 token 的普通公式，任何 tabType 都不受影响")
    void noTreeToken_anyTabType_passes() {
        Map<String, Object> plain = new HashMap<>();
        plain.put("type", "field");
        plain.put("value", "单价");
        String formulas = formulasJsonWith("普通公式", plain);
        assertDoesNotThrow(() -> svc.assertTreeTokenGates("零件", formulas));
        assertDoesNotThrow(() -> svc.assertTreeTokenGates(null, formulas));
        assertDoesNotThrow(() -> svc.assertTreeTokenGates("BOM", formulas));
    }

    // ------------------------------------------------------------------
    // 闸④：BOM 页签禁用 previous_row_subtotal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("闸④: BOM tabType + previous_row_subtotal 公式 → 400")
    void bomTabType_withPrev_rejected() {
        String formulas = formulasJsonWith("上一行公式", prevToken());
        BusinessException ex = assertThrows(BusinessException.class,
            () -> svc.assertTreeTokenGates("BOM", formulas));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("上一行公式"), ex.getMessage());
        assertTrue(ex.getMessage().contains("previous_row_subtotal"), ex.getMessage());
    }

    @Test
    @DisplayName("放行: 非 BOM tabType + previous_row_subtotal 公式 → 不抛（PREV 只在 BOM 页签被禁）")
    void nonBomTabType_withPrev_passes() {
        String formulas = formulasJsonWith("上一行公式", prevToken());
        assertDoesNotThrow(() -> svc.assertTreeTokenGates("零件", formulas));
    }

    // ------------------------------------------------------------------
    // 闸①（经 assertTreeTokenGates 间接触发）：targetExpr 内嵌套非法 token
    // ------------------------------------------------------------------

    @Test
    @DisplayName("闸①经由闸门集成: tree_ref.targetExpr 内嵌套 cross_tab_ref → 400（即使 tabType=BOM）")
    void bomTabType_withIllegalInnerToken_stillRejectedByGate1() {
        Map<String, Object> badTreeRef = new HashMap<>();
        badTreeRef.put("type", "tree_ref");
        badTreeRef.put("dir", "PARENT");
        badTreeRef.put("agg", "NONE");
        badTreeRef.put("targetExpr", List.of(Map.of(
            "type", "cross_tab_ref", "source", "回料", "agg", "SUM",
            "match", List.of(Map.of("a", "料号", "b", "料号")))));
        String formulas = formulasJsonWith("非法内层公式", badTreeRef);
        BusinessException ex = assertThrows(BusinessException.class,
            () -> svc.assertTreeTokenGates("BOM", formulas));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("非法内层公式"), ex.getMessage());
        assertTrue(ex.getMessage().contains("cross_tab_ref"), ex.getMessage());
    }

    // ------------------------------------------------------------------
    // 闸③（反向闸，位于 applyTabType）：已配父子公式的组件改 tabType 离开 BOM → 拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("闸③: 组件 tabType 原为 BOM 且公式含 tree_ref，改 tabType 为「零件」→ 400")
    void existingBomComponentWithTreeRef_changingTabTypeAway_rejected() {
        Component component = new Component();
        component.tabType = "BOM";
        component.formulas = formulasJsonWith("累计用量公式", treeRefToken("PARENT"));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> svc.applyTabType(component, "零件", null, "料件名称"));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("父子取值"), ex.getMessage());
        assertTrue(ex.getMessage().contains("删除"), ex.getMessage());
        // 拦截后组件 tabType 不应被改动
        assertEquals("BOM", component.tabType);
    }

    @Test
    @DisplayName("闸③: 组件 tabType 原为 BOM 且公式含 tree_ref，改 tabType 为「(清空)」→ 400")
    void existingBomComponentWithTreeRef_clearingTabType_rejected() {
        Component component = new Component();
        component.tabType = "BOM";
        component.formulas = formulasJsonWith("层级公式", treeAttrToken());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> svc.applyTabType(component, "", null, null));
        assertEquals(400, ex.getCode());
        assertEquals("BOM", component.tabType);
    }

    @Test
    @DisplayName("对照: 组件 tabType 原为 BOM 且公式含 tree_ref，tabType 仍设为 BOM → 不受闸③影响")
    void existingBomComponentWithTreeRef_stayingBom_passes() {
        Component component = new Component();
        component.tabType = "BOM";
        component.formulas = formulasJsonWith("累计用量公式", treeRefToken("PARENT"));

        assertDoesNotThrow(() -> svc.applyTabType(component, "BOM", null, null));
        assertEquals("BOM", component.tabType);
    }

    @Test
    @DisplayName("对照: 组件此前不是 BOM（wasBom=false），即便公式含父子 token，改 tabType 也不触发闸③"
        + "（闸③只拦「真发生 BOM→非 BOM 转出」；此场景是闸②的职责，不在 applyTabType 里拦）")
    void nonBomComponent_changingTabType_notBlockedByGate3() {
        Component component = new Component();
        component.tabType = "零件"; // 从未是 BOM
        component.formulas = formulasJsonWith("累计用量公式", treeRefToken("PARENT"));

        // partNameField 满足"外购件"标识列要求，applyTabType 本身应放行（wasBom=false → 闸③不介入）。
        assertDoesNotThrow(() -> svc.applyTabType(component, "外购件", null, "料件名称"));
        assertEquals("外购件", component.tabType);
    }

    @Test
    @DisplayName("对照: 组件 tabType 原为 BOM 但公式无父子 token，改 tabType 离开 BOM → 不受闸③影响")
    void existingBomComponentWithoutTreeToken_changingTabTypeAway_passes() {
        Component component = new Component();
        component.tabType = "BOM";
        Map<String, Object> plain = new HashMap<>();
        plain.put("type", "field");
        plain.put("value", "单价");
        component.formulas = formulasJsonWith("普通公式", plain);

        assertDoesNotThrow(() -> svc.applyTabType(component, "零件", null, "料件名称"));
        assertEquals("零件", component.tabType);
    }
}
