package com.cpq.quotation.service;

import com.cpq.quotation.rowkey.DeletedRowKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BL-0127 · 钉死 {@link CardSnapshotService#seedEditRowsFromRowData} 的行为契约。
 *
 * <p><b>背景</b>：{@code buildCardValues} 原先对 editRows 传 null（"加产品时恒空"），叠加 saveDraft 的
 * D-1 置 NULL，使「用户编辑 → 快照」这条路彻底断掉 —— 重建后的 formulaResults 永远是 driver 默认值口径，
 * 而前端行内 FORMULA 单元格优先读该快照、列小计走本地实时引擎 → 同一列两个值（QT-20260805-0080 报障）。
 *
 * <p><b>为什么必须有独立单测</b>：本改动唯一的风险点是<b>行对齐</b>——错位不会报错，只会把 A 行的输入
 * 写到 B 行头上（静默改数）。端到端测里"值恰好一样"会把错位掩盖掉，只有针对性用例能鉴别。
 */
@QuarkusTest
class EditRowsFromRowDataTest {

    @Inject CardSnapshotService svc;
    @Inject FormulaCalculator formulaCalculator;

    static final ObjectMapper M = new ObjectMapper();

    /** 单组件 tab：料件(INPUT_TEXT) / 材料占比(INPUT_NUMBER) / 备注(INPUT_TEXT,只读) / 材料成本(FORMULA) / 单位(BASIC_DATA)。 */
    private JsonNode snapshot() throws Exception {
        return M.readTree("""
            [{"componentId":"C1","tabName":"物料","componentType":"NORMAL","fields":[
              {"name":"料件","fieldType":"INPUT_TEXT"},
              {"name":"材料占比","fieldType":"INPUT_NUMBER"},
              {"name":"备注","fieldType":"INPUT_TEXT","editable":false},
              {"name":"材料成本","fieldType":"FORMULA"},
              {"name":"单位","fieldType":"BASIC_DATA","basicDataPath":"$v._单位"}
            ]}]""");
    }

    private ArrayNode baseRows(String json) throws Exception { return (ArrayNode) M.readTree(json); }

    private Map<String, ArrayNode> baseMap(ArrayNode rows) {
        Map<String, ArrayNode> m = new LinkedHashMap<>();
        m.put("C1", rows);
        return m;
    }

    private Map<String, JsonNode> rkfMap(String json) throws Exception {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        m.put("C1", M.readTree(json));
        return m;
    }

    /** 跑一次回种，返回 C1 的 editRows（null = 未产出）。 */
    private ArrayNode seed(JsonNode snapshot, ArrayNode base, String rowDataJson, String rkfJson,
                           List<DeletedRowKeys.Tombstone> tombs) throws Exception {
        Map<String, String> rd = new LinkedHashMap<>();
        rd.put("C1", rowDataJson);
        Map<String, List<DeletedRowKeys.Tombstone>> del = new LinkedHashMap<>();
        del.put("C1", tombs == null ? List.of() : tombs);
        Map<String, ArrayNode> into = new LinkedHashMap<>();
        svc.seedEditRowsFromRowData(snapshot, baseMap(base), rd, rkfMap(rkfJson), del, into);
        return into.get("C1");
    }

    private JsonNode valuesOf(ArrayNode edits, String rowKey) {
        for (JsonNode e : edits) if (rowKey.equals(e.path("rowKey").asText(""))) return e.path("values");
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** T1.1 无墓碑等长：每行回种一条，rowKey 与权威口径（buildRawRowKeys + uniquify）逐字相等。 */
    @Test
    void t1_1_rowkeys_match_authoritative_algorithm() throws Exception {
        JsonNode snap = snapshot();
        ArrayNode base = baseRows("""
            [{"driverRow":{"料件":"Ag粉","材料占比":25}},
             {"driverRow":{"料件":"TU2丝","材料占比":65}}]""");
        String rowData = """
            [{"row_index":0,"料件":"Ag粉","材料占比":"0.25","材料成本":6.08},
             {"row_index":1,"料件":"TU2丝","材料占比":"0.65","材料成本":0.07}]""";

        ArrayNode edits = seed(snap, base, rowData, "[\"料件\"]", null);
        assertNotNull(edits, "应产出 editRows");
        assertEquals(2, edits.size());

        List<String> authoritative = FormulaCalculator.uniquifyRowKeys(
            formulaCalculator.buildRawRowKeys(M.readTree("[\"料件\"]"), snap.get(0).path("fields"), base, List.of()));
        assertEquals(authoritative.get(0), edits.get(0).path("rowKey").asText());
        assertEquals(authoritative.get(1), edits.get(1).path("rowKey").asText());

        assertEquals("0.25", valuesOf(edits, authoritative.get(0)).path("材料占比").asText());
        assertEquals("0.65", valuesOf(edits, authoritative.get(1)).path("材料占比").asText());
    }

    /** T1.2 只回种 INPUT*：FORMULA / BASIC_DATA / 只读列一律不进 values（回种公式值 = 权威反转）。 */
    @Test
    void t1_2_only_input_fields_are_seeded() throws Exception {
        ArrayNode base = baseRows("[{\"driverRow\":{\"料件\":\"Ag粉\"}}]");
        String rowData = """
            [{"row_index":0,"料件":"Ag粉","材料占比":"0.25","材料成本":6.08,"单位":"g/pcs","备注":"手写"}]""";

        ArrayNode edits = seed(snapshot(), base, rowData, "[\"料件\"]", null);
        JsonNode v = edits.get(0).path("values");
        assertTrue(v.has("料件") && v.has("材料占比"), "INPUT* 列应回种");
        assertFalse(v.has("材料成本"), "FORMULA 列禁止回种");
        assertFalse(v.has("单位"), "BASIC_DATA 列禁止回种");
        assertFalse(v.has("备注"), "editable=false 的列不回种");
    }

    /** T1.3 键存在即已定值：""（用户显式清空）必须回种；键缺失才交给后端解析默认值。 */
    @Test
    void t1_3_empty_string_is_a_value_missing_key_is_not() throws Exception {
        ArrayNode base = baseRows("[{\"driverRow\":{\"料件\":\"Ag粉\"}}]");
        ArrayNode edits = seed(snapshot(), base,
            "[{\"row_index\":0,\"料件\":\"Ag粉\",\"材料占比\":\"\"}]", "[\"料件\"]", null);

        JsonNode v = edits.get(0).path("values");
        assertTrue(v.has("材料占比"), "显式清空必须回种（否则重开又被烘回默认值）");
        assertEquals("", v.path("材料占比").asText());

        ArrayNode edits2 = seed(snapshot(), base,
            "[{\"row_index\":0,\"料件\":\"Ag粉\"}]", "[\"料件\"]", null);
        assertFalse(edits2.get(0).path("values").has("材料占比"), "键不存在 = 从未定值，不回种");
    }

    /** T1.4 墓碑对齐：row_data 是"减墓碑"口径，必须按 keepMask 保留序配对，不能按 baseRows 下标。 */
    @Test
    void t1_4_tombstoned_rows_do_not_shift_alignment() throws Exception {
        JsonNode snap = snapshot();
        ArrayNode base = baseRows("""
            [{"driverRow":{"料件":"H85"}},
             {"driverRow":{"料件":"Ag粉"}},
             {"driverRow":{"料件":"TU2丝"}}]""");
        // 删掉第 0 行(H85) → row_data 只剩 2 行
        String fpH85 = DeletedRowKeys.rowFingerprint(List.of("料件"), base.get(0).path("driverRow"));
        List<DeletedRowKeys.Tombstone> tombs = new ArrayList<>();
        tombs.add(new DeletedRowKeys.Tombstone("H85", fpH85, null));

        String rowData = """
            [{"row_index":0,"料件":"Ag粉","材料占比":"0.25"},
             {"row_index":1,"料件":"TU2丝","材料占比":"0.65"}]""";

        ArrayNode edits = seed(snap, base, rowData, "[\"料件\"]", tombs);
        assertNotNull(edits, "保留行应回种（按 baseRows 下标错配会让全部行校验失败 → null）");
        assertEquals(2, edits.size(), "只应回种保留下来的 2 行");

        List<String> keys = FormulaCalculator.uniquifyRowKeys(
            formulaCalculator.buildRawRowKeys(M.readTree("[\"料件\"]"), snap.get(0).path("fields"), base, tombs));
        // 若按 baseRows 下标错配，Ag粉 的 0.25 会落到 H85 头上
        assertEquals("0.25", valuesOf(edits, keys.get(1)).path("材料占比").asText(), "Ag粉 应拿到 0.25");
        assertEquals("0.65", valuesOf(edits, keys.get(2)).path("材料占比").asText(), "TU2丝 应拿到 0.65");
        assertNull(valuesOf(edits, keys.get(0)), "被删的 H85 不应出现在回种结果里");
    }

    /** T1.5 尾部手动行：row_data 比保留行多出的行被忽略，不抛异常。 */
    @Test
    void t1_5_trailing_manual_rows_are_ignored() throws Exception {
        ArrayNode base = baseRows("[{\"driverRow\":{\"料件\":\"Ag粉\"}}]");
        ArrayNode edits = seed(snapshot(), base, """
            [{"row_index":0,"料件":"Ag粉","材料占比":"0.25"},
             {"row_index":1,"料件":"手工行","材料占比":"9"}]""", "[\"料件\"]", null);
        assertEquals(1, edits.size());
        assertEquals("0.25", edits.get(0).path("values").path("材料占比").asText());
    }

    /** T1.6 校验失败即跳过：内容键对不上的行不回种（宁可退化成改造前，也不能写到别的料号头上）。 */
    @Test
    void t1_6_mismatched_row_is_skipped_not_guessed() throws Exception {
        JsonNode snap = snapshot();
        ArrayNode base = baseRows("""
            [{"driverRow":{"料件":"Ag粉"}},
             {"driverRow":{"料件":"TU2丝"}}]""");
        // 第 0 行 row_data 的料件被改成不存在的值 → 该行必须跳过，第 1 行照常
        ArrayNode edits = seed(snap, base, """
            [{"row_index":0,"料件":"错位行","材料占比":"9.99"},
             {"row_index":1,"料件":"TU2丝","材料占比":"0.65"}]""", "[\"料件\"]", null);

        List<String> keys = FormulaCalculator.uniquifyRowKeys(
            formulaCalculator.buildRawRowKeys(M.readTree("[\"料件\"]"), snap.get(0).path("fields"), base, List.of()));
        assertNull(valuesOf(edits, keys.get(0)), "对不上的行不得回种");
        assertNotNull(valuesOf(edits, keys.get(1)), "其余行不受影响");
        assertEquals("0.65", valuesOf(edits, keys.get(1)).path("材料占比").asText());
    }

    /** T1.6b 数值型行键的类型漂移（25 vs 25.0）不算错位。 */
    @Test
    void t1_6b_numeric_rowkey_type_drift_is_not_a_mismatch() throws Exception {
        ArrayNode base = baseRows("[{\"driverRow\":{\"料件\":25}}]");
        ArrayNode edits = seed(snapshot(), base,
            "[{\"row_index\":0,\"料件\":25.0,\"材料占比\":\"0.25\"}]", "[\"料件\"]", null);
        assertNotNull(edits, "数值格式漂移不应判成错位");
        assertEquals("0.25", edits.get(0).path("values").path("材料占比").asText());
    }

    /** T1.7 价格锁豁免：__priceLocked 行整行跳过（价格由 task-0729 归位写入，UI 本就只读）。 */
    @Test
    void t1_7_price_locked_row_is_not_seeded() throws Exception {
        ArrayNode base = baseRows("""
            [{"driverRow":{"料件":"Ag粉","__priceLocked":true}},
             {"driverRow":{"料件":"TU2丝"}}]""");
        ArrayNode edits = seed(snapshot(), base, """
            [{"row_index":0,"料件":"Ag粉","材料占比":"9.99"},
             {"row_index":1,"料件":"TU2丝","材料占比":"0.65"}]""", "[\"料件\"]", null);
        assertEquals(1, edits.size(), "锁定行不回种，非锁定行照常");
        assertEquals("0.65", edits.get(0).path("values").path("材料占比").asText());
    }

    /** T1.8 空/缺失输入不抛：row_data 空数组 / 非法 JSON / 无 baseRows 都安静返回。 */
    @Test
    void t1_8_empty_and_malformed_inputs_are_safe() throws Exception {
        ArrayNode base = baseRows("[{\"driverRow\":{\"料件\":\"Ag粉\"}}]");
        assertNull(seed(snapshot(), base, "[]", "[\"料件\"]", null), "空 row_data → 不产出");
        assertNull(seed(snapshot(), base, "{ not json", "[\"料件\"]", null), "非法 JSON → 不抛、不产出");
        assertNull(seed(snapshot(), baseRows("[]"), "[{\"row_index\":0}]", "[\"料件\"]", null), "无 baseRows → 不产出");
    }

    /** T1.9 显式 editRows 优先：同 (rowKey, 字段) 冲突时保留 editCardValue 写入的值。 */
    @Test
    void t1_9_explicit_edits_win_over_seeded() throws Exception {
        JsonNode snap = snapshot();
        ArrayNode base = baseRows("[{\"driverRow\":{\"料件\":\"Ag粉\"}}]");
        List<String> keys = FormulaCalculator.uniquifyRowKeys(
            formulaCalculator.buildRawRowKeys(M.readTree("[\"料件\"]"), snap.get(0).path("fields"), base, List.of()));

        Map<String, ArrayNode> into = new LinkedHashMap<>();
        into.put("C1", (ArrayNode) M.readTree(
            "[{\"rowKey\":\"" + keys.get(0) + "\",\"values\":{\"材料占比\":\"7\"}}]"));

        Map<String, String> rd = new LinkedHashMap<>();
        rd.put("C1", "[{\"row_index\":0,\"料件\":\"Ag粉\",\"材料占比\":\"0.25\",\"备注2\":\"x\"}]");
        Map<String, List<DeletedRowKeys.Tombstone>> del = new LinkedHashMap<>();
        del.put("C1", List.of());

        svc.seedEditRowsFromRowData(snap, baseMap(base), rd, rkfMap("[\"料件\"]"), del, into);

        JsonNode v = valuesOf(into.get("C1"), keys.get(0));
        assertEquals("7", v.path("材料占比").asText(), "显式 editRows 值优先，不被回种覆盖");
    }
}
