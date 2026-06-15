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
    // T5: agg=NONE 时 target 为空（且无 targetExpr）→ 抛 BusinessException 含"目标列"
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T5: agg=NONE 且 target 为空且无 targetExpr → 抛 BusinessException 含'目标列'")
    void none_agg_blank_target_throws() {
        Map<String, Object> token = validToken();
        token.put("agg", "NONE");
        token.remove("target");
        // 无 targetExpr，应抛错
        BusinessException ex = assertThrows(BusinessException.class, () ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
        assertTrue(ex.getMessage().contains("目标列"),
                "消息应含 '目标列'，实际: " + ex.getMessage());
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

    // ------------------------------------------------------------------
    // T10: agg=NONE，无 target，但 targetExpr 非空 → 不抛（targetExpr 可替代 target）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T10: agg=NONE，无 target，有非空 targetExpr → 通过不抛")
    void none_agg_no_target_but_targetExpr_passes() {
        Map<String, Object> token = new HashMap<>();
        token.put("type", "cross_tab_ref");
        token.put("source", "comp_material");
        token.put("agg", "NONE");
        token.put("match", List.of(Map.of("a", "material_no", "b", "part_no")));
        // 无 target；用 targetExpr 替代
        token.put("targetExpr", List.of(Map.of("type", "field", "value", "单价")));
        assertDoesNotThrow(() ->
                svc.validateFormulas(emptyFields(), formulasWith(token)),
                "targetExpr 非空时即使 target 缺失也应通过");
    }

    // ------------------------------------------------------------------
    // T11: agg=SUM，无 target，无 targetExpr → 抛 BusinessException（两者均缺）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T11: agg=SUM，无 target，无 targetExpr → 抛 BusinessException")
    void sum_agg_no_target_no_targetExpr_throws() {
        Map<String, Object> token = new HashMap<>();
        token.put("type", "cross_tab_ref");
        token.put("source", "comp_material");
        token.put("agg", "SUM");
        token.put("match", List.of(Map.of("a", "material_no", "b", "part_no")));
        // 既无 target 也无 targetExpr
        BusinessException ex = assertThrows(BusinessException.class, () ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
        assertEquals(400, ex.getCode());
    }

    // ------------------------------------------------------------------
    // T12 (TDD-red): SUMIF 族 — match=[] 但携带合法 predicate → 应通过不抛
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T12: SUMIF 族 match=[] + predicate 存在 → 通过不抛")
    void sumif_token_empty_match_with_predicate_passes() {
        Map<String, Object> token = new HashMap<>();
        token.put("type", "cross_tab_ref");
        token.put("source", "comp_fee");
        token.put("agg", "SUM");
        token.put("target", "金额");
        // targetExpr 非空（满足目标列校验）
        token.put("targetExpr", List.of(Map.of("type", "field", "value", "金额")));
        // match 故意留空（SUMIF 族靠 predicate 过滤，不需要 match）
        token.put("match", new ArrayList<>());
        // 合法 predicate：类型 = '管理费'（ConditionPredicateJson 格式：op 用符号, lhs/rhs 带 kind）
        Map<String, Object> predicate = new HashMap<>();
        predicate.put("op", "=");
        predicate.put("lhs", Map.of("kind", "sourceField", "field", "类型"));
        predicate.put("rhs", Map.of("kind", "literal", "value", "管理费"));
        token.put("predicate", predicate);

        assertDoesNotThrow(() ->
                svc.validateFormulas(emptyFields(), formulasWith(token)),
                "SUMIF 族 match=[] 且带 predicate 时不应被拒绝");
    }

    // ------------------------------------------------------------------
    // T13 (TDD-red 对照): match=[] 且无 predicate → 仍拒（原有语义不变）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T13: match=[] 且无 predicate → 仍抛 BusinessException 含'match'")
    void cross_tab_ref_empty_match_without_predicate_still_rejected() {
        Map<String, Object> token = new HashMap<>();
        token.put("type", "cross_tab_ref");
        token.put("source", "comp_fee");
        token.put("agg", "SUM");
        token.put("target", "金额");
        // match 空，且没有 predicate
        token.put("match", new ArrayList<>());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                svc.validateFormulas(emptyFields(), formulasWith(token)),
                "无 predicate 时 match=[] 仍应被拒绝");
        assertTrue(ex.getMessage().contains("match"),
                "消息应含 'match'，实际: " + ex.getMessage());
        assertEquals(400, ex.getCode());
    }
}
