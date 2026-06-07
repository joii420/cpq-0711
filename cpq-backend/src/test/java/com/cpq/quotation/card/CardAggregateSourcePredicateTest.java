package com.cpq.quotation.card;

import com.cpq.template.service.CardAggregateSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CardAggregateSourcePredicateTest {
    @AfterEach void cleanup() { CardAggregateSource.clear(); }

    @Test void predicateFor_returns_binding_dynamic_predicate() {
        var binding = new CardAggregateSource.Binding("comp:0", Map.of("c0", "关联号"), "c0=='P9'");
        CardAggregateSource.set(new CardAggregateSource.Ctx(null,
                Map.of("加工#1", binding)));
        assertEquals("c0=='P9'", CardAggregateSource.predicateFor("加工#1"));
        assertEquals("c0=='P9'", CardAggregateSource.predicateFor(" 加工#1 ")); // trim
    }

    @Test void predicateFor_null_when_no_binding_or_no_predicate() {
        var staticBinding = new CardAggregateSource.Binding("comp:0", Map.of("c0", "工序")); // 2 参 → null 谓词
        CardAggregateSource.set(new CardAggregateSource.Ctx(null, Map.of("加工", staticBinding)));
        assertNull(CardAggregateSource.predicateFor("加工"));      // 有 binding 无动态谓词
        assertNull(CardAggregateSource.predicateFor("不存在"));     // 无 binding
        CardAggregateSource.clear();
        assertNull(CardAggregateSource.predicateFor("加工"));      // 无 Ctx
    }
}
