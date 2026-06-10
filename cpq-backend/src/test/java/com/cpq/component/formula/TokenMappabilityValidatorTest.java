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

    // 两个不同源 cross_tab_ref 顶层相乘(互相对齐,非锚定本组件) → 不可映射
    @Test
    void twoForeignDetailsMultiplied_isNotMappable() {
        List<Map<String,Object>> expr = List.of(
            Map.of("type","cross_tab_ref","source","投料","agg","NONE",
                   "match", List.of(Map.of("a","料号","b","料号"))),
            Map.of("type","operator","value","*"),
            Map.of("type","cross_tab_ref","source","回料","agg","NONE",
                   "match", List.of(Map.of("a","料号","b","料号")))
        );
        var r = v.validate(expr);
        assertFalse(r.mappable());
        assertNotNull(r.reason());
    }
}
