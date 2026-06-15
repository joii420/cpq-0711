package com.cpq.quotation;

import com.cpq.quotation.service.CardSnapshotService;
import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 7 TDD — backfillSubtotalsFromResolved 求和单位换算（物化点5）。
 *
 * <p>场景：重量列配了 unit_source_field="单位"，原值 500g/1000g。
 * 换算后应求 0.5kg+1.0kg = 1.5，而非 500+1000 = 1500。
 * resolvedRows 本身（落库）不得被修改（不变性断言）。
 */
class SubtotalUnitConversionTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** fields JSON：重量(is_subtotal=true, unit_source_field="单位") + 单位(INPUT_TEXT) */
    private static final String FIELDS_JSON =
        "[" +
        "  {\"name\":\"重量\",\"field_type\":\"INPUT_NUMBER\",\"is_subtotal\":true,\"unit_source_field\":\"单位\"}," +
        "  {\"name\":\"单位\",\"field_type\":\"INPUT_TEXT\"}" +
        "]";

    @Test
    void subtotalUsesCanonicalUnit_notRawValue() throws Exception {
        JsonNode fields = M.readTree(FIELDS_JSON);

        // resolvedRows：两行，原值 500g / 1000g
        List<Map<String, Object>> resolvedRows = new ArrayList<>();
        Map<String, Object> row0 = new LinkedHashMap<>();
        row0.put("重量", 500);
        row0.put("单位", "g");
        resolvedRows.add(row0);

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("重量", 1000);
        row1.put("单位", "g");
        resolvedRows.add(row1);

        // 构造 CardSnapshotService（plain new + 反射注入 formulaCalculator）
        CardSnapshotService svc = new CardSnapshotService();
        var fcField = CardSnapshotService.class.getDeclaredField("formulaCalculator");
        fcField.setAccessible(true);
        fcField.set(svc, new FormulaCalculator());

        // 反射调用 private backfillSubtotalsFromResolved
        Method m = CardSnapshotService.class.getDeclaredMethod(
            "backfillSubtotalsFromResolved",
            JsonNode.class, List.class, String.class, String.class, String.class, Map.class);
        m.setAccessible(true);

        Map<String, Double> componentSubtotals = new HashMap<>();
        m.invoke(svc, fields, resolvedRows, "c1", "code1", "tab1", componentSubtotals);

        // 期望：换算后 0.5 + 1.0 = 1.5（单位 g → KG 系数 0.001）
        Double tabColKey = componentSubtotals.get("tab1#重量");
        assertNotNull(tabColKey, "componentSubtotals 应含 tab1#重量 键");
        assertEquals(1.5, tabColKey, 1e-6,
            "求和应用换算后值（0.5+1.0=1.5kg），而非原值（500+1000=1500），实=" + tabColKey);

        // 不变性：resolvedRows 本身不得被修改（落库存原值）
        Object originalWeight = resolvedRows.get(0).get("重量");
        assertTrue(originalWeight instanceof Number,
            "resolvedRows[0].重量 应仍为 Number 类型，实=" + originalWeight.getClass());
        assertEquals(500, ((Number) originalWeight).intValue(),
            "resolvedRows[0].重量 应仍为 500（原值），落库行不得被修改，实=" + originalWeight);
    }
}
