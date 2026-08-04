package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * spec 2026-08-03「键存在即权威」矩阵 golden。
 *
 * <p>刻意<b>不</b>加 {@code @QuarkusTest}：FormulaCalculator 是无可变状态的纯计算 bean
 * （类注释明确「同时支持 new 直接单测」），不起 Quarkus 就不依赖数据库/Flyway，
 * 在共享测试库被并发会话改动时仍然可跑。
 */
class InputKeyPresenceAuthorityTest {

    private final FormulaCalculator calc = new FormulaCalculator();
    private static final ObjectMapper M = new ObjectMapper();

    /** 有源 INPUT / 无源但有 content 的 INPUT / 无源无 content 的 INPUT / BASIC_DATA / FORMULA */
    private static final String FIELDS = """
      [ {"name":"损耗率","field_type":"INPUT_NUMBER",
         "default_source":{"type":"BASIC_DATA","path":"$mc_view._损耗率"}},
        {"name":"税率","field_type":"INPUT_NUMBER","content":"13"},
        {"name":"备注","field_type":"INPUT_TEXT"},
        {"name":"元素","field_type":"BASIC_DATA","basic_data_path":"$mc_view._元素"},
        {"name":"材料成本","field_type":"FORMULA"} ]
      """;

    private static final String DRIVER_ROW = "{\"元素\":\"Ag\"}";
    private static final String BDV =
        "{\"{$mc_view._损耗率}\":1.05,\"{$mc_view._元素}\":\"Ag\"}";
    private static final String FORMULA_VALUES = "{\"材料成本\":641.925}";

    private Map<String, Object> resolve(String editValuesJson) throws Exception {
        JsonNode fields = M.readTree(FIELDS);
        JsonNode editValues = editValuesJson == null ? null : M.readTree(editValuesJson);
        return calc.resolveRowByFieldName(
            fields, M.readTree(DRIVER_ROW), M.readTree(BDV), editValues, M.readTree(FORMULA_VALUES));
    }

    // ── 本次唯一意图变更的一格 ────────────────────────────────────────────────
    @Test
    void 显式清空的INPUT必须落键且值为空串() throws Exception {
        Map<String, Object> row = resolve("{\"损耗率\":\"\"}");
        assertTrue(row.containsKey("损耗率"),
            "显式清空必须落键——否则库里「用户清空」与「从未填过」同形，重开会被默认值回填");
        assertEquals("", row.get("损耗率"), "空值的物理表示统一为空串，不能是 null");
    }

    @Test
    void 显式清空不得回落default_source() throws Exception {
        Map<String, Object> row = resolve("{\"损耗率\":\"\"}");
        assertNotEquals(1.05, row.get("损耗率"), "清空后不得再取 $mc_view._损耗率 的 1.05");
    }

    @Test
    void 显式清空不得回落静态content() throws Exception {
        Map<String, Object> row = resolve("{\"税率\":\"\"}");
        assertTrue(row.containsKey("税率"));
        assertEquals("", row.get("税率"), "清空后不得回落 content=13");
    }

    // ── 以下全部是「必须逐位不变」的既有行为（回归护栏）────────────────────────
    @Test
    void 键缺失时仍按default_source烘值() throws Exception {
        Map<String, Object> row = resolve("{}");
        assertEquals(1.05, ((Number) row.get("损耗率")).doubleValue(), 1e-9);
    }

    @Test
    void 键缺失且无源时仍回落静态content() throws Exception {
        Map<String, Object> row = resolve("{}");
        assertEquals("13", row.get("税率"));
    }

    @Test
    void 键缺失且无源无content时不落键() throws Exception {
        Map<String, Object> row = resolve("{}");
        assertFalse(row.containsKey("备注"), "从未定值且无任何默认值 → 不落键（保持原行为）");
    }

    @Test
    void editValues为null时等价于键全缺失() throws Exception {
        Map<String, Object> row = resolve(null);
        assertEquals(1.05, ((Number) row.get("损耗率")).doubleValue(), 1e-9);
        assertEquals("13", row.get("税率"));
        assertFalse(row.containsKey("备注"));
    }

    @Test
    void 有值的INPUT原样保留() throws Exception {
        Map<String, Object> row = resolve("{\"损耗率\":2.5,\"备注\":\"手填\"}");
        assertEquals(2.5, ((Number) row.get("损耗率")).doubleValue(), 1e-9);
        assertEquals("手填", row.get("备注"));
    }

    @Test
    void 非INPUT字段一律不受本次改动影响() throws Exception {
        Map<String, Object> empty = resolve("{\"损耗率\":\"\"}");
        Map<String, Object> filled = resolve("{\"损耗率\":2.5}");
        assertEquals("Ag", empty.get("元素"));
        assertEquals("Ag", filled.get("元素"));
        assertEquals(641.925, ((Number) empty.get("材料成本")).doubleValue(), 1e-9);
        assertEquals(641.925, ((Number) filled.get("材料成本")).doubleValue(), 1e-9);
    }

    @Test
    void editValues里的null按键缺失处理不落键() throws Exception {
        // 空值的物理表示只认空串；null 走「键缺失」链路（不引入第三种空语义）
        Map<String, Object> row = resolve("{\"损耗率\":null}");
        assertEquals(1.05, ((Number) row.get("损耗率")).doubleValue(), 1e-9);
    }
}
