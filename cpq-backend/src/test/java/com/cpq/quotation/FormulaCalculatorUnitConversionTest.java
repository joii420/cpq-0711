package com.cpq.quotation;

import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 物化点3 TDD：FormulaCalculator.computeRows 在 collectFieldValues / currentRowRaw 之前，
 * 对配了 unit_source_field 的列执行 UnitConversion.convertNodeRow 归一。
 *
 * <p>场景：重量=500，单位="g"，单价=2。
 * g→KG 系数=0.001，归一后重量=0.5，金额=重量×单价=0.5×2=1.0。
 * 未换算前：金额=500×2=1000。
 */
class FormulaCalculatorUnitConversionTest {

    private final FormulaCalculator calc = new FormulaCalculator();
    private final ObjectMapper om = new ObjectMapper();

    private JsonNode json(String s) {
        try {
            return om.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 核心场景：重量配了 unit_source_field="单位"，g→KG 系数=0.001。
     * driverRow={重量:500, 单位:"g", 单价:2}，FORMULA 金额=重量×单价。
     * 期望：归一后 重量=0.5，金额=0.5×2=1.0（而非未换算的 1000）。
     */
    @Test
    void computeRows_convertsUnitBeforeFormulaEval() {
        // fields：重量(INPUT_NUMBER, unit_source_field=单位), 单位(BASIC_DATA/text), 单价(INPUT_NUMBER), 金额(FORMULA)
        String fields = "["
            + "{\"name\":\"重量\",\"fieldType\":\"INPUT_NUMBER\",\"unit_source_field\":\"单位\"},"
            + "{\"name\":\"单位\",\"fieldType\":\"INPUT_NUMBER\"},"
            + "{\"name\":\"单价\",\"fieldType\":\"INPUT_NUMBER\"},"
            + "{\"name\":\"金额\",\"fieldType\":\"FORMULA\",\"formulaName\":\"f_amount\"}"
            + "]";

        // formulas：金额 = 重量 × 单价
        String formulas = "["
            + "{\"name\":\"f_amount\",\"expression\":["
            + "  {\"type\":\"field\",\"value\":\"重量\"},"
            + "  {\"type\":\"operator\",\"value\":\"×\"},"
            + "  {\"type\":\"field\",\"value\":\"单价\"}"
            + "]}"
            + "]";

        String rowKeyFields = "[\"重量\"]";

        // baseRows：一行，driverRow 含 重量=500, 单位="g", 单价=2
        String baseRows = "["
            + "{\"driverRow\":{\"重量\":500,\"单位\":\"g\",\"单价\":2},"
            + " \"basicDataValues\":{}}"
            + "]";

        JsonNode result = calc.calculate(
            json(fields), json(formulas), null,
            json(rowKeyFields), json(baseRows), json("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>()
        );

        // 应只有 1 行
        assertEquals(1, result.size(), "应返回 1 行结果");

        double 金额 = result.get(0).path("values").path("金额").asDouble();
        // 换算后：重量=500g→0.5KG，金额=0.5×2=1.0
        assertEquals(1.0, 金额, 1e-4,
            "unit_source_field 归一后：重量 500g=0.5KG，金额=0.5×2=1.0（未换算则为 1000）");
    }

    /**
     * 无 unit_source_field 字段：换算不应影响结果（回归）。
     * 重量=500, 单价=2, 金额=重量×单价=1000。
     */
    @Test
    void computeRows_noUnitSourceField_unchanged() {
        String fields = "["
            + "{\"name\":\"重量\",\"fieldType\":\"INPUT_NUMBER\"},"
            + "{\"name\":\"单价\",\"fieldType\":\"INPUT_NUMBER\"},"
            + "{\"name\":\"金额\",\"fieldType\":\"FORMULA\",\"formulaName\":\"f_amount\"}"
            + "]";

        String formulas = "["
            + "{\"name\":\"f_amount\",\"expression\":["
            + "  {\"type\":\"field\",\"value\":\"重量\"},"
            + "  {\"type\":\"operator\",\"value\":\"×\"},"
            + "  {\"type\":\"field\",\"value\":\"单价\"}"
            + "]}"
            + "]";

        String rowKeyFields = "[\"重量\"]";

        String baseRows = "["
            + "{\"driverRow\":{\"重量\":500,\"单价\":2},"
            + " \"basicDataValues\":{}}"
            + "]";

        JsonNode result = calc.calculate(
            json(fields), json(formulas), null,
            json(rowKeyFields), json(baseRows), json("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>()
        );

        assertEquals(1, result.size());
        double 金额 = result.get(0).path("values").path("金额").asDouble();
        assertEquals(1000.0, 金额, 1e-4, "无 unit_source_field：重量=500，金额=500×2=1000");
    }

    /**
     * 单位="KG"：系数=1，归一不改变值（KG 为标准单位）。
     * 重量=2.5, 单位="KG", 单价=10, 金额=2.5×10=25。
     */
    @Test
    void computeRows_kgUnit_noChange() {
        String fields = "["
            + "{\"name\":\"重量\",\"fieldType\":\"INPUT_NUMBER\",\"unit_source_field\":\"单位\"},"
            + "{\"name\":\"单位\",\"fieldType\":\"INPUT_NUMBER\"},"
            + "{\"name\":\"单价\",\"fieldType\":\"INPUT_NUMBER\"},"
            + "{\"name\":\"金额\",\"fieldType\":\"FORMULA\",\"formulaName\":\"f_amount\"}"
            + "]";

        String formulas = "["
            + "{\"name\":\"f_amount\",\"expression\":["
            + "  {\"type\":\"field\",\"value\":\"重量\"},"
            + "  {\"type\":\"operator\",\"value\":\"×\"},"
            + "  {\"type\":\"field\",\"value\":\"单价\"}"
            + "]}"
            + "]";

        String rowKeyFields = "[\"重量\"]";

        String baseRows = "["
            + "{\"driverRow\":{\"重量\":2.5,\"单位\":\"KG\",\"单价\":10},"
            + " \"basicDataValues\":{}}"
            + "]";

        JsonNode result = calc.calculate(
            json(fields), json(formulas), null,
            json(rowKeyFields), json(baseRows), json("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>()
        );

        assertEquals(1, result.size());
        double 金额 = result.get(0).path("values").path("金额").asDouble();
        assertEquals(25.0, 金额, 1e-4, "单位=KG（系数=1）：重量=2.5，金额=2.5×10=25");
    }

    /**
     * 单位="T"（吨）：系数=1000，归一后 重量=0.5T→500KG。
     * 重量=0.5, 单位="T", 单价=3, 金额=500×3=1500。
     */
    @Test
    void computeRows_tonUnit_multiplied() {
        String fields = "["
            + "{\"name\":\"重量\",\"fieldType\":\"INPUT_NUMBER\",\"unit_source_field\":\"单位\"},"
            + "{\"name\":\"单位\",\"fieldType\":\"INPUT_NUMBER\"},"
            + "{\"name\":\"单价\",\"fieldType\":\"INPUT_NUMBER\"},"
            + "{\"name\":\"金额\",\"fieldType\":\"FORMULA\",\"formulaName\":\"f_amount\"}"
            + "]";

        String formulas = "["
            + "{\"name\":\"f_amount\",\"expression\":["
            + "  {\"type\":\"field\",\"value\":\"重量\"},"
            + "  {\"type\":\"operator\",\"value\":\"×\"},"
            + "  {\"type\":\"field\",\"value\":\"单价\"}"
            + "]}"
            + "]";

        String rowKeyFields = "[\"重量\"]";

        String baseRows = "["
            + "{\"driverRow\":{\"重量\":0.5,\"单位\":\"T\",\"单价\":3},"
            + " \"basicDataValues\":{}}"
            + "]";

        JsonNode result = calc.calculate(
            json(fields), json(formulas), null,
            json(rowKeyFields), json(baseRows), json("[]"),
            new HashMap<>(), new HashMap<>(), new HashMap<>()
        );

        assertEquals(1, result.size());
        double 金额 = result.get(0).path("values").path("金额").asDouble();
        // 0.5T × 1000 = 500KG，500 × 3 = 1500
        assertEquals(1500.0, 金额, 1e-4, "单位=T（系数=1000）：0.5T→500KG，金额=500×3=1500");
    }
}
