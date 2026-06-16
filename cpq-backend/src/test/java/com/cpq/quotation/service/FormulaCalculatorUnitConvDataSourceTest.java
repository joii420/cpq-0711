package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 单位换算时机回归（后端，对应前端 unitConversion.dataSource.test.ts / 用户 COMP-0028）：
 * 组成用量 = INPUT_NUMBER + default_source $ll_view._组成用量（值来自数据源，不在 driverRow），
 * 配 unit_source_field=单位（单位也来自 $ll_view._单位）。同页签公式 材料费 = 组成用量。
 * snake_case 生产快照格式。换算须在 collectFieldValues + fillInputDefaultSourceByFieldName 之后做。
 */
class FormulaCalculatorUnitConvDataSourceTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private JsonNode j(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static final String FIELDS = "["
        + "{\"name\":\"组成用量\",\"field_type\":\"INPUT_NUMBER\",\"default_source\":{\"type\":\"BASIC_DATA\",\"path\":\"$ll_view._组成用量\"},\"unit_source_field\":\"单位\"},"
        + "{\"name\":\"单位\",\"field_type\":\"INPUT_TEXT\",\"default_source\":{\"type\":\"BASIC_DATA\",\"path\":\"$ll_view._单位\"}},"
        + "{\"name\":\"材料费\",\"field_type\":\"FORMULA\"}"
        + "]";
    private static final String FORMULAS =
        "[{\"name\":\"材料费\",\"expression\":[{\"type\":\"field\",\"value\":\"组成用量\"}]}]";
    private static final String RKF = "[\"__seq_no__\"]";

    private String baseRows(String unit) {
        return "[{\"driverRow\":{},\"basicDataValues\":{\"{$ll_view._组成用量}\":500,\"{$ll_view._单位}\":\"" + unit + "\"}}]";
    }

    @Test
    void dataSourceColumn_unitG_convertedToKg() {
        JsonNode r = calc.calculate(j(FIELDS), j(FORMULAS), null, j(RKF),
            j(baseRows("G")), j("[]"), Map.of(), Map.of(), Map.of());
        assertEquals(0.5, r.get(0).path("values").path("材料费").asDouble(), 1e-9,
            "组成用量 500g → 0.5KG → 材料费=0.5（而非原值 500）");
    }

    @Test
    void dataSourceColumn_unitKG_unchanged() {
        JsonNode r = calc.calculate(j(FIELDS), j(FORMULAS), null, j(RKF),
            j(baseRows("KG")), j("[]"), Map.of(), Map.of(), Map.of());
        assertEquals(500.0, r.get(0).path("values").path("材料费").asDouble(), 1e-9,
            "单位=KG 系数×1 → 材料费=500");
    }
}
