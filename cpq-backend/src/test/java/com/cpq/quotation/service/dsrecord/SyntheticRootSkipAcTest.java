package com.cpq.quotation.service.dsrecord;

import com.cpq.dataset.registry.QuoteRegistry;
import com.cpq.dataset.registry.SheetDef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-45（用户裁决甲）：树页签的<b>合成根行</b>不进 {@code _record}。
 *
 * <h3>四格双侧还原（把 {@link DsRecordProjector#representsNoBaseRow} 的调用点注掉即可复现）</h3>
 * <pre>
 *              |  修复在  |  改回去
 *   树页签单   |   绿     |   红      ← T1：根行必须被跳过
 *   非树页签单 |   绿     |   绿      ← T2：改动的边界，元素BOM 一行都不许动
 * </pre>
 *
 * <p>🚨 <b>非空守卫</b>：两个夹具都先断言「确实有东西可验」——
 * 树夹具必须真的含 1 行合成根行（否则「跳过生效」恒真），
 * 元素夹具必须真的有行且<b>零</b> {@code __nodeId}（否则「没被误伤」恒真）。
 *
 * <p>夹具形状<b>逐字抄自实测</b>（{@code QT-20260908-0615} 的
 * {@code quotation_line_component_data.snapshot_rows}，2026-09-08 采自 {@code cpq_db_0724}），
 * 只把料号缩短。🚫 别把根行的 {@code driverRow} 简化成「只有 material_no」——
 * 实测根行的 {@code driverRow} <b>带全套业务键、值全 null</b>（$view 对成品 LEFT JOIN 落空的形状），
 * 简化掉就验不出「判据没在看值」这件事。
 */
class SyntheticRootSkipAcTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final QuoteRegistry REG = new QuoteRegistry();

    // ── 夹具 ────────────────────────────────────────────────────────────────
    /** 树页签（物料BOM）：1 行合成根 + 2 行真实边行。 */
    private static final String TREE_SNAPSHOT = """
        [
          {"__lvl":1,"__nodeId":"FG01","__parentId":null,"__parentNo":null,
           "__hfPartNo":"FG01","__nodeType":null,
           "driverRow":{"parent_no":null,"hf_part_no":"FG01","material_no":"FG01",
                        "_BOM_销售料号":null,"_BOM_投入料号":null,"_BOM_项次":null}},
          {"__lvl":2,"__nodeId":"FG01/RM01","__parentId":"FG01","__parentNo":"FG01",
           "__hfPartNo":"RM01","__nodeType":"零件",
           "driverRow":{"parent_no":"FG01","hf_part_no":"RM01","material_no":"RM01",
                        "_BOM_销售料号":"FG01","_BOM_投入料号":"RM01","_BOM_项次":1}},
          {"__lvl":2,"__nodeId":"FG01/RM02","__parentId":"FG01","__parentNo":"FG01",
           "__hfPartNo":"RM02","__nodeType":"零件",
           "driverRow":{"parent_no":"FG01","hf_part_no":"RM02","material_no":"RM02",
                        "_BOM_销售料号":"FG01","_BOM_投入料号":"RM02","_BOM_项次":2}}
        ]""";

    /** 非树页签（物料与元素BOM）：3 行，<b>零</b> {@code __nodeId}。 */
    private static final String FLAT_SNAPSHOT = """
        [
          {"driverRow":{"hf_part_no":"FG01","material_no":"FG01",
                        "_EL_销售料号":"FG01","_EL_元素":"Ag","_EL_项次":1}},
          {"driverRow":{"hf_part_no":"FG01","material_no":"FG01",
                        "_EL_销售料号":"FG01","_EL_元素":"Ni","_EL_项次":2}},
          {"driverRow":{"hf_part_no":"FG01","material_no":"FG01",
                        "_EL_销售料号":"FG01","_EL_元素":"Cu","_EL_项次":3}}
        ]""";

    private static DsSheetBinding treeBinding() {
        return binding("MATERIAL_BOM", new java.util.LinkedHashMap<>(Map.of(
                "_BOM_销售料号", "material_no",
                "_BOM_投入料号", "input_material_no",
                "_BOM_项次", "item_seq")), List.of("input_material_no"));
    }

    private static DsSheetBinding flatBinding() {
        return binding("ELEMENT_BOM", new java.util.LinkedHashMap<>(Map.of(
                "_EL_销售料号", "material_no",
                "_EL_元素", "element_code",
                "_EL_项次", "item_seq")), List.of("element_code", "item_seq"));
    }

    private static DsSheetBinding binding(String sheetKey, Map<String, String> f2c, List<String> grain) {
        SheetDef sheet = REG.sheets().stream().filter(s -> sheetKey.equals(s.sheetKey)).findFirst().orElseThrow();
        return new DsSheetBinding(UUID.randomUUID(), sheet, sheetKey, f2c,
                Map.of(), List.of(), null, List.of(), grain);
    }

    // ── 非空守卫：夹具本身得先站得住 ─────────────────────────────────────────
    private static int countSyntheticRoots(String json) throws Exception {
        JsonNode arr = M.readTree(json);
        int n = 0;
        for (JsonNode r : arr) if (DsRecordProjector.representsNoBaseRow(r)) n++;
        return n;
    }

    private static int countWithNodeId(String json) throws Exception {
        JsonNode arr = M.readTree(json);
        int n = 0;
        for (JsonNode r : arr) if (r.hasNonNull("__nodeId")) n++;
        return n;
    }

    // ── T1 · 树页签：合成根行必须被跳过（改回去 ⇒ 必红）─────────────────────
    @Test
    @DisplayName("T1 树页签：3 行 snapshot → 2 行 _record，合成根行不进；每行都带投入料号")
    void treeTabSkipsSyntheticRoot() throws Exception {
        // 非空守卫①：夹具真的含 1 行合成根行 + 3 行都是树行
        assertEquals(3, countWithNodeId(TREE_SNAPSHOT), "夹具必须全是树行，否则本用例验的不是树页签");
        assertEquals(1, countSyntheticRoots(TREE_SNAPSHOT), "夹具必须恰好含 1 行合成根行，否则「跳过生效」恒真");

        List<DsRecordRow> rows = DsRecordProjector.project(
                treeBinding(), "material_no", TREE_SNAPSHOT, null, null, "FG01", 0);

        assertEquals(2, rows.size(), "合成根行必须被跳过：3 行 snapshot 只应投影出 2 行真实边行");
        for (DsRecordRow r : rows) {
            assertNotNull(r.columnValues.get("input_material_no"),
                    "投影出的每一行都必须表征一条真实 BOM 边（input_material_no 非空）");
            assertNotNull(r.anchorValues.get("input_material_no"), "锚点键同样不许是空壳行");
        }
        assertEquals(List.of("RM01", "RM02"),
                rows.stream().map(r -> String.valueOf(r.columnValues.get("input_material_no"))).toList());
    }

    // ── T2 · 非树页签：一行都不许动（改回去 ⇒ 仍绿 = 改动边界）───────────────
    @Test
    @DisplayName("T2 非树页签（元素BOM）：3 行进 3 行出，本次改动对它零影响")
    void flatTabUntouched() throws Exception {
        // 非空守卫②：夹具真的有行，且零 __nodeId（结构上就进不了 D-45 的分支）
        assertEquals(3, M.readTree(FLAT_SNAPSHOT).size(), "夹具必须真的有行，否则「没被误伤」恒真");
        assertEquals(0, countWithNodeId(FLAT_SNAPSHOT), "非树页签的行结构上不带 __nodeId");
        assertEquals(0, countSyntheticRoots(FLAT_SNAPSHOT));

        List<DsRecordRow> rows = DsRecordProjector.project(
                flatBinding(), "material_no", FLAT_SNAPSHOT, null, null, "FG01", 0);

        assertEquals(3, rows.size(), "元素BOM 侧一行都不许少");
        assertEquals(List.of("Ag", "Ni", "Cu"),
                rows.stream().map(r -> String.valueOf(r.columnValues.get("element_code"))).toList());
    }

    // ── T3 · 判据必须是结构信号，不是「粒度列为 NULL」──────────────────────
    @Test
    @DisplayName("T3 用户把真实边行的『投入料号』清空 → 粒度列变 NULL，但它仍不是合成根行")
    void clearedGrainColumnIsNotSynthetic() throws Exception {
        // 这一行的粒度列（input_material_no）为 NULL —— 字面判据会把它当构件丢掉（= 静默吞用户编辑）；
        // 结构判据只看 __nodeId / __parentId，照样认它是真实边行。
        String json = """
            [{"__lvl":2,"__nodeId":"FG01/RM01","__parentId":"FG01","__parentNo":"FG01",
              "__hfPartNo":"RM01","__nodeType":"零件",
              "driverRow":{"parent_no":"FG01","hf_part_no":"RM01","material_no":"RM01",
                           "_BOM_销售料号":"FG01","_BOM_投入料号":null,"_BOM_项次":1}}]""";
        assertEquals(0, countSyntheticRoots(json), "粒度列为 NULL 的真实边行 🚫 不许被判成合成根行");

        List<DsRecordRow> rows = DsRecordProjector.project(
                treeBinding(), "material_no", json, null, null, "FG01", 0);
        assertEquals(1, rows.size(), "它必须照常进 _record");
        assertNull(rows.get(0).columnValues.get("input_material_no"));
    }

    // ── T4 · 谓词本身的边界 ────────────────────────────────────────────────
    @Test
    @DisplayName("T4 谓词边界：非树行 / 只有一侧父键为空 / null 入参")
    void predicateBoundaries() throws Exception {
        assertFalse(DsRecordProjector.representsNoBaseRow(null));
        assertFalse(DsRecordProjector.representsNoBaseRow(M.readTree("{}")),
                "没有 __nodeId ⇒ 不是树行 ⇒ 一律不跳过");
        assertFalse(DsRecordProjector.representsNoBaseRow(
                        M.readTree("{\"__parentId\":null,\"__parentNo\":null}")),
                "光是两个父键都空、却不带 __nodeId ⇒ 非树行，🚫 不许跳过");
        assertFalse(DsRecordProjector.representsNoBaseRow(
                        M.readTree("{\"__nodeId\":\"A/B\",\"__parentId\":\"A\",\"__parentNo\":null}")),
                "两个权威口径有分歧（一个说有父一个说没父）⇒ 从严不跳过");
        assertTrue(DsRecordProjector.representsNoBaseRow(
                        M.readTree("{\"__nodeId\":\"A\",\"__parentId\":null,\"__parentNo\":null}")),
                "树行 + 两侧父键皆空 = spine 根 = 合成根行");
    }
}
