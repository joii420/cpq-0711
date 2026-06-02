package com.cpq.quotation.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.card.CardDataProvider;
import com.cpq.template.service.CardAggregateSource;
import com.cpq.template.service.TemplateFormulaService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CardAggregateTest {
    @Inject TemplateFormulaService svc;

    private QuotationLineComponentData tab(String comp, int sort, String json, String sub) {
        var d = new QuotationLineComponentData();
        d.componentId = UUID.fromString(comp); d.sortOrder = sort; d.rowData = json; d.subtotal = new BigDecimal(sub);
        return d;
    }
    @AfterEach void tearDown() { CardAggregateSource.clear(); }

    @Test void sum_over_card_with_condition_and_inline_expr() {
        var comp = "22222222-2222-2222-2222-222222222222";
        var tabs = List.of(tab(comp, 0,
            "[{\"工序\":\"电镀\",\"单价\":3,\"数量\":2},{\"工序\":\"酸洗\",\"单价\":5,\"数量\":1},{\"工序\":\"电镀\",\"单价\":4,\"数量\":1}]",
            "0"));
        CardAggregateSource.set(new CardAggregateSource.Ctx(
            new CardDataProvider(tabs),
            Map.of("加工", new CardAggregateSource.Binding(comp + ":0",
                Map.of("c0", "工序", "c1", "单价", "c2", "数量")))));
        // 电镀行: 3*2 + 4*1 = 10
        Object r = svc.evaluateExpressionPublic(
            "SUM_OVER([加工] WHERE c0=='电镀', c1*c2)",
            Map.of(), new HashMap<>(), null, "P1", List.of());
        assertEquals(0, new BigDecimal("10").compareTo(new BigDecimal(r.toString())));
    }
}
