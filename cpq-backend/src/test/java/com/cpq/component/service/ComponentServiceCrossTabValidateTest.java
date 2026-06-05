package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯 JUnit 单测（无 @QuarkusTest / DB）：验证 ComponentService.validateFormulas
 * 对 cross_tab_ref token 的结构校验。
 *
 * <p>validateFormulas 是 package-private，可直接在同包调用（已从 private 改为 package-private
 * 以支持轻量单测，避免 @QuarkusTest 对每个边界用例都需要 DB + 事务）。
 */
@DisplayName("ComponentService — cross_tab_ref token 结构校验")
class ComponentServiceCrossTabValidateTest {

    private ComponentService svc;

    /** 最小合法的 cross_tab_ref token（source/match/agg/target 均合法）。 */
    private static Map<String, Object> validToken() {
        Map<String, Object> token = new HashMap<>();
        token.put("type", "cross_tab_ref");
        token.put("source", "comp_material");
        token.put("agg", "SUM");
        token.put("target", "unit_price");
        token.put("match", List.of(Map.of("a", "material_no", "b", "part_no")));
        return token;
    }

    /** 把一个 token 包在公式 expression 里，再封进 formulas 列表。 */
    private static List<Map<String, Object>> formulasWith(Map<String, Object> token) {
        Map<String, Object> formula = new HashMap<>();
        formula.put("name", "测试公式");
        formula.put("expression", List.of(token));
        return List.of(formula);
    }

    /** 空 fields 列表（cross_tab_ref 校验不依赖 fields）。 */
    private static List<Map<String, Object>> emptyFields() {
        return new ArrayList<>();
    }

    @BeforeEach
    void setUp() {
        // ComponentService 没有无参数依赖注入字段参与校验路径，直接 new 即可。
        // TemplateService / EntityManager 等仅在 create/update 的 DB 路径使用，
        // validateFormulas 本身不调用任何注入字段。
        svc = new ComponentService();
    }

    // ------------------------------------------------------------------
    // T1: 合法 token → 不抛
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T1: 合法 cross_tab_ref token → 通过不抛")
    void valid_token_passes() {
        assertDoesNotThrow(() ->
                svc.validateFormulas(emptyFields(), formulasWith(validToken())));
    }

    // ------------------------------------------------------------------
    // T2: source 缺失 → 抛 BusinessException 含"source"
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T2: source 为 null → 抛 BusinessException 含'source'")
    void missing_source_throws() {
        Map<String, Object> token = validToken();
        token.remove("source");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
        assertTrue(ex.getMessage().contains("source"),
                "消息应含 'source'，实际: " + ex.getMessage());
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("T2b: source 为空白字符串 → 抛 BusinessException 含'source'")
    void blank_source_throws() {
        Map<String, Object> token = validToken();
        token.put("source", "   ");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
        assertTrue(ex.getMessage().contains("source"),
                "消息应含 'source'，实际: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // T3: match 缺失 / 空列表 → 抛 BusinessException 含"match"
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T3a: match 为 null → 抛 BusinessException 含'match'")
    void missing_match_throws() {
        Map<String, Object> token = validToken();
        token.remove("match");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
        assertTrue(ex.getMessage().contains("match"),
                "消息应含 'match'，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("T3b: match 为空列表 → 抛 BusinessException 含'match'")
    void empty_match_throws() {
        Map<String, Object> token = validToken();
        token.put("match", new ArrayList<>());
        BusinessException ex = assertThrows(BusinessException.class, () ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
        assertTrue(ex.getMessage().contains("match"),
                "消息应含 'match'，实际: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // T4: agg 非法值 → 抛 BusinessException 含"聚合"
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T4a: agg=INVALID → 抛 BusinessException 含'聚合'")
    void illegal_agg_throws() {
        Map<String, Object> token = validToken();
        token.put("agg", "INVALID");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
        assertTrue(ex.getMessage().contains("聚合"),
                "消息应含'聚合'，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("T4b: agg=null → 抛 BusinessException 含'聚合'")
    void null_agg_throws() {
        Map<String, Object> token = validToken();
        token.remove("agg");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
        assertTrue(ex.getMessage().contains("聚合"),
                "消息应含'聚合'，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("T4c: agg 大小写不敏感 — agg='sum' → 通过")
    void agg_case_insensitive_passes() {
        Map<String, Object> token = validToken();
        token.put("agg", "sum");
        assertDoesNotThrow(() ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
    }

    // ------------------------------------------------------------------
    // T5: agg=NONE 时 target 为空 → 抛 BusinessException 含"target"
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T5: agg=NONE 且 target 为空 → 抛 BusinessException 含'target'")
    void none_agg_blank_target_throws() {
        Map<String, Object> token = validToken();
        token.put("agg", "NONE");
        token.remove("target");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
        assertTrue(ex.getMessage().contains("target"),
                "消息应含 'target'，实际: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // T6: agg=COUNT 时 target 可以为空 → 通过
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T6: agg=COUNT 且 target 为空 → 通过（COUNT 不需要 target）")
    void count_agg_without_target_passes() {
        Map<String, Object> token = validToken();
        token.put("agg", "COUNT");
        token.remove("target");
        assertDoesNotThrow(() ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
    }

    // ------------------------------------------------------------------
    // T7: 非 cross_tab_ref token 不受影响
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T7: component_subtotal token 不触发 cross_tab_ref 校验")
    void non_cross_tab_token_not_affected() {
        Map<String, Object> token = new HashMap<>();
        token.put("type", "component_subtotal");
        // 故意不设 source/match/agg/target
        assertDoesNotThrow(() ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
    }

    // ------------------------------------------------------------------
    // T8: formulas 列表为空 → 不抛
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T8: 空公式列表 → 通过不抛")
    void empty_formulas_passes() {
        assertDoesNotThrow(() ->
                svc.validateFormulas(emptyFields(), new ArrayList<>()));
    }

    // ------------------------------------------------------------------
    // T9: 合法 agg 的全部枚举值 → 均通过
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T9: 所有合法 agg 枚举值均通过")
    void all_valid_agg_values_pass() {
        for (String agg : List.of("NONE", "SUM", "AVG", "COUNT", "MAX", "MIN")) {
            Map<String, Object> token = validToken();
            token.put("agg", agg);
            if ("COUNT".equals(agg)) token.remove("target"); // COUNT 不需要 target
            assertDoesNotThrow(() ->
                    svc.validateFormulas(emptyFields(), formulasWith(token)),
                    "agg=" + agg + " 应通过");
        }
    }
}
