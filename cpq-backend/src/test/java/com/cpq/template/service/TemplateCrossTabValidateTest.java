package com.cpq.template.service;

import com.cpq.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 2.2: 模板级 cross_tab_ref 校验单元测试（纯输入构造，不打 DB）。
 *
 * <p>校验由 {@link TemplateService#validateCrossTabRefs(List, Map)} 承载，且在
 * {@code publish()} 真实路径上被调用（见 TemplateService.publish）。本测试直接驱动该
 * package-private 方法，覆盖三种情形：
 * <ol>
 *   <li>源组件不在本卡片 → BusinessException</li>
 *   <li>A↔B 互相引用形成环 → BusinessException</li>
 *   <li>B 引 A（A 存在）且无环 → 通过</li>
 * </ol>
 */
class TemplateCrossTabValidateTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final TemplateService svc = new TemplateService();

    /** 构造一个组件的 formulas JsonNode，含一个引用 source 的 cross_tab_ref token。 */
    private JsonNode formulasReferencing(String... sources) {
        try {
            StringBuilder tokens = new StringBuilder();
            for (int i = 0; i < sources.length; i++) {
                if (i > 0) tokens.append(',');
                tokens.append("{\"type\":\"cross_tab_ref\",\"source\":\"").append(sources[i])
                      .append("\",\"agg\":\"SUM\",\"match\":[\"k\"]}");
            }
            String json = "[{\"name\":\"f\",\"expression\":[" + tokens + "]}]";
            return M.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode noFormulas() {
        try {
            return M.readTree("[]");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void sourceNotInCard_throws() {
        String a = "11111111-1111-1111-1111-111111111111";
        String b = "22222222-2222-2222-2222-222222222222";
        String missing = "99999999-9999-9999-9999-999999999999";
        List<String> compIds = List.of(a, b);
        Map<String, JsonNode> formulas = new LinkedHashMap<>();
        formulas.put(a, noFormulas());
        formulas.put(b, formulasReferencing(missing)); // B 引一个不在卡片的源

        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.validateCrossTabRefs(compIds, formulas));
        assertTrue(ex.getMessage().contains("不在本卡片"), "应提示源组件不在本卡片: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(missing), "应包含缺失的源组件 id");
    }

    @Test
    void cycle_throws() {
        String a = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String b = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        List<String> compIds = List.of(a, b);
        Map<String, JsonNode> formulas = new LinkedHashMap<>();
        formulas.put(a, formulasReferencing(b)); // A 依赖 B
        formulas.put(b, formulasReferencing(a)); // B 依赖 A → 环

        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.validateCrossTabRefs(compIds, formulas));
        assertTrue(ex.getMessage().contains("循环引用"), "应提示循环引用: " + ex.getMessage());
    }

    @Test
    void happyPath_referencesExistingNoCycle_passes() {
        String a = "aaaaaaaa-0000-0000-0000-000000000001";
        String b = "bbbbbbbb-0000-0000-0000-000000000002";
        List<String> compIds = List.of(a, b);
        Map<String, JsonNode> formulas = new LinkedHashMap<>();
        formulas.put(a, noFormulas());
        formulas.put(b, formulasReferencing(a)); // B 引 A，A 存在，无环

        assertDoesNotThrow(() -> svc.validateCrossTabRefs(compIds, formulas));
    }

    @Test
    void noCrossTabRefs_passes() {
        String a = "cccccccc-0000-0000-0000-000000000001";
        List<String> compIds = List.of(a);
        Map<String, JsonNode> formulas = new LinkedHashMap<>();
        formulas.put(a, noFormulas());
        assertDoesNotThrow(() -> svc.validateCrossTabRefs(compIds, formulas));
    }
}
