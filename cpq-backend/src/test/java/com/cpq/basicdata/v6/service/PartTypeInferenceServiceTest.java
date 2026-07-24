package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * update-0723 Task B2：{@link PartTypeInferenceService} 单测。
 * 覆盖判定顺序（权威 sheet → material_recipe 库内兜底 → material_master 库内兜底 → 默认零件）
 * + 类型冲突检测（U6 洞①）。
 *
 * <p>库内兜底断言用真实存在于 dev DB 的数据（material_recipe 991/992，见 dev-docs 黄金样例
 * 报价系统模板0723.xlsx），避免额外插数据的写入依赖。
 */
@QuarkusTest
class PartTypeInferenceServiceTest {

    @Inject PartTypeInferenceService service;

    private SheetRow row(int rowNo, Map<String, String> cells) {
        return new SheetRow(rowNo, cells);
    }

    @Test
    void infer_sheetMatch_recipe() {
        Map<String, List<SheetRow>> sheets = new HashMap<>();
        Map<String, String> r1 = new HashMap<>();
        r1.put("销售料号", "P1"); r1.put("材质料号", "T-RECIPE-1"); r1.put("材质料号名称", "测试材质1");
        sheets.put("物料与元素BOM", List.of(row(1, r1)));

        var idx = service.buildIndex(sheets);
        var infer = idx.infer("T-RECIPE-1", null);
        assertEquals(PartTypeInferenceService.RECIPE, infer.characteristic());
        assertEquals(PartTypeInferenceService.Source.SHEET, infer.source());

        // 名称命中同样生效（U4：料号列或名称列命中其一即算）
        var inferByName = idx.infer(null, "测试材质1");
        assertEquals(PartTypeInferenceService.RECIPE, inferByName.characteristic());
    }

    @Test
    void infer_sheetMatch_assembly_viaSelfProcessFee() {
        Map<String, List<SheetRow>> sheets = new HashMap<>();
        Map<String, String> r1 = new HashMap<>();
        r1.put("销售料号", "P1"); r1.put("投入料号", "T-ASM-1");
        sheets.put("自制加工费", List.of(row(1, r1)));

        var idx = service.buildIndex(sheets);
        assertEquals(PartTypeInferenceService.ASSEMBLY, idx.infer("T-ASM-1", null).characteristic());
    }

    @Test
    void infer_sheetMatch_assembly_viaMainPartSheets() {
        Map<String, List<SheetRow>> sheets = new HashMap<>();
        Map<String, String> r1 = new HashMap<>();
        r1.put("销售料号", "T-MAINPART-1");
        sheets.put("客户料号与宏丰料号的关系", List.of(row(1, r1)));

        var idx = service.buildIndex(sheets);
        assertEquals(PartTypeInferenceService.ASSEMBLY, idx.infer("T-MAINPART-1", null).characteristic(),
            "主件（销售料号）应归入零件类型（U1）");
    }

    @Test
    void infer_sheetMatch_outsourced_viaComponentOtherFee() {
        Map<String, List<SheetRow>> sheets = new HashMap<>();
        Map<String, String> r1 = new HashMap<>();
        r1.put("销售料号", "P1"); r1.put("组成件料号", "T-OUT-1"); r1.put("组成件名称", "测试外购件1");
        sheets.put("组成件其他费用", List.of(row(1, r1)));

        var idx = service.buildIndex(sheets);
        assertEquals(PartTypeInferenceService.OUTSOURCED, idx.infer("T-OUT-1", null).characteristic());
    }

    @Test
    void infer_recipeDbFallback_realData991() {
        // 无任何权威 sheet 命中，纯靠 material_recipe 库内兜底（991/992 是真实存在数据）。
        var idx = service.buildIndex(Map.of());
        var infer = idx.infer("991", null);
        assertEquals(PartTypeInferenceService.RECIPE, infer.characteristic());
        assertEquals(PartTypeInferenceService.Source.RECIPE_DB, infer.source());
        assertEquals("991", idx.resolveRecipeCode("991", null));

        var inferByName = idx.infer(null, "H65带");
        assertEquals(PartTypeInferenceService.RECIPE, inferByName.characteristic());
    }

