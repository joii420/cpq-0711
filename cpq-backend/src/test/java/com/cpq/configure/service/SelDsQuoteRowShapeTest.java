package com.cpq.configure.service;

import com.cpq.configure.dto.ElementOverride;
import com.cpq.configure.dto.MaterialSelection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * task-260903 · 阶段 A —— {@code ds_quote_*} 行集组装的形状守卫。
 *
 * <p>纯内存，不连库、不起 Quarkus（三个 builder 都不碰 {@code em}）。
 * 守的是三条「写错了不会报错、只会在渲染侧静默出错」的约定：
 * <ol>
 *   <li><b>A-4</b>：{@code output_material_type} 必须显式填 —— 它参与行指纹，
 *       留空会让用户下次导入同料号时指纹对不上而整组升版</li>
 *   <li><b>A-9</b>：调用方<b>不许</b>自己填 {@code version_no} / {@code row_fingerprint} ——
 *       那是 {@code VersionedGroupWriter} 的职责，自己算必然与导入侧漂移</li>
 *   <li><b>约束③</b>：{@code rough_weight}/{@code scrap_rate} 等刻意留 NULL，
 *       {@code component_qty} 必须映射（兼容视图把它映到 V6 {@code composition_qty}，53 段模板引用）</li>
 * </ol>
 */
@DisplayName("task-260903 A · ds_quote_* 行集形状")
class SelDsQuoteRowShapeTest {

    private final ConfigureProductService svc = new ConfigureProductService();

    private static MaterialSelection mat(String code, String ratio, String... elems) {
        MaterialSelection ms = new MaterialSelection();
        ms.recipeCode = code;
        ms.ratio = ratio == null ? null : new BigDecimal(ratio);
        ms.elements = new java.util.ArrayList<>();
        for (int i = 0; i < elems.length; i += 2) {
            ElementOverride eo = new ElementOverride();
            eo.elementCode = elems[i];
            eo.pct = new BigDecimal(elems[i + 1]);
            ms.elements.add(eo);
        }
        return ms;
    }

    /** 三个 builder 的产物都不许带这两个键。 */
    private static void assertNoWriterOwnedKeys(List<Map<String, Object>> rows) {
        for (Map<String, Object> r : rows) {
            assertFalse(r.containsKey("version_no"),
                "A-9：调用方不许填 version_no —— 版本号归 VersionedGroupWriter，填了会与导入侧漂移");
            assertFalse(r.containsKey("row_fingerprint"),
                "A-9：调用方不许填 row_fingerprint —— 自己算指纹必然与导入侧口径漂移");
        }
    }

    @Test
    @DisplayName("A-2/A-AC-1② 材质行：每材质一行、RECIPE、占比 12 位小数不丢")
    void recipeRows() {
        List<Map<String, Object>> rows = svc.buildRecipeBomRows(
            "P1", List.of(mat("00006", "70.123456789012"), mat("00007", "29.876543210988")));

        assertEquals(2, rows.size(), "两个材质应产出两行");
        assertEquals(1, rows.get(0).get("item_seq"));
        assertEquals(2, rows.get(1).get("item_seq"));
        assertEquals("00006", rows.get(0).get("input_material_no"), "投入料号 = 材质料号 recipe.code");
        assertEquals(SelDsQuoteWriter.OUT_RECIPE, rows.get(0).get("output_material_type"),
            "A-4：材质行的投影维度必须是 RECIPE");
        assertEquals(new BigDecimal("70.123456789012"), rows.get(0).get("material_ratio"),
            "A-AC-1②：占比必须原样带满 12 位小数，不许在组装期舍入");
        assertEquals("P1", rows.get(0).get("material_no"), "轴列必须在行里（persistedColumns 含它）");

        // 约束③：这些列刻意留 NULL —— V6 时代选配侧本来就写 NULL，留 NULL 才是行为等价
        for (String k : List.of("gross_weight", "net_weight", "weight_unit", "loss_rate", "defect_rate")) {
            assertNull(rows.get(0).get(k), k + " 应刻意留 NULL，不许臆造数值");
        }
        assertNoWriterOwnedKeys(rows);
    }

    @Test
    @DisplayName("A-3/A-AC-1③ 元素行：N 个材质的元素合成一组，material_part_no 分辨归属")
    void elementRows() {
        List<Map<String, Object>> rows = svc.buildElementBomRows("P1",
            List.of(mat("00006", "70", "Ag", "90.5", "Ni", "9.5"),
                    mat("00007", "30", "Cu", "100")));

        assertEquals(3, rows.size(),
            "两个材质共 3 个元素 → 必须合成一组 3 行；新表轴只有 material_no，分次写后一次会抹掉前一次");
        assertEquals("00006", rows.get(0).get("material_part_no"));
        assertEquals("00007", rows.get(2).get("material_part_no"), "第二个材质的元素靠 material_part_no 分回去");
        assertEquals(List.of(1, 2, 3), rows.stream().map(r -> r.get("item_seq")).toList(),
            "item_seq 跨材质连续编号");
        assertEquals(new BigDecimal("90.5"), rows.get(0).get("content_pct"));
        assertNoWriterOwnedKeys(rows);
    }

    @Test
    @DisplayName("A-2/A-AC-6 组合产品：ASSEMBLY + RECIPE 合成一组，装配用量落 component_qty")
    void compositeRows() {
        List<Map<String, Object>> rows =
            svc.buildCompositeBomRows("PARENT", List.of("C1", "C2"), List.of(2, 3));

        assertEquals(4, rows.size(), "2 子件 → 2 条 ASSEMBLY + 2 条 RECIPE，必须在同一组里给全");
        assertEquals(SelDsQuoteWriter.OUT_ASSEMBLY, rows.get(0).get("output_material_type"));
        assertEquals(SelDsQuoteWriter.OUT_RECIPE, rows.get(2).get("output_material_type"),
            "A-AC-6：同一父料号既要有 ASSEMBLY 行也要有 RECIPE 行");
        // 约束③：component_qty 必须映射（兼容视图 → V6 composition_qty，53 段模板引用）
        assertEquals(new BigDecimal(2), rows.get(0).get("component_qty"));
        assertEquals(new BigDecimal(3), rows.get(1).get("component_qty"));
        assertNull(rows.get(2).get("component_qty"), "RECIPE 行不带装配用量");
        assertEquals(List.of(1, 2, 3, 4), rows.stream().map(r -> r.get("item_seq")).toList(),
            "item_seq 在两类行之间连续，不重号");
        assertNoWriterOwnedKeys(rows);
    }

    @Test
    @DisplayName("边界：材质为空 / 子件为空 → 空行集，不抛异常")
    void emptyInputs() {
        assertEquals(0, svc.buildRecipeBomRows("P1", null).size());
        assertEquals(0, svc.buildElementBomRows("P1", List.of()).size());
        assertEquals(0, svc.buildCompositeBomRows("P1", null, null).size());
    }

    @Test
    @DisplayName("A-4：外购件是第三态 OUTSOURCED —— V6 characteristic 三态在新表值域里必须齐全")
    void outsourcedIsThirdState() {
        assertEquals("OUTSOURCED", SelDsQuoteWriter.OUT_OUTSOURCED);
        assertEquals("ASSEMBLY", SelDsQuoteWriter.OUT_ASSEMBLY);
        assertEquals("RECIPE", SelDsQuoteWriter.OUT_RECIPE);
    }
}
