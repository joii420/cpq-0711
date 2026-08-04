package com.cpq.component.formula;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯 JUnit 单测（无 @QuarkusTest / DB）：task-0803 Task5 闸① —— {@code tree_ref.targetExpr}
 * 内层白名单校验（{@link TokenMappabilityValidator#validateTreeRefTargetExpr}）。
 *
 * <p>白名单：field/operator/number/bracket_open/bracket_close/global_variable/tree_attr。
 * 必拒：嵌套 tree_ref / cross_tab_ref / component_subtotal / b_field / previous_row_subtotal。
 */
@DisplayName("TokenMappabilityValidator — tree_ref.targetExpr 内层白名单（闸①）")
class TokenMappabilityValidatorTreeRefTest {

    private final TokenMappabilityValidator v = new TokenMappabilityValidator();

    @Test
    @DisplayName("放行：field/operator/number/bracket_open/bracket_close/global_variable/tree_attr 混合")
    void allowedTypesMix_isMappable() {
        List<Map<String, Object>> targetExpr = List.of(
            Map.of("type", "bracket_open"),
            Map.of("type", "field", "value", "用量"),
            Map.of("type", "operator", "value", "*"),
            Map.of("type", "field", "value", "单价"),
            Map.of("type", "bracket_close"),
            Map.of("type", "operator", "value", "+"),
            Map.of("type", "number", "value", "1"),
            Map.of("type", "operator", "value", "+"),
            Map.of("type", "global_variable", "code", "ELEM_PRICE"),
            Map.of("type", "operator", "value", "+"),
            Map.of("type", "tree_attr", "attr", "LVL")
        );
        var r = v.validateTreeRefTargetExpr(targetExpr);
        assertTrue(r.mappable(), r.reason());
    }

    @Test
    @DisplayName("放行：null targetExpr 视为空")
    void nullTargetExpr_isMappable() {
        var r = v.validateTreeRefTargetExpr(null);
        assertTrue(r.mappable(), r.reason());
    }

    @Test
    @DisplayName("放行：空 targetExpr")
    void emptyTargetExpr_isMappable() {
        var r = v.validateTreeRefTargetExpr(List.of());
        assertTrue(r.mappable(), r.reason());
    }

    @Test
    @DisplayName("拒绝：嵌套 tree_ref")
    void nestedTreeRef_isRejected() {
        List<Map<String, Object>> targetExpr = List.of(
            Map.of("type", "field", "value", "用量"),
            Map.of("type", "operator", "value", "+"),
            Map.of("type", "tree_ref", "dir", "PARENT", "agg", "NONE",
                   "targetExpr", List.of(Map.of("type", "field", "value", "累计用量")))
        );
        var r = v.validateTreeRefTargetExpr(targetExpr);
        assertFalse(r.mappable());
        assertTrue(r.reason().contains("tree_ref"), r.reason());
    }

    @Test
    @DisplayName("拒绝：cross_tab_ref")
    void crossTabRef_isRejected() {
        List<Map<String, Object>> targetExpr = List.of(
            Map.of("type", "cross_tab_ref", "source", "回料", "agg", "SUM",
                   "match", List.of(Map.of("a", "料号", "b", "料号")))
        );
        var r = v.validateTreeRefTargetExpr(targetExpr);
        assertFalse(r.mappable());
        assertTrue(r.reason().contains("cross_tab_ref"), r.reason());
    }

    @Test
    @DisplayName("拒绝：component_subtotal")
    void componentSubtotal_isRejected() {
        List<Map<String, Object>> targetExpr = List.of(
            Map.of("type", "component_subtotal", "value", "投料")
        );
        var r = v.validateTreeRefTargetExpr(targetExpr);
        assertFalse(r.mappable());
        assertTrue(r.reason().contains("component_subtotal"), r.reason());
    }

    @Test
    @DisplayName("拒绝：b_field")
    void bField_isRejected() {
        List<Map<String, Object>> targetExpr = List.of(
            Map.of("type", "b_field", "value", "料号")
        );
        var r = v.validateTreeRefTargetExpr(targetExpr);
        assertFalse(r.mappable());
        assertTrue(r.reason().contains("b_field"), r.reason());
    }

    @Test
    @DisplayName("拒绝：previous_row_subtotal")
    void previousRowSubtotal_isRejected() {
        List<Map<String, Object>> targetExpr = List.of(
            Map.of("type", "previous_row_subtotal")
        );
        var r = v.validateTreeRefTargetExpr(targetExpr);
        assertFalse(r.mappable());
        assertTrue(r.reason().contains("previous_row_subtotal"), r.reason());
    }
}
