package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 行键唯一性消歧（撞键 → #序号）回归。
 *
 * <p>线上 bug：外购件 row_key_fields=[料件,要素]，两行 driver (料件=空,要素=单价) → 行键都 ||单价 →
 * editRows 写覆盖只活 1 条 → resolvedRows「末值×行数」塌缩 → 来料 cross_tab 逐行匹配错。
 */
class FormulaCalculatorRowKeyUniqueTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();
    private JsonNode j(String s) { try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }

    @Test
    void uniquifyRowKeys_uniqueUnchanged_collisionSuffixed() {
        assertEquals(List.of("a", "b"), FormulaCalculator.uniquifyRowKeys(List.of("a", "b")));
        assertEquals(List.of("||单价#0", "||单价#1"),
                FormulaCalculator.uniquifyRowKeys(List.of("||单价", "||单价")));
        assertEquals(List.of("x#0", "k", "x#1"),
                FormulaCalculator.uniquifyRowKeys(List.of("x", "k", "x")));
        assertEquals(List.of(), FormulaCalculator.uniquifyRowKeys(List.of()));
    }

    @Test
    void computeRows_collidingDriverKeys_editRowsBindPerRow() {
        // 外购件: row_key_fields=[料件,要素], 两行 driver (空料件+单价); 数量 由 editRows 逐行赋值
        JsonNode fields = j("["
            + "{\"name\":\"料件\",\"fieldType\":\"INPUT_TEXT\","
            + " \"defaultSource\":{\"type\":\"BASIC_DATA\",\"path\":\"$wgj_view._料件\"}},"
            + "{\"name\":\"要素\",\"fieldType\":\"INPUT_TEXT\","
            + " \"defaultSource\":{\"type\":\"BASIC_DATA\",\"path\":\"$wgj_view._要素\"}},"
            + "{\"name\":\"数量\",\"fieldType\":\"INPUT_NUMBER\"},"
            + "{\"name\":\"金额\",\"fieldType\":\"FORMULA\"}"
            + "]");
        JsonNode formulas = j("[{\"name\":\"金额\",\"expression\":[{\"type\":\"field\",\"value\":\"数量\"}]}]");
        JsonNode rowKeyFields = j("[\"料件\",\"要素\"]");
        JsonNode baseRows = j("["
            + "{\"driverRow\":{\"_料件\":null,\"_要素\":\"单价\"},"
            + " \"basicDataValues\":{\"{$wgj_view._料件}\":null,\"{$wgj_view._要素}\":\"单价\"}},"
            + "{\"driverRow\":{\"_料件\":null,\"_要素\":\"单价\"},"
            + " \"basicDataValues\":{\"{$wgj_view._料件}\":null,\"{$wgj_view._要素}\":\"单价\"}}"
            + "]");
        // 两条 editRow 分别按唯一化键绑定到两行
        JsonNode editRows = j("["
            + "{\"rowKey\":\"||单价#0\",\"values\":{\"数量\":10}},"
            + "{\"rowKey\":\"||单价#1\",\"values\":{\"数量\":20}}"
            + "]");

        JsonNode out = calc.calculate(fields, formulas, null, rowKeyFields,
                baseRows, editRows, Map.of(), Map.of(), Map.of());

        assertEquals(2, out.size(), "应有 2 行");
        // 两行 rowKey 唯一（修复前两行都 ||单价）
        assertEquals("||单价#0", out.get(0).path("rowKey").asText());
        assertEquals("||单价#1", out.get(1).path("rowKey").asText());
        // editRows 各自生效：行0 数量=10→金额10，行1 数量=20→金额20（修复前两行同值=末值×塌缩）
        assertEquals(10.0, out.get(0).path("values").path("金额").asDouble(), 1e-9);
        assertEquals(20.0, out.get(1).path("values").path("金额").asDouble(), 1e-9);
    }
}
