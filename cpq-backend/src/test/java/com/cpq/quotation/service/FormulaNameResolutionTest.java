package com.cpq.quotation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * repair-0803 B3（BL-0098）：公式名解析口径测试。
 *
 * <p>固化 {@link FormulaCalculator#resolveFormulaNameForField} 的 4 级回退语义，
 * 其中 <b>第 3 级 positional fallback 是 BL-0098 的根源</b> ——
 * 未显式绑定的 FORMULA 字段会按「在 FORMULA 字段中的相对位置」取 {@code formulas[同位置]}，
 * 因此调整公式顺序会让字段<b>静默换算法</b>。
 *
 * <p>{@link #realCase_COMP0157_材料成本_按位置隐式绑到银点公式()} 用 2026-08-02 线上故障
 * （QT-20260802-0049）的真实配置复现该机制：用户认为「银点材料成本公式没有被任何字段绑定」，
 * 实际它正通过位置回退决定「材料成本」列的算法。
 */
@DisplayName("repair-0803 B3：FORMULA 字段的公式名解析口径")
class FormulaNameResolutionTest {

    private static final ObjectMapper M = new ObjectMapper();

    private final FormulaCalculator calc = new FormulaCalculator();

    private JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 第 0 级：显式 formula_name 最高优先。 */
    @Test
    @DisplayName("显式 formula_name 优先于一切")
    void explicitNameWins() {
        JsonNode fields = json("""
            [{"name":"甲","field_type":"FORMULA","formula_name":"公式B"}]""");
        JsonNode formulas = json("""
            [{"name":"公式A","expression":[]},{"name":"公式B","expression":[]}]""");

        assertEquals("公式B", calc.resolveFormulaNameForField(fields.get(0), fields, formulas, null, 0));
    }

    /** 第 2 级：字段名 == 公式名。 */
    @Test
    @DisplayName("无显式绑定时，字段名与公式名相同则按名匹配")
    void matchByName() {
        JsonNode fields = json("""
            [{"name":"回收成本","field_type":"FORMULA"}]""");
        JsonNode formulas = json("""
            [{"name":"其它","expression":[]},{"name":"回收成本","expression":[]}]""");

        assertEquals("回收成本", calc.resolveFormulaNameForField(fields.get(0), fields, formulas, null, 0));
    }

    /**
     * 第 3 级：positional fallback —— <b>BL-0098 的根源</b>。
     *
     * <p>真实案例：`COMP-0157「物料」`的 `材料成本` 字段 formula_name 为空、无同名公式，
     * 它是第 3 个 FORMULA 字段（下标 2），于是匹配 formulas[2] =「银点材料成本公式」。
     */
    @Test
    @DisplayName("【真实案例】COMP-0157 材料成本 → 按位置隐式绑到「银点材料成本公式」")
    void realCase_COMP0157_材料成本_按位置隐式绑到银点公式() {
        // 还原线上配置的相对顺序（只保留与解析相关的字段）
        JsonNode fields = json("""
            [{"name":"料件","field_type":"INPUT_TEXT"},
             {"name":"来料回收费","field_type":"FORMULA","formula_name":"来料回收费取值公式"},
             {"name":"来料财务费","field_type":"FORMULA","formula_name":"来料财务费取值公式"},
             {"name":"材料成本","field_type":"FORMULA"},
             {"name":"材料损耗成本","field_type":"FORMULA","formula_name":"材料损耗成本"}]""");
        JsonNode formulas = json("""
            [{"name":"来料回收费取值公式","expression":[]},
             {"name":"来料财务费取值公式","expression":[]},
             {"name":"银点材料成本公式","expression":[]},
             {"name":"非银点类材料成本公式","expression":[]},
             {"name":"材料损耗成本","expression":[]}]""");

        // 「材料成本」在 fields 中的完整下标是 3，但它是第 3 个 FORMULA 字段（相对位置 2）
        String resolved = calc.resolveFormulaNameForField(fields.get(3), fields, formulas, null, 3);

        assertEquals("银点材料成本公式", resolved,
            "BL-0098：未显式绑定的 FORMULA 字段按位置匹配 formulas[相对位置]，"
            + "这正是用户认为『该公式未被任何字段绑定』却能影响算值的原因");
    }

    /**
     * BL-0098 的危害固化：**公式顺序一变，未显式绑定的字段就静默换算法**。
     * 同一个「材料成本」字段，仅因为公式列表插入了一条，算法就从「银点」变成了「非银点」。
     */
    @Test
    @DisplayName("【危害】公式列表插入一条 → 未绑定字段静默换算法")
    void insertingFormulaSilentlyRebinds() {
        JsonNode fields = json("""
            [{"name":"甲","field_type":"FORMULA","formula_name":"甲公式"},
             {"name":"乙","field_type":"FORMULA","formula_name":"乙公式"},
             {"name":"材料成本","field_type":"FORMULA"}]""");

        JsonNode before = json("""
            [{"name":"甲公式","expression":[]},{"name":"乙公式","expression":[]},
             {"name":"银点材料成本公式","expression":[]}]""");
        assertEquals("银点材料成本公式",
            calc.resolveFormulaNameForField(fields.get(2), fields, before, null, 2));

        // 有人在列表中间插入了一条新公式 —— 字段配置一个字没动
        JsonNode after = json("""
            [{"name":"甲公式","expression":[]},{"name":"新插入的公式","expression":[]},
             {"name":"乙公式","expression":[]},{"name":"银点材料成本公式","expression":[]}]""");
        assertEquals("乙公式",
            calc.resolveFormulaNameForField(fields.get(2), fields, after, null, 2),
            "字段配置未改，算法却从「银点材料成本公式」变成了「乙公式」—— 不报错、不提示，"
            + "这就是 BL-0098 要求保存期固化 formula_name 的理由");
    }

    /** 解析不到时返回 null，调用方应保持原样（不得写入空串）。 */
    @Test
    @DisplayName("解析不到返回 null")
    void unresolvableReturnsNull() {
        JsonNode fields = json("""
            [{"name":"甲","field_type":"FORMULA"}]""");
        JsonNode formulas = json("[]");

        assertNull(calc.resolveFormulaNameForField(fields.get(0), fields, formulas, null, 0));
    }
}