    @Test
    void infer_defaultFallback_noMatchAnywhere_isAssembly() {
        var idx = service.buildIndex(Map.of());
        var infer = idx.infer("NO-SUCH-TOKEN-XYZ-999", null);
        assertEquals(PartTypeInferenceService.ASSEMBLY, infer.characteristic());
        assertEquals(PartTypeInferenceService.Source.DEFAULT, infer.source());
    }

    @Test
    void resolveRecipeCode_unknownCode_returnsNull() {
        var idx = service.buildIndex(Map.of());
        assertNull(idx.resolveRecipeCode("NOT-A-REAL-RECIPE-CODE-999", null));
        assertNull(idx.resolveRecipeCode(null, "NOT-A-REAL-RECIPE-NAME-999"));
    }

    @Test
    void conflict_sameTokenAcrossRecipeAndOutsourced_isDetected() {
        Map<String, List<SheetRow>> sheets = new HashMap<>();
        Map<String, String> elemRow = new HashMap<>();
        elemRow.put("销售料号", "P1"); elemRow.put("材质料号", "CLASH-TOKEN");
        Map<String, String> compRow = new HashMap<>();
        compRow.put("销售料号", "P1"); compRow.put("组成件料号", "CLASH-TOKEN");
        sheets.put("物料与元素BOM", List.of(row(1, elemRow)));
        sheets.put("组成件其他费用", List.of(row(5, compRow)));

        var idx = service.buildIndex(sheets);
        assertEquals(1, idx.conflicts().size(), "同一 token 命中材质+外购件两个权威集应记 1 条冲突");
        var conflict = idx.conflicts().get(0);
        assertTrue(conflict.message().contains("CLASH-TOKEN"));
        assertTrue(conflict.message().contains("材质"));
        assertTrue(conflict.message().contains("外购件"));
        assertTrue(conflict.message().contains("类型冲突"));
    }

    @Test
    void conflict_assemblyAndMainPart_sameBucket_notAConflict() {
        // 主件（销售料号）与「零件（自制加工费投入料号）」同属零件桶，不应算冲突。
        Map<String, List<SheetRow>> sheets = new HashMap<>();
        Map<String, String> selfProc = new HashMap<>();
        selfProc.put("销售料号", "P1"); selfProc.put("投入料号", "SHARED-ASM-TOKEN");
        Map<String, String> mainPart = new HashMap<>();
        mainPart.put("销售料号", "SHARED-ASM-TOKEN");
        sheets.put("自制加工费", List.of(row(1, selfProc)));
        sheets.put("成品其他费用", List.of(row(1, mainPart)));

        var idx = service.buildIndex(sheets);
        assertTrue(idx.conflicts().isEmpty(), "主件与自制加工费零件同属零件类型，不应误判冲突");
        assertEquals(PartTypeInferenceService.ASSEMBLY, idx.infer("SHARED-ASM-TOKEN", null).characteristic());
    }

    @Test
    void nameToCodeSeed_sameRowCodeAndName_isSeeded() {
        Map<String, List<SheetRow>> sheets = new HashMap<>();
        Map<String, String> bomRow = new HashMap<>();
        bomRow.put("销售料号", "P1"); bomRow.put("投入料号", "SEED-CODE-1"); bomRow.put("投入料号名称", "SEED-NAME-1");
        sheets.put("物料BOM", List.of(row(1, bomRow)));

        var idx = service.buildIndex(sheets);
        assertEquals("SEED-CODE-1", idx.nameToCodeSeed().get("SEED-NAME-1"),
            "R2：同行给出码+名应种下 name→code 供全导入共享的 BatchState 消费");
    }
}
