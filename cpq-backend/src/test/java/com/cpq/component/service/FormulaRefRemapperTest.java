package com.cpq.component.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD 单测：FormulaRefRemapper 纯函数重写三类跨组件引用。
 *
 * <p>覆盖场景：
 * <ol>
 *   <li>cross_tab_ref.source 命中 idMap → 被替换</li>
 *   <li>targetExpr[].source 命中 idMap → 所有元素均替换</li>
 *   <li>component_subtotal.component_code 命中 codeMap → 被替换</li>
 *   <li>不误伤：source / component_code 不在 map 中 → 原样保留</li>
 *   <li>混合多 token + 多 formula → 各自正确处理</li>
 *   <li>畸形/空输入 → 返回原值不抛异常</li>
 *   <li>幂等：对已重映射结果再 remap(同 map) → 不变</li>
 * </ol>
 */
class FormulaRefRemapperTest {

    // ── 测试用 UUID 常量 ─────────────────────────────────────────────────────
    private static final String OLD_ID_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String NEW_ID_A = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String OLD_ID_B = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String NEW_ID_B = "bbbbbbbb-0000-0000-0000-000000000002";
    /** 不在 idMap 中的 UUID（已指向新副本，不应再替换） */
    private static final String UNRELATED_ID = "cccccccc-0000-0000-0000-000000000099";

    private static final String OLD_CODE = "COMP-0028";
    private static final String NEW_CODE = "COMP-1234-IMPORTED";
    private static final String UNRELATED_CODE = "COMP-9999";

    // ── TC-1: cross_tab_ref.source 命中 idMap → 被替换 ─────────────────────

    @Test
    void crossTabRef_source_replaced_when_in_idMap() throws Exception {
        String formulasJson = """
                [
                  {
                    "name": "总成本",
                    "expression": [
                      {
                        "type": "cross_tab_ref",
                        "agg": "SUM",
                        "source": "%s",
                        "sourceLabel": "材料成本",
                        "targetExpr": [],
                        "match": []
                      }
                    ]
                  }
                ]
                """.formatted(OLD_ID_A);

        Map<String, String> idMap = Map.of(OLD_ID_A, NEW_ID_A);
        String result = FormulaRefRemapper.remap(formulasJson, idMap, Map.of());

        assertTrue(result.contains(NEW_ID_A), "source 应被替换为新 id");
        assertFalse(result.contains(OLD_ID_A), "旧 id 不应保留");
    }

    // ── TC-2: targetExpr[].source 命中 idMap → 数组内多元素都替换 ─────────

    @Test
    void crossTabRef_targetExpr_sources_all_replaced() throws Exception {
        String formulasJson = """
                [
                  {
                    "name": "测试公式",
                    "expression": [
                      {
                        "type": "cross_tab_ref",
                        "agg": "SUM",
                        "source": "%s",
                        "targetExpr": [
                          { "type": "field", "value": "数量", "source": "%s" },
                          { "type": "field", "value": "单价", "source": "%s" }
                        ],
                        "match": []
                      }
                    ]
                  }
                ]
                """.formatted(OLD_ID_A, OLD_ID_A, OLD_ID_B);

        Map<String, String> idMap = Map.of(OLD_ID_A, NEW_ID_A, OLD_ID_B, NEW_ID_B);
        String result = FormulaRefRemapper.remap(formulasJson, idMap, Map.of());

        // 顶层 source 替换
        assertTrue(result.contains(NEW_ID_A), "顶层 source 应替换");
        // targetExpr 内两个 source 都应替换
        assertTrue(result.contains(NEW_ID_B), "targetExpr[1].source 应替换");
        // 旧值不应出现
        assertFalse(result.contains(OLD_ID_A), "OLD_ID_A 不应保留");
        assertFalse(result.contains(OLD_ID_B), "OLD_ID_B 不应保留");
    }

    // ── TC-3: component_subtotal.component_code 命中 codeMap → 被替换 ──────

    @Test
    void componentSubtotal_code_replaced_when_in_codeMap() throws Exception {
        String formulasJson = """
                [
                  {
                    "name": "管理费",
                    "expression": [
                      {
                        "type": "component_subtotal",
                        "component_code": "%s",
                        "tab_name": "材料成本"
                      }
                    ]
                  }
                ]
                """.formatted(OLD_CODE);

        Map<String, String> codeMap = Map.of(OLD_CODE, NEW_CODE);
        String result = FormulaRefRemapper.remap(formulasJson, Map.of(), codeMap);

        assertTrue(result.contains(NEW_CODE), "component_code 应被替换");
        assertFalse(result.contains(OLD_CODE), "旧 code 不应保留");
    }

