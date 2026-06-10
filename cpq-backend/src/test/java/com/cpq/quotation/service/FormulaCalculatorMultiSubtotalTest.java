package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan 2-核心：多小计列按列求和。纯 JUnit（new FormulaCalculator()，无 Quarkus）。
 * token 数组表达式 + BASIC_DATA 取 basicDataValues 口径与 FormulaCalculatorTest 一致。
 */
class FormulaCalculatorMultiSubtotalTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private JsonNode j(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // 两个小计列：材料费 = 单价*数量；加工费 = 工时*费率。输入走 BASIC_DATA(每行各自 basicDataValues)。
    private static final String FIELDS = "["
        + "{\"name\":\"单价\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.up\"},"
        + "{\"name\":\"数量\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.qty\"},"
        + "{\"name\":\"工时\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.hr\"},"
        + "{\"name\":\"费率\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.rate\"},"
        + "{\"name\":\"材料费\",\"fieldType\":\"FORMULA\",\"isSubtotal\":true},"
        + "{\"name\":\"加工费\",\"fieldType\":\"FORMULA\",\"isSubtotal\":true}"
        + "]";
    private static final String FORMULAS = "["
        + "{\"name\":\"材料费\",\"expression\":[{\"type\":\"field\",\"value\":\"单价\"},"
        + "{\"type\":\"operator\",\"value\":\"*\"},{\"type\":\"field\",\"value\":\"数量\"}]},"
        + "{\"name\":\"加工费\",\"expression\":[{\"type\":\"field\",\"value\":\"工时\"},"
        + "{\"type\":\"operator\",\"value\":\"*\"},{\"type\":\"field\",\"value\":\"费率\"}]}"
        + "]";
    private static final String RKF = "[\"material_no\"]";
    private static final String BASEROWS = "["
        + "{\"driverRow\":{\"material_no\":\"M1\"},\"basicDataValues\":{\"{v.up}\":10,\"{v.qty}\":2,\"{v.hr}\":3,\"{v.rate}\":5}},"
        + "{\"driverRow\":{\"material_no\":\"M2\"},\"basicDataValues\":{\"{v.up}\":4,\"{v.qty}\":5,\"{v.hr}\":1,\"{v.rate}\":7}}"
        + "]";

    @Test
    void findSubtotalFieldNames_returnsAll() {
        List<String> names = calc.findSubtotalFieldNames(j(FIELDS));
        assertEquals(List.of("材料费", "加工费"), names);
    }

    @Test
    void computeTabSubtotalsByColumn_perColumnSums() {
        Map<String, BigDecimal> byCol = calc.computeTabSubtotalsByColumn(
            j(FIELDS), j(FORMULAS), null, j(RKF), j(BASEROWS), j("[]"), Map.of());
        // 材料费 = 10*2 + 4*5 = 40 ; 加工费 = 3*5 + 1*7 = 22
        assertEquals(0, byCol.get("材料费").compareTo(new BigDecimal("40")), "材料费=" + byCol.get("材料费"));
        assertEquals(0, byCol.get("加工费").compareTo(new BigDecimal("22")), "加工费=" + byCol.get("加工费"));
    }

    @Test
    void computeTabSubtotal_sumsAllSubtotalColumns() {
        BigDecimal sum = calc.computeTabSubtotal(
            j(FIELDS), j(FORMULAS), null, j(RKF), j(BASEROWS), j("[]"), Map.of());
        assertEquals(0, sum.compareTo(new BigDecimal("62")), "62 期望, 实=" + sum); // 40 + 22
    }

    @Test
    void singleSubtotal_backwardCompatible() {
        String oneSub = "["
            + "{\"name\":\"单价\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.up\"},"
            + "{\"name\":\"数量\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.qty\"},"
            + "{\"name\":\"材料费\",\"fieldType\":\"FORMULA\",\"isSubtotal\":true}"
            + "]";
        String f = "[{\"name\":\"材料费\",\"expression\":[{\"type\":\"field\",\"value\":\"单价\"},"
            + "{\"type\":\"operator\",\"value\":\"*\"},{\"type\":\"field\",\"value\":\"数量\"}]}]";
        BigDecimal sum = calc.computeTabSubtotal(j(oneSub), j(f), null, j(RKF), j(BASEROWS), j("[]"), Map.of());
        assertEquals(0, sum.compareTo(new BigDecimal("40")), "单小计列=原行为, 实=" + sum); // 10*2 + 4*5
    }
}
