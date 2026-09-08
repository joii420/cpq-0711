package com.cpq.quotation.service.dsrecord;

import com.cpq.dataset.registry.QuoteRegistry;
import com.cpq.dataset.registry.SheetDef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-46：<b>物料BOM 从来没有被报价单回填过</b> —— 轴值解析优先级反了 + 树闭包被组过滤丢掉。
 *
 * <h3>缺陷实证（主线亲验，两库全量非抽样）</h3>
 * <pre>
 *   ds_quote_material_bom_record : 64 行 / 真实边行 0 条 / 全部 input_material_no IS NULL / 涉 30 张单
 *   ds_quote_element_bom_record  : 186 行 / 合成行 0 条 / 涉 72 张单     ← 健康的对照组
 * </pre>
 *
 * <h3>四格双侧还原（两个病灶各自可单独还原）</h3>
 * <pre>
 *   病灶一：DsRecordProjector.resolveAxis 把 hf_part_no 提回第一优先级
 *   病灶二：DsQuoteRecordService.acceptAxis 改回 `touchedAxes.contains(axis)`
 *
 *                     |  修复在  |  改回去
 *   树页签(物料BOM)    |   绿     |   红      ← T1/T2（病灶一）、T4（病灶二）
 *   非树页签(元素BOM)  |   绿     |   绿      ← T3/T5 = 改动边界，一行都不许动
 * </pre>
 *
 * <p>🚨 <b>非空守卫</b>：树夹具必须真的有<b>子行</b>（否则「出现真实边行」恒真）；
 * 元素夹具必须真的有行（否则「未被误伤」恒真）。两条都在用例开头断言。
 *
 * <p>夹具形状<b>逐字抄自实测</b>（{@code QT-20260908-0615} 的 {@code snapshot_rows}，
 * 2026-09-08 采自 {@code cpq_db_0724}），只把料号缩短。关键的实测事实是：
 * 树页签 {@code driverRow} 里 {@code hf_part_no}=<b>子件</b> 而 {@code _BOM_销售料号}=<b>父件</b>，
 * 主表 {@code ds_quote_material_bom} 的轴列 {@code material_no} 存的是<b>父件</b>
 * （实测边行 {@code (material_no=T260907T-RM01, input_material_no=T260907T-RM03)}）。
 */
class TreeAxisResolutionAcTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final QuoteRegistry REG = new QuoteRegistry();

    // ── 夹具 ────────────────────────────────────────────────────────────────
    /**
     * 树页签（物料BOM）·<b>三层 spine</b>，抄自实测：
     * <pre>
     *   FG01                      根（合成根行，D-45 跳过）
     *    ├─ RM01   父=FG01        真实边行 ⇒ 轴值必须是 FG01
     *    ├─ SC01   父=FG01        真实边行 ⇒ 轴值必须是 FG01
     *    └─ RM01/RM03  父=RM01    真实边行 ⇒ 轴值必须是 **RM01**（中间件 = 树闭包派生轴）
     * </pre>
     * 🔑 最后一行是 D-46 病灶二的靶子：老口径下它的轴值算成 RM03（子件），
     * 新口径下算成 RM01（父件），而 RM01 不在 touchedAxes 里 ⇒ 还要 acceptAxis 放行才活得下来。
     */
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
          {"__lvl":2,"__nodeId":"FG01/SC01","__parentId":"FG01","__parentNo":"FG01",
           "__hfPartNo":"SC01","__nodeType":"外购件",
           "driverRow":{"parent_no":"FG01","hf_part_no":"SC01","material_no":"SC01",
                        "_BOM_销售料号":"FG01","_BOM_投入料号":"SC01","_BOM_项次":3}},
          {"__lvl":3,"__nodeId":"FG01/RM01/RM03","__parentId":"FG01/RM01","__parentNo":"RM01",
           "__hfPartNo":"RM03","__nodeType":"零件",
           "driverRow":{"parent_no":"RM01","hf_part_no":"RM03","material_no":"RM03",
                        "_BOM_销售料号":"RM01","_BOM_投入料号":"RM03","_BOM_项次":1}}
        ]""";

    /** 非树页签（物料与元素BOM）：3 行，{@code hf_part_no} 与轴列值<b>恒等</b>（实测 8/8 同）。 */
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
        Map<String, String> f2c = new LinkedHashMap<>();
        f2c.put("_BOM_销售料号", "material_no");
        f2c.put("_BOM_投入料号", "input_material_no");
        f2c.put("_BOM_项次", "item_seq");
        return binding("MATERIAL_BOM", f2c, List.of("input_material_no"));
    }

    private static DsSheetBinding flatBinding() {
        Map<String, String> f2c = new LinkedHashMap<>();
        f2c.put("_EL_销售料号", "material_no");
        f2c.put("_EL_元素", "element_code");
        f2c.put("_EL_项次", "item_seq");
        return binding("ELEMENT_BOM", f2c, List.of("element_code", "item_seq"));
    }

    private static DsSheetBinding binding(String sheetKey, Map<String, String> f2c, List<String> grain) {
        SheetDef sheet = REG.sheets().stream().filter(s -> sheetKey.equals(s.sheetKey)).findFirst().orElseThrow();
        return new DsSheetBinding(UUID.randomUUID(), sheet, sheetKey, f2c,
                Map.of(), List.of(), null, List.of(), grain);
    }

    /** 非空守卫：夹具里真的有「带父边的子行」。 */
    private static int countChildRows(String json) throws Exception {
        JsonNode arr = M.readTree(json);
        int n = 0;
        for (JsonNode r : arr) if (r.hasNonNull("__parentNo")) n++;
        return n;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 病灶一 · resolveAxis 优先级
    // ══════════════════════════════════════════════════════════════════════

    // ── T1 · 树页签：轴值必须是**父件**，不是 hf_part_no（子件）───────────────
    @Test
    @DisplayName("T1 树页签：3 条真实边行的轴值 = 父件料号（FG01/FG01/RM01），🚫 不是 hf_part_no 的子件")
    void treeRowsUseParentAsAxis() throws Exception {
        assertEquals(3, countChildRows(TREE_SNAPSHOT),
                "非空守卫：夹具必须真的有 3 行子行，否则「出现真实边行」恒真");

        List<DsRecordRow> rows = DsRecordProjector.project(
                treeBinding(), "material_no", TREE_SNAPSHOT, null, null, "FG01", 0);

        assertEquals(3, rows.size(), "合成根行被 D-45 跳过，3 条真实边行必须全部投影出来");
        assertEquals(List.of("FG01", "FG01", "RM01"), rows.stream().map(r -> r.axisValue).toList(),
                "轴值取的必须是本 sheet 轴列(material_no ← _BOM_销售料号)的实际值 = 父件");
        // 🚫 反向断言：老口径（hf_part_no 优先）会得到子件，那正是 D-46 的形状
        assertFalse(rows.stream().map(r -> r.axisValue).toList().equals(List.of("RM01", "SC01", "RM03")),
                "若轴值等于子件料号，说明 resolveAxis 又把 hf_part_no 提回第一优先级了（D-46 回归）");
    }

    // ── T2 · 树页签：每条边行都带投入料号（= 真实边行，不是合成根行）──────────
    @Test
    @DisplayName("T2 树页签：投影出的每一行都是真实 BOM 边（input_material_no 非空）")
    void treeRowsAreRealEdges() throws Exception {
        List<DsRecordRow> rows = DsRecordProjector.project(
                treeBinding(), "material_no", TREE_SNAPSHOT, null, null, "FG01", 0);
        assertEquals(3, rows.size());
        for (DsRecordRow r : rows) {
            assertNotNull(r.columnValues.get("input_material_no"),
                    "实测缺陷形态：_record 里 input_material_no 全 NULL（64/64）⇒ 一条真实边行都没有");
        }
        assertEquals(List.of("RM01", "SC01", "RM03"),
                rows.stream().map(r -> String.valueOf(r.columnValues.get("input_material_no"))).toList());
    }

    // ── T3 · 非树页签：逐行不变（改动边界；两侧都必须绿）─────────────────────
    @Test
    @DisplayName("T3 非树页签（元素BOM）：轴值仍是 FG01，3 行进 3 行出 —— 改动边界")
    void flatTabAxisUnchanged() throws Exception {
        assertEquals(3, M.readTree(FLAT_SNAPSHOT).size(), "非空守卫：夹具必须真的有行");

        List<DsRecordRow> rows = DsRecordProjector.project(
                flatBinding(), "material_no", FLAT_SNAPSHOT, null, null, "FG01", 0);

        assertEquals(3, rows.size(), "元素BOM 侧一行都不许少");
        assertEquals(List.of("FG01", "FG01", "FG01"), rows.stream().map(r -> r.axisValue).toList(),
                "元素BOM 的 hf_part_no 与轴列值恒等（实测 8/8）⇒ 优先级调换后逐字不变");
        assertEquals(List.of("Ag", "Ni", "Cu"),
                rows.stream().map(r -> String.valueOf(r.columnValues.get("element_code"))).toList());
    }

    // ── T3b · 页签没表征轴列时，hf_part_no 仍是兜底（🚫 不许把兜底删掉）───────
    @Test
    @DisplayName("T3b 轴列未被页签表征 → 退回 hf_part_no；连它都没有 → 退回卡片销售料号")
    void fallbackChainStillWorks() {
        Map<String, String> f2c = new LinkedHashMap<>();
        f2c.put("_EL_元素", "element_code");          // 刻意不映射 material_no
        DsSheetBinding b = binding("ELEMENT_BOM", f2c, List.of("element_code"));

        List<DsRecordRow> withHf = DsRecordProjector.project(b, "material_no",
                "[{\"driverRow\":{\"hf_part_no\":\"FG09\",\"_EL_元素\":\"Ag\"}}]", null, null, "CARD", 0);
        assertEquals(List.of("FG09"), withHf.stream().map(r -> r.axisValue).toList(),
                "轴列没被表征 ⇒ 第二级 hf_part_no 兜底");

        List<DsRecordRow> noHf = DsRecordProjector.project(b, "material_no",
                "[{\"driverRow\":{\"_EL_元素\":\"Ag\"}}]", null, null, "CARD", 0);
        assertEquals(List.of("CARD"), noHf.stream().map(r -> r.axisValue).toList(),
                "连 hf_part_no 都没有 ⇒ 末级兜底 = 产品卡片销售料号");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 病灶二 · acceptAxis（touchedAxes 的新语义）
    // ══════════════════════════════════════════════════════════════════════

    // ── T4 · 树闭包派生轴必须放行（改回 touchedAxes.contains ⇒ 必红）──────────
    @Test
    @DisplayName("T4 中间件轴值(RM01)必须放行 —— 它是本单卡片自己投出来的树闭包派生轴")
    void derivedTreeAxisAccepted() {
        Set<String> touched = new LinkedHashSet<>(List.of("FG01"));
        Set<String> otherProducts = new LinkedHashSet<>();   // 本单只有 FG01 一个产品

        assertTrue(DsQuoteRecordService.acceptAxis("FG01", touched, otherProducts), "本次变更产品的组：必写");
        assertTrue(DsQuoteRecordService.acceptAxis("RM01", touched, otherProducts),
                "🔴 D-46：中间件料号不在 touchedAxes 里，但它是本单树闭包的一环 ⇒ 必须放行。"
                        + "丢了就等于 ds_quote_material_bom 永远不会被报价单回填");
    }

    // ── T4b · 端到端：投影 + 过滤 串起来看，3 条边行一条都不许掉 ───────────────
    @Test
    @DisplayName("T4b 投影→过滤 串联：FG01 单产品单，3 条边行落进 2 个轴值组（FG01×2 + RM01×1）")
    void projectThenFilterKeepsAllEdges() throws Exception {
        List<DsRecordRow> rows = DsRecordProjector.project(
                treeBinding(), "material_no", TREE_SNAPSHOT, null, null, "FG01", 0);
        Set<String> touched = new LinkedHashSet<>(List.of("FG01"));
        Set<String> otherProducts = new LinkedHashSet<>();

        Map<String, List<DsRecordRow>> byAxis = new LinkedHashMap<>();
        for (DsRecordRow r : rows) {
            if (!DsQuoteRecordService.acceptAxis(r.axisValue, touched, otherProducts)) continue;
            byAxis.computeIfAbsent(r.axisValue, k -> new ArrayList<>()).add(r);
        }

        assertEquals(Set.of("FG01", "RM01"), byAxis.keySet(),
                "写入面 = 产品轴值 FG01 ∪ 树闭包派生轴 RM01");
        assertEquals(2, byAxis.get("FG01").size());
        assertEquals(1, byAxis.get("RM01").size());
        assertEquals(3, byAxis.values().stream().mapToInt(List::size).sum(),
                "🔴 缺陷形态：老口径下这里是 0（3 条边行的轴值全算成子件，全被过滤掉）");
    }

    // ── T5 · 护栏：轴值是本单另一个产品的销售料号 ⇒ 本次不写 ──────────────────
    @Test
    @DisplayName("T5 护栏：FG02 既是本单另一个产品、又是 FG01 的下阶件 ⇒ 该组本次不写")
    void otherProductAxisSkipped() {
        Set<String> touched = new LinkedHashSet<>(List.of("FG01"));
        Set<String> otherProducts = new LinkedHashSet<>(List.of("FG02"));

        assertFalse(DsQuoteRecordService.acceptAxis("FG02", touched, otherProducts),
                "FG02 组归它自己的卡片管，而那张卡片不在本次 scopeLines 里 ⇒ 放行会被 deleteGroups "
                        + "整组删掉再只写回本卡片这一份 ⇒ 静默丢行");
        assertTrue(DsQuoteRecordService.acceptAxis("RM01", touched, otherProducts),
                "非产品的中间件仍然放行 —— 护栏只挡『另一个产品』这一种");
        assertFalse(DsQuoteRecordService.acceptAxis(null, touched, otherProducts));
        assertFalse(DsQuoteRecordService.acceptAxis("  ", touched, otherProducts));
    }

    // ── T5b · 整单同步（changedLineItemIds=null）时护栏不该误伤 ─────────────────
    @Test
    @DisplayName("T5b 整单同步：全部产品都在 touchedAxes ⇒ otherProductAxes 为空 ⇒ 护栏不生效")
    void fullSyncHasNoGuardEffect() {
        Set<String> touched = new LinkedHashSet<>(List.of("FG01", "FG02"));
        Set<String> otherProducts = new LinkedHashSet<>();   // 整单同步时它必然为空
        assertTrue(DsQuoteRecordService.acceptAxis("FG02", touched, otherProducts));
        assertTrue(DsQuoteRecordService.acceptAxis("RM01", touched, otherProducts));
    }
}