    // ── TC-4: 不误伤 — 不在 map 中的引用原样保留 ───────────────────────────

    @Test
    void noRemap_when_id_not_in_idMap() throws Exception {
        String formulasJson = """
                [
                  {
                    "name": "引用无关组件",
                    "expression": [
                      {
                        "type": "cross_tab_ref",
                        "agg": "SUM",
                        "source": "%s",
                        "targetExpr": [
                          { "type": "field", "value": "数量", "source": "%s" }
                        ],
                        "match": []
                      }
                    ]
                  }
                ]
                """.formatted(UNRELATED_ID, UNRELATED_ID);

        // idMap 只包含 OLD_ID_A→NEW_ID_A，不包含 UNRELATED_ID
        Map<String, String> idMap = Map.of(OLD_ID_A, NEW_ID_A);
        String result = FormulaRefRemapper.remap(formulasJson, idMap, Map.of());

        assertTrue(result.contains(UNRELATED_ID), "不在 idMap 中的 id 应原样保留");
        assertFalse(result.contains(OLD_ID_A), "OLD_ID_A 未出现在输入中，结果也不应出现");
    }

    @Test
    void noRemap_when_code_not_in_codeMap() throws Exception {
        String formulasJson = """
                [
                  {
                    "name": "无关 subtotal",
                    "expression": [
                      {
                        "type": "component_subtotal",
                        "component_code": "%s",
                        "tab_name": "费用"
                      }
                    ]
                  }
                ]
                """.formatted(UNRELATED_CODE);

        // codeMap 只包含 OLD_CODE→NEW_CODE，不包含 UNRELATED_CODE
        Map<String, String> codeMap = Map.of(OLD_CODE, NEW_CODE);
        String result = FormulaRefRemapper.remap(formulasJson, Map.of(), codeMap);

        assertTrue(result.contains(UNRELATED_CODE), "不在 codeMap 中的 code 应原样保留");
    }

    // ── TC-5: 混合多 token + 多 formula → 各自正确处理 ─────────────────────

    @Test
    void mixed_multiple_formulas_and_tokens_all_handled_correctly() throws Exception {
        String formulasJson = """
                [
                  {
                    "name": "公式A",
                    "expression": [
                      {
                        "type": "cross_tab_ref",
                        "agg": "SUM",
                        "source": "%s",
                        "targetExpr": [
                          { "type": "field", "value": "数量", "source": "%s" }
                        ],
                        "match": []
                      },
                      { "type": "operator", "value": "+" },
                      {
                        "type": "component_subtotal",
                        "component_code": "%s",
                        "tab_name": "材料成本"
                      }
                    ]
                  },
                  {
                    "name": "公式B",
                    "expression": [
                      {
                        "type": "cross_tab_ref",
                        "agg": "AVG",
                        "source": "%s",
                        "targetExpr": [
                          { "type": "field", "value": "单价", "source": "%s" }
                        ],
                        "match": []
                      },
                      {
                        "type": "component_subtotal",
                        "component_code": "%s",
                        "tab_name": "加工成本"
                      }
                    ]
                  }
                ]
                """.formatted(
                OLD_ID_A, OLD_ID_A, OLD_CODE,   // 公式A
                OLD_ID_B, OLD_ID_B, UNRELATED_CODE // 公式B（UNRELATED_CODE 不在 codeMap）
        );

        Map<String, String> idMap = Map.of(OLD_ID_A, NEW_ID_A, OLD_ID_B, NEW_ID_B);
        Map<String, String> codeMap = Map.of(OLD_CODE, NEW_CODE);
        String result = FormulaRefRemapper.remap(formulasJson, idMap, codeMap);

        // 公式A 的 cross_tab_ref 和 targetExpr
        assertTrue(result.contains(NEW_ID_A), "公式A cross_tab_ref.source 应替换");
        // 公式A 的 component_subtotal
        assertTrue(result.contains(NEW_CODE), "公式A component_subtotal.code 应替换");
        // 公式B 的 cross_tab_ref 和 targetExpr
        assertTrue(result.contains(NEW_ID_B), "公式B cross_tab_ref.source 应替换");
        // 公式B 的 UNRELATED_CODE 不在 codeMap → 原样保留
        assertTrue(result.contains(UNRELATED_CODE), "公式B 不在 codeMap 的 code 应保留");
        // 旧值不应出现
        assertFalse(result.contains(OLD_ID_A), "OLD_ID_A 不应保留");
        assertFalse(result.contains(OLD_ID_B), "OLD_ID_B 不应保留");
        assertFalse(result.contains(OLD_CODE), "OLD_CODE 不应保留");
        // operator token 不应被破坏（不含任何 source/component_code 字段）
        assertTrue(result.contains("\"operator\""), "operator token 应原样保留");
    }

