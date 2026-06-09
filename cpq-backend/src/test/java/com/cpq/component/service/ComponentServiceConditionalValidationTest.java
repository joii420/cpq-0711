package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Plan 3c：条件公式引用校验 + 硬环检测。 */
@QuarkusTest
class ComponentServiceConditionalValidationTest {

    @Inject ComponentService svc;

    private Map<String, Object> formulaField(String name, Object condFormula) {
        return Map.of("name", name, "field_type", "FORMULA", "conditional_formula", condFormula);
    }

    private static Map<String, Object> emptyWhen() {
        return Map.of("kind", "group", "logic", "and", "children", List.of());
    }

    @Test
    void missingRuleFormula_throws() {
        var fields = List.<Map<String, Object>>of(formulaField("加工费", Map.of(
            "rules", List.of(Map.of("when", emptyWhen(), "formula", "不存在的公式")),
            "default", "f_base")));
        var formulas = List.<Map<String, Object>>of(Map.of("name", "f_base", "expression", List.of()));
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.validateFormulas(fields, formulas));
        assertTrue(ex.getMessage().contains("不存在"), ex.getMessage());
    }

    @Test
    void missingDefaultFormula_throws() {
        var fields = List.<Map<String, Object>>of(formulaField("加工费", Map.of(
            "rules", List.of(Map.of("when", emptyWhen(), "formula", "f_base")),
            "default", "缺失默认")));
        var formulas = List.<Map<String, Object>>of(Map.of("name", "f_base", "expression", List.of()));
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.validateFormulas(fields, formulas));
        assertTrue(ex.getMessage().contains("不存在"), ex.getMessage());
    }

    @Test
    void validConditional_passes() {
        var fields = List.<Map<String, Object>>of(formulaField("加工费", Map.of(
            "rules", List.of(Map.of("when", emptyWhen(), "formula", "f_turn")),
            "default", "f_base")));
        var formulas = List.<Map<String, Object>>of(
            Map.of("name", "f_turn", "expression", List.of()),
            Map.of("name", "f_base", "expression", List.of()));
        assertDoesNotThrow(() -> svc.validateFormulas(fields, formulas));
    }

    @Test
    void formulaCycle_throws() {
        var fields = List.<Map<String, Object>>of(
            Map.of("name", "A", "field_type", "FORMULA"),
            Map.of("name", "B", "field_type", "FORMULA"));
        var formulas = List.<Map<String, Object>>of(
            Map.of("name", "A", "expression", List.of(Map.of("type", "field", "value", "B"))),
            Map.of("name", "B", "expression", List.of(Map.of("type", "field", "value", "A"))));
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.validateFormulas(fields, formulas));
        assertTrue(ex.getMessage().contains("循环引用"), ex.getMessage());
    }

    @Test
    void conditionCycle_throws() {
        // A 的条件引用 B(列)，B 的公式引用 A → 并集依赖成环。
        var condA = Map.of(
            "rules", List.of(Map.of("when",
                Map.of("kind", "leaf", "left", "B", "op", "gt", "rhs", Map.of("type", "literal", "value", "1")),
                "formula", "f0")),
            "default", "f0");
        var fields = List.<Map<String, Object>>of(
            Map.of("name", "A", "field_type", "FORMULA", "conditional_formula", condA),
            Map.of("name", "B", "field_type", "FORMULA"));
        var formulas = List.<Map<String, Object>>of(
            Map.of("name", "f0", "expression", List.of()),
            Map.of("name", "B", "expression", List.of(Map.of("type", "field", "value", "A"))));
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.validateFormulas(fields, formulas));
        assertTrue(ex.getMessage().contains("循环引用"), ex.getMessage());
    }
}
