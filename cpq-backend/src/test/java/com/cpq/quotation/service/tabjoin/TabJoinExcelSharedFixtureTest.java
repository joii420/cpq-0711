package com.cpq.quotation.service.tabjoin;

import com.cpq.quotation.service.card.CardDataProvider;
import com.cpq.quotation.service.card.CardEffectiveRows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260916 (B-5 / AC-14 a~e): shared parity fixture for Excel TAB_JOIN_FORMULA columns.
 *
 * <p>Consumes {@code tabjoin-excel-cases.json}; the frontend copy
 * ({@code cpq-frontend/src/pages/quotation/__fixtures__/tabjoin-excel-cases.json}) must be byte-identical.
 * Every case is evaluated through the production entry {@link TabJoinPlanEvaluator#evaluateColumn}
 * and compared with {@code compareTo} (no tolerance).
 */
class TabJoinExcelSharedFixtureTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();

    private static JsonNode fixture() throws Exception {
        try (InputStream is = TabJoinExcelSharedFixtureTest.class.getClassLoader()
                .getResourceAsStream("tabjoin-excel-cases.json")) {
            assertNotNull(is, "tabjoin-excel-cases.json not found in test resources");
            return OM.readTree(is);
        }
    }

    @SuppressWarnings("unchecked")
    private static CardDataProvider provider(JsonNode fx) {
        Map<String, CardEffectiveRows.TabRows> eff = new LinkedHashMap<>();
        for (JsonNode t : fx.get("tabs")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode r : t.get("rows")) rows.add(OM.convertValue(r, Map.class));
            Map<String, BigDecimal> byCol = new LinkedHashMap<>();
            t.get("subtotalByColumn").fields()
                .forEachRemaining(e -> byCol.put(e.getKey(), new BigDecimal(e.getValue().asText())));
            eff.put(t.get("tabKey").asText(), new CardEffectiveRows.TabRows(
                rows, new BigDecimal(t.get("tabTotal").asText()), byCol));
        }
        return CardDataProvider.fromEffectiveRows(eff);
    }

    private static Map<String, Object> column(JsonNode fx, String expression) {
        List<Map<String, Object>> tabs = new ArrayList<>();
        for (JsonNode t : fx.get("tabs")) {
            List<String> rkf = new ArrayList<>();
            t.get("rowKeyFields").forEach(n -> rkf.add(n.asText()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("alias", t.get("alias").asText());
            m.put("tabKey", t.get("tabKey").asText());
            m.put("rowKeyFields", rkf);
            tabs.add(m);
        }
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("col_key", "col_fx");
        col.put("source_type", "TAB_JOIN_FORMULA");
        col.put("expression", expression);
        col.put("tabs", tabs);
        return col;
    }

    @Test
    void fixtureIsNonEmptyAndCoversAc14aToE() throws Exception {
        JsonNode fx = fixture();
        assertTrue(fx.get("tabs").size() >= 1);
        JsonNode first = fx.get("tabs").get(0);
        assertEquals(6, first.get("rows").size(), "物料 must carry the 6 real rows of QT-20260916-0881");
        Set<String> ids = new HashSet<>();
        fx.get("cases").forEach(c -> ids.add(c.get("id").asText()));
        for (String id : List.of("AC-14a", "AC-14b", "AC-14c", "AC-14d", "AC-14e")) {
            assertTrue(ids.contains(id), "missing case " + id);
        }
        // AC-14 前置: 物料「材料成本」6 行真实值
        List<String> mc = new ArrayList<>();
        first.get("rows").forEach(r -> mc.add(r.get("材料成本").asText()));
        assertEquals(List.of("0", "0.059191597", "0.463735546", "1.437983994", "0.015491845", "0.002538082"), mc);
    }

    @TestFactory
    Collection<DynamicTest> sharedCases() throws Exception {
        JsonNode fx = fixture();
        List<DynamicTest> out = new ArrayList<>();
        for (JsonNode c : fx.get("cases")) {
            String id = c.get("id").asText();
            String expr = c.get("expression").asText();
            BigDecimal expected = new BigDecimal(c.get("expected").asText());
            out.add(DynamicTest.dynamicTest(id + " " + expr, () -> {
                BigDecimal got = ev.evaluateColumn(column(fx, expr), provider(fx));
                assertNotNull(got);
                System.out.println("[tabjoin-fixture] " + id + " " + expr + " => " + got.toPlainString());
                assertEquals(0, expected.compareTo(got),
                    "case " + id + " [" + expr + "] expected " + expected + " got " + got);
            }));
        }
        return out;
    }
}