    // ── TC-6: 畸形/空输入 → 返回原值不抛异常 ────────────────────────────────

    @Test
    void null_input_returns_null() {
        String result = FormulaRefRemapper.remap(null, Map.of(OLD_ID_A, NEW_ID_A), Map.of());
        assertNull(result, "null 输入应返回 null");
    }

    @Test
    void empty_string_returns_empty_string() {
        String result = FormulaRefRemapper.remap("", Map.of(OLD_ID_A, NEW_ID_A), Map.of());
        assertEquals("", result, "空字符串输入应返回空字符串");
    }

    @Test
    void empty_array_returns_empty_array() {
        String input = "[]";
        String result = FormulaRefRemapper.remap(input, Map.of(OLD_ID_A, NEW_ID_A), Map.of());
        // 空数组无 token 可改，应仍为合法 JSON 数组
        assertNotNull(result);
        assertTrue(result.contains("[") && result.contains("]"), "空数组输入应返回 JSON 数组");
    }

    @Test
    void invalid_json_returns_original_value() {
        String badJson = "this is not json {{";
        String result = FormulaRefRemapper.remap(badJson, Map.of(OLD_ID_A, NEW_ID_A), Map.of());
        assertEquals(badJson, result, "非法 JSON 应返回原值");
    }

    @Test
    void non_array_json_returns_original_value() {
        String objJson = "{\"key\":\"value\"}";
        String result = FormulaRefRemapper.remap(objJson, Map.of(OLD_ID_A, NEW_ID_A), Map.of());
        assertEquals(objJson, result, "非数组 JSON 应返回原值");
    }

    @Test
    void null_maps_treated_as_empty() {
        String formulasJson = """
                [
                  {
                    "name": "测试",
                    "expression": [
                      {
                        "type": "cross_tab_ref",
                        "source": "%s",
                        "targetExpr": [],
                        "match": []
                      }
                    ]
                  }
                ]
                """.formatted(OLD_ID_A);

        // null map 当空 map 处理 → source 不替换 → 原样保留
        String result = FormulaRefRemapper.remap(formulasJson, null, null);
        assertNotNull(result, "null map 不应抛异常");
        assertTrue(result.contains(OLD_ID_A), "null idMap 不替换任何 source");
    }

    // ── TC-7: 幂等 — 对已重映射结果再 remap(同 map) → 不变 ─────────────────

    @Test
    void remap_is_idempotent() throws Exception {
        String formulasJson = """
                [
                  {
                    "name": "幂等测试",
                    "expression": [
                      {
                        "type": "cross_tab_ref",
                        "agg": "SUM",
                        "source": "%s",
                        "targetExpr": [
                          { "type": "field", "value": "数量", "source": "%s" }
                        ],
                        "match": []
                      },
                      {
                        "type": "component_subtotal",
                        "component_code": "%s",
                        "tab_name": "材料"
                      }
                    ]
                  }
                ]
                """.formatted(OLD_ID_A, OLD_ID_A, OLD_CODE);

        Map<String, String> idMap = Map.of(OLD_ID_A, NEW_ID_A);
        Map<String, String> codeMap = Map.of(OLD_CODE, NEW_CODE);

        // 第一次 remap
        String first = FormulaRefRemapper.remap(formulasJson, idMap, codeMap);
        // 第二次 remap：新值(NEW_ID_A, NEW_CODE)不在 idMap/codeMap 的 key 中，不应再改变
        String second = FormulaRefRemapper.remap(first, idMap, codeMap);

        // 语义不变（内容等价），比较是否包含相同的新值
        assertTrue(second.contains(NEW_ID_A), "二次 remap 应仍含新 id");
        assertTrue(second.contains(NEW_CODE), "二次 remap 应仍含新 code");
        assertFalse(second.contains(OLD_ID_A), "旧 id 不应重新出现");
        assertFalse(second.contains(OLD_CODE), "旧 code 不应重新出现");

        // 两次结果语义等价（JSON 解析级比较）
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(om.readTree(first), om.readTree(second), "二次 remap 结果应与一次相同");
    }
}
