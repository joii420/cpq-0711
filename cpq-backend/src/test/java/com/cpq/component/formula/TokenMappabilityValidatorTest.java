package com.cpq.component.formula;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TokenMappabilityValidatorTest {

    private final TokenMappabilityValidator v = new TokenMappabilityValidator();

    // 同行字段 + 单源跨页签 → 可映射
    @Test
    void sameRowFieldsPlusSingleSourceCrossTab_isMappable() {
        List<Map<String,Object>> expr = List.of(
            Map.of("type","field","value","单重"),
            Map.of("type","operator","value","*"),
            Map.of("type","field","value","单价"),
            Map.of("type","operator","value","-"),
            Map.of("type","cross_tab_ref","source","回料","agg","SUM",
                   "match", List.of(Map.of("a","料号","b","料号")))
        );
        var r = v.validate(expr);
        assertTrue(r.mappable(), r.reason());
    }

    // 新规则(v4-C 命门1)：两个 agg=NONE 但 match 非空 → 可映射(有公共行键可对齐)
    // 旧规则拒绝"≥2 NONE"已废弃；新规则只拒"match 为空"。
    // 原用例名 twoForeignDetailsMultiplied_isNotMappable，已改为 assertTrue。
    @Test
    void twoForeignDetails_nonEmptyMatch_isMappable() {
        List<Map<String,Object>> expr = List.of(
            Map.of("type","cross_tab_ref","source","投料","agg","NONE",
                   "match", List.of(Map.of("a","料号","b","料号"))),
            Map.of("type","operator","value","*"),
            Map.of("type","cross_tab_ref","source","回料","agg","NONE",
                   "match", List.of(Map.of("a","料号","b","料号")))
        );
        var r = v.validate(expr);
        assertTrue(r.mappable());
    }

    // cross_tab_ref 空 match(NONE) → 拒绝
    @Test
    void rejects_crossTabRef_with_empty_match() {
        var token = new java.util.HashMap<String, Object>();
        token.put("type", "cross_tab_ref");
        token.put("agg", "NONE");
        token.put("match", java.util.List.of());
        var r = v.validate(java.util.List.of(token));
        assertFalse(r.mappable(), "空 match 必拒");
    }

    // cross_tab_ref 空 match(SUM) → 同样拒绝
    @Test
    void rejects_crossTabRef_SUM_empty_match() {
        var token = new java.util.HashMap<String, Object>();
        token.put("type", "cross_tab_ref");
        token.put("agg", "SUM");
        token.put("match", java.util.List.of());
        assertFalse(v.validate(java.util.List.of(token)).mappable());
    }

    // 两个 NONE 非空 match → 可映射（与上面用例一致，单独验证）
    @Test
    void allows_two_NONE_with_nonempty_match() {
        var a = new java.util.HashMap<String, Object>();
        a.put("type", "cross_tab_ref"); a.put("agg", "NONE");
        a.put("match", java.util.List.of(java.util.Map.of("a", "k", "b", "k")));
        var b = new java.util.HashMap<>(a);
        assertTrue(v.validate(java.util.List.of(a, b)).mappable());
    }

    // RowKeyCompare 工具类验证
    @Test
    void rowKeyCompare_subset_and_comparable() {
        assertTrue(com.cpq.component.formula.RowKeyCompare.isSubset(java.util.List.of("子件"), java.util.List.of("子件", "工序")));
        assertTrue(com.cpq.component.formula.RowKeyCompare.comparable(java.util.List.of("子件", "工序"), java.util.List.of("子件")));
        assertTrue(com.cpq.component.formula.RowKeyCompare.comparable(java.util.List.of("A", "B"), java.util.List.of("B", "A")));
        assertFalse(com.cpq.component.formula.RowKeyCompare.comparable(java.util.List.of("料号"), java.util.List.of("工序")));
    }
}
